package pe.pucp.paqrap.planner.core;

/**
 * Resultado de evaluar la secuencia de entregas de una unidad: kilometros,
 * costo en soles y las violaciones de las restricciones blandas (atraso
 * ponderado por urgencia, exceso sobre la jornada y refrigerio fuera de su
 * ventana). Una ruta es factible cuando no presenta ninguna violacion.
 */
public record ResultadoRuta(boolean valida,
                            double km,
                            double costo,
                            double atrasoPonderadoH,
                            double excesoTurnoH,
                            double refrigerioFueraH,
                            long finSegundos,
                            int entregas,
                            int recargas,
                            int[] consumo) {

    public static ResultadoRuta invalida(int nAlmacenes) {
        return new ResultadoRuta(false, 0, 0, 0, 0, 0, 0, 0, 0, new int[nAlmacenes]);
    }

    public double violacionTurno() {
        return excesoTurnoH + refrigerioFueraH;
    }

    public boolean factible() {
        return valida && atrasoPonderadoH <= 1.0E-9 && violacionTurno() <= 1.0E-9;
    }
}
