package pe.pucp.paqrap.app.sim;

import pe.pucp.paqrap.app.Config;
import pe.pucp.paqrap.app.datos.Bloqueo;
import pe.pucp.paqrap.app.datos.Formato;
import pe.pucp.paqrap.app.datos.FuenteDatos;
import pe.pucp.paqrap.app.modelo.Almacen;
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
 */
public final class Simulacion implements Runnable {

    public enum Escenario { DIA_A_DIA, SEMANAL, COLAPSO }

    public enum Estado { PREPARADA, EJECUTANDO, TERMINADA }

    private static final double PASO_MAX_SEG = 30.0;      // granularidad del movimiento
    private static final long TICK_MS = 100;              // cada cuánto avanza el reloj real
    private static final double ENTREGA_SEG = 3600.0;     // acondicionamiento de la entrega
    private static final double REFRIGERIO_SEG = 3600.0;

    private final Config cfg;
    private final Escenario escenario;
    private final LocalDateTime inicio;
    private final MapaReticula mapa;
    private final Caminos caminos;
    private final List<Almacen> almacenes = new ArrayList<>();
    private final List<Unidad> unidades = new ArrayList<>();
    private final Map<String, Pedido> pedidos = new LinkedHashMap<>();
    private final List<Bloqueo> bloqueosActivos = new ArrayList<>();
    private final Planificador planificador;
    private final FuenteDatos fuente;
    private final Indicadores semaforo;

    private final double factor;          // segundos simulados por segundo real
    private final double cicloSeg;        // Sc
    private final double saMs;            // Sa: tiempo máximo de cómputo (criterio C de colapso)
    private final double margenSeg;       // holgura opcional frente al límite de cada pedido
    private final int maximoPorEntrega;   // entregas parciales
    private final double duracionSeg;     // 5D: 120 h

    private double t;
    private double proximoCiclo;
    private LocalDateTime proximaRecarga;
    private volatile Estado estado = Estado.PREPARADA;
    private volatile boolean detener;
    private String motivoFin = "";
    private double tcHoras = -1;

    private int contador, recibidos, completados, enPlazo, vencidos, ciclos, sinPlanificar, iteraciones;
    private long msUltimoPlan, msMaximoPlan;
    private double costo, kmTotal;
    private String ultimoError = "";

