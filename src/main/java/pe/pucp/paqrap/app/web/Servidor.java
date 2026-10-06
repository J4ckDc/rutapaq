package pe.pucp.paqrap.app.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pe.pucp.paqrap.app.Config;
import pe.pucp.paqrap.app.modelo.Nodo;
import pe.pucp.paqrap.app.sim.Planificadores;
import pe.pucp.paqrap.planner.core.Planificador;
import pe.pucp.paqrap.app.sim.Simulacion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Servidor HTTP del JDK (sin dependencias).
 *   POST /api/simulacion?escenario=SEMANAL|COLAPSO|DIA_A_DIA&fecha=aaaa-mm-dd   inicia
 *   POST /api/simulacion/detener                                               detiene
 *   GET  /api/estado                                                           estado completo (JSON)
 *   GET  /api/stream                                                           estado cada N ms (SSE)
 *   POST /api/pedidos?x=&y=&cantidad=&plazo=&cliente=                          registro en línea
 *   GET  /api/config                                                           datos para la interfaz
 *   GET  /                                                                     visualizador
 */
public final class Servidor {
    private static final Set<Integer> PLAZOS = Set.of(4, 8, 12, 18, 36);

    private final Config cfg;
    private final List<Cliente> clientes = new CopyOnWriteArrayList<>();
    private volatile Simulacion sim;
    private Thread hilo;

    private record Cliente(HttpExchange ex, OutputStream os) {
    }

    public Servidor(Config cfg) {
        this.cfg = cfg;
    }

    public void iniciar() throws IOException {
        String host = cfg.texto("servidor.host", "0.0.0.0");
        int puerto = cfg.entero("servidor.puerto", 8080);
        HttpServer s = HttpServer.create(new InetSocketAddress(host, puerto), 0);
        s.createContext("/api/simulacion", this::simulacion);
        s.createContext("/api/simulacion/detener", this::detener);
        s.createContext("/api/estado", ex -> responder(ex, 200, "application/json", estadoJson()));
        s.createContext("/api/stream", this::stream);
        s.createContext("/api/pedidos", this::pedido);
        s.createContext("/api/config", this::config);
        s.createContext("/", this::estatico);
        s.setExecutor(Executors.newCachedThreadPool());
        s.start();

        ScheduledExecutorService pub = Executors.newSingleThreadScheduledExecutor();
        long cada = cfg.entero("visualizador.publicacionMs", 500);
        pub.scheduleAtFixedRate(this::publicar, cada, cada, TimeUnit.MILLISECONDS);
        System.out.printf("RUTAPAQ escuchando en http://%s:%d (planificador %s de rutapaq-planner)%n", host, puerto, crearPlanificador().nombre());
    }

    private Planificador crearPlanificador() {
        return Planificadores.crear(cfg.planificador());
    }

    private String estadoJson() {
        Simulacion s = sim;
        return s == null ? "{\"estado\":\"SIN_SIMULACION\"}" : s.snapshotJson();
    }

    // ---------------------------------------------------------------- SSE

