package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;

/**
 * Pedido del producto "P". La fecha limite es el resultado de la fecha de
 * registro mas la ventana comprometida (4, 8, 12, 18 o 36 horas) y constituye
 * una restriccion dura del planificador.
 */
public final class Pedido {

    private final String idPedido;
    private final String idCliente;
    private final NodoRed nodoDestino;
    private final int cantidadPaquetes;
    private final LocalDateTime fechaHoraRegistro;
    private final int ventanaHoras;
    private final LocalDateTime fechaHoraLimite;
    private final Prioridad prioridad;
    private final String idPedidoPadre;
    private EstadoPedido estado;
    private String idVehiculoAsignado;

    public Pedido(String idPedido, String idCliente, NodoRed nodoDestino, int cantidadPaquetes,
                  LocalDateTime fechaHoraRegistro, int ventanaHoras, String idPedidoPadre) {
        this.idPedido = idPedido;
        this.idCliente = idCliente;
        this.nodoDestino = nodoDestino;
        this.cantidadPaquetes = cantidadPaquetes;
        this.fechaHoraRegistro = fechaHoraRegistro;
        this.ventanaHoras = ventanaHoras;
        this.fechaHoraLimite = fechaHoraRegistro.plusHours(ventanaHoras);
        this.prioridad = Prioridad.desdeVentana(ventanaHoras);
        this.idPedidoPadre = idPedidoPadre;
        this.estado = EstadoPedido.PENDIENTE;
    }

    public String getIdPedido() {
        return idPedido;
    }

    public String getIdCliente() {
        return idCliente;
    }

    public NodoRed getNodoDestino() {
        return nodoDestino;
    }

    public int getCantidadPaquetes() {
        return cantidadPaquetes;
    }

    public LocalDateTime getFechaHoraRegistro() {
        return fechaHoraRegistro;
    }

    public int getVentanaHoras() {
        return ventanaHoras;
    }

    public LocalDateTime getFechaHoraLimite() {
        return fechaHoraLimite;
    }

    public Prioridad getPrioridad() {
        return prioridad;
    }

    public String getIdPedidoPadre() {
        return idPedidoPadre;
    }

    public EstadoPedido getEstado() {
        return estado;
    }

    public void setEstado(EstadoPedido estado) {
        this.estado = estado;
    }

    public String getIdVehiculoAsignado() {
        return idVehiculoAsignado;
    }

    public void setIdVehiculoAsignado(String idVehiculoAsignado) {
        this.idVehiculoAsignado = idVehiculoAsignado;
    }

    @Override
    public String toString() {
        return idPedido + "[" + cantidadPaquetes + "p/" + ventanaHoras + "h]";
    }
}
