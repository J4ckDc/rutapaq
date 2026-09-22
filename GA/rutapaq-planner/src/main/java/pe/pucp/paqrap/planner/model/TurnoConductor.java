package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;

/**
 * Turno de ocho horas (cambios a las 07:00, 15:00 y 23:00) con la hora
 * obligatoria de alimentacion, que debe ubicarse al menos una hora despues del
 * inicio y una hora antes del fin de la jornada.
 */
public final class TurnoConductor {

    private final String idTurno;
    private final String idConductor;
    private final String idVehiculoAsignado;
    private final LocalDateTime horaInicio;
    private final LocalDateTime horaFin;
    private LocalDateTime inicioRefrigerio;
    private LocalDateTime finRefrigerio;
    private boolean refrigerioConsumido;

    public TurnoConductor(String idTurno, String idConductor, String idVehiculoAsignado,
                          LocalDateTime horaInicio) {
        this.idTurno = idTurno;
        this.idConductor = idConductor;
        this.idVehiculoAsignado = idVehiculoAsignado;
        this.horaInicio = horaInicio;
        this.horaFin = horaInicio.plusHours(8);
    }

    public String getIdTurno() {
        return idTurno;
    }

    public String getIdConductor() {
        return idConductor;
    }

    public String getIdVehiculoAsignado() {
        return idVehiculoAsignado;
    }

    public LocalDateTime getHoraInicio() {
        return horaInicio;
    }

    public LocalDateTime getHoraFin() {
        return horaFin;
    }

    /** Limite inferior admisible para el inicio del refrigerio. */
    public LocalDateTime getVentanaRefrigerioDesde() {
        return horaInicio.plusHours(1);
    }

    /** Limite superior admisible para el inicio del refrigerio (fin <= horaFin - 1 h). */
    public LocalDateTime getVentanaRefrigerioHasta() {
        return horaFin.minusHours(2);
    }

    public LocalDateTime getInicioRefrigerio() {
        return inicioRefrigerio;
    }

    public LocalDateTime getFinRefrigerio() {
        return finRefrigerio;
    }

    public boolean isRefrigerioConsumido() {
        return refrigerioConsumido;
    }

    public void registrarRefrigerio(LocalDateTime inicio) {
        this.inicioRefrigerio = inicio;
        this.finRefrigerio = inicio.plusHours(1);
        this.refrigerioConsumido = true;
    }

    @Override
    public String toString() {
        return idTurno + "[" + horaInicio.toLocalTime() + "-" + horaFin.toLocalTime() + "]";
    }
}
