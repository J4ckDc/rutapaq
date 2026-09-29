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
import pe.pucp.paqrap.planner.core.DatosPlanificacion;
import pe.pucp.paqrap.planner.core.InstanciaPlanificacion;
import pe.pucp.paqrap.planner.core.Penalizador;
import pe.pucp.paqrap.planner.core.Planificador;
import pe.pucp.paqrap.planner.core.SolucionRutas;
import pe.pucp.paqrap.planner.model.PlanDistribucion;

/**
 * Busqueda Local de Gran Vecindario Adaptativa (ALNS) para el componente
 * planificador de PaqRap.
 *
 * <p>En cada ejecucion del ciclo programado construye una solucion inicial por
 * insercion voraz y la mejora alternando operadores de destruccion y reparacion
 * elegidos por ruleta adaptativa, con aceptacion por recocido simulado. Las
 * restricciones del caso (capacidad, saldo de almacenes, plazos, jornada y
 * refrigerio) se tratan como duras: una insercion que las viole se rechaza, y
 * las recargas intermedias las inserta el evaluador comun.</p>
 *
 * <p>La ejecucion es interrumpible: al agotarse el presupuesto de computo P
 * devuelve de inmediato la mejor solucion hallada.</p>
 */
public final class ALNS_PAQRAP implements Planificador {

    private final Configuracion cfg;
    private final SplittableRandom rnd;
    private final List<OperadorDestroy> destroy;
    private final List<OperadorRepair> repair;
    private final double factorT0;
    private final double alfa;
    private final double temperaturaMinima;
    private final int tamanoSegmento;
    private final double lambda;
    private final double sigma1;
    private final double sigma2;
    private final double sigma3;
    private final double qMin;
    private final double qMax;
    private final int maxIteraciones;
    private final int iteracionesSinMejora;

    private RuletaAdaptativa<OperadorDestroy> ruletaDestroy;
    private RuletaAdaptativa<OperadorRepair> ruletaRepair;
    private int iteraciones;
    private long tiempoBusquedaMs;

    public ALNS_PAQRAP(Configuracion cfg, long semilla) {
        this.cfg = cfg;
        this.rnd = new SplittableRandom(semilla);
        this.factorT0 = cfg.numero("alns.factorT0", 0.05);
        this.alfa = cfg.numero("alns.alfa", 0.95);
        this.temperaturaMinima = cfg.numero("alns.temperaturaMinima", 1.0E-3);
        this.tamanoSegmento = cfg.entero("alns.tamanoSegmento", 100);
        this.lambda = cfg.numero("alns.lambda", 0.80);
        this.sigma1 = cfg.numero("alns.sigma1", 33.0);
        this.sigma2 = cfg.numero("alns.sigma2", 20.0);
        this.sigma3 = cfg.numero("alns.sigma3", 12.0);
        this.qMin = cfg.numero("alns.qMin", 0.15);
        this.qMax = cfg.numero("alns.qMax", 0.35);
        this.maxIteraciones = cfg.entero("alns.maxIteraciones", 1_000_000);
        this.iteracionesSinMejora = cfg.entero("alns.iteracionesSinMejora", 4000);
        this.destroy = List.of(
                new RemocionAleatoria(),
                new RemocionPeorCosto(cfg.numero("alns.pDeterminismo", 5.0)),
                new RemocionRelacionadaShaw(cfg.numero("alns.shaw.phi1", 0.6),
                        cfg.numero("alns.shaw.phi2", 0.4), cfg.numero("alns.shaw.horizonteHoras", 36.0)),
                new RemocionDeRuta());
        this.repair = List.of(
                new InsercionCostoMinimo(),
                new InsercionRegretK(cfg.entero("alns.kRegret", 3),
                        cfg.numero("alns.penalizacionSinAlternativa", 1.0E6)));
    }

    @Override
    public String nombre() {
        return "ALNS";
    }

    @Override
    public int iteracionesEjecutadas() {
        return iteraciones;
    }

