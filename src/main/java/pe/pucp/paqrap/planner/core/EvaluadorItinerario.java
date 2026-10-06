package pe.pucp.paqrap.planner.core;

import java.util.List;

import pe.pucp.paqrap.planner.model.ParadaRuta;
import pe.pucp.paqrap.planner.model.TipoParada;

/**
 * Evaluador unico de rutas, compartido por el ALNS y el HGS. Recorre la
 * secuencia de entregas de una unidad y construye su itinerario real:
 *
 * <ul>
 *   <li><b>Viajes con recarga.</b> Cuando la carga a bordo no alcanza para la
 *       siguiente entrega, la unidad se desvia al almacen mas cercano con saldo
 *       suficiente y carga hasta su capacidad. Una unidad puede recargar varias
 *       veces dentro de su turno; el tiempo de carga es despreciable.</li>
 *   <li><b>Plazos.</b> El cumplimiento se evalua con la hora de llegada, porque
 *       la hora de acondicionamiento de la entrega no cuenta dentro del plazo.</li>
 *   <li><b>Refrigerio.</b> Se coloca en el primer instante admisible a partir de
 *       inicio + 1 h y debe terminar a mas tardar en fin - 1 h.</li>
 *   <li><b>Jornada.</b> El exceso sobre el fin del turno se cuantifica como
 *       violacion.</li>
 * </ul>
 *
 * <p>El saldo disponible de cada almacen se recibe como parametro (saldo total
 * menos el consumo de las demas unidades), de modo que la evaluacion no depende
 * de estado compartido mutable y puede ejecutarse en paralelo.</p>
 */
public final class EvaluadorItinerario {

    private EvaluadorItinerario() {
    }

    /** Estado incremental de una ruta en construccion. */
    public static final class Traza {
        public int nodo;
        public long t;
        public double km;
        public int carga;
        public int almacenCarga = -1;
        public boolean refPendiente;
        public long turnoFin;
        public long refDesde;
        public long refHasta;
        public double atraso;
        public double refFuera;
        public int entregas;
        public int recargas;
        public int[] consumo;
        public boolean valida = true;

        public Traza copia() {
            Traza c = new Traza();
            c.nodo = nodo;
            c.t = t;
            c.km = km;
            c.carga = carga;
            c.almacenCarga = almacenCarga;
            c.refPendiente = refPendiente;
            c.turnoFin = turnoFin;
            c.refDesde = refDesde;
            c.refHasta = refHasta;
            c.atraso = atraso;
            c.refFuera = refFuera;
            c.entregas = entregas;
            c.recargas = recargas;
            c.consumo = consumo.clone();
            c.valida = valida;
            return c;
        }
    }

    /** Turnos de ocho horas con cambios a las 07:00, 15:00 y 23:00. */
    private static final long PRIMER_TURNO = 7L * 3600L;
    private static final long DURACION_TURNO = 8L * 3600L;

    public static Traza iniciar(DatosPlanificacion d, DatosPlanificacion.Unidad u) {
        Traza tr = new Traza();
        tr.nodo = u.nodo;
        tr.t = u.inicio;
        tr.carga = u.cargaInicial;
        tr.consumo = new int[d.nAlm];
        fijarTurno(tr, u.inicio);
        // Si la ventana del refrigerio ya paso cuando la unidad inicia su ruta,
        // el conductor lo tomo mientras la unidad estaba inactiva.
        tr.refPendiente = u.refPendiente && tr.t <= tr.refHasta;
        return tr;
    }

    private static void fijarTurno(Traza tr, long instante) {
        long inicioTurno = PRIMER_TURNO
                + DURACION_TURNO * Math.floorDiv(instante - PRIMER_TURNO, DURACION_TURNO);
        tr.turnoFin = inicioTurno + DURACION_TURNO;
        tr.refDesde = inicioTurno + 3600L;
        tr.refHasta = tr.turnoFin - 2L * 3600L;
    }

    /**
     * Cambio de turno: al cruzar el fin de la jornada entra un nuevo conductor
     * con su propia hora de refrigerio. Si el conductor saliente no alcanzo a
     * tomar la suya, se registra la violacion.
     */
    private static void avanzarTurnos(Traza tr) {
        while (tr.t >= tr.turnoFin) {
            if (tr.refPendiente) {
                tr.refFuera += 1.0;
            }
            fijarTurno(tr, tr.turnoFin);
            tr.refPendiente = true;
        }
    }

    /**
     * Agrega una entrega a la ruta, insertando la recarga y el refrigerio que
     * resulten necesarios. Devuelve false si la entrega no es alcanzable o
     * excede la capacidad de la unidad.
     */
    public static boolean extender(DatosPlanificacion d, DatosPlanificacion.Unidad u, Traza tr,
                                   int pedido, int[] disponible, List<ParadaRuta> itinerario) {
        int q = d.cantidad[pedido];
        if (!tr.valida || q > u.capacidad) {
            tr.valida = false;
            return false;
        }
        if (tr.carga < q && !recargar(d, u, tr, q, disponible, itinerario)) {
            return false;
        }
        int km = d.dist.km(tr.nodo, d.nodoPedido[pedido]);
        if (km < 0) {
            tr.valida = false;
            return false;
        }
        tr.km += km;
        tr.t += DatosPlanificacion.viaje(km, u.velocidad);
        refrigerio(d, u, tr, itinerario);
        long llegada = tr.t;
        long atraso = llegada - d.limite[pedido];
        if (atraso > 0) {
            tr.atraso += d.mu[pedido] * atraso / 3600.0;
        }
        tr.t += 3600L;                                     // 1 h de acondicionamiento
        if (itinerario != null) {
            itinerario.add(new ParadaRuta(TipoParada.ENTREGA, d.dist.nodoDe(d.nodoPedido[pedido]),
                    d.pedidos[pedido].getId(), q, d.instanteDe(llegada), d.instanteDe(tr.t),
                    -atraso / 3600.0));
        }
        tr.nodo = d.nodoPedido[pedido];
        tr.carga -= q;
        tr.entregas++;
        if (tr.almacenCarga >= 0) {
            tr.consumo[tr.almacenCarga] += q;
        }
        return true;
    }

