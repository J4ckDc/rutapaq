package pe.pucp.paqrap.app.modelo;

/**
 * Pedido de un cliente. Los tiempos son segundos simulados desde el inicio de la simulación.
 * Como en rutapaq-planner, el plazo se cumple si la unidad LLEGA antes del límite:
 * la hora de acondicionamiento de la entrega no cuenta dentro del plazo.
 */
public final class Pedido {
    public final String id;
    public final String cliente;
    public final Nodo nodo;
    public final int cantidad;
    public final int plazoHoras;
    public final double registro;

    public int entregado;           // paquetes ya entregados
    public int asignado;            // paquetes en el itinerario de alguna unidad, aún no entregados
    public double llegadaFinal = -1;   // llegada de la entrega que completó el pedido
    public double liberado = -1;       // fin del acondicionamiento de esa entrega
    public boolean vencido;

    public Pedido(String id, String cliente, Nodo nodo, int cantidad, int plazoHoras, double registro) {
        this.id = id;
        this.cliente = cliente;
        this.nodo = nodo;
        this.cantidad = cantidad;
        this.plazoHoras = plazoHoras;
        this.registro = registro;
    }

    public double limite() {
        return registro + plazoHoras * 3600.0;
    }

    public int porAsignar() {
        return cantidad - entregado - asignado;
    }

    public boolean completo() {
        return entregado >= cantidad;
    }
}
