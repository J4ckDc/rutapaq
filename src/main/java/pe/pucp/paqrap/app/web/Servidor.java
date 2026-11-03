package pe.pucp.paqrap.app.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pe.pucp.paqrap.app.CatalogoConfiguracion;
import pe.pucp.paqrap.app.Config;
import pe.pucp.paqrap.app.modelo.Averia;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Servidor HTTP del JDK (sin dependencias).
 *   POST /api/simulacion?escenario=SEMANAL|COLAPSO|DIA_A_DIA&fecha=aaaa-mm-ddThh:mm   inicia (reemplaza la anterior)
 *   POST /api/simulacion/detener                                               detiene (v02: pausa, se puede reanudar)
 *   POST /api/simulacion/reanudar                                              reanuda (v02)
 *   GET  /api/estado                                                           estado completo (JSON)
 *   GET  /api/stream                                                           estado cada N ms (SSE)
 *   POST /api/pedidos?x=&y=&cantidad=&plazo=&cliente=                          registro en línea
 *   POST /api/averias?unidad=TA03&tipo=2                                       registra una avería (v03)
 *   GET  /api/averias                                                          historial de averías del escenario (v03)
 *   GET  /api/config                                                           datos para la interfaz
 *   GET  /api/plan                                                             v0.5: última planificación estable (JSON)
 *   GET  /api/plan.csv                                                         v0.5: la misma, en CSV
 *   GET  /api/configuracion                                                    v0.5: parámetros editables (B01, B02, B04, B06-B09)
 *   POST /api/configuracion?clave=valor&...                                    v0.5: valida, guarda en config.properties y aplica
 *   POST /api/configuracion/restaurar                                          v0.5: vuelve a los valores de fábrica
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
        s.createContext("/api/simulacion/reanudar", this::reanudar);
        s.createContext("/api/estado", ex -> responder(ex, 200, "application/json", estadoJson()));
        s.createContext("/api/stream", this::stream);
        s.createContext("/api/pedidos", this::pedido);
        s.createContext("/api/averias", this::averias);
        s.createContext("/api/config", this::config);
        s.createContext("/api/plan", this::plan);                 // v0.5: última planificación estable (JSON)
        s.createContext("/api/configuracion", this::configuracion);                    // v0.5: pantalla Configuración
        s.createContext("/api/configuracion/restaurar", this::restaurarConfiguracion);  // v0.5: valores de fábrica
        s.createContext("/api/plan.csv", this::planCsv);          // v0.5: la misma, en CSV
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
        return s == null ? "{\"estado\":\"SIN_SIMULACION\",\"configVersion\":" + cfg.version() + "}" : s.snapshotJson();
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
                // v02: acepta fecha y hora (aaaa-mm-ddThh:mm) o solo fecha (empieza a las 00:00)
                String v = f == null || f.isBlank() ? cfg.texto("escenario.fechaInicio", "2026-01-01") : f.trim();
                inicio = v.contains("T") ? LocalDateTime.parse(v).truncatedTo(ChronoUnit.MINUTES) : LocalDate.parse(v).atStartOfDay();
            }
        } catch (RuntimeException e) {
            responder(ex, 400, "application/json", "{\"error\":\"fecha inválida (aaaa-mm-dd o aaaa-mm-ddThh:mm)\"}");
            return;
        }
        synchronized (this) {
            if (sim != null) sim.cancelar();
            if (hilo != null) {
                try {
                    hilo.join(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            // v0.5: capacidad, velocidad y costo de la flota con los valores guardados (la flota cambia solo al inicio)
            pe.pucp.paqrap.planner.model.TipoVehiculo.configurar(cfg.planificador());
            try {
                sim = new Simulacion(cfg, esc, inicio, crearPlanificador());
            } catch (IllegalArgumentException e) {      // v0.5 (B01): p. ej. un almacén fuera del mapa configurado
                sim = null;
                hilo = null;
                responder(ex, 400, "application/json", "{\"error\":" + Json.str(e.getMessage()) + "}");
                return;
            }
            hilo = new Thread(sim, "simulacion");
            hilo.setDaemon(true);
            hilo.start();
        }
        responder(ex, 200, "application/json", "{\"ok\":true}");
    }

    /** v02: Detener deja la simulación en pausa (antes la terminaba sin posibilidad de continuar). */
    private void detener(HttpExchange ex) throws IOException {
        Simulacion s = sim;
        if (s == null || !s.pausar()) {
            responder(ex, 409, "application/json", "{\"error\":\"no hay una simulación en ejecución\"}");
            return;
        }
        responder(ex, 200, "application/json", "{\"ok\":true}");
    }

    /** v02: continúa una simulación detenida desde el mismo instante. */
    private void reanudar(HttpExchange ex) throws IOException {
        Simulacion s = sim;
        if (s == null || !s.reanudar()) {
            responder(ex, 409, "application/json", "{\"error\":\"no hay una simulación detenida\"}");
            return;
        }
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
            // v0.5 (B01): límites según el mapa de la simulación en curso (ien.mapaAnchoKm x ien.mapaAltoKm)
            if (x < 0 || x > s.anchoMapa() || y < 0 || y > s.altoMapa() || cantidad <= 0) throw new IllegalArgumentException("datos");
            String id = s.registrarPedido(q.getOrDefault("cliente", "c-web"), new Nodo(x, y), cantidad, plazo);
            responder(ex, 200, "application/json", "{\"id\":" + Json.str(id) + "}");
        } catch (RuntimeException e) {
            responder(ex, 400, "application/json", "{\"error\":" + Json.str("datos inválidos: x 0-" + s.anchoMapa()
                    + ", y 0-" + s.altoMapa() + ", cantidad > 0, plazo 4/8/12/18/36") + "}");
        }
    }

    /**
     * v03: averías. POST registra la avería de una unidad en el instante actual de la simulación (RF05-04, RF05-05)
     * y responde con la avería registrada; GET devuelve el historial de averías del escenario en curso.
     */
    private void averias(HttpExchange ex) throws IOException {
        Simulacion s = sim;
        if (ex.getRequestMethod().equals("GET")) {
            responder(ex, 200, "application/json", s == null ? "[]" : s.averiasJson());
            return;
        }
        if (!ex.getRequestMethod().equals("POST")) {
            responder(ex, 405, "text/plain", "Use GET o POST");
            return;
        }
        if (s == null || s.estado() == Simulacion.Estado.TERMINADA) {
            responder(ex, 409, "application/json", "{\"error\":\"no hay una simulación en curso\"}");
            return;
        }
        Map<String, String> q = parametros(ex);
        int tipo;
        try {
            tipo = Integer.parseInt(q.getOrDefault("tipo", "").trim());
        } catch (NumberFormatException e) {
            responder(ex, 400, "application/json", "{\"error\":\"el tipo de avería debe ser 1, 2 o 3\"}");
            return;
        }
        try {
            Averia av = s.registrarAveria(q.get("unidad"), tipo);
            responder(ex, 200, "application/json", "{\"averia\":" + s.averiaJson(av) + "}");
        } catch (IllegalArgumentException e) {
            responder(ex, 400, "application/json", "{\"error\":" + Json.str(e.getMessage()) + "}");
        } catch (IllegalStateException e) {
            responder(ex, 409, "application/json", "{\"error\":" + Json.str(e.getMessage()) + "}");
        }
    }

    private void config(HttpExchange ex) throws IOException {
        responder(ex, 200, "application/json", "{\"algoritmo\":" + Json.str(cfg.texto("planificador.algoritmo", "ALNS"))
                + ",\"fechaInicio\":" + Json.str(cfg.texto("escenario.fechaInicio", "2026-01-01"))
                + ",\"horaInicio\":" + Json.str(cfg.texto("escenario.horaInicio", "00:00"))
                + ",\"minutosSemanal\":" + Json.num(cfg.decimal("escenario.semanal.minutosReales", 30))
                + ",\"factorColapso\":" + Json.num(cfg.decimal("escenario.colapso.factor", 600))
                + ",\"averiaTipo1Horas\":" + Json.num(cfg.decimal("averia.tipo1.horas", 2))
                + ",\"averiaEnLugarHoras\":" + Json.num(cfg.decimal("averia.enLugar.horas", 4))
                + ",\"averiaTipo3Dias\":" + Json.num(cfg.decimal("averia.tipo3.diasMinimos", 2))
                + ",\"averiaTipo3Hora\":" + Json.str(cfg.texto("averia.tipo3.horaRetorno", "15:00"))
                + ",\"averiaTrasvaseMinutos\":" + Json.num(cfg.decimal("averia.trasvase.minutos", 30))
                + ",\"stockVerde\":" + Json.num(cfg.decimal("semaforo.almacen.verde", 30))
                + ",\"stockAmbar\":" + Json.num(cfg.decimal("semaforo.almacen.ambar", 10))
                + ",\"ventanaHoras\":" + Json.num(cfg.decimal("panel.ventanaHoras", 4))
                // v0.5 (B01): tamaño del mapa (el visualizador dibuja la retícula con estos valores)
                + ",\"mapa\":{\"ancho\":" + cfg.entero("ien.mapaAnchoKm", 70) + ",\"alto\":" + cfg.entero("ien.mapaAltoKm", 50) + "}"
                + ",\"configVersion\":" + cfg.version()
                // v0.5: todos los umbrales del semáforo, para que las leyendas coincidan con la configuración
                + ",\"semaforo\":" + new pe.pucp.paqrap.app.sim.Indicadores(cfg).umbralesJson() + "}");
    }

    // ---------------------------------------------------------------- v0.5: pantalla Configuración

    private void configuracion(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("GET")) {
            Map<String, String> defecto = new HashMap<>();
            for (CatalogoConfiguracion.Campo c : CatalogoConfiguracion.CAMPOS) defecto.put(c.clave(), vigente(c, cfg.deFabrica(c.clave())));
            Simulacion s = sim;
            responder(ex, 200, "application/json", CatalogoConfiguracion.json(vigentes(), defecto, cfg.version(),
                    s == null ? "SIN_SIMULACION" : s.estado().name()));
            return;
        }
        if (!ex.getRequestMethod().equals("POST")) {
            responder(ex, 405, "text/plain", "Use GET o POST");
            return;
        }
        guardarConfiguracion(ex, parametros(ex));
    }

    private void restaurarConfiguracion(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            responder(ex, 405, "text/plain", "Use POST");
            return;
        }
        Map<String, String> fabrica = new LinkedHashMap<>();
        for (CatalogoConfiguracion.Campo c : CatalogoConfiguracion.CAMPOS) {
            String v = cfg.deFabrica(c.clave());
            if (v != null) fabrica.put(c.clave(), v);
        }
        guardarConfiguracion(ex, fabrica);
    }

    /** Valor vigente de un campo, normalizado como lo muestra la pantalla. */
    private String vigente(CatalogoConfiguracion.Campo c, String crudo) {
        try {
            return CatalogoConfiguracion.normalizar(c, crudo, cfg.entero("ien.mapaAnchoKm", 70), cfg.entero("ien.mapaAltoKm", 50));
        } catch (RuntimeException e) {
            return crudo == null ? "" : crudo.trim();
        }
    }

    private Map<String, String> vigentes() {
        Map<String, String> m = new LinkedHashMap<>();
        for (CatalogoConfiguracion.Campo c : CatalogoConfiguracion.CAMPOS) m.put(c.clave(), vigente(c, cfg.texto(c.clave(), "")));
        return m;
    }

    /**
     * Valida todos los valores recibidos (cada uno y en conjunto), guarda en config.properties solo los que cambiaron
     * y aplica: velocidades en el siguiente ciclo de la simulación en curso, reglas de averías y semáforo de inmediato;
     * el resto rige al iniciar la próxima simulación. Responde qué se aplicó y cuándo, o los errores por campo.
     */
    private synchronized void guardarConfiguracion(HttpExchange ex, Map<String, String> entrada) throws IOException {
        Map<String, String> resultantes = vigentes();
        // v0.5 (B01): las ubicaciones de los almacenes se revisan contra el tamaño del mapa que quedaría (validarConjunto);
        // aquí solo se exige que estén dentro del máximo permitido para el mapa
        int ancho = (int) CatalogoConfiguracion.campo("ien.mapaAnchoKm").max(), alto = (int) CatalogoConfiguracion.campo("ien.mapaAltoKm").max();
        Map<String, String> cambios = new LinkedHashMap<>();
        Map<String, String> errores = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entrada.entrySet()) {
            CatalogoConfiguracion.Campo c = CatalogoConfiguracion.campo(e.getKey());
            if (c == null) continue;                              // solo se aceptan las claves de la pantalla
            try {
                String v = CatalogoConfiguracion.normalizar(c, e.getValue(), ancho, alto);
                if (!v.equals(resultantes.get(c.clave()))) cambios.put(c.clave(), v);
                resultantes.put(c.clave(), v);
            } catch (IllegalArgumentException err) {
                errores.put(c.clave(), err.getMessage());
            }
        }
        if (errores.isEmpty()) errores.putAll(CatalogoConfiguracion.validarConjunto(resultantes));
        if (!errores.isEmpty()) {
            StringBuilder b = new StringBuilder("{\"errores\":{");
            boolean primero = true;
            for (Map.Entry<String, String> e : errores.entrySet()) {
                if (!primero) b.append(',');
                b.append(Json.str(e.getKey())).append(':').append(Json.str(e.getValue()));
                primero = false;
            }
            responder(ex, 400, "application/json", b.append("}}").toString());
            return;
        }
        try {
            cfg.guardar(cambios);
        } catch (IOException e) {
            responder(ex, 500, "application/json", "{\"error\":" + Json.str("No se pudo escribir " + cfg.archivo() + ": " + e.getMessage()) + "}");
            return;
        }
        Simulacion s = sim;
        boolean enCurso = s != null && (s.estado() == Simulacion.Estado.EJECUTANDO || s.estado() == Simulacion.Estado.PAUSADA
                || s.estado() == Simulacion.Estado.PREPARADA);
        Map<pe.pucp.paqrap.planner.model.TipoVehiculo, Double> velocidades = new java.util.EnumMap<>(pe.pucp.paqrap.planner.model.TipoVehiculo.class);
        boolean averias = false;
        Map<CatalogoConfiguracion.Aplica, List<String>> porCuando = new java.util.EnumMap<>(CatalogoConfiguracion.Aplica.class);
        for (String k : cambios.keySet()) {
            CatalogoConfiguracion.Campo c = CatalogoConfiguracion.campo(k);
            porCuando.computeIfAbsent(c.aplica(), x -> new java.util.ArrayList<>())
                    .add((c.grupo() != null ? c.grupo() : CatalogoConfiguracion.SECCIONES.stream()
                            .filter(x -> x.id().equals(c.seccion())).findFirst().map(CatalogoConfiguracion.Seccion::titulo).orElse(""))
                            + " · " + c.etiqueta());
            if (k.endsWith(".velocidadKmh")) {
                String tipo = k.substring("flota.".length(), k.length() - ".velocidadKmh".length()).toUpperCase();
                velocidades.put(pe.pucp.paqrap.planner.model.TipoVehiculo.valueOf(tipo), Double.parseDouble(cambios.get(k)));
            }
            if (k.startsWith("averia.")) averias = true;
        }
        if (!velocidades.isEmpty()) {
            if (enCurso) s.programarVelocidades(velocidades);
            else velocidades.forEach(pe.pucp.paqrap.planner.model.TipoVehiculo::setVelocidadKmH);
        }
        if (averias && s != null) s.recargarReglasAveria();
        System.out.printf("Configuración guardada en %s: %s%n", cfg.archivo(), cambios);
        StringBuilder b = new StringBuilder("{\"ok\":true,\"cambios\":").append(cambios.size())
                .append(",\"simulacionEnCurso\":").append(enCurso).append(",\"archivo\":").append(Json.str(String.valueOf(cfg.archivo())));
        for (CatalogoConfiguracion.Aplica a : CatalogoConfiguracion.Aplica.values()) {
            b.append(",\"").append(a.name().toLowerCase()).append("\":[");
            List<String> l = porCuando.getOrDefault(a, List.of());
            for (int i = 0; i < l.size(); i++) b.append(i > 0 ? "," : "").append(Json.str(l.get(i)));
            b.append(']');
        }
        responder(ex, 200, "application/json", b.append('}').toString());
    }

    /** v0.5: última planificación estable de la simulación en curso o terminada (rúbrica G08 a G10). */
    private void plan(HttpExchange ex) throws IOException {
        Simulacion s = sim;
        responder(ex, 200, "application/json", s == null ? "{\"plan\":null}" : s.planJson());
    }

    private void planCsv(HttpExchange ex) throws IOException {
        Simulacion s = sim;
        String csv = s == null ? null : s.planCsv();
        if (csv == null) {
            responder(ex, 404, "text/plain", "Aún no hay una planificación");
            return;
        }
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"ultima-planificacion.csv\"");
        responder(ex, 200, "text/csv", csv);
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
                    : ruta.endsWith(".css") ? "text/css" : ruta.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
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
