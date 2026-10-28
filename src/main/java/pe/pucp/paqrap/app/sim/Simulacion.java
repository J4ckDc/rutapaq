package pe.pucp.paqrap.app.sim;

import pe.pucp.paqrap.app.Config;
import pe.pucp.paqrap.app.datos.Bloqueo;
import pe.pucp.paqrap.app.datos.Formato;
import pe.pucp.paqrap.app.datos.FuenteDatos;
import pe.pucp.paqrap.app.datos.PlanMantenimiento;
import pe.pucp.paqrap.app.modelo.Almacen;
import pe.pucp.paqrap.app.modelo.Averia;
import pe.pucp.paqrap.app.modelo.Nodo;
import pe.pucp.paqrap.app.modelo.Parada;
import pe.pucp.paqrap.app.modelo.Pedido;
import pe.pucp.paqrap.app.modelo.TipoUnidad;
import pe.pucp.paqrap.app.modelo.Unidad;
import pe.pucp.paqrap.app.web.Json;
import pe.pucp.paqrap.planner.core.InstanciaPlanificacion;
import pe.pucp.paqrap.planner.core.MapaReticula;
import pe.pucp.paqrap.planner.core.Planificador;
import pe.pucp.paqrap.planner.ien.LectorVentas;
import pe.pucp.paqrap.planner.model.ParadaRuta;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TipoAlmacen;
import pe.pucp.paqrap.planner.model.TipoVehiculo;
import pe.pucp.paqrap.planner.model.TurnoConductor;
import pe.pucp.paqrap.planner.model.Vehiculo;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Corazón del sistema: reloj simulado, estado en memoria y ciclo de planificación.
 *
 * Cada Sc minutos simulados (ien.scMinutos) se ejecuta el planificador de rutapaq-planner
 * (ALNS_PAQRAP o HGS_PAQRAP) con el estado vigente, igual que en el SimuladorColapso del IEN:
 *   - cada unidad conserva solo la parada hacia la que ya se está moviendo;
 *   - todo lo demás se replanifica: pedidos nuevos, pendientes y los que estaban en itinerarios no iniciados;
 *   - el plan devuelto (entregas, recargas y refrigerios) pasa a ser el itinerario de cada unidad.
 * Entre ciclos, las unidades recorren la retícula nodo a nodo y ejecutan su itinerario.
 * Todo el acceso al estado está sincronizado sobre este objeto.
 *
 * Cambios v02:
 *   - Detener ya no termina la simulación: la deja PAUSADA y se puede reanudar (pausar / reanudar).
 *   - Mantenimiento preventivo: la unidad no recibe rutas que crucen su día de mantenimiento y,
 *     durante ese día (00:00 a 23:59), queda en el almacén central fuera de servicio.
 *   - Si el planificador falla, las unidades conservan su itinerario anterior.
 *   - El % a tiempo cuenta también los pedidos vencidos que aún no se entregan.
 *
 * Cambios v03 (averías, P&R pregunta 3; reglas en ReglasAveria):
 *   - Una avería se registra solo de forma manual desde el visualizador (registrarAveria), en cualquier escenario
 *     (v0.4.1, indicación del profesor). La unidad queda detenida en su posición exacta, sus
 *     pedidos vuelven al planificador y se replanifica de inmediato con un ciclo extraordinario (RF07-01).
 *   - Tipo 1: detenida 2 h con su carga; el planificador la sigue considerando desde que termina la avería.
 *   - Tipos 2 y 3: no reciben rutas. Durante las 4 h que permanecen en el lugar, sus paquetes se pueden trasvasar
 *     a otra unidad (30 min): el planificador las ve como un depósito temporal (TipoAlmacen.TRASVASE). Luego
 *     pasan de forma instantánea al almacén central con lo no trasvasado y vuelven a operar al final del turno
 *     siguiente (tipo 2) o tras al menos 2 días, en el turno de 15:00 (tipo 3).
 *   - Las averías no bloquean la calle: las demás unidades siguen circulando por ahí (P&R pregunta 7).
 *
 * Correcciones v03:
 *   - Un pedido vencido se sigue planificando hasta entregarlo, con la prioridad más baja (pedidoParaPlanificar).
 *     Antes, con plazos duros, un pedido que ya no podía llegar a tiempo no se asignaba nunca.
 *   - Un pedido que el planificador no logra asignar se parte en el ciclo siguiente según la capacidad de la moto
 *     (tamanosDeLote), para que lo lleven varias unidades si no hay un auto libre.
 *   - El reloj simulado avanza según el tiempo real transcurrido (run), no 100 ms fijos por vuelta.
 *
 * Cambios v04 (simulación hasta el colapso en el visualizador):
 *   - Al colapsar se guarda el reporte del colapso (colapsoJson): criterio, fecha y hora, Tc, pedido involucrado y
 *     estado de la flota en ese instante; el estado publicado indica además cómo terminó (tipoFin).
 *   - Si se acaban los datos de ventas y ya se entregó todo, termina "sin colapso" (SIN_COLAPSO).
 *   - v0.4.1: las averías tampoco se leen de archivo en el colapso; se registran solo desde el visualizador.
 */
public final class Simulacion implements Runnable {

    public enum Escenario { DIA_A_DIA, SEMANAL, COLAPSO }

    public enum Estado { PREPARADA, EJECUTANDO, PAUSADA, TERMINADA }

    private static final double PASO_MAX_SEG = 30.0;      // granularidad del movimiento
    private static final long TICK_MS = 100;              // cada cuánto avanza el reloj real
    /** v03: tope de tiempo real que se simula en una vuelta (si la PC se suspende, no salta horas de golpe). */
    private static final long MAX_VUELTA_NS = 5_000_000_000L;
    /** v03: plazo con que se replanifica un pedido vencido: el mayor del caso, que tiene la prioridad más baja. */
    private static final int PLAZO_VENCIDO_HORAS = 36;
    private static final double ENTREGA_SEG = 3600.0;     // acondicionamiento de la entrega
    private static final double REFRIGERIO_SEG = 3600.0;
    /** v03: identificador del depósito temporal de una unidad averiada (p. ej. TRV-TA03). */
    static final String TRASVASE = "TRV-";
    /** v03: averías finalizadas que se envían al visualizador (las vigentes se envían siempre). */
    private static final int HISTORIAL_VISIBLE = 60;

    private final Config cfg;
    private final Escenario escenario;
    private final LocalDateTime inicio;
    private final MapaReticula mapa;
    private final Caminos caminos;
    private final List<Almacen> almacenes = new ArrayList<>();
    private final List<Unidad> unidades = new ArrayList<>();
    private final Map<String, Unidad> unidadPorId = new HashMap<>();
    private final Map<String, Pedido> pedidos = new LinkedHashMap<>();
    private final List<Bloqueo> bloqueosActivos = new ArrayList<>();
    private final Planificador planificador;
    private final FuenteDatos fuente;
    private final PlanMantenimiento mantenimiento;
    private final Indicadores semaforo;
    private final ReglasAveria reglas;
    private final List<Averia> averias = new ArrayList<>();   // v03: historial del escenario (vigentes y finalizadas)

    private final double factor;          // segundos simulados por segundo real
    private final double cicloSeg;        // Sc
    private final double saMs;            // Sa: tiempo máximo de cómputo (criterio C de colapso)
    private final double margenSeg;       // holgura opcional frente al límite de cada pedido
    private final int maximoPorEntrega;   // entregas parciales
    private final double duracionSeg;     // 5D: 120 h
    private final double horizonteRutaSeg; // una ruta no dura más que esto (mantenimiento)

    private double t;
    private double proximoCiclo;
    private LocalDateTime proximaRecarga;
    private volatile Estado estado = Estado.PREPARADA;
    private volatile boolean cancelada;    // al iniciar otra simulación, esta termina
    private long nsReales;                 // tiempo real en ejecución, sin contar pausas
    private String motivoFin = "";
    /** v04: cómo terminó: FIN_PERIODO (5 días), COLAPSO, SIN_COLAPSO, ERROR o REEMPLAZADA ("" si sigue). */
    private String tipoFin = "";
    private double tcHoras = -1;
    /** v04: reporte del colapso tal como estaba en ese instante (JSON); null si no hubo colapso. */
    private String colapsoJson;
    /** v04: fin de los datos de ventas de la carpeta (null si no hay archivos): sin colapso hasta allí, termina. */
    private final LocalDateTime finDatos;

    private int contador, recibidos, completados, enPlazo, vencidos, ciclos, sinPlanificar, iteraciones;
    private int contadorAverias, trasvasados, devueltos;   // v03
    private long msUltimoPlan, msMaximoPlan;
    private double costo, kmTotal;
    private String ultimoError = "";

