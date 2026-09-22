package pe.pucp.paqrap.planner.hgs;

/**
 * Penalizaciones adaptativas omegaSLA, omegaTurno y omegaStock. Cada
 * periodoAjuste descendientes se comparan con la proporcion objetivo de
 * individuos factibles: si la proporcion observada es menor, las penalizaciones
 * se incrementan; si es mayor, se reducen. Asi la busqueda transita por regiones
 * infactibles sin instalarse en ellas. omegaNoAtendido no se adapta: domina la
 * funcion objetivo y fuerza la atencion de la totalidad de la cartera.
 */
final class Penalizaciones {

    private static final double MINIMO = 1.0;
    private static final double MAXIMO = 1.0E6;

    double sla;
    double turno;
    double stock;
    final double noAtendido;

    private final double objetivo;
    private final int periodo;
    private int registrados;
    private int factiblesEnPeriodo;

    Penalizaciones(ParametrosHGS p) {
        this.sla = p.omegaSLA();
        this.turno = p.omegaTurno();
        this.stock = p.omegaStock();
        this.noAtendido = p.omegaNoAtendido();
        this.objetivo = p.objetivoFactibles();
        this.periodo = Math.max(1, p.periodoAjuste());
    }

    /** Registra un descendiente y devuelve true si corresponde ajustar las penalizaciones. */
    boolean registrar(boolean factible) {
        registrados++;
        if (factible) {
            factiblesEnPeriodo++;
        }
        return registrados % periodo == 0;
    }

    void ajustar() {
        double proporcion = factiblesEnPeriodo / (double) periodo;
        double factor = 1.0;
        if (proporcion < objetivo - 0.05) {
            factor = 1.2;
        } else if (proporcion > objetivo + 0.05) {
            factor = 0.85;
        }
        sla = acotar(sla * factor);
        turno = acotar(turno * factor);
        stock = acotar(stock * factor);
        factiblesEnPeriodo = 0;
    }

    private static double acotar(double v) {
        return Math.max(MINIMO, Math.min(MAXIMO, v));
    }
}
