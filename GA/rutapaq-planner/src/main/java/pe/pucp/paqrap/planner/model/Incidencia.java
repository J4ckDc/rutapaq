package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;

/** Evento disruptivo: bloqueo de calle o falla mecanica de una unidad. */
public final class Incidencia {

    private final String idIncidencia;
    private final TipoIncidencia tipoIncidencia;
    private final Arco arcoAfectado;
    private final String idVehiculoAfectado;
    private final LocalDateTime fechaHoraReporte;
    private final double duracionEstimadaHoras;
    private final LocalDateTime fechaHoraFinEstimada;

    private Incidencia(String idIncidencia, TipoIncidencia tipoIncidencia, Arco arcoAfectado,
                       String idVehiculoAfectado, LocalDateTime fechaHoraReporte,
                       double duracionEstimadaHoras) {
        this.idIncidencia = idIncidencia;
        this.tipoIncidencia = tipoIncidencia;
        this.arcoAfectado = arcoAfectado;
        this.idVehiculoAfectado = idVehiculoAfectado;
        this.fechaHoraReporte = fechaHoraReporte;
        this.duracionEstimadaHoras = duracionEstimadaHoras;
        this.fechaHoraFinEstimada = fechaHoraReporte.plusSeconds(Math.round(duracionEstimadaHoras * 3600));
    }

    public static Incidencia bloqueoCalle(String id, Arco arco, LocalDateTime reporte, double horas) {
        return new Incidencia(id, TipoIncidencia.BLOQUEO_CALLE, arco, null, reporte, horas);
    }

    public static Incidencia fallaMecanica(String id, String idVehiculo, LocalDateTime reporte, double horas) {
        return new Incidencia(id, TipoIncidencia.FALLA_MECANICA, null, idVehiculo, reporte, horas);
    }

    public String getIdIncidencia() {
        return idIncidencia;
    }

    public TipoIncidencia getTipoIncidencia() {
        return tipoIncidencia;
    }

    public Arco getArcoAfectado() {
        return arcoAfectado;
    }

    public String getIdVehiculoAfectado() {
        return idVehiculoAfectado;
    }

    public LocalDateTime getFechaHoraReporte() {
        return fechaHoraReporte;
    }

    public double getDuracionEstimadaHoras() {
        return duracionEstimadaHoras;
    }

    public LocalDateTime getFechaHoraFinEstimada() {
        return fechaHoraFinEstimada;
    }

    @Override
    public String toString() {
        return idIncidencia + ":" + tipoIncidencia;
    }
}
