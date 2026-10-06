package pe.pucp.paqrap.planner.hgs;

import pe.pucp.paqrap.planner.core.Penalizador;

/**
 * Ajuste adaptativo de las penalizaciones omegaSLA y omegaTurno del HGS. Cada
 * periodoAjuste descendientes se compara la proporcion de individuos factibles
 * con la proporcion objetivo: si es menor, las penalizaciones suben; si es
 * mayor, bajan. Asi la busqueda transita por regiones infactibles sin
 * instalarse en ellas.
 */
final class PenalizacionesAdaptativas {

    private static final double MINIMO = 1.0;
    private static final double MAXIMO = 1.0E6;

    private final double objetivo;
    private final int periodo;
    private int registrados;
    private int factibles;

    PenalizacionesAdaptativas(ParametrosHGS p) {
        this.objetivo = p.objetivoFactibles();
        this.periodo = Math.max(1, p.periodoAjuste());
    }

    boolean registrar(boolean factible) {
        registrados++;
        if (factible) {
            factibles++;
        }
        return registrados % periodo == 0;
    }

    void ajustar(Penalizador pen) {
        double proporcion = factibles / (double) periodo;
        double factor = 1.0;
        if (proporcion < objetivo - 0.05) {
            factor = 1.2;
        } else if (proporcion > objetivo + 0.05) {
            factor = 0.85;
        }
        pen.setSla(acotar(pen.getSla() * factor));
        pen.setTurno(acotar(pen.getTurno() * factor));
        factibles = 0;
    }

    private static double acotar(double v) {
        return Math.max(MINIMO, Math.min(MAXIMO, v));
    }
}
