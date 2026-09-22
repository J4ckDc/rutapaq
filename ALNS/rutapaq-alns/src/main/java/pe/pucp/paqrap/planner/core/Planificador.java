package pe.pucp.paqrap.planner.core;

import pe.pucp.paqrap.planner.model.PlanDistribucion;

/**
 * Interfaz comun del componente planificador. ALNS y HGS/GA la implementan, de
 * modo que el entorno de experimentacion numerica intercambia ambos algoritmos
 * sin modificar el codigo del banco de pruebas.
 */
public interface Planificador {

    String nombre();

    PlanDistribucion resolver(InstanciaEscenario instancia);

    /** Numero de iteraciones o generaciones efectivamente ejecutadas en la ultima corrida. */
    int iteracionesEjecutadas();
}
