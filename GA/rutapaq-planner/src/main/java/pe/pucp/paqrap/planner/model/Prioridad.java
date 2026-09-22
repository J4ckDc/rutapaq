package pe.pucp.paqrap.planner.model;

/**
 * Prioridad derivada de la ventana comprometida del pedido.
 * El factor mu pondera el arrepentimiento en el operador Regret-k y la
 * penalizacion por pedido no atendido en la funcion objetivo.
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
    public static Prioridad desdeVentana(int ventanaHoras) {
        if (ventanaHoras <= 8) {
            return ALTA;
        }
        if (ventanaHoras <= 18) {
            return MEDIA;
        }
        return REGULAR;
    }
}