    private void stream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("X-Accel-Buffering", "no");   // para que Nginx no acumule
        ex.sendResponseHeaders(200, 0);
        Cliente c = new Cliente(ex, ex.getResponseBody());
        clientes.add(c);
        enviar(c, ("data: " + estadoJson() + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private void publicar() {
        try {
            if (clientes.isEmpty()) return;
            byte[] msg = ("data: " + estadoJson() + "\n\n").getBytes(StandardCharsets.UTF_8);
            for (Cliente c : clientes) enviar(c, msg);
        } catch (RuntimeException e) {
            System.err.println("Error al publicar: " + e);
        }
    }

    private void enviar(Cliente c, byte[] msg) {
        try {
            c.os().write(msg);
            c.os().flush();
        } catch (IOException e) {
            clientes.remove(c);
            c.ex().close();
        }
    }

    // ---------------------------------------------------------------- API

    private void simulacion(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            responder(ex, 405, "text/plain", "Use POST");
            return;
        }
        Map<String, String> q = parametros(ex);
        Simulacion.Escenario esc;
        try {
            esc = Simulacion.Escenario.valueOf(q.getOrDefault("escenario", "SEMANAL").toUpperCase());
        } catch (IllegalArgumentException e) {
            responder(ex, 400, "application/json", "{\"error\":\"escenario inválido\"}");
            return;
        }
        LocalDateTime inicio;
        try {
            String f = q.get("fecha");
            if (esc == Simulacion.Escenario.DIA_A_DIA && (f == null || f.isBlank())) {
                inicio = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
            } else {
                inicio = LocalDate.parse(f == null || f.isBlank() ? cfg.texto("escenario.fechaInicio", "2026-01-01") : f).atStartOfDay();
            }
        } catch (RuntimeException e) {
            responder(ex, 400, "application/json", "{\"error\":\"fecha inválida (aaaa-mm-dd)\"}");
            return;
        }
        synchronized (this) {
            if (sim != null) sim.detener();
            if (hilo != null) {
                try {
                    hilo.join(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            sim = new Simulacion(cfg, esc, inicio, crearPlanificador());
            hilo = new Thread(sim, "simulacion");
            hilo.setDaemon(true);
            hilo.start();
        }
        responder(ex, 200, "application/json", "{\"ok\":true}");
    }

    private void detener(HttpExchange ex) throws IOException {
        Simulacion s = sim;
        if (s != null) s.detener();
        responder(ex, 200, "application/json", "{\"ok\":true}");
    }

    private void pedido(HttpExchange ex) throws IOException {
        Simulacion s = sim;
        if (!ex.getRequestMethod().equals("POST")) {
            responder(ex, 405, "text/plain", "Use POST");
            return;
        }
        if (s == null || s.estado() != Simulacion.Estado.EJECUTANDO) {
            responder(ex, 409, "application/json", "{\"error\":\"no hay una simulación en ejecución\"}");
            return;
        }
        Map<String, String> q = parametros(ex);
        try {
            int x = Integer.parseInt(q.get("x")), y = Integer.parseInt(q.get("y"));
            int cantidad = Integer.parseInt(q.get("cantidad")), plazo = Integer.parseInt(q.get("plazo"));
            if (!PLAZOS.contains(plazo)) throw new IllegalArgumentException("plazo");
            if (x < 0 || x > 70 || y < 0 || y > 50 || cantidad <= 0) throw new IllegalArgumentException("datos");
            String id = s.registrarPedido(q.getOrDefault("cliente", "c-web"), new Nodo(x, y), cantidad, plazo);
            responder(ex, 200, "application/json", "{\"id\":" + Json.str(id) + "}");
        } catch (RuntimeException e) {
            responder(ex, 400, "application/json",
                    "{\"error\":\"datos inválidos: x 0-70, y 0-50, cantidad > 0, plazo 4/8/12/18/36\"}");
        }
    }

    private void config(HttpExchange ex) throws IOException {
        responder(ex, 200, "application/json", "{\"algoritmo\":" + Json.str(cfg.texto("planificador.algoritmo", "ALNS"))
                + ",\"fechaInicio\":" + Json.str(cfg.texto("escenario.fechaInicio", "2026-01-01"))
                + ",\"minutosSemanal\":" + Json.num(cfg.decimal("escenario.semanal.minutosReales", 30)) + "}");
    }

    // ---------------------------------------------------------------- archivos estáticos

    private void estatico(HttpExchange ex) throws IOException {
        String ruta = ex.getRequestURI().getPath();
        if (ruta.equals("/")) ruta = "/index.html";
        if (ruta.contains("..")) {
            responder(ex, 400, "text/plain", "ruta inválida");
            return;
        }
        try (InputStream in = Servidor.class.getResourceAsStream("/web" + ruta)) {
            if (in == null) {
                responder(ex, 404, "text/plain", "No encontrado");
                return;
            }
            String tipo = ruta.endsWith(".html") ? "text/html" : ruta.endsWith(".js") ? "application/javascript"
                    : ruta.endsWith(".css") ? "text/css" : "application/octet-stream";
            responder(ex, 200, tipo, in.readAllBytes());
        }
    }

    // ---------------------------------------------------------------- utilidades

    private static Map<String, String> parametros(HttpExchange ex) throws IOException {
        Map<String, String> r = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (String parte : new String[]{q, cuerpo}) {
            if (parte == null || parte.isBlank()) continue;
            for (String kv : parte.split("&")) {
                int i = kv.indexOf('=');
                if (i <= 0) continue;
                r.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return r;
    }

    private static void responder(HttpExchange ex, int codigo, String tipo, String cuerpo) throws IOException {
        responder(ex, codigo, tipo, cuerpo.getBytes(StandardCharsets.UTF_8));
    }

    private static void responder(HttpExchange ex, int codigo, String tipo, byte[] cuerpo) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo + "; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(codigo, cuerpo.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(cuerpo);
        }
    }
}
