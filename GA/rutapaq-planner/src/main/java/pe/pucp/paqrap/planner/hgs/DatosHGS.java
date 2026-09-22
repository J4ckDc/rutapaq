package pe.pucp.paqrap.planner.hgs;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.ContextoPlanificacion;
import pe.pucp.paqrap.planner.core.RedVial;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.EstadoVehiculo;
import pe.pucp.paqrap.planner.model.Incidencia;
import pe.pucp.paqrap.planner.model.NodoRed;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TurnoConductor;
import pe.pucp.paqrap.planner.model.Vehiculo;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/**
 * Instancia preprocesada e inmutable del HGS. Traduce el contexto de
 * planificacion a arreglos de enteros y reales (pedidos del banco, unidades
 * disponibles, matriz local de distancias y vecindario granular), de modo que la
 * decodificacion y la educacion de los individuos se ejecuten en paralelo sin
 * estado compartido mutable.
 *
 * <p>En la reoptimizacion rodante se aplican las incidencias sobre la red y la
 * flota, las visitas ejecutadas o inminentes del plan vigente se congelan como
 * prefijo inmutable de la unidad que las atiende y solo los pedidos pendientes
 * ingresan a la permutacion del cromosoma.</p>
 */
final class DatosHGS {

    /** Unidad de transporte disponible para el turno, con su prefijo congelado si lo tiene. */
    static final class Slot {
        Vehiculo vehiculo;
        int capacidad;
        double velocidad;
        double costoKm;
        long inicio;
        long turnoFin;
        long refDesde;
        long refHasta;
        boolean refPendienteInicial;
        int almacenFijo = -1;
        LocalDateTime inicioModelo;
        List<VisitaCliente> prefijo = List.of();
        EvaluadorRuta.Traza trazaPrefijo;
        RutaEval evalPrefijo;

        boolean tienePrefijo() {
            return !prefijo.isEmpty();
        }
    }

    Configuracion cfg;
    ContextoPlanificacion ctx;
    LocalDateTime base;

    // Banco de pedidos a planificar
    Pedido[] pedidos;
    int n;
    int[] nodoPedido;
    int[] paq;
    double[] mu;
    long[] limite;
    long[] servicio;
    int[] almacenCercano;
    double[] angulo;

    // Almacenes
    Almacen[] almacenes;
    int nAlm;
    int[] nodoAlmacen;
    boolean[] infinito;
    int[] saldo;
    int[] consumoFijo;

    // Red y flota
    double[][] dist;
    Slot[] slots;
    int K;
    List<Ruta> rutasFijasAveriadas = new ArrayList<>();
    int[][] vecinos;

    private DatosHGS() {
    }

    long seg(LocalDateTime instante) {
        return Duration.between(base, instante).getSeconds();
    }

