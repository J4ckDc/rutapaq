package pe.pucp.paqrap.planner.alns;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import pe.pucp.paqrap.planner.alns.destroy.RemocionAleatoria;
import pe.pucp.paqrap.planner.alns.destroy.RemocionDeRuta;
import pe.pucp.paqrap.planner.alns.destroy.RemocionPeorCosto;
import pe.pucp.paqrap.planner.alns.destroy.RemocionRelacionadaShaw;
import pe.pucp.paqrap.planner.alns.repair.InsercionCostoMinimo;
import pe.pucp.paqrap.planner.alns.repair.InsercionRegretK;
import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.ContextoPlanificacion;
import pe.pucp.paqrap.planner.core.InstanciaEscenario;
import pe.pucp.paqrap.planner.core.Planificador;
import pe.pucp.paqrap.planner.core.ValidadorFactibilidad;
import pe.pucp.paqrap.planner.model.Incidencia;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;

/**
 * Busqueda Local de Gran Vecindario Adaptativa (ALNS) para el componente
 * planificador de PaqRap, bajo un esquema de reoptimizacion rodante: al recibir
 * un evento disruptivo las visitas ejecutadas o inminentes se declaran
 * inmutables y la busqueda opera unicamente sobre el subconjunto de decisiones
 * pendientes.
 *
 * <p>La ejecucion es interrumpible: al agotarse el presupuesto de iteraciones o
 * el limite de tiempo del escenario en curso, el algoritmo retorna de inmediato
 * la mejor solucion factible hallada hasta ese instante, de modo que el
 * planificador nunca queda sin un plan valido.</p>
 */
public final class ALNS_PAQRAP implements Planificador {

    private final Configuracion cfg;
    private final ValidadorFactibilidad validador;
    private final List<OperadorDestroy> operadoresDestroy;
    private final List<OperadorRepair> operadoresRepair;
    private final SplittableRandom rnd;

    private RuletaAdaptativa<OperadorDestroy> ruletaDestroy;
    private RuletaAdaptativa<OperadorRepair> ruletaRepair;
    private int iteracionesEjecutadas;
    private long tiempoUltimaCorridaMs;

    public ALNS_PAQRAP(Configuracion cfg) {
        this(cfg, new ValidadorFactibilidad(cfg), cfg.semilla());
    }

    public ALNS_PAQRAP(Configuracion cfg, ValidadorFactibilidad validador, long semilla) {
        this.cfg = cfg;
        this.validador = validador;
        this.rnd = new SplittableRandom(semilla);
        this.operadoresDestroy = List.of(
                new RemocionAleatoria(),
                new RemocionPeorCosto(),
                new RemocionRelacionadaShaw(),
                new RemocionDeRuta());
        this.operadoresRepair = List.of(
                new InsercionCostoMinimo(),
                new InsercionRegretK());
    }

    @Override
    public String nombre() {
        return "ALNS";
    }

    @Override
    public PlanDistribucion resolver(InstanciaEscenario instancia) {
        return optimizar(instancia.getContexto(), instancia.getPlanVigente(),
                instancia.getPedidosNuevos(), instancia.getIncidencias());
    }

    @Override
    public int iteracionesEjecutadas() {
        return iteracionesEjecutadas;
    }

    public long tiempoUltimaCorridaMs() {
        return tiempoUltimaCorridaMs;
    }

    public RuletaAdaptativa<OperadorDestroy> getRuletaDestroy() {
        return ruletaDestroy;
    }

    public RuletaAdaptativa<OperadorRepair> getRuletaRepair() {
        return ruletaRepair;
    }

