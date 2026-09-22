package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;

/**
 * Parada de una ruta. Una visita congelada corresponde a una entrega ya
 * ejecutada o inminente y resulta inmutable para los operadores de destruccion.
 */
public final class VisitaCliente {

    private final Pedido pedido;
    private final int paquetesEntregados;
    private int ordenEnRuta;
    private LocalDateTime horaLlegadaEstimada;
    private LocalDateTime horaSalidaEstimada;
    private double holguraHoras;
    private boolean congelada;

    public VisitaCliente(Pedido pedido, int paquetesEntregados) {
        this.pedido = pedido;
        this.paquetesEntregados = paquetesEntregados;
    }

    public VisitaCliente copia() {
        VisitaCliente v = new VisitaCliente(pedido, paquetesEntregados);
        v.ordenEnRuta = ordenEnRuta;
        v.horaLlegadaEstimada = horaLlegadaEstimada;
        v.horaSalidaEstimada = horaSalidaEstimada;
        v.holguraHoras = holguraHoras;
        v.congelada = congelada;
        return v;
    }

    public Pedido getPedido() {
        return pedido;
    }

    public String getIdPedido() {
        return pedido.getIdPedido();
    }

    public NodoRed getNodoCliente() {
        return pedido.getNodoDestino();
    }

    public int getPaquetesEntregados() {
        return paquetesEntregados;
    }

    public int getOrdenEnRuta() {
        return ordenEnRuta;
    }

    public void setOrdenEnRuta(int ordenEnRuta) {
        this.ordenEnRuta = ordenEnRuta;
    }

    public LocalDateTime getHoraLlegadaEstimada() {
        return horaLlegadaEstimada;
    }

    public void setHoraLlegadaEstimada(LocalDateTime horaLlegadaEstimada) {
        this.horaLlegadaEstimada = horaLlegadaEstimada;
    }

    public LocalDateTime getHoraSalidaEstimada() {
        return horaSalidaEstimada;
    }

    public void setHoraSalidaEstimada(LocalDateTime horaSalidaEstimada) {
        this.horaSalidaEstimada = horaSalidaEstimada;
    }

    public double getHolguraHoras() {
        return holguraHoras;
    }

    public void setHolguraHoras(double holguraHoras) {
        this.holguraHoras = holguraHoras;
    }

    public boolean isCongelada() {
        return congelada;
    }

    public void setCongelada(boolean congelada) {
        this.congelada = congelada;
    }

    @Override
    public String toString() {
        return getIdPedido() + "@" + horaLlegadaEstimada;
    }
}