    public Simulacion(Config cfg, Escenario escenario, LocalDateTime inicio, Planificador planificador) {
        this.cfg = cfg;
        this.escenario = escenario;
        this.inicio = inicio;
        this.planificador = planificador;
        this.semaforo = new Indicadores(cfg);
        this.reglas = new ReglasAveria(cfg);
        this.fuente = new FuenteDatos(Path.of(cfg.texto("datos.carpeta", "datos")), inicio);
        this.finDatos = FuenteDatos.finDeVentas(Path.of(cfg.texto("datos.carpeta", "datos")));
        this.mantenimiento = new PlanMantenimiento(Path.of(cfg.texto("datos.carpeta", "datos")));
        this.mapa = new MapaReticula(cfg.entero("ien.mapaAnchoKm", 70), cfg.entero("ien.mapaAltoKm", 50));
        this.caminos = new Caminos(mapa);

        this.cicloSeg = cfg.decimal("ien.scMinutos", 60) * 60;
        this.saMs = cfg.decimal("ien.saSegundos", 30) * 1000;
        this.margenSeg = cfg.decimal("planificador.margenMinutos", 0) * 60;
        this.maximoPorEntrega = cfg.entero("ien.maximoProductosPorEntrega", 24);
        this.duracionSeg = escenario == Escenario.SEMANAL ? 5 * 24 * 3600 : Double.POSITIVE_INFINITY;
        this.horizonteRutaSeg = cfg.decimal("planificador.horizonteRutaHoras", 8) * 3600;
        this.factor = switch (escenario) {
            case SEMANAL -> duracionSeg / (cfg.decimal("escenario.semanal.minutosReales", 30) * 60);
            case COLAPSO -> cfg.decimal("escenario.colapso.factor", 600);
            case DIA_A_DIA -> 1.0;
        };

        int capInt = cfg.entero("ien.capacidadIntermedio", 1000);
        almacenes.add(new Almacen("ALM-CENTRAL", nodo(cfg.texto("ien.almacenCentral", "27,14")), -1));
        almacenes.add(new Almacen("ALM-NOROESTE", nodo(cfg.texto("ien.almacenNorOeste", "12,38")), capInt));
        almacenes.add(new Almacen("ALM-ESTE", nodo(cfg.texto("ien.almacenEste", "57,27")), capInt));

        Nodo central = almacenes.get(0).nodo;
        agregarFlota(TipoUnidad.AUTO, cfg.entero("ien.autos", 10), central);
        agregarFlota(TipoUnidad.MOTO, cfg.entero("ien.motos", 15), central);
        agregarFlota(TipoUnidad.BICI, cfg.entero("ien.bicicletas", 12), central);
        for (Unidad u : unidades) unidadPorId.put(u.id, u);

        this.proximaRecarga = inicio.toLocalDate().atTime(23, 59, 59);
        if (!proximaRecarga.isAfter(inicio)) proximaRecarga = proximaRecarga.plusDays(1);
    }

    private void agregarFlota(TipoUnidad tipo, int n, Nodo central) {
        for (int k = 1; k <= n; k++) unidades.add(new Unidad(String.format("%s%02d", tipo.prefijo, k), tipo, central));
    }

