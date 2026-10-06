package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;

/**
 * Pedido del historial de ventas: ddDhhHmmm:x,y,idCliente,qq,hl.
 * La fecha limite es la hora de registro mas el plazo comprometido (4, 8, 12,
 * 18 o 36 h) y su vencimiento sin entrega define el colapso por incumplimiento.
 * La hora de acondicionamiento de la entrega no cuenta dentro del plazo: el
 * cumplimiento se evalua con la hora de llegada de la unidad.
 */
public final class Pedido {

    private final String id;
    private final String idCliente;
    private final int nodo;
    private final int x;
    private final int y;
    private final int cantidad;
    private final LocalDateTime registro;
    private final int plazoHoras;
    private final LocalDateTime limite;
    private final Prioridad prioridad;
    private final String idPedidoPadre;
    private EstadoPedido estado = EstadoPedido.PENDIENTE;
    private LocalDateTime horaEntrega;

    public Pedido(String id, String idCliente, int nodo, int x, int y, int cantidad,
                  LocalDateTime registro, int plazoHoras, String idPedidoPadre) {
        this.id = id;
        this.idCliente = idCliente;
        this.nodo = nodo;
        this.x = x;
        this.y = y;
        this.cantidad = cantidad;
        this.registro = registro;
        this.plazoHoras = plazoHoras;
        this.limite = registro.plusHours(plazoHoras);
        this.prioridad = Prioridad.desdePlazo(plazoHoras);
        this.idPedidoPadre = idPedidoPadre;
    }

    public String getId() {
        return id;
    }

    public String getIdCliente() {
        return idCliente;
    }

    public int getNodo() {
        return nodo;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getCantidad() {
        return cantidad;
    }

    public LocalDateTime getRegistro() {
        return registro;
    }

    public int getPlazoHoras() {
        return plazoHoras;
    }

    public LocalDateTime getLimite() {
        return limite;
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

    public LocalDateTime getHoraEntrega() {
        return horaEntrega;
    }

    public void setHoraEntrega(LocalDateTime horaEntrega) {
        this.horaEntrega = horaEntrega;
    }

    @Override
    public String toString() {
        return id + "[" + cantidad + "p/" + plazoHoras + "h]";
    }
}
