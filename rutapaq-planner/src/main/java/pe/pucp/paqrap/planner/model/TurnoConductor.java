package pe.pucp.paqrap.planner.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Turno de ocho horas con cambios a las 07:00, 15:00 y 23:00, y una hora de
 * refrigerio que debe ubicarse al menos una hora despues del inicio y una hora
 * antes del fin de la jornada.
 */
public final class TurnoConductor {

    private static final LocalTime[] INICIOS = {LocalTime.of(7, 0), LocalTime.of(15, 0), LocalTime.of(23, 0)};

    private final LocalDateTime inicio;
    private final LocalDateTime fin;
    private boolean refrigerioConsumido;

    private TurnoConductor(LocalDateTime inicio) {
        this.inicio = inicio;
        this.fin = inicio.plusHours(8);
    }

    /** Turno vigente en el instante dado. */
    public static TurnoConductor vigenteEn(LocalDateTime instante) {
        LocalDate dia = instante.toLocalDate();
        LocalDateTime mejor = dia.minusDays(1).atTime(INICIOS[2]);
        for (LocalDate d : new LocalDate[]{dia.minusDays(1), dia, dia.plusDays(1)}) {
            for (LocalTime t : INICIOS) {
                LocalDateTime c = d.atTime(t);
                if (!c.isAfter(instante) && c.isAfter(mejor)) {
                    mejor = c;
                }
            }
        }
        return new TurnoConductor(mejor);
    }

    public LocalDateTime getInicio() {
        return inicio;
    }

    public LocalDateTime getFin() {
        return fin;
    }

    public LocalDateTime getRefrigerioDesde() {
        return inicio.plusHours(1);
    }

    public LocalDateTime getRefrigerioHasta() {
        return fin.minusHours(2);
    }

    public boolean isRefrigerioConsumido() {
        return refrigerioConsumido;
    }

    public void setRefrigerioConsumido(boolean refrigerioConsumido) {
        this.refrigerioConsumido = refrigerioConsumido;
    }

    @Override
    public String toString() {
        return "T[" + inicio + " - " + fin + "]";
    }
}