    public Simulacion(Config cfg, Escenario escenario, LocalDateTime inicio, Planificador planificador) {
        this.cfg = cfg;
        this.escenario = escenario;
        this.inicio = inicio;
        this.planificador = planificador;
        this.semaforo = new Indicadores(cfg);
        this.fuente = new FuenteDatos(Path.of(cfg.texto("datos.carpeta", "datos")), inicio);
        this.mapa = new MapaReticula(cfg.entero("ien.mapaAnchoKm", 70), cfg.entero("ien.mapaAltoKm", 50));
        this.caminos = new Caminos(mapa);

        this.cicloSeg = cfg.decimal("ien.scMinutos", 60) * 60;
        this.saMs = cfg.decimal("ien.saSegundos", 30) * 1000;
        this.margenSeg = cfg.decimal("planificador.margenMinutos", 0) * 60;
        this.maximoPorEntrega = cfg.entero("ien.maximoProductosPorEntrega", 24);
        this.duracionSeg = escenario == Escenario.SEMANAL ? 5 * 24 * 3600 : Double.POSITIVE_INFINITY;
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

    public void detener() {
        detener = true;
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
        try {
            while (!detener && estado == Estado.EJECUTANDO) {
                Thread.sleep(TICK_MS);
                synchronized (this) {
                    avanzar(factor * TICK_MS / 1000.0);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            synchronized (this) {
                ultimoError = e.toString();
                terminar("Error: " + e.getMessage());
            }
        } finally {
            synchronized (this) {
                if (estado != Estado.TERMINADA) terminar("Detenida por el usuario");
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

    private void terminar(String motivo) {
        if (estado == Estado.TERMINADA) return;
        estado = Estado.TERMINADA;
        motivoFin = motivo;
    }

    private void colapso(String criterio, String detalle) {
        tcHoras = t / 3600.0;
        terminar(String.format("Colapso %s: %s (Tc = %.1f h)", criterio, detalle, tcHoras));
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

        // 4. ciclo de planificación
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
                    colapso("P", "el pedido " + p.id + " venció sin entrega");
                    return;
                }
            }
        }
        pedidos.values().removeIf(p -> p.completo() && p.asignado <= 0 && t > p.liberado);

        // 7. fin de la simulación de 5 días
        if (t >= duracionSeg) terminar("Fin de la simulación de 5 días");
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

    // ------------------------------------------------------------------ planificación (rutapaq-planner)

    private void planificar() {
        ciclos++;

        // a) cada unidad conserva solo la parada hacia la que ya se mueve; el resto se replanifica
        for (Unidad u : unidades) recortar(u);

        // b) estado de la flota tal como lo espera el planificador
        Map<String, Integer> reservado = new HashMap<>();
        List<Vehiculo> flota = new ArrayList<>();
        for (Unidad u : unidades) flota.add(vehiculo(u, reservado));

        // c) almacenes con el saldo que queda tras las recargas ya comprometidas
        List<pe.pucp.paqrap.planner.model.Almacen> alm = new ArrayList<>();
        for (Almacen a : almacenes) {
            pe.pucp.paqrap.planner.model.Almacen x = new pe.pucp.paqrap.planner.model.Almacen(a.id,
                    a.infinito() ? TipoAlmacen.CENTRAL : TipoAlmacen.INTERMEDIO, caminos.indice(a.nodo),
                    a.nodo.x(), a.nodo.y(), a.infinito() ? 0 : a.capacidad);
            if (!a.infinito()) x.retirar(Math.min(a.capacidad, a.capacidad - a.stock + reservado.getOrDefault(a.id, 0)));
            alm.add(x);
        }

        // d) cartera: todo lo que falta asignar, fraccionado como en el IEN (entregas parciales)
        Map<String, String> original = new HashMap<>();
        List<pe.pucp.paqrap.planner.model.Pedido> cartera = new ArrayList<>();
        for (Pedido p : pedidos.values()) {
            if (p.porAsignar() <= 0) continue;
            pe.pucp.paqrap.planner.model.Pedido x = new pe.pucp.paqrap.planner.model.Pedido(p.id, p.cliente,
                    caminos.indice(p.nodo), p.nodo.x(), p.nodo.y(), p.porAsignar(),
                    instante(p.registro - margenSeg), p.plazoHoras, null);
            for (pe.pucp.paqrap.planner.model.Pedido parte : LectorVentas.fraccionar(x, maximoPorEntrega)) {
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
            ultimoError = "Planificador: " + e;
            return;
        }
        msUltimoPlan = (System.nanoTime() - ini) / 1_000_000;
        msMaximoPlan = Math.max(msMaximoPlan, msUltimoPlan);
        iteraciones = planificador.iteracionesEjecutadas();
        sinPlanificar = plan.getNoPlanificados().size();
        if (escenario == Escenario.COLAPSO && msUltimoPlan >= saMs) {
            colapso("C", "el planificador tardó " + msUltimoPlan + " ms (Ta >= Sa)");
            return;
        }

        // f) el plan pasa a ser el itinerario de cada unidad
        Map<String, Unidad> porId = new HashMap<>();
        for (Unidad u : unidades) porId.put(u.id, u);
        for (Ruta r : plan.getRutas()) {
            Unidad u = porId.get(r.getVehiculo().getId());
            if (u == null) continue;
            boolean refrigerio = false;
            Nodo ultimo = u.nodo;
            for (ParadaRuta pr : r.getItinerario()) {
                Nodo n = caminos.nodo(pr.nodo());
                switch (pr.tipo()) {
                    // El evaluador cuenta el refrigerio antes de la siguiente llegada:
                    // aquí se toma al llegar al nodo de esa parada, antes de atenderla.
                    case REFRIGERIO -> refrigerio = true;
                    case RECARGA -> {
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

    /** Conserva la parada hacia la que la unidad ya se mueve (y su refrigerio previo); libera el resto. */
    private void recortar(Unidad u) {
        List<Parada> conservar = new ArrayList<>();
        boolean enMovimiento = !u.camino.isEmpty();
        boolean cerrado = !enMovimiento;
        for (Parada p : u.paradas) {
            if (!cerrado) {
                conservar.add(p);
                if (p.tipo() != Parada.Tipo.REFRIGERIO) cerrado = true;
                continue;
            }
            if (p.tipo() == Parada.Tipo.ENTREGA) {
                Pedido ped = pedidos.get(p.pedidoId());
                if (ped != null) ped.asignado -= p.cantidad();
            }
        }
        u.paradas.clear();
        u.paradas.addAll(conservar);
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
            if (primero && !u.camino.isEmpty()) km = u.camino.size() - u.progreso;
            else {
                int d = caminos.distancia(n, p.nodo());
                km = d < 0 ? n.manhattan(p.nodo()) : d;
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
                    reservado.merge(p.almacenId(), u.tipo.capacidad - carga, Integer::sum);
                    carga = u.tipo.capacidad;
                }
                case REFRIGERIO -> {
                    turnoRefrigerio = TurnoConductor.vigenteEn(instante(tl)).getInicio();
                    tl += REFRIGERIO_SEG;
                }
            }
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
        double tl = desde;
        int guardia = 0;
        while (tl < hasta && guardia++ < 10_000) {
            if (u.ocupadoHasta > tl) {
                tl = Math.min(hasta, u.ocupadoHasta);
                continue;
            }
            u.ocupacion = "";
            Parada p = u.paradas.peekFirst();
            if (p == null) return;                               // sin trabajo

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

            Nodo sig = u.camino.peekFirst();
            if (u.progreso == 0 && !sig.equals(p.nodo()) && caminos.bloqueado(sig)) {
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

    // ------------------------------------------------------------------ lectura del estado

    public synchronized String snapshotJson() {
        StringBuilder b = new StringBuilder(64_000);
        b.append("{\"estado\":").append(Json.str(estado.name()))
                .append(",\"escenario\":").append(Json.str(escenario.name()))
                .append(",\"algoritmo\":").append(Json.str(planificador.nombre()))
                .append(",\"inicio\":").append(Json.str(inicio.toString()))
                .append(",\"hora\":").append(Json.str(instante(t).withNano(0).toString()))
                .append(",\"horasTranscurridas\":").append(Json.num(t / 3600.0))
                .append(",\"factor\":").append(Json.num(factor))
                .append(",\"ciclos\":").append(ciclos)
                .append(",\"msUltimoPlan\":").append(msUltimoPlan)
                .append(",\"iteraciones\":").append(iteraciones)
                .append(",\"sinPlanificar\":").append(sinPlanificar)
                .append(",\"motivoFin\":").append(Json.str(motivoFin))
                .append(",\"tc\":").append(tcHoras < 0 ? "null" : Json.num(tcHoras))
                .append(",\"error\":").append(Json.str(ultimoError));

        // indicadores con semáforo
        int enUso = 0;
        for (Unidad u : unidades) if (!u.paradas.isEmpty() || u.ocupacion.equals("ENTREGANDO")) enUso++;
        double pctPlazo = completados == 0 ? 100.0 : 100.0 * enPlazo / completados;
        double pctFlota = 100.0 * enUso / unidades.size();
        int minStock = Integer.MAX_VALUE;
        for (Almacen a : almacenes) if (!a.infinito()) minStock = Math.min(minStock, a.stock);
        int pendientes = 0;
        for (Pedido p : pedidos.values()) if (!p.completo()) pendientes++;
        b.append(",\"indicadores\":[");
        indicador(b, "Entregas en plazo", String.format("%.1f %%", pctPlazo), semaforo.mayorEsMejor("plazo", pctPlazo, 95, 85), true);
        indicador(b, "Pedidos vencidos", String.valueOf(vencidos), semaforo.menorEsMejor("vencidos", vencidos, 0, 3), false);
        indicador(b, "Flota en uso", String.format("%.0f %% (%d de %d)", pctFlota, enUso, unidades.size()), semaforo.menorEsMejor("flota", pctFlota, 70, 90), false);
        indicador(b, "Stock mínimo en intermedios", String.valueOf(minStock), semaforo.mayorEsMejor("stock", minStock, 300, 100), false);
        indicador(b, "Pedidos recibidos", String.valueOf(recibidos), Indicadores.Nivel.NEUTRO, false);
        indicador(b, "Pedidos completados", String.valueOf(completados), Indicadores.Nivel.NEUTRO, false);
        indicador(b, "Pedidos pendientes", String.valueOf(pendientes), Indicadores.Nivel.NEUTRO, false);
        indicador(b, "Costo acumulado", String.format("S/ %,.0f (%,.0f km)", costo, kmTotal), Indicadores.Nivel.NEUTRO, false);
        b.append(']');

        b.append(",\"almacenes\":[");
        for (int i = 0; i < almacenes.size(); i++) {
            Almacen a = almacenes.get(i);
            if (i > 0) b.append(',');
            b.append("{\"id\":").append(Json.str(a.id)).append(",\"x\":").append(a.nodo.x()).append(",\"y\":").append(a.nodo.y())
                    .append(",\"stock\":").append(a.infinito() ? "null" : String.valueOf(a.stock)).append('}');
        }
        b.append(']');

        b.append(",\"unidades\":[");
        for (int i = 0; i < unidades.size(); i++) {
            Unidad u = unidades.get(i);
            if (i > 0) b.append(',');
            String est = !u.ocupacion.isEmpty() ? u.ocupacion : u.paradas.isEmpty() ? "DISPONIBLE" : "EN_RUTA";
            b.append("{\"id\":").append(Json.str(u.id)).append(",\"tipo\":").append(Json.str(u.tipo.name()))
                    .append(",\"x\":").append(Json.num(u.x())).append(",\"y\":").append(Json.num(u.y()))
                    .append(",\"carga\":").append(u.carga).append(",\"capacidad\":").append(u.tipo.capacidad)
                    .append(",\"estado\":").append(Json.str(est)).append(",\"km\":").append(Json.num(Math.round(u.km)))
                    .append(",\"camino\":[");
            boolean primero = true;
            for (Nodo n : u.camino) {
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
            b.append("{\"id\":").append(Json.str(p.id)).append(",\"x\":").append(p.nodo.x()).append(",\"y\":").append(p.nodo.y())
                    .append(",\"cantidad\":").append(p.cantidad).append(",\"entregado\":").append(p.entregado)
                    .append(",\"plazo\":").append(p.plazoHoras)
                    .append(",\"limite\":").append(Json.str(instante(p.limite()).withNano(0).toString()))
                    .append(",\"nivel\":").append(Json.str(p.vencido ? "ROJO" : semaforo.pedido(frac).name())).append('}');
            if (n >= 3000) break;
        }
        b.append(']');

        b.append(",\"bloqueos\":[");
        Set<Nodo> vistos = new LinkedHashSet<>();
        for (Bloqueo bl : bloqueosActivos) vistos.addAll(bl.nodos());
        boolean primero = true;
        for (Nodo v : vistos) {
            if (!primero) b.append(',');
            b.append(v.x()).append(',').append(v.y());
            primero = false;
        }
        b.append("]}");
        return b.toString();
    }

    private static void indicador(StringBuilder b, String nombre, String valor, Indicadores.Nivel nivel, boolean primero) {
        if (!primero) b.append(',');
        b.append("{\"nombre\":").append(Json.str(nombre)).append(",\"valor\":").append(Json.str(valor))
                .append(",\"nivel\":").append(Json.str(nivel.name())).append('}');
    }

    /** Resumen de una línea para pruebas en consola. */
    public synchronized String resumen() {
        double pct = completados == 0 ? 100.0 : 100.0 * enPlazo / completados;
        return String.format("t=%6.1f h | recibidos=%d completados=%d en plazo=%.1f%% vencidos=%d pendientes=%d | ciclos=%d Ta max=%d ms sinPlanificar=%d | S/ %,.0f | %s",
                t / 3600, recibidos, completados, pct, vencidos, pedidos.size(), ciclos, msMaximoPlan, sinPlanificar, costo,
                estado == Estado.TERMINADA ? motivoFin : estado.name());
    }
}
