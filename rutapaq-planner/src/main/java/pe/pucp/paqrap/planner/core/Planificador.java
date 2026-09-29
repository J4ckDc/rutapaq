package pe.pucp.paqrap.planner.core;

import pe.pucp.paqrap.planner.model.PlanDistribucion;

/**
 * Interfaz comun del componente planificador. El ALNS y el HGS/GA la
 * implementan, de modo que el simulador del ciclo programado invoque a ambos de
 * la misma forma y con el mismo presupuesto de computo.
 */
public interface Planificador {

    String nombre();

    PlanDistribucion planificar(InstanciaPlanificacion instancia);

    /** Iteraciones (ALNS) o descendientes (HGS) de la ultima ejecucion. */
    int iteracionesEjecutadas();

    /** Tiempo de busqueda de la ultima ejecucion, en milisegundos. */
    long tiempoBusquedaMs();
}