    @Override
    public long tiempoBusquedaMs() {
        return tiempoBusquedaMs;
    }

    @Override
    public PlanDistribucion planificar(InstanciaPlanificacion instancia) {
        final long tIni = System.nanoTime();
        final long limiteNanos = (long) (cfg.presupuestoSegundos() * 1_000_000_000L);
        iteraciones = 0;

        DatosPlanificacion d = DatosPlanificacion.construir(instancia);
        Penalizador pen = Penalizador.duro(cfg.omegaNoPlanificado());
        SolucionRutas actual = SolucionRutas.vacia(d, pen);
        if (d.n == 0 || d.K == 0) {
            tiempoBusquedaMs = (System.nanoTime() - tIni) / 1_000_000L;
            return actual.construirPlan(instancia.getInstante());
        }

        // Solucion inicial factible por insercion voraz de menor costo.
        List<Integer> banco = new ArrayList<>();
        for (int p = 0; p < d.n; p++) {
            banco.add(p);
        }
        new InsercionCostoMinimo().reparar(actual, banco, rnd);

        SolucionRutas mejor = actual.copiar();
        double temperatura = Math.max(temperaturaMinima, factorT0 * Math.max(1.0, actual.costoTotal()));
        double alfaIteracion = Math.pow(alfa, 1.0 / Math.max(1, tamanoSegmento));
        ruletaDestroy = new RuletaAdaptativa<>(destroy);
        ruletaRepair = new RuletaAdaptativa<>(repair);

        int sinMejora = 0;
        for (int iter = 1; iter <= maxIteraciones; iter++) {
            if (System.nanoTime() - tIni >= limiteNanos || sinMejora >= iteracionesSinMejora) {
                break;                                  // presupuesto agotado o convergencia
            }
            iteraciones = iter;
            int asignados = actual.numAsignados();
            int libres = Math.max(asignados, 1);
            int q = tamanoVecindario(libres);
            OperadorDestroy od = ruletaDestroy.seleccionar(rnd);
            OperadorRepair or = ruletaRepair.seleccionar(rnd);

            SolucionRutas candidata = actual.copiar();
            List<Integer> removidos = od.destruir(candidata, q, rnd);
            if (removidos.isEmpty()) {
                continue;
            }
            or.reparar(candidata, removidos, rnd);

            double psi = 0.0;
            double delta = candidata.costoTotal() - actual.costoTotal();
            if (delta < 0.0) {
                actual = candidata;
                psi = sigma2;
                if (actual.costoTotal() < mejor.costoTotal() - 1.0E-9) {
                    mejor = actual.copiar();
                    psi = sigma1;
                    sinMejora = -1;
                }
            } else if (rnd.nextDouble() < Math.exp(-delta / temperatura)) {
                actual = candidata;
                psi = sigma3;
            }
            sinMejora++;
            ruletaDestroy.acumular(od, psi);
            ruletaRepair.acumular(or, psi);
            if (iter % Math.max(1, tamanoSegmento) == 0) {
                ruletaDestroy.actualizarPesos(lambda);
                ruletaRepair.actualizarPesos(lambda);
            }
            temperatura = Math.max(temperaturaMinima, alfaIteracion * temperatura);
        }

        tiempoBusquedaMs = (System.nanoTime() - tIni) / 1_000_000L;
        return mejor.construirPlan(instancia.getInstante());
    }

    /** q se muestrea de forma uniforme entre el 15 % y el 35 % de los pedidos planificados. */
    private int tamanoVecindario(int asignados) {
        int min = Math.max(1, (int) Math.floor(qMin * asignados));
        int max = Math.max(min, (int) Math.ceil(qMax * asignados));
        return min + rnd.nextInt(max - min + 1);
    }

    public RuletaAdaptativa<OperadorDestroy> getRuletaDestroy() {
        return ruletaDestroy;
    }

    public RuletaAdaptativa<OperadorRepair> getRuletaRepair() {
        return ruletaRepair;
    }
}