    static DatosHGS construir(ContextoPlanificacion ctx, PlanDistribucion vigente, List<Pedido> nuevos,
                              List<Incidencia> incidencias, Configuracion cfg, int vecinosGranulares) {
        aplicarIncidencias(ctx, incidencias);
        DatosHGS d = new DatosHGS();
        d.cfg = cfg;
        d.ctx = ctx;
        d.base = ctx.getInstante().toLocalDate().atStartOfDay();
        LocalDateTime corte = ctx.getInstante().plusMinutes(cfg.horizonteCongelamientoMinutos());

        List<Almacen> alms = ctx.getAlmacenes();
        d.almacenes = alms.toArray(new Almacen[0]);
        d.nAlm = d.almacenes.length;
        Map<String, Integer> idxAlm = new HashMap<>();
        for (int a = 0; a < d.nAlm; a++) {
            idxAlm.put(d.almacenes[a].getIdAlmacen(), a);
        }

        // 1. Congelamiento del plan vigente y banco de pedidos pendientes.
        LinkedHashMap<String, Pedido> banco = new LinkedHashMap<>();
        Map<String, Ruta> rutaPrefijo = new HashMap<>();
        Map<String, List<VisitaCliente>> prefijos = new HashMap<>();
        if (vigente != null) {
            for (Ruta r : vigente.getListaRutas()) {
                List<VisitaCliente> congeladas = new ArrayList<>();
                boolean enPrefijo = true;
                for (VisitaCliente vc : r.getListaParadas()) {
                    boolean congelar = enPrefijo && (vc.isCongelada()
                            || (vc.getHoraLlegadaEstimada() != null && !vc.getHoraLlegadaEstimada().isAfter(corte)));
                    if (congelar) {
                        VisitaCliente c = vc.copia();
                        c.setCongelada(true);
                        congeladas.add(c);
                    } else {
                        enPrefijo = false;
                        banco.putIfAbsent(vc.getIdPedido(), vc.getPedido());
                    }
                }
                Vehiculo v = r.getVehiculo();
                if (congeladas.isEmpty()) {
                    continue;
                }
                if (v.getEstado() == EstadoVehiculo.AVERIADO) {
                    Ruta fija = new Ruta(r.getIdRuta(), v, r.getAlmacenOrigen(), r.getFechaHoraInicio());
                    for (VisitaCliente c : congeladas) {
                        fija.getListaParadas().add(c);
                    }
                    fija.reindexar();
                    d.rutasFijasAveriadas.add(fija);
                } else {
                    rutaPrefijo.put(v.getIdVehiculo(), r);
                    prefijos.put(v.getIdVehiculo(), congeladas);
                }
            }
            for (Pedido p : vigente.getPedidosNoAtendidos()) {
                banco.putIfAbsent(p.getIdPedido(), p);
            }
        }
        if (nuevos != null) {
            for (Pedido p : nuevos) {
                banco.putIfAbsent(p.getIdPedido(), p);
            }
        }

        // 2. Nodos relevantes y matriz local de distancias (caminos minimos vigentes).
        LinkedHashMap<String, Integer> idxNodo = new LinkedHashMap<>();
        List<NodoRed> nodos = new ArrayList<>();
        d.nodoAlmacen = new int[d.nAlm];
        for (int a = 0; a < d.nAlm; a++) {
            d.nodoAlmacen[a] = registrar(d.almacenes[a].getNodo(), idxNodo, nodos);
        }
        d.pedidos = banco.values().toArray(new Pedido[0]);
        d.n = d.pedidos.length;
        d.nodoPedido = new int[d.n];
        d.paq = new int[d.n];
        d.mu = new double[d.n];
        d.limite = new long[d.n];
        d.servicio = new long[d.n];
        for (int i = 0; i < d.n; i++) {
            Pedido p = d.pedidos[i];
            d.nodoPedido[i] = registrar(p.getNodoDestino(), idxNodo, nodos);
            d.paq[i] = p.getCantidadPaquetes();
            d.mu[i] = p.getPrioridad().getFactorMu();
            d.limite[i] = d.seg(p.getFechaHoraLimite());
            d.servicio[i] = Math.round(p.getNodoDestino().getTiempoServicioHoras() * 3600.0);
        }
        for (List<VisitaCliente> pref : prefijos.values()) {
            for (VisitaCliente vc : pref) {
                registrar(vc.getNodoCliente(), idxNodo, nodos);
            }
        }
        RedVial red = ctx.getRedVial();
        int m = nodos.size();
        d.dist = new double[m][m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                d.dist[i][j] = red.distanciaKm(nodos.get(i), nodos.get(j));
            }
        }

        // 3. Almacenes: saldo disponible y consumo de las visitas congeladas.
        d.infinito = new boolean[d.nAlm];
        d.saldo = new int[d.nAlm];
        d.consumoFijo = new int[d.nAlm];
        for (int a = 0; a < d.nAlm; a++) {
            d.infinito[a] = d.almacenes[a].isEsInventarioInfinito();
            d.saldo[a] = d.infinito[a] ? Integer.MAX_VALUE : d.almacenes[a].getCapacidadActual();
        }
        for (Ruta fija : d.rutasFijasAveriadas) {
            d.consumoFijo[idxAlm.get(fija.getAlmacenOrigen().getIdAlmacen())] += fija.getCarga();
        }

        // 4. Unidades disponibles (slots), con prefijo congelado cuando corresponde.
        List<Slot> slots = new ArrayList<>();
        for (Vehiculo v : ctx.getFlota()) {
            TurnoConductor turno = v.getTurnoActual();
            if (v.getEstado() == EstadoVehiculo.AVERIADO || turno == null) {
                continue;
            }
            Slot s = new Slot();
            s.vehiculo = v;
            s.capacidad = v.getCapacidadMaxima();
            s.velocidad = v.getVelocidadPromedioKmH();
            s.costoKm = v.getCostoPorKm();
            s.turnoFin = d.seg(turno.getHoraFin());
            s.refDesde = d.seg(turno.getVentanaRefrigerioDesde());
            s.refHasta = d.seg(turno.getVentanaRefrigerioHasta());
            s.refPendienteInicial = !turno.isRefrigerioConsumido();
            Ruta r = rutaPrefijo.get(v.getIdVehiculo());
            if (r != null) {
                LocalDateTime ini = r.getFechaHoraInicio().isBefore(turno.getHoraInicio())
                        ? turno.getHoraInicio() : r.getFechaHoraInicio();
                s.inicioModelo = r.getFechaHoraInicio();
                s.inicio = d.seg(ini);
                s.almacenFijo = idxAlm.get(r.getAlmacenOrigen().getIdAlmacen());
                s.prefijo = prefijos.get(v.getIdVehiculo());
                EvaluadorRuta.Traza tr = new EvaluadorRuta.Traza();
                tr.nodo = d.nodoAlmacen[s.almacenFijo];
                tr.t = s.inicio;
                tr.refPendiente = s.refPendienteInicial;
                for (VisitaCliente vc : s.prefijo) {
                    EvaluadorRuta.extenderCrudo(d.dist, s, tr, idxNodo.get(vc.getNodoCliente().getIdNodo()),
                            d.seg(vc.getPedido().getFechaHoraLimite()), vc.getPedido().getPrioridad().getFactorMu(),
                            Math.round(vc.getNodoCliente().getTiempoServicioHoras() * 3600.0),
                            vc.getPaquetesEntregados());
                }
                s.trazaPrefijo = tr;
                s.evalPrefijo = EvaluadorRuta.cerrar(d, s, s.almacenFijo, tr);
                d.consumoFijo[s.almacenFijo] += tr.carga;
            } else {
                LocalDateTime ini = ctx.getInstante().isAfter(turno.getHoraInicio())
                        ? ctx.getInstante() : turno.getHoraInicio();
                s.inicioModelo = ini;
                s.inicio = d.seg(ini);
            }
            slots.add(s);
        }
        d.slots = slots.toArray(new Slot[0]);
        d.K = d.slots.length;