    private static Nodo nodo(String xy) {
        String[] a = xy.split(",");
        return new Nodo(Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim()));
    }

    private LocalDateTime instante(double seg) {
        return inicio.plusNanos((long) (seg * 1e9));
    }

    private double segundos(LocalDateTime x) {
        return Duration.between(inicio, x).toNanos() / 1e9;
    }

    // ------------------------------------------------------------------ hilo

    /** Botón Detener (v02): congela el reloj simulado; todo el estado se conserva para reanudar. */
    public synchronized boolean pausar() {
        if (estado != Estado.EJECUTANDO) return false;
        estado = Estado.PAUSADA;
        return true;
    }

    /** Botón Reanudar (v02): la simulación sigue exactamente desde el instante en que se detuvo. */
    public synchronized boolean reanudar() {
        if (estado != Estado.PAUSADA) return false;
        estado = Estado.EJECUTANDO;
        return true;
    }

    /** Termina la simulación definitivamente (se usa al iniciar otra). */
    public void cancelar() {
        cancelada = true;
    }

    public Estado estado() {
        return estado;
    }

    public String algoritmo() {
        return planificador.nombre();
    }

    @Override
    public void run() {
        estado = Estado.EJECUTANDO;
        long ultimo = System.nanoTime();
        try {
            while (!cancelada && estado != Estado.TERMINADA) {
                Thread.sleep(TICK_MS);
                long ahoraNs = System.nanoTime();
                // v03: se simula el tiempo real que pasó de verdad desde la vuelta anterior (incluye lo que tardó
                // el planificador), no 100 ms fijos. Antes la simulación se atrasaba respecto del reloj real.
                long transcurrido = Math.min(ahoraNs - ultimo, MAX_VUELTA_NS);
                ultimo = ahoraNs;
                synchronized (this) {
                    if (estado == Estado.EJECUTANDO) {      // en pausa el reloj simulado no avanza
                        nsReales += transcurrido;
                        avanzar(factor * transcurrido / 1e9);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            synchronized (this) {
                ultimoError = e.toString();
                terminar("Error: " + e.getMessage(), "ERROR");
            }
        } finally {
            synchronized (this) {
                if (estado != Estado.TERMINADA) terminar("Reemplazada por una nueva simulación", "REEMPLAZADA");
            }
            try {
                fuente.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Avanza el reloj simulado lo más rápido posible (usado también para pruebas sin servidor). */
    public synchronized void avanzar(double segundosSimulados) throws IOException {
        if (estado == Estado.PREPARADA) estado = Estado.EJECUTANDO;
        double resto = segundosSimulados;
        while (resto > 0 && estado == Estado.EJECUTANDO) {
            double dt = Math.min(resto, PASO_MAX_SEG);
            paso(dt);
            resto -= dt;
        }
    }

    private void terminar(String motivo, String tipo) {
        if (estado == Estado.TERMINADA) return;
        estado = Estado.TERMINADA;
        motivoFin = motivo;
        tipoFin = tipo;
    }

    /**
     * Colapso logístico: la simulación se detiene. v04: se guarda el reporte del colapso tal como estaba en ese
     * instante (criterio, fecha y hora simulada, Tc, pedido involucrado y estado de la flota), que el visualizador
     * muestra al terminar. pedido es null en el criterio C (Ta >= Sa), que no depende de un pedido.
     */
    private void colapso(String criterio, String detalle, Pedido pedido) {
        tcHoras = t / 3600.0;
        int[] f = contarFlota();
        StringBuilder b = new StringBuilder(1024);
        b.append("{\"criterio\":").append(Json.str(criterio))
                .append(",\"descripcion\":").append(Json.str(criterio.equals("P")
                        ? "Un pedido llegó a su límite sin ser entregado"
                        : "El planificador tardó Sa o más en una ejecución (Ta >= Sa)"))
                .append(",\"detalle\":").append(Json.str(detalle))
                .append(",\"hora\":").append(hora(t))
                .append(",\"tc\":").append(Json.num(tcHoras))
                .append(",\"pedido\":");
        if (pedido == null) b.append("null");
        else {
            Set<String> llevan = new LinkedHashSet<>();
            for (Unidad u : unidades) {
                for (Parada x : u.paradas) if (x.tipo() == Parada.Tipo.ENTREGA && pedido.id.equals(x.pedidoId())) llevan.add(u.id);
            }
            b.append("{\"id\":").append(Json.str(pedido.id))
                    .append(",\"cliente\":").append(Json.str(pedido.cliente))
                    .append(",\"x\":").append(pedido.nodo.x()).append(",\"y\":").append(pedido.nodo.y())
                    .append(",\"cantidad\":").append(pedido.cantidad)
                    .append(",\"entregado\":").append(pedido.entregado)
                    .append(",\"plazo\":").append(pedido.plazoHoras)
                    .append(",\"registro\":").append(hora(pedido.registro))
                    .append(",\"limite\":").append(hora(pedido.limite()))
                    .append(",\"unidades\":");
            lista(b, llevan);
            b.append('}');
        }
        b.append(",\"flota\":{\"total\":").append(unidades.size())
                .append(",\"enRuta\":").append(f[0]).append(",\"disponibles\":").append(f[1])
                .append(",\"mantenimiento\":").append(f[2]).append(",\"averiadas\":").append(f[3])
                .append(",\"averiadasTipo1\":").append(f[4]).append(",\"averiadasTipo2\":").append(f[5])
                .append(",\"averiadasTipo3\":").append(f[6]).append("}}");
        colapsoJson = b.toString();
        terminar(String.format("Colapso %s: %s (Tc = %.1f h)", criterio, detalle, tcHoras), "COLAPSO");
    }

    /** Unidades en ruta (incluye entregando, refrigerio y trasvase), disponibles, en mantenimiento y averiadas (total y por tipo). */
    private int[] contarFlota() {
        int[] f = new int[7];
        for (Unidad u : unidades) {
            String e = estadoUnidad(u);
            if (e.equals("AVERIADA")) {
                f[3]++;
                f[3 + u.averia.tipo]++;
            } else if (e.equals("MANTENIMIENTO")) f[2]++;
            else if (e.equals("DISPONIBLE")) f[1]++;
            else f[0]++;
        }
        return f;
    }

    // ------------------------------------------------------------------ un paso de tiempo

    private void paso(double dt) throws IOException {
        t += dt;
        LocalDateTime ahora = instante(t);

        // 1. pedidos nuevos
        for (Formato.LineaPedido l : fuente.pedidosHasta(ahora)) {
            if (l.registro().isBefore(inicio)) continue;
            agregarPedido(l.cliente(), l.nodo(), l.cantidad(), l.plazoHoras(), segundos(l.registro()));
        }

        // 2. bloqueos que empiezan o terminan (se aplican sobre la retícula del planificador)
        boolean cambio = false;
        for (Bloqueo b : fuente.bloqueosHasta(ahora)) {
            if (b.fin().isAfter(ahora)) {
                bloqueosActivos.add(b);
                cambio = true;
            }
        }
        cambio |= bloqueosActivos.removeIf(b -> !b.fin().isAfter(ahora));
        if (cambio) {
            mapa.limpiarBloqueos();
            for (Bloqueo b : bloqueosActivos) for (Nodo n : b.nodos()) mapa.bloquear(caminos.indice(n));
        }

        // 3. recarga diaria de los almacenes intermedios (23:59:59)
        while (!ahora.isBefore(proximaRecarga)) {
            almacenes.forEach(Almacen::recargar);
            proximaRecarga = proximaRecarga.plusDays(1);
        }

        // 3b. mantenimiento preventivo (v02): entra a las 00:00 del día indicado y sale al terminar el día
        LocalDate hoy = ahora.toLocalDate();
        for (Unidad u : unidades) {
            boolean toca = mantenimiento.enMantenimiento(u.id, hoy);
            if (toca && !u.enMantenimiento) entrarMantenimiento(u);
            else if (!toca && u.enMantenimiento) u.enMantenimiento = false;   // vuelve disponible en el central
        }

        // 3c. averías (v03): traslados al almacén central y retornos de las vigentes
        revisarAverias();

        // 4. ciclo de planificación (también el extraordinario que pide una avería)
        if (t >= proximoCiclo) {
            planificar();
            proximoCiclo = t + cicloSeg;
            if (estado != Estado.EJECUTANDO) return;
        }

        // 5. movimiento de las unidades
        for (Unidad u : unidades) mover(u, t - dt, t);

        // 6. pedidos vencidos y detector de colapso (criterio P: vence un plazo sin entrega)
        for (Pedido p : pedidos.values()) {
            if (p.vencido) continue;
            boolean tarde = p.completo() ? p.llegadaFinal > p.limite() : t > p.limite();
            if (tarde) {
                p.vencido = true;
                vencidos++;
                if (escenario == Escenario.COLAPSO) {
                    colapso("P", "el pedido " + p.id + " venció sin entrega", p);
                    return;
                }
            }
        }
        pedidos.values().removeIf(p -> p.completo() && p.asignado <= 0 && t > p.liberado);

        // 7. fin de la simulación de 5 días
        if (t >= duracionSeg) terminar("Fin de la simulación de 5 días", "FIN_PERIODO");

        // 8. v04: hasta el colapso, sin colapso: se acabaron los datos de ventas y ya se entregó todo lo pendiente
        //    (si un pedido vence antes, eso ya es colapso y la simulación terminó en el paso 6)
        if (escenario == Escenario.COLAPSO && finDatos != null && !ahora.isBefore(finDatos)) {
            boolean todoEntregado = true;
            for (Pedido p : pedidos.values()) if (!p.completo()) todoEntregado = false;
            if (todoEntregado) {
                terminar("Sin colapso: se entregaron todos los pedidos hasta el fin de los datos ("
                        + finDatos.minusMinutes(1).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        + ")", "SIN_COLAPSO");
            }
        }
    }

    /**
     * Inicio del día de mantenimiento. La planificación ya evita asignarle rutas que crucen las 00:00
     * (ver planificar), así que normalmente la unidad está libre. Si aún tuviera paradas, según el curso
     * "debe retornar de inmediato": sus entregas pendientes vuelven a planificarse y la unidad pasa al
     * almacén central con su carga (traslado instantáneo, como en las averías tipo 2 y 3).
     * v03: si tenía una avería tipo 2 o 3 y seguía en el lugar, pasa al almacén central con lo no trasvasado
     * (la avería sigue vigente hasta su retorno); las unidades que iban a trasvasar sus paquetes se replanifican.
     */
    private void entrarMantenimiento(Unidad u) {
        Averia av = u.averia;
        if (av != null && av.seTraslada() && av.fase == Averia.Fase.EN_LUGAR) trasladarAlCentral(u);
        for (Parada p : u.paradas) liberar(p);
        u.paradas.clear();
        u.camino.clear();
        u.progreso = 0;
        u.carga = 0;
        u.nodo = almacenes.get(0).nodo;
        u.ocupadoHasta = t;
        u.ocupacion = "";
        u.enMantenimiento = true;
    }

    private void agregarPedido(String cliente, Nodo nodo, int cantidad, int plazoHoras, double registro) {
        if (!mapa.dentro(nodo.x(), nodo.y()) || cantidad <= 0) return;
        String id = "P" + (++contador);
        pedidos.put(id, new Pedido(id, cliente, nodo, cantidad, plazoHoras, registro));
        recibidos++;
    }

    /** Registro en línea (operación día a día). Se planifica en el siguiente paso. */
    public synchronized String registrarPedido(String cliente, Nodo nodo, int cantidad, int plazoHoras) {
        agregarPedido(cliente, nodo, cantidad, plazoHoras, t);
        proximoCiclo = t;
        return "P" + contador;
    }

    /** Devuelve al planificador el pedido de una entrega que sale de un itinerario. */
    private void liberar(Parada p) {
        if (p.tipo() != Parada.Tipo.ENTREGA) return;
        Pedido ped = pedidos.get(p.pedidoId());
        if (ped != null) ped.asignado -= p.cantidad();
    }

    // ------------------------------------------------------------------ averías (v03)

    /**
     * Registro desde el visualizador (RF05-04, RF05-05): la avería ocurre en el instante actual de la simulación,
     * en cualquiera de los escenarios. Lanza IllegalArgumentException si la unidad o el tipo no son válidos e
     * IllegalStateException si la unidad no puede averiarse ahora (ya averiada, en mantenimiento o simulación
     * terminada). Si la simulación está detenida, la replanificación se hace en este mismo momento.
     */
    public synchronized Averia registrarAveria(String unidadId, int tipo) {
        if (estado == Estado.TERMINADA) throw new IllegalStateException("La simulación ya terminó");
        ReglasAveria.validarTipo(tipo);
        Unidad u = unidadPorId.get(unidadId == null ? "" : unidadId.trim().toUpperCase());
        if (u == null) throw new IllegalArgumentException("No existe la unidad " + unidadId);
        String motivo = motivoNoAveriable(u);
        if (motivo != null) throw new IllegalStateException(motivo);
        Averia av = averiar(u, tipo, t, Averia.VISUALIZADOR);
        if (estado == Estado.PAUSADA) {               // en pausa el reloj no avanza: se replanifica ya
            planificar();
            proximoCiclo = t + cicloSeg;
        }
        return av;
    }

    /** Por qué una unidad no puede averiarse ahora (null si puede). */
    private String motivoNoAveriable(Unidad u) {
        if (u.averia != null) {
            return u.id + " ya está averiada (tipo " + u.averia.tipo + ") hasta el "
                    + instante(u.averia.disponible).withNano(0).toString().replace('T', ' ');
        }
        if (u.enMantenimiento) return u.id + " está en mantenimiento preventivo";
        return null;
    }

    /**
     * La unidad queda detenida en su posición (RF05-04): se liberan sus paradas para que sus pedidos se reasignen
     * (RF07-01) y se pide un ciclo de planificación inmediato. Si iba a mitad de una cuadra, conserva solo esa
     * cuadra: al terminar una avería tipo 1 la completa hasta la esquina y desde allí sigue con su nuevo plan.
     */
    private Averia averiar(Unidad u, int tipo, double cuando, String origen) {
        double x = u.x(), y = u.y();
        Set<String> afectados = new LinkedHashSet<>();
        for (Parada p : u.paradas) {
            if (p.tipo() == Parada.Tipo.ENTREGA) afectados.add(p.pedidoId());
            liberar(p);
        }
        u.paradas.clear();
        Nodo esquina = esquinaCercana(u);
        detenerEnLaCuadra(u);

        LocalDateTime ini = instante(cuando);
        double finEnLugar = segundos(reglas.finEnLugar(tipo, ini));
        double disponible = segundos(reglas.disponible(tipo, ini));
        Averia av = new Averia("AV" + (++contadorAverias), u.id, u.tipo, tipo, origen, cuando, x, y, esquina,
                finEnLugar, disponible, u.carga, new ArrayList<>(afectados));
        u.averia = av;
        u.ocupacion = "";
        u.ocupadoHasta = Math.max(u.ocupadoHasta, disponible);       // no se mueve mientras dure la avería
        averias.add(av);
        proximoCiclo = Math.min(proximoCiclo, t);                     // replanificación inmediata
        System.out.printf("[%s] Avería tipo %d (%s) en %s en (%.1f, %.1f) con %d paquetes; %d pedidos a replanificar; disponible %s%n",
                ini.withNano(0), tipo, origen, u.id, x, y, u.carga, afectados.size(), instante(disponible).withNano(0));
        return av;
    }

    /** Cambios de fase de las averías vigentes: traslado al almacén central (tipos 2 y 3) y retorno a la operación. */
    private void revisarAverias() {
        for (Unidad u : unidades) {
            Averia av = u.averia;
            if (av == null) continue;
            if (av.seTraslada() && av.fase == Averia.Fase.EN_LUGAR && t >= av.finEnLugar) trasladarAlCentral(u);
            if (t >= av.disponible) finalizarAveria(u);
        }
    }

    /** Tipos 2 y 3: al cumplir las 4 h pasa de forma instantánea al almacén central con los paquetes no trasvasados. */
    private void trasladarAlCentral(Unidad u) {
        Averia av = u.averia;
        av.devueltos += u.carga;
        devueltos += u.carga;
        u.carga = 0;
        u.nodo = almacenes.get(0).nodo;
        u.camino.clear();
        u.progreso = 0;
        u.ocupacion = "";
        av.fase = Averia.Fase.EN_CENTRAL;
        cancelarTrasvases(u.id);
        System.out.printf("[%s] %s pasa al almacén central con %d paquetes no trasvasados (trasvasados: %d)%n",
                instante(t).withNano(0), u.id, av.devueltos, av.trasvasados);
    }

    private void finalizarAveria(Unidad u) {
        Averia av = u.averia;
        if (av.seTraslada() && av.fase == Averia.Fase.EN_LUGAR) trasladarAlCentral(u);
        av.fase = Averia.Fase.FINALIZADA;
        av.fin = t;
        u.averia = null;
        // los tipos 2 y 3 vuelven en el almacén central y no estaban en el plan: se incorporan de inmediato
        if (av.seTraslada()) proximoCiclo = Math.min(proximoCiclo, t);
        System.out.printf("[%s] %s vuelve a operar (avería tipo %d)%n", instante(t).withNano(0), u.id, av.tipo);
    }

    /**
     * Si la unidad averiada ya no está en el lugar, las unidades que iban a recoger sus paquetes sueltan ese
     * trasvase y las entregas que dependían de él; vuelven a planificarse en el siguiente paso.
     */
    private void cancelarTrasvases(String averiada) {
        String deposito = TRASVASE + averiada;
        for (Unidad w : unidades) {
            List<Parada> lista = new ArrayList<>(w.paradas);
            int desde = -1;
            for (int i = 0; i < lista.size() && desde < 0; i++) {
                Parada p = lista.get(i);
                if (p.tipo() == Parada.Tipo.RECARGA && deposito.equals(p.almacenId())) desde = i;
            }
            if (desde < 0) continue;
            for (int i = desde; i < lista.size(); i++) liberar(lista.get(i));
            w.paradas.clear();
            w.paradas.addAll(lista.subList(0, desde));
            if (desde == 0) detenerEnLaCuadra(w);
            proximoCiclo = Math.min(proximoCiclo, t);
        }
    }

    /** Esquina más cercana a la posición de la unidad (allí se hace el trasvase). */
    private static Nodo esquinaCercana(Unidad u) {
        Nodo sig = u.camino.peekFirst();
        return sig != null && u.progreso >= 0.5 ? sig : u.nodo;
    }

    /** Deja a la unidad sin camino, salvo la cuadra que está recorriendo (si va a mitad de una). */
    private static void detenerEnLaCuadra(Unidad u) {
        if (u.camino.isEmpty()) return;
        Nodo sig = u.camino.peekFirst();
        u.camino.clear();
        if (u.progreso > 0) u.camino.add(sig);
    }

    private static boolean esTrasvase(String almacenId) {
        return almacenId != null && almacenId.startsWith(TRASVASE);
    }

    /** Paquetes que aún tiene la unidad averiada de un depósito de trasvase (0 si ya no está en el lugar). */
    private int saldoTrasvase(String deposito) {
        Unidad a = unidadPorId.get(deposito.substring(TRASVASE.length()));
        return a == null || a.averia == null || a.averia.fase != Averia.Fase.EN_LUGAR ? 0 : a.carga;
    }

    private boolean esAlmacen(Nodo n) {
        for (Almacen a : almacenes) if (a.nodo.equals(n)) return true;
        return false;
    }

    // ------------------------------------------------------------------ planificación (rutapaq-planner)

    private void planificar() {
        ciclos++;

        // v02: copia de los itinerarios por si el planificador falla (se restauran en el paso e)
        Map<Unidad, List<Parada>> antes = new HashMap<>();
        for (Unidad u : unidades) antes.put(u, new ArrayList<>(u.paradas));

        // a) cada unidad conserva solo la parada hacia la que ya se mueve; el resto se replanifica
        for (Unidad u : unidades) recortar(u);

        // b) estado de la flota tal como lo espera el planificador
        //    v02: no reciben rutas las unidades en mantenimiento ni las que entran a mantenimiento antes
        //    de que termine el horizonte de ruta (así ninguna ruta cruza las 00:00 de su mantenimiento).
        //    v03: tampoco las averiadas tipo 2 y 3; la de tipo 1 sí, disponible al terminar sus 2 h y con su carga.
        Map<String, Integer> reservado = new HashMap<>();
        List<Vehiculo> flota = new ArrayList<>();
        LocalDateTime finHorizonte = instante(t + horizonteRutaSeg);
        for (Unidad u : unidades) {
            Vehiculo v = vehiculo(u, reservado);   // siempre: reserva el stock de sus recargas conservadas
            boolean disponible = !u.enMantenimiento && !mantenimiento.empiezaEntre(u.id, instante(t), finHorizonte)
                    && (u.averia == null || !u.averia.seTraslada());
            if (disponible) flota.add(v);
        }

        // c) almacenes con el saldo que queda tras las recargas ya comprometidas
        List<pe.pucp.paqrap.planner.model.Almacen> alm = new ArrayList<>();
        for (Almacen a : almacenes) {
            pe.pucp.paqrap.planner.model.Almacen x = new pe.pucp.paqrap.planner.model.Almacen(a.id,
                    a.infinito() ? TipoAlmacen.CENTRAL : TipoAlmacen.INTERMEDIO, caminos.indice(a.nodo),
                    a.nodo.x(), a.nodo.y(), a.infinito() ? 0 : a.capacidad);
            if (!a.infinito()) x.retirar(Math.min(a.capacidad, a.capacidad - a.stock + reservado.getOrDefault(a.id, 0)));
            alm.add(x);
        }
        // c2) v03: unidades con avería tipo 2 o 3 que siguen en el lugar: sus paquetes se pueden trasvasar mientras
        //     la recogida termine antes de que las lleven al almacén central (depósito temporal del planificador).
        if (reglas.trasvaseHabilitado) {
            for (Unidad u : unidades) {
                Averia av = u.averia;
                if (av == null || !av.seTraslada() || av.fase != Averia.Fase.EN_LUGAR || esAlmacen(av.nodo)) continue;
                String id = TRASVASE + u.id;
                int saldo = u.carga - reservado.getOrDefault(id, 0);
                if (saldo <= 0 || t + reglas.segundosTrasvase() > av.finEnLugar) continue;
                pe.pucp.paqrap.planner.model.Almacen x = new pe.pucp.paqrap.planner.model.Almacen(id,
                        TipoAlmacen.TRASVASE, caminos.indice(av.nodo), av.nodo.x(), av.nodo.y(), saldo);
                x.setDisponibleHasta(instante(av.finEnLugar));
                x.setSegundosAtencion(Math.round(reglas.segundosTrasvase()));
                alm.add(x);
            }
        }

        // d) cartera: todo lo que falta asignar, fraccionado como en el IEN (entregas parciales)
        //    v03: un pedido vencido se sigue planificando (debe llegar aunque sea tarde), con la prioridad más baja;
        //    un pedido que no se logró asignar en el ciclo anterior se parte en lotes más pequeños (particion).
        Map<String, String> original = new HashMap<>();
        List<pe.pucp.paqrap.planner.model.Pedido> cartera = new ArrayList<>();
        int[] lotes = tamanosDeLote();
        for (Pedido p : pedidos.values()) {
            if (p.porAsignar() <= 0) continue;
            for (pe.pucp.paqrap.planner.model.Pedido parte : LectorVentas.fraccionar(pedidoParaPlanificar(p),
                    lotes[Math.min(p.particion, lotes.length - 1)])) {
                cartera.add(parte);
                original.put(parte.getId(), p.id);
            }
        }
        if (cartera.isEmpty()) return;

        // e) ejecución del planificador (Ta)
        PlanDistribucion plan;
        long ini = System.nanoTime();
        try {
            plan = planificador.planificar(new InstanciaPlanificacion(mapa, alm, flota, cartera, instante(t),
                    cfg.planificador()));
        } catch (RuntimeException e) {
            // v02: en la v01 las unidades quedaban sin tareas hasta el siguiente ciclo porque recortar()
            // ya había borrado sus paradas. Ahora conservan su itinerario anterior.
            ultimoError = "Planificador: " + e;
            restaurar(antes);
            return;
        }
        msUltimoPlan = (System.nanoTime() - ini) / 1_000_000;
        msMaximoPlan = Math.max(msMaximoPlan, msUltimoPlan);
        ultimoError = "";                                    // v02: el planificador volvió a funcionar
        iteraciones = planificador.iteracionesEjecutadas();
        sinPlanificar = plan.getNoPlanificados().size();
        // v03: lo que no se pudo asignar se parte en lotes más pequeños en el ciclo siguiente (una vez por ciclo)
        Set<String> noAsignados = new LinkedHashSet<>();
        for (pe.pucp.paqrap.planner.model.Pedido x : plan.getNoPlanificados()) noAsignados.add(original.get(x.getId()));
        for (String id : noAsignados) {
            Pedido p = pedidos.get(id);
            if (p != null && p.particion < lotes.length - 1) p.particion++;
        }
        if (escenario == Escenario.COLAPSO && msUltimoPlan >= saMs) {
            colapso("C", "el planificador tardó " + msUltimoPlan + " ms (Ta >= Sa)", null);
            return;
        }

        // f) el plan pasa a ser el itinerario de cada unidad
        for (Ruta r : plan.getRutas()) {
            Unidad u = unidadPorId.get(r.getVehiculo().getId());
            if (u == null) continue;
            boolean refrigerio = false;
            Nodo ultimo = u.nodo;
            for (ParadaRuta pr : r.getItinerario()) {
                Nodo n = caminos.nodo(pr.nodo());
                switch (pr.tipo()) {
                    // El evaluador cuenta el refrigerio antes de la siguiente llegada:
                    // aquí se toma al llegar al nodo de esa parada, antes de atenderla.
                    case REFRIGERIO -> refrigerio = true;
                    case RECARGA -> {                        // v03: también un trasvase (TRV-TTNN)
                        if (refrigerio) u.paradas.addLast(Parada.refrigerio(n));
                        u.paradas.addLast(Parada.recarga(n, pr.referencia(), pr.cantidad()));
                        refrigerio = false;
                    }
                    case ENTREGA -> {
                        Pedido p = pedidos.get(original.get(pr.referencia()));
                        if (p == null) continue;
                        if (refrigerio) u.paradas.addLast(Parada.refrigerio(n));
                        u.paradas.addLast(Parada.entrega(n, p.id, pr.cantidad()));
                        p.asignado += pr.cantidad();
                        refrigerio = false;
                    }
                }
                ultimo = n;
            }
            if (refrigerio) u.paradas.addLast(Parada.refrigerio(ultimo));
        }
    }

    /**
     * v03: el pedido tal como lo recibe el planificador. Si ya venció, el plazo original ya no se puede cumplir y,
     * con plazos duros, el ALNS no lo asignaría nunca; pero el producto debe llegar aunque sea tarde. Por eso se le
     * entrega como un pedido nuevo registrado ahora con el plazo de PLAZO_VENCIDO_HORAS (36 h): prioridad REGULAR,
     * la más baja, para que no le quite el lugar a los pedidos que aún pueden llegar a tiempo. No queda relegado: el
     * planificador castiga mucho dejar un pedido sin planificar y ninguna ruta pasa de planificador.horizonteRutaHoras,
     * así que sale en las siguientes rutas con espacio. Los indicadores lo siguen contando como vencido (Pedido.limite
     * no cambia) y en el escenario de colapso no aplica: la simulación ya terminó cuando venció el primero.
     */
    private pe.pucp.paqrap.planner.model.Pedido pedidoParaPlanificar(Pedido p) {
        boolean vencido = t > p.limite();
        return new pe.pucp.paqrap.planner.model.Pedido(p.id, p.cliente, caminos.indice(p.nodo), p.nodo.x(), p.nodo.y(),
                p.porAsignar(), vencido ? instante(t) : instante(p.registro - margenSeg),
                vencido ? PLAZO_VENCIDO_HORAS : p.plazoHoras, null);
    }

    /**
     * v03: tamaños de lote para partir un pedido: ien.maximoProductosPorEntrega y la mayor capacidad de la flota que
     * sea menor que ese máximo (24 y 8, la de la moto, con la flota del caso). Un pedido que cabe en un auto se
     * planifica entero; si el planificador no logra asignarlo, en el ciclo siguiente se parte según la capacidad de la
     * moto (10 = 8 + 2) y ya no más. No se baja a la capacidad de la bicicleta: en la prueba de estrés (los 10 autos
     * averiados 72 h con los pedidos de octubre de 2026) partir hasta 4 dejó 98,4-99,2 % a tiempo frente a 99,3-99,5 %
     * partiendo solo hasta 8, porque cuando la flota está saturada un pedido también queda sin asignar por falta de
     * unidades libres, no por su tamaño, y cada entrega parcial suma 1 h de acondicionamiento.
     */
    private int[] tamanosDeLote() {
        int menor = 0;
        for (Unidad u : unidades) {
            int c = u.tipo.capacidad;
            if (c > menor && c < maximoPorEntrega) menor = c;
        }
        return menor > 0 ? new int[]{maximoPorEntrega, menor} : new int[]{maximoPorEntrega};
    }

    /**
     * v02: deshace recortar() cuando el planificador falla. recortar() conserva un prefijo de las paradas
     * y libera el resto; aquí se devuelven esas paradas y sus pedidos vuelven a quedar asignados.
     */
    private void restaurar(Map<Unidad, List<Parada>> antes) {
        for (Unidad u : unidades) {
            List<Parada> previas = antes.get(u);
            for (int i = u.paradas.size(); i < previas.size(); i++) {
                Parada p = previas.get(i);
                if (p.tipo() == Parada.Tipo.ENTREGA) {
                    Pedido ped = pedidos.get(p.pedidoId());
                    if (ped != null) ped.asignado += p.cantidad();
                }
            }
            u.paradas.clear();
            u.paradas.addAll(previas);
        }
    }

    /**
     * Conserva la parada hacia la que la unidad ya se mueve (y su refrigerio previo); libera el resto.
     * v03: una unidad averiada no se está moviendo, así que no conserva ninguna.
     */
    private void recortar(Unidad u) {
        List<Parada> conservar = new ArrayList<>();
        boolean enMovimiento = !u.camino.isEmpty() && u.averia == null;
        boolean cerrado = !enMovimiento;
        for (Parada p : u.paradas) {
            if (!cerrado) {
                conservar.add(p);
                if (p.tipo() != Parada.Tipo.REFRIGERIO) cerrado = true;
                continue;
            }
            liberar(p);
        }
        u.paradas.clear();
        u.paradas.addAll(conservar);
    }

    /** km entre dos nodos respetando bloqueos (Manhattan si no hay camino). */
    private double km(Nodo a, Nodo b) {
        int d = caminos.distancia(a, b);
        return d < 0 ? a.manhattan(b) : d;
    }

    /** Dónde, cuándo y con cuánta carga queda libre la unidad tras sus paradas conservadas. */
    private Vehiculo vehiculo(Unidad u, Map<String, Integer> reservado) {
        double tl = Math.max(t, u.ocupadoHasta);
        Nodo n = u.nodo;
        int carga = u.carga;
        LocalDateTime turnoRefrigerio = u.turnoConRefrigerio;
        boolean primero = true;
        for (Parada p : u.paradas) {
            double km;
            if (primero && !u.camino.isEmpty()) {
                km = u.camino.size() - u.progreso;
                Nodo fin = u.camino.peekLast();
                if (!fin.equals(p.nodo())) km += km(fin, p.nodo());   // v03: termina la cuadra y sigue a la parada
            } else {
                km = km(n, p.nodo());
            }
            primero = false;
            tl += u.tipo.segundos(km);
            n = p.nodo();
            switch (p.tipo()) {
                case ENTREGA -> {
                    tl += ENTREGA_SEG;
                    carga = Math.max(0, carga - p.cantidad());
                }
                case RECARGA -> {
                    if (esTrasvase(p.almacenId())) {
                        // v03: recoge lo que aún no reservaron otras unidades; el trasvase toma 30 min
                        int libre = Math.max(0, saldoTrasvase(p.almacenId()) - reservado.getOrDefault(p.almacenId(), 0));
                        int q = Math.min(u.tipo.capacidad - carga, libre);
                        reservado.merge(p.almacenId(), q, Integer::sum);
                        carga += q;
                        tl += reglas.segundosTrasvase();
                    } else {
                        reservado.merge(p.almacenId(), u.tipo.capacidad - carga, Integer::sum);
                        carga = u.tipo.capacidad;
                    }
                }
                case REFRIGERIO -> {
                    turnoRefrigerio = TurnoConductor.vigenteEn(instante(tl)).getInicio();
                    tl += REFRIGERIO_SEG;
                }
            }
        }
        if (u.paradas.isEmpty() && !u.camino.isEmpty()) {
            // v03: unidad detenida a mitad de cuadra (avería tipo 1 o trasvase cancelado): queda libre en la esquina
            tl += u.tipo.segundos(u.camino.size() - u.progreso);
            n = u.camino.peekLast();
        }
        Vehiculo v = new Vehiculo(u.id, TipoVehiculo.valueOf(u.tipo.name()), caminos.indice(n), instante(tl));
        v.setCarga(carga);
        TurnoConductor turno = TurnoConductor.vigenteEn(instante(tl));
        turno.setRefrigerioConsumido(turno.getInicio().equals(turnoRefrigerio));
        v.setTurno(turno);
        return v;
    }

    // ------------------------------------------------------------------ ejecución del itinerario

    private void mover(Unidad u, double desde, double hasta) {
        if (u.averia != null && u.averia.seTraslada()) return;  // v03: tipos 2 y 3 no se mueven por sí mismas
        double tl = desde;
        int guardia = 0;
        while (tl < hasta && guardia++ < 10_000) {
            if (u.ocupadoHasta > tl) {                            // entrega, refrigerio, trasvase o avería tipo 1
                tl = Math.min(hasta, u.ocupadoHasta);
                continue;
            }
            u.ocupacion = "";
            Parada p = u.paradas.peekFirst();
            if (p == null && u.camino.isEmpty()) return;          // sin trabajo

            if (u.camino.isEmpty()) {
                if (u.nodo.equals(p.nodo())) {
                    ejecutar(u, p, tl);
                    u.paradas.pollFirst();
                    continue;
                }
                List<Nodo> c = caminos.camino(u.nodo, p.nodo());
                if (c == null) return;                           // sin camino por bloqueos: espera
                u.camino.addAll(c);
                u.progreso = 0;
            }

            // v03: sin paradas (p == null) la unidad solo termina la cuadra en la que se detuvo
            Nodo sig = u.camino.peekFirst();
            if (p != null && u.progreso == 0 && !sig.equals(p.nodo()) && caminos.bloqueado(sig)) {
                u.camino.clear();                                // apareció un bloqueo: recalcular
                List<Nodo> c = caminos.camino(u.nodo, p.nodo());
                if (c == null) return;
                u.camino.addAll(c);
                continue;
            }

            double velKmS = u.tipo.velocidadKmh / 3600.0;
            double faltaKm = 1.0 - u.progreso;
            double necesario = faltaKm / velKmS;
            if (tl + necesario <= hasta) {
                tl += necesario;
                recorrer(u, faltaKm);
                u.nodo = u.camino.pollFirst();
                u.progreso = 0;
            } else {
                double avance = (hasta - tl) * velKmS;
                recorrer(u, avance);
                u.progreso += avance;
                tl = hasta;
            }
        }
    }

    private void recorrer(Unidad u, double km) {
        u.km += km;
        kmTotal += km;
        costo += km * u.tipo.costoKm;
    }

    private void ejecutar(Unidad u, Parada p, double tl) {
        switch (p.tipo()) {
            case ENTREGA -> {
                Pedido ped = pedidos.get(p.pedidoId());
                if (ped == null) return;
                ped.asignado -= p.cantidad();
                int q = Math.min(Math.min(u.carga, p.cantidad()), ped.cantidad - ped.entregado);
                if (q <= 0) return;                              // sin carga: se replanifica
                ped.entregado += q;
                u.carga -= q;
                u.ocupadoHasta = tl + ENTREGA_SEG;
                u.ocupacion = "ENTREGANDO";
                if (ped.completo()) {
                    ped.llegadaFinal = tl;
                    ped.liberado = tl + ENTREGA_SEG;
                    completados++;
                    if (tl <= ped.limite()) enPlazo++;
                }
            }
            case RECARGA -> {
                if (esTrasvase(p.almacenId())) {
                    trasvasar(u, p, tl);
                    return;
                }
                for (Almacen a : almacenes) {
                    if (!a.id.equals(p.almacenId())) continue;
                    int q = Math.min(u.tipo.capacidad - u.carga, a.stock);
                    if (!a.infinito()) a.stock -= q;
                    u.carga += q;
                }
            }
            case REFRIGERIO -> {
                u.ocupadoHasta = tl + REFRIGERIO_SEG;
                u.ocupacion = "REFRIGERIO";
                u.turnoConRefrigerio = TurnoConductor.vigenteEn(instante(tl)).getInicio();
            }
        }
    }

    /**
     * v03: trasvase. La unidad receptora recoge de la averiada los paquetes que le caben y queda ocupada el tiempo
     * del trasvase (30 min). Si la averiada ya no está en el lugar o le quedan menos paquetes de los previstos, se
     * pide una replanificación: las entregas que no se puedan cubrir vuelven al planificador.
     */
    private void trasvasar(Unidad receptora, Parada p, double tl) {
        Unidad averiada = unidadPorId.get(p.almacenId().substring(TRASVASE.length()));
        Averia av = averiada == null ? null : averiada.averia;
        int q = av == null || av.fase != Averia.Fase.EN_LUGAR ? 0
                : Math.min(receptora.tipo.capacidad - receptora.carga, averiada.carga);
        if (q < p.cantidad()) proximoCiclo = Math.min(proximoCiclo, t);
        if (q <= 0) return;
        averiada.carga -= q;
        receptora.carga += q;
        av.trasvasados += q;
        av.receptoras.add(receptora.id);
        trasvasados += q;
        receptora.ocupadoHasta = tl + reglas.segundosTrasvase();
        receptora.ocupacion = "TRASVASE";
        System.out.printf("[%s] Trasvase: %s recoge %d paquetes de %s%n", instante(tl).withNano(0), receptora.id, q, averiada.id);
    }

    // ------------------------------------------------------------------ lectura del estado

    /** Pedidos cuyo plazo ya venció y que todavía no terminan de entregarse. */
    private int vencidosSinEntregar() {
        int n = 0;
        for (Pedido p : pedidos.values()) if (p.vencido && !p.completo()) n++;
        return n;
    }

    /**
     * % de pedidos a tiempo. v02: el denominador incluye los pedidos vencidos que aún no se entregan.
     * En la v01 era enPlazo / completados, así que un pedido atrasado que nunca llegaba no bajaba el %.
     * (Un pedido vencido que luego se completa ya está en "completados", sin contar en enPlazo.)
     */
    private double pctATiempo() {
        int base = completados + vencidosSinEntregar();
        return base == 0 ? 100.0 : 100.0 * enPlazo / base;
    }

    private String estadoUnidad(Unidad u) {
        if (u.averia != null) return "AVERIADA";                 // v03
        if (u.enMantenimiento) return "MANTENIMIENTO";
        if (!u.ocupacion.isEmpty()) return u.ocupacion;          // ENTREGANDO, REFRIGERIO o TRASVASE (v03)
        return u.paradas.isEmpty() ? "DISPONIBLE" : "EN_RUTA";
    }

    /** v03: hasta cuándo una unidad no está disponible (avería o mantenimiento); null si está operativa. */
    private LocalDateTime noDisponibleHasta(Unidad u) {
        LocalDateTime hasta = null;
        if (u.enMantenimiento) {
            LocalDate dia = instante(t).toLocalDate().plusDays(1);
            for (int i = 0; i < 31 && mantenimiento.enMantenimiento(u.id, dia); i++) dia = dia.plusDays(1);
            hasta = dia.atStartOfDay();
        }
        if (u.averia != null) {
            LocalDateTime fin = instante(u.averia.disponible);
            if (hasta == null || fin.isAfter(hasta)) hasta = fin;
        }
        return hasta;
    }

    private String hora(double seg) {
        return Json.str(instante(seg).withNano(0).toString());
    }

    private static String hora(LocalDateTime x) {
        return Json.str(x.withNano(0).toString());
    }

    private static String nombreAlmacen(String id) {
        return switch (id) {
            case "ALM-CENTRAL" -> "Almacén Central";
            case "ALM-NOROESTE" -> "Nor-Oeste";
            case "ALM-ESTE" -> "Este";
            default -> id;
        };
    }

    /** Foto completa del estado que se envía a los navegadores (cada visualizador.publicacionMs). */
    public synchronized String snapshotJson() {
        StringBuilder b = new StringBuilder(96_000);
        b.append("{\"estado\":").append(Json.str(estado.name()))
                .append(",\"escenario\":").append(Json.str(escenario.name()))
                .append(",\"algoritmo\":").append(Json.str(planificador.nombre()))
                .append(",\"inicio\":").append(Json.str(inicio.toString()))
                .append(",\"hora\":").append(Json.str(instante(t).withNano(0).toString()))
                .append(",\"horasTranscurridas\":").append(Json.num(t / 3600.0))
                .append(",\"segundosReales\":").append(Json.num(nsReales / 1e9))
                .append(",\"duracionHoras\":").append(Double.isInfinite(duracionSeg) ? "null" : Json.num(duracionSeg / 3600.0))
                .append(",\"dia\":").append((int) Math.floor(t / 86400.0) + 1)
                .append(",\"factor\":").append(Json.num(factor))
                .append(",\"scMin\":").append(Json.num(cicloSeg / 60.0))
                .append(",\"saSeg\":").append(Json.num(cicloSeg / factor))
                .append(",\"ciclos\":").append(ciclos)
                .append(",\"msUltimoPlan\":").append(msUltimoPlan)
                .append(",\"msMaximoPlan\":").append(msMaximoPlan)
                .append(",\"iteraciones\":").append(iteraciones)
                .append(",\"sinPlanificar\":").append(sinPlanificar)
                .append(",\"motivoFin\":").append(Json.str(motivoFin))
                .append(",\"tipoFin\":").append(Json.str(tipoFin))
                .append(",\"tc\":").append(tcHoras < 0 ? "null" : Json.num(tcHoras))
                .append(",\"colapso\":").append(colapsoJson == null ? "null" : colapsoJson)
                .append(",\"lineasConError\":").append(fuente.lineasConError() + mantenimiento.errores())
                .append(",\"error\":").append(Json.str(ultimoError));

        // ---- resumen de indicadores (con su nivel de semáforo)
        int[] fl = contarFlota();
        int enRuta = fl[0], disponibles = fl[1], enMant = fl[2], averiadas = fl[3];
        int[] porTipo = {0, fl[4], fl[5], fl[6]};
        int pendientes = 0;
        for (Pedido p : pedidos.values()) if (!p.completo()) pendientes++;
        double pct = pctATiempo();
        b.append(",\"resumen\":{")
                .append("\"recibidos\":").append(recibidos)
                .append(",\"entregados\":").append(completados)
                .append(",\"aTiempo\":").append(enPlazo)
                .append(",\"pctATiempo\":").append(Json.num(pct))
                .append(",\"nivelATiempo\":").append(Json.str(semaforo.mayorEsMejor("plazo", pct, 95, 85).name()))
                .append(",\"vencidos\":").append(vencidos)
                .append(",\"vencidosSinEntregar\":").append(vencidosSinEntregar())
                .append(",\"pendientes\":").append(pendientes)
                .append(",\"unidadesEnRuta\":").append(enRuta)
                .append(",\"unidadesDisponibles\":").append(disponibles)
                .append(",\"unidadesMantenimiento\":").append(enMant)
                .append(",\"unidadesAveriadas\":").append(averiadas)
                .append(",\"averiadasTipo1\":").append(porTipo[1])
                .append(",\"averiadasTipo2\":").append(porTipo[2])
                .append(",\"averiadasTipo3\":").append(porTipo[3])
                .append(",\"averias\":").append(averias.size())
                .append(",\"trasvasados\":").append(trasvasados)
                .append(",\"devueltos\":").append(devueltos)
                .append(",\"flota\":").append(unidades.size())
                .append(",\"km\":").append(Json.num(Math.round(kmTotal)))
                .append(",\"costo\":").append(Json.num(Math.round(costo)))
                .append('}');

        // ---- quién atiende cada pedido y qué unidades van a recargar a cada almacén
        Map<String, Set<String>> atendidoPor = new HashMap<>();
        Map<String, Set<String>> vanARecargar = new HashMap<>();
        for (Unidad u : unidades) {
            for (Parada p : u.paradas) {
                if (p.tipo() == Parada.Tipo.ENTREGA) atendidoPor.computeIfAbsent(p.pedidoId(), k -> new LinkedHashSet<>()).add(u.id);
                if (p.tipo() == Parada.Tipo.RECARGA) vanARecargar.computeIfAbsent(p.almacenId(), k -> new LinkedHashSet<>()).add(u.id);
            }
        }

        b.append(",\"almacenes\":[");
        for (int i = 0; i < almacenes.size(); i++) {
            Almacen a = almacenes.get(i);
            if (i > 0) b.append(',');
            b.append("{\"id\":").append(Json.str(a.id)).append(",\"nombre\":").append(Json.str(nombreAlmacen(a.id)))
                    .append(",\"x\":").append(a.nodo.x()).append(",\"y\":").append(a.nodo.y())
                    .append(",\"central\":").append(a.infinito());
            if (!a.infinito()) {
                // v0.4.1: el semáforo del almacén intermedio va por % de stock respecto de su capacidad
                double stockPct = 100.0 * a.stock / a.capacidad;
                b.append(",\"stock\":").append(a.stock).append(",\"capacidad\":").append(a.capacidad)
                        .append(",\"stockPct\":").append(Json.num(stockPct))
                        .append(",\"nivel\":").append(Json.str(semaforo.mayorEsMejor("almacen", stockPct, 30, 10).name()));
            }
            b.append(",\"recargan\":");
            lista(b, vanARecargar.getOrDefault(a.id, Set.of()));
            b.append('}');
        }
        b.append(']');

        b.append(",\"unidades\":[");
        for (int i = 0; i < unidades.size(); i++) {
            Unidad u = unidades.get(i);
            if (i > 0) b.append(',');
            double ocup = 100.0 * u.carga / u.tipo.capacidad;
            b.append("{\"id\":").append(Json.str(u.id)).append(",\"tipo\":").append(Json.str(u.tipo.name()))
                    .append(",\"x\":").append(Json.num(u.x())).append(",\"y\":").append(Json.num(u.y()))
                    .append(",\"carga\":").append(u.carga).append(",\"capacidad\":").append(u.tipo.capacidad)
                    .append(",\"nivel\":").append(Json.str(semaforo.menorEsMejor("carga", ocup, 70, 90).name()))
                    .append(",\"estado\":").append(Json.str(estadoUnidad(u))).append(",\"km\":").append(Json.num(Math.round(u.km)));
            // v03: avería vigente y hasta cuándo no está disponible (avería o mantenimiento)
            Averia av = u.averia;
            if (av == null) b.append(",\"averia\":null");
            else b.append(",\"averia\":{\"id\":").append(Json.str(av.id)).append(",\"tipo\":").append(av.tipo)
                    .append(",\"fase\":").append(Json.str(av.fase.name()))
                    .append(",\"inicio\":").append(hora(av.inicio))
                    .append(",\"finLugar\":").append(hora(av.finEnLugar))
                    .append(",\"disponible\":").append(hora(av.disponible)).append('}');
            LocalDateTime hasta = noDisponibleHasta(u);
            b.append(",\"noDisponibleHasta\":").append(hasta == null ? "null" : hora(hasta));
            b.append(",\"camino\":[");
            boolean primero = true;
            for (Nodo n : av != null ? List.<Nodo>of() : u.camino) {   // una unidad averiada no dibuja camino
                if (!primero) b.append(',');
                b.append('[').append(n.x()).append(',').append(n.y()).append(']');
                primero = false;
            }
            b.append("],\"paradas\":[");
            primero = true;
            for (Parada p : u.paradas) {
                if (!primero) b.append(',');
                b.append("{\"x\":").append(p.nodo().x()).append(",\"y\":").append(p.nodo().y())
                        .append(",\"tipo\":").append(Json.str(p.tipo().name()))
                        .append(",\"ref\":").append(Json.str(p.tipo() == Parada.Tipo.ENTREGA ? p.pedidoId() : p.tipo() == Parada.Tipo.RECARGA ? p.almacenId() : "refrigerio"))
                        .append(",\"cantidad\":").append(p.cantidad()).append('}');
                primero = false;
            }
            b.append("]}");
        }
        b.append(']');

        b.append(",\"pedidos\":[");
        int n = 0;
        for (Pedido p : pedidos.values()) {
            if (p.completo()) continue;
            if (n++ > 0) b.append(',');
            double frac = (p.limite() - t) / (p.plazoHoras * 3600.0);
            b.append("{\"id\":").append(Json.str(p.id)).append(",\"cliente\":").append(Json.str(p.cliente))
                    .append(",\"x\":").append(p.nodo.x()).append(",\"y\":").append(p.nodo.y())
                    .append(",\"cantidad\":").append(p.cantidad).append(",\"entregado\":").append(p.entregado)
                    .append(",\"asignado\":").append(p.asignado)
                    .append(",\"plazo\":").append(p.plazoHoras)
                    .append(",\"registro\":").append(Json.str(instante(p.registro).withNano(0).toString()))
                    .append(",\"limite\":").append(Json.str(instante(p.limite()).withNano(0).toString()))
                    .append(",\"holgura\":").append(Json.num(Math.max(-1, Math.min(1, frac))))
                    .append(",\"vencido\":").append(p.vencido)
                    .append(",\"nivel\":").append(Json.str(p.vencido ? "ROJO" : semaforo.pedido(frac).name()))
                    .append(",\"unidades\":");
            lista(b, atendidoPor.getOrDefault(p.id, Set.of()));
            b.append('}');
            if (n >= 3000) break;
        }
        b.append(']');

        // ---- v03: averías del escenario (las vigentes y las últimas finalizadas) y qué pasaría si ocurre una ahora
        b.append(",\"averias\":[");
        int finalizadas = 0;
        boolean primeraAveria = true;
        for (int i = averias.size() - 1; i >= 0; i--) {
            Averia av = averias.get(i);
            if (!av.activa() && ++finalizadas > HISTORIAL_VISIBLE) continue;
            if (!primeraAveria) b.append(',');
            averiaJson(b, av, vanARecargar.getOrDefault(TRASVASE + av.unidad, Set.of()));
            primeraAveria = false;
        }
        LocalDateTime ahora = instante(t);
        b.append("],\"reglasAveria\":{\"trasvase\":").append(reglas.trasvaseHabilitado)
                .append(",\"trasvaseMinutos\":").append(Json.num(reglas.minutosTrasvase))
                .append(",\"horasEnLugar\":").append(Json.num(reglas.horasEnLugar))
                .append(",\"disponibleTipo1\":").append(hora(reglas.disponible(1, ahora)))
                .append(",\"disponibleTipo2\":").append(hora(reglas.disponible(2, ahora)))
                .append(",\"disponibleTipo3\":").append(hora(reglas.disponible(3, ahora)))
                .append(",\"trasladoTipo23\":").append(hora(reglas.finEnLugar(2, ahora))).append('}');

        b.append(",\"bloqueos\":[");
        for (int i = 0; i < bloqueosActivos.size(); i++) {
            Bloqueo bl = bloqueosActivos.get(i);
            if (i > 0) b.append(',');
            b.append("{\"inicio\":").append(Json.str(bl.inicio().toString()))
                    .append(",\"fin\":").append(Json.str(bl.fin().toString())).append(",\"vertices\":[");
            for (int k = 0; k < bl.vertices().size(); k++) {
                Nodo v = bl.vertices().get(k);
                if (k > 0) b.append(',');
                b.append('[').append(v.x()).append(',').append(v.y()).append(']');
            }
            b.append("]}");
        }
        b.append("]}");
        return b.toString();
    }

    /** v03: una avería en JSON (estado, historial y respuesta del registro). */
    private void averiaJson(StringBuilder b, Averia av, Set<String> vanATrasvasar) {
        Unidad u = unidadPorId.get(av.unidad);
        b.append("{\"id\":").append(Json.str(av.id))
                .append(",\"unidad\":").append(Json.str(av.unidad))
                .append(",\"tipoUnidad\":").append(Json.str(av.tipoUnidad.name()))
                .append(",\"tipo\":").append(av.tipo)
                .append(",\"nombreTipo\":").append(Json.str(Averia.nombreTipo(av.tipo)))
                .append(",\"origen\":").append(Json.str(av.origen))
                .append(",\"fase\":").append(Json.str(av.fase.name()))
                .append(",\"inicio\":").append(hora(av.inicio))
                .append(",\"finLugar\":").append(hora(av.finEnLugar))
                .append(",\"disponible\":").append(hora(av.disponible))
                .append(",\"fin\":").append(av.fin < 0 ? "null" : hora(av.fin))
                .append(",\"x\":").append(Json.num(av.x)).append(",\"y\":").append(Json.num(av.y))
                .append(",\"nodo\":[").append(av.nodo.x()).append(',').append(av.nodo.y()).append(']')
                .append(",\"carga\":").append(av.carga)
                .append(",\"cargaActual\":").append(av.fase == Averia.Fase.EN_LUGAR && u != null ? u.carga : 0)
                .append(",\"trasvasable\":").append(reglas.trasvaseHabilitado && av.seTraslada()
                        && av.fase == Averia.Fase.EN_LUGAR && !esAlmacen(av.nodo))
                .append(",\"trasvasados\":").append(av.trasvasados)
                .append(",\"devueltos\":").append(av.devueltos)
                .append(",\"pedidos\":");
        lista(b, av.pedidos);
        b.append(",\"receptoras\":");
        lista(b, av.receptoras);
        b.append(",\"vanATrasvasar\":");
        lista(b, vanATrasvasar);
        b.append('}');
    }

    /** v03: la avería registrada, en JSON (respuesta de POST /api/averias). */
    public synchronized String averiaJson(Averia av) {
        StringBuilder b = new StringBuilder(512);
        averiaJson(b, av, Set.of());
        return b.toString();
    }

    /** v03: historial completo de averías del escenario, de la más antigua a la más reciente (GET /api/averias). */
    public synchronized String averiasJson() {
        StringBuilder b = new StringBuilder(256 + 512 * averias.size()).append('[');
        for (int i = 0; i < averias.size(); i++) {
            if (i > 0) b.append(',');
            averiaJson(b, averias.get(i), Set.of());
        }
        return b.append(']').toString();
    }

    // ---- v03: acceso para las pruebas del paquete

    synchronized Unidad unidad(String id) {
        return unidadPorId.get(id);
    }

    synchronized List<Averia> averias() {
        return List.copyOf(averias);
    }

    synchronized Pedido pedido(String id) {
        return pedidos.get(id);
    }

    synchronized LocalDateTime ahora() {
        return instante(t);
    }

    private static void lista(StringBuilder b, Iterable<String> valores) {
        b.append('[');
        boolean primero = true;
        for (String v : valores) {
            if (!primero) b.append(',');
            b.append(Json.str(v));
            primero = false;
        }
        b.append(']');
    }

    /** Resumen de una línea para pruebas en consola. */
    public synchronized String resumen() {
        int vigentes = 0;
        for (Unidad u : unidades) if (u.averia != null) vigentes++;
        return String.format("t=%6.1f h | recibidos=%d completados=%d a tiempo=%.1f%% vencidos=%d (sin entregar %d) pendientes=%d | ciclos=%d Ta max=%d ms sinPlanificar=%d | S/ %,.0f | averías=%d (vigentes %d, trasvasados %d, devueltos %d) | %s",
                t / 3600, recibidos, completados, pctATiempo(), vencidos, vencidosSinEntregar(), pedidos.size(), ciclos, msMaximoPlan, sinPlanificar, costo,
                averias.size(), vigentes, trasvasados, devueltos, estado == Estado.TERMINADA ? motivoFin : estado.name());
    }
}