    private static boolean recargar(DatosPlanificacion d, DatosPlanificacion.Unidad u, Traza tr,
                                    int necesario, int[] disponible, List<ParadaRuta> itinerario) {
        int mejor = -1;
        int mejorKm = Integer.MAX_VALUE;
        for (int a = 0; a < d.nAlm; a++) {
            int libre = d.infinito[a] ? Integer.MAX_VALUE : disponible[a] - tr.consumo[a];
            if (libre < necesario) {
                continue;
            }
            int km = d.dist.km(tr.nodo, d.nodoAlmacen[a]);
            if (km >= 0 && km < mejorKm) {
                mejorKm = km;
                mejor = a;
            }
        }
        if (mejor < 0) {
            tr.valida = false;
            return false;
        }
        tr.km += mejorKm;
        tr.t += DatosPlanificacion.viaje(mejorKm, u.velocidad);
        refrigerio(d, u, tr, itinerario);
        tr.nodo = d.nodoAlmacen[mejor];
        int libre = d.infinito[mejor] ? Integer.MAX_VALUE : disponible[mejor] - tr.consumo[mejor];
        tr.carga = Math.min(u.capacidad, libre);
        tr.almacenCarga = mejor;
        tr.recargas++;
        if (itinerario != null) {
            itinerario.add(new ParadaRuta(TipoParada.RECARGA, d.dist.nodoDe(d.nodoAlmacen[mejor]),
                    d.almacenes[mejor].getId(), tr.carga, d.instanteDe(tr.t), d.instanteDe(tr.t), 0.0));
        }
        return true;
    }

    private static void refrigerio(DatosPlanificacion d, DatosPlanificacion.Unidad u, Traza tr,
                                   List<ParadaRuta> itinerario) {
        avanzarTurnos(tr);
        if (!tr.refPendiente || tr.t < tr.refDesde) {
            return;
        }
        if (tr.t > tr.refHasta) {
            tr.refFuera += (tr.t - tr.refHasta) / 3600.0;
        }
        long inicio = tr.t;
        tr.t += 3600L;
        tr.refPendiente = false;
        if (itinerario != null) {
            itinerario.add(new ParadaRuta(TipoParada.REFRIGERIO, d.dist.nodoDe(tr.nodo), "refrigerio", 0,
                    d.instanteDe(inicio), d.instanteDe(tr.t), 0.0));
        }
        avanzarTurnos(tr);
    }

    /** Cierra la ruta sin modificar la traza: refrigerio pendiente y exceso de jornada. */
    public static ResultadoRuta cerrar(DatosPlanificacion d, DatosPlanificacion.Unidad u, Traza tr) {
        if (!tr.valida) {
            return ResultadoRuta.invalida(d.nAlm);
        }
        // La ruta no puede extenderse mas alla del horizonte de planificacion.
        double exceso = Math.max(0L, tr.t - u.horizonte) / 3600.0;
        return new ResultadoRuta(true, tr.km, tr.km * u.costoKm, tr.atraso, exceso, tr.refFuera,
                tr.t, tr.entregas, tr.recargas, tr.consumo.clone());
    }

    /**
     * Hora de llegada (en segundos desde la base) de cada entrega de la
     * secuencia; Long.MAX_VALUE si la ruta no es valida. Permite identificar las
     * entregas que no alcanzan su plazo.
     */
    public static long[] llegadas(DatosPlanificacion d, int unidad, int[] secuencia, int[] disponible) {
        DatosPlanificacion.Unidad u = d.unidades[unidad];
        Traza tr = iniciar(d, u);
        long[] llegadas = new long[secuencia.length];
        java.util.Arrays.fill(llegadas, Long.MAX_VALUE);
        for (int i = 0; i < secuencia.length; i++) {
            if (!extender(d, u, tr, secuencia[i], disponible, null)) {
                return llegadas;
            }
            llegadas[i] = tr.t - 3600L;                 // la llegada precede al acondicionamiento
        }
        return llegadas;
    }

    public static ResultadoRuta evaluar(DatosPlanificacion d, int unidad, int[] secuencia, int[] disponible) {
        return evaluar(d, unidad, secuencia, disponible, null);
    }

    public static ResultadoRuta evaluar(DatosPlanificacion d, int unidad, int[] secuencia,
                                        int[] disponible, List<ParadaRuta> itinerario) {
        DatosPlanificacion.Unidad u = d.unidades[unidad];
        Traza tr = iniciar(d, u);
        for (int p : secuencia) {
            if (!extender(d, u, tr, p, disponible, itinerario)) {
                return ResultadoRuta.invalida(d.nAlm);
            }
        }
        return cerrar(d, u, tr);
    }
}
