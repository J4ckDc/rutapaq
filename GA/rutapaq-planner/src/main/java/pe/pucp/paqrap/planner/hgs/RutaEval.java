package pe.pucp.paqrap.planner.hgs;

/**
 * Resultado de evaluar una ruta con penalizaciones (restricciones blandas). A
 * diferencia de ValidadorFactibilidad, que rechaza la ruta ante la primera
 * violacion, esta evaluacion cuantifica cada violacion para que la aptitud del
 * individuo pueda guiar la busqueda a traves de regiones infactibles.
 */
record RutaEval(boolean valida,
                double km,
                double costoKm,
                double atrasoPonderadoH,
                double excesoTurnoH,
                double refrigerioFueraH,
                int carga,
                long fin) {

    static final RutaEval INVALIDA = new RutaEval(false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            0, 0, 0, 0, 0);

    double violacionTurno() {
        return excesoTurnoH + refrigerioFueraH;
    }

    double costo(Penalizaciones pen, double factor) {
        if (!valida) {
            return Double.POSITIVE_INFINITY;
        }
        return costoKm + factor * (pen.sla * atrasoPonderadoH + pen.turno * violacionTurno());
    }
}
