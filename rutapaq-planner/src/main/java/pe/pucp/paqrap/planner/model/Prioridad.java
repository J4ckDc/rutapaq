package pe.pucp.paqrap.planner.model;

/**
 * Prioridad derivada del plazo comprometido. El factor mu pondera el
 * arrepentimiento del ALNS y la penalizacion por producto no planificado, de
 * modo que los plazos cortos se atienden primero.
 */
public enum Prioridad {
    ALTA(5.0),
    MEDIA(3.0),
    REGULAR(1.0);

    private final double factorMu;

    Prioridad(double factorMu) {
        this.factorMu = factorMu;
    }

    public double getFactorMu() {
        return factorMu;
    }

    /** 4 y 8 h -> ALTA; 12 y 18 h -> MEDIA; 36 h -> REGULAR. */
    public static Prioridad desdePlazo(int plazoHoras) {
        if (plazoHoras <= 8) {
            return ALTA;
        }
        return plazoHoras <= 18 ? MEDIA : REGULAR;
    }
}