        // 5. Almacen mas cercano, angulo polar y vecindario granular de cada pedido.
        d.almacenCercano = new int[d.n];
        d.angulo = new double[d.n];
        NodoRed centro = d.almacenes[0].getNodo();
        for (int i = 0; i < d.n; i++) {
            int mejor = 0;
            for (int a = 1; a < d.nAlm; a++) {
                if (d.dist[d.nodoAlmacen[a]][d.nodoPedido[i]] < d.dist[d.nodoAlmacen[mejor]][d.nodoPedido[i]]) {
                    mejor = a;
                }
            }
            d.almacenCercano[i] = mejor;
            NodoRed nd = d.pedidos[i].getNodoDestino();
            d.angulo[i] = Math.atan2(nd.getLatitud() - centro.getLatitud(), nd.getLongitud() - centro.getLongitud());
        }
        int gamma = Math.min(vecinosGranulares, Math.max(0, d.n - 1));
        d.vecinos = new int[d.n][];
        Integer[] orden = new Integer[d.n];
        for (int i = 0; i < d.n; i++) {
            final int u = i;
            for (int j = 0; j < d.n; j++) {
                orden[j] = j;
            }
            Arrays.sort(orden, Comparator.comparingDouble(j -> d.dist[d.nodoPedido[u]][d.nodoPedido[j]]));
            int[] vec = new int[gamma];
            int c = 0;
            for (int j = 0; j < d.n && c < gamma; j++) {
                if (orden[j] != u) {
                    vec[c++] = orden[j];
                }
            }
            d.vecinos[i] = vec;
        }
        return d;
    }

    private static int registrar(NodoRed nodo, Map<String, Integer> idx, List<NodoRed> nodos) {
        Integer i = idx.get(nodo.getIdNodo());
        if (i != null) {
            return i;
        }
        idx.put(nodo.getIdNodo(), nodos.size());
        nodos.add(nodo);
        return nodos.size() - 1;
    }

    /** Bloqueos: arco invalidado en ambos sentidos. Averias: unidad inmovilizada y deposito temporal. */
    static void aplicarIncidencias(ContextoPlanificacion ctx, List<Incidencia> incidencias) {
        if (incidencias == null) {
            return;
        }
        RedVial red = ctx.getRedVial();
        for (Incidencia inc : incidencias) {
            switch (inc.getTipoIncidencia()) {
                case BLOQUEO_CALLE -> {
                    red.bloquear(inc.getArcoAfectado());
                    red.invalidarCaminos();
                }
                case FALLA_MECANICA -> {
                    Vehiculo v = ctx.vehiculo(inc.getIdVehiculoAfectado());
                    if (v != null && v.getEstado() != EstadoVehiculo.AVERIADO) {
                        NodoRed pos = v.getUbicacionActual();
                        red.agregarNodoEnPosicionDe(
                                NodoRed.depositoTemporal("DT-" + v.getIdVehiculo(), pos, v.getCargaActual()),
                                pos.getIdNodo());
                        v.setEstado(EstadoVehiculo.AVERIADO);
                    }
                }
                default -> throw new IllegalStateException("Incidencia no soportada: " + inc);
            }
        }
    }

    /** Costo del prefijo congelado de una unidad (0 si parte vacia desde un almacen). */
    double costoBase(int slot, Penalizaciones pen, double factor) {
        Slot s = slots[slot];
        return s.tienePrefijo() ? s.evalPrefijo.costo(pen, factor) : 0.0;
    }

    int cargaBase(int slot) {
        Slot s = slots[slot];
        return s.tienePrefijo() ? s.trazaPrefijo.carga : 0;
    }

    int almacenDe(int slot, int pedidoInicial, int[] gamma) {
        Slot s = slots[slot];
        return s.almacenFijo >= 0 ? s.almacenFijo : gamma[pedidoInicial];
    }

    double exceso(int almacen, int consumo) {
        return infinito[almacen] ? 0.0 : Math.max(0, consumo - saldo[almacen]);
    }
}
