package pe.pucp.paqrap.planner.core;

import java.time.LocalDateTime;

/**
 * Resultado inmutable de ValidadorFactibilidad: evita modificar el estado
 * compartido durante la evaluacion de inserciones.
 */
public record ResultadoFactibilidad(boolean factible,
                                    String motivo,
                                    double holguraMinimaHoras,
                                    double distanciaKm,
                                    double costoSoles,
                                    double tiempoHoras,
                                    LocalDateTime fechaHoraFin,
                                    LocalDateTime inicioRefrigerio) {

    public static ResultadoFactibilidad infactible(String motivo) {
        return new ResultadoFactibilidad(false, motivo, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, null, null);
    }
}
