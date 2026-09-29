package pe.pucp.paqrap.planner.core;

/**
 * Valorizacion de una ruta. En modo duro (ALNS) cualquier violacion hace
 * inaceptable la ruta; en modo blando (HGS) las violaciones se penalizan con
 * coeficientes que la busqueda ajusta de forma adaptativa.
 */
public final class Penalizador {

    private double sla;
    private double turno;
    private final double noAtendido;
    private final boolean duro;

    public Penalizador(double sla, double turno, double noAtendido, boolean duro) {
        this.sla = sla;
        this.turno = turno;
        this.noAtendido = noAtendido;
        this.duro = duro;
    }

    public static Penalizador duro(double noAtendido) {
        return new Penalizador(0, 0, noAtendido, true);
    }

    public double costo(ResultadoRuta r) {
        if (!r.valida()) {
            return Double.POSITIVE_INFINITY;
        }
        if (duro) {
            return r.factible() ? r.costo() : Double.POSITIVE_INFINITY;
        }
        return r.costo() + sla * r.atrasoPonderadoH() + turno * r.violacionTurno();
    }

    public boolean aceptable(ResultadoRuta r) {
        return r.valida() && (!duro || r.factible());
    }

    public double getSla() {
        return sla;
    }

    public void setSla(double sla) {
        this.sla = sla;
    }

    public double getTurno() {
        return turno;
    }

    public void setTurno(double turno) {
        this.turno = turno;
    }

    public double getNoAtendido() {
        return noAtendido;
    }

    public boolean isDuro() {
        return duro;
    }
}