    /**
     * Procedimiento principal ALNS_PAQRAP_OPTIMIZAR.
     *
     * @param ctx           estado observable de la operacion
     * @param planActual    plan vigente, o null en la planificacion inicial del turno
     * @param nuevosPedidos pedidos llegados desde la ultima invocacion
     * @param incidencias   bloqueos de calles y fallas mecanicas reportadas
     * @return plan de distribucion factible de menor costo hallado
     */
    public PlanDistribucion optimizar(ContextoPlanificacion ctx, PlanDistribucion planActual,
                                      List<Pedido> nuevosPedidos, List<Incidencia> incidencias) {
        final long tIni = System.nanoTime();

        // 01-03. Congelamiento de asignaciones ejecutadas y aplicacion de incidencias.
        EstadoALNS actual = EstadoALNS.desde(planActual, ctx, validador);
        actual.congelarAsignaciones(cfg.horizonteCongelamientoMinutos());
        List<Pedido> banco = new ArrayList<>(ManejadorIncidencias.aplicar(actual, incidencias));
        if (nuevosPedidos != null) {
            banco.addAll(nuevosPedidos);
        }
        banco.addAll(actual.getPedidosNoAtendidos());
        actual.getPedidosNoAtendidos().clear();

        // 04. Solucion inicial factible por insercion voraz de menor costo.
        new InsercionCostoMinimo().reparar(actual, banco, rnd);

        // 05-06. Incumbente, temperatura inicial y pesos de la ruleta.
        EstadoALNS mejor = actual.copiar();
        double temperatura = Math.max(cfg.temperaturaMinima(), cfg.factorT0() * actual.costoTotal());
        double alfaIteracion = Math.pow(cfg.alfa(), 1.0 / Math.max(1, cfg.tamanoSegmento()));
        ruletaDestroy = new RuletaAdaptativa<>(operadoresDestroy);
        ruletaRepair = new RuletaAdaptativa<>(operadoresRepair);
        final long limiteNanos = (long) (cfg.limiteTiempoSegundos() * 1_000_000_000L);
        iteracionesEjecutadas = 0;

        // 07-31. Bucle de recocido simulado con vecindarios adaptativos.
        for (int iter = 1; iter <= cfg.maxIteraciones(); iter++) {
            if (System.nanoTime() - tIni >= limiteNanos) {
                break;
            }
            iteracionesEjecutadas = iter;
            int libres = actual.numVisitasLibres();
            if (libres == 0) {
                break;
            }
            OperadorDestroy destruir = ruletaDestroy.seleccionar(rnd);
            OperadorRepair reparar = ruletaRepair.seleccionar(rnd);
            int q = tamanoVecindario(libres);

            EstadoALNS candidata = actual.copiar();
            List<Pedido> removidos = destruir.destruir(candidata, q, rnd);
            if (removidos.isEmpty()) {
                continue;
            }
            reparar.reparar(candidata, removidos, rnd);

            double psi = 0.0;
            // Una candidata que deja mas pedidos sin atender se descarta antes de comparar costos.
            if (candidata.numNoAtendidos() <= actual.numNoAtendidos()) {
                double delta = candidata.costoTotal() - actual.costoTotal();
                if (delta < 0.0) {
                    actual = candidata;
                    psi = cfg.sigma2();
                    if (actual.costoTotal() < mejor.costoTotal()) {
                        mejor = actual.copiar();
                        psi = cfg.sigma1();
                    }
                } else if (rnd.nextDouble() < Math.exp(-delta / temperatura)) {
                    actual = candidata;
                    psi = cfg.sigma3();
                }
            }
            ruletaDestroy.acumular(destruir, psi);
            ruletaRepair.acumular(reparar, psi);

            if (iter % Math.max(1, cfg.tamanoSegmento()) == 0) {
                ruletaDestroy.actualizarPesos(cfg.lambda());
                ruletaRepair.actualizarPesos(cfg.lambda());
            }
            temperatura = Math.max(cfg.temperaturaMinima(), alfaIteracion * temperatura);
        }

        tiempoUltimaCorridaMs = (System.nanoTime() - tIni) / 1_000_000L;
        return mejor.construirPlan();
    }

    /** q se muestrea de forma uniforme entre el 15 % y el 35 % de las visitas libres. */
    private int tamanoVecindario(int visitasLibres) {
        int qMin = Math.max(1, (int) Math.floor(cfg.qMin() * visitasLibres));
        int qMax = Math.max(qMin, (int) Math.ceil(cfg.qMax() * visitasLibres));
        return qMin + rnd.nextInt(qMax - qMin + 1);
    }
}
