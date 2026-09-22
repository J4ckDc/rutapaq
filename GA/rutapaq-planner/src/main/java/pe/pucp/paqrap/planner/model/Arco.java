package pe.pucp.paqrap.planner.model;

import java.util.Objects;

/**
 * Arco no dirigido de la red vial: todas las calles son de doble sentido, por lo
 * que (i, j) y (j, i) representan el mismo elemento y comparten identidad.
 */
public final class Arco {

    private final String extremoA;
    private final String extremoB;

    private Arco(String extremoA, String extremoB) {
        this.extremoA = extremoA;
        this.extremoB = extremoB;
    }

    public static Arco de(String unExtremo, String otroExtremo) {
        Objects.requireNonNull(unExtremo);
        Objects.requireNonNull(otroExtremo);
        return unExtremo.compareTo(otroExtremo) <= 0
                ? new Arco(unExtremo, otroExtremo)
                : new Arco(otroExtremo, unExtremo);
    }

    public String getExtremoA() {
        return extremoA;
    }

    public String getExtremoB() {
        return extremoB;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Arco)) {
            return false;
        }
        Arco otro = (Arco) o;
        return extremoA.equals(otro.extremoA) && extremoB.equals(otro.extremoB);
    }

    @Override
    public int hashCode() {
        return Objects.hash(extremoA, extremoB);
    }

    @Override
    public String toString() {
        return "(" + extremoA + " <-> " + extremoB + ")";
    }
}
