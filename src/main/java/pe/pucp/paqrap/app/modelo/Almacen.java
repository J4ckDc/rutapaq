package pe.pucp.paqrap.app.modelo;

/** Almacén: el central tiene stock infinito (capacidad -1); los intermedios, 1 000 unidades (ien.capacidadIntermedio). */
public final class Almacen {
    public final String id;
    public final Nodo nodo;
    public final int capacidad;
    public int stock;

    public Almacen(String id, Nodo nodo, int capacidad) {
        this.id = id;
        this.nodo = nodo;
        this.capacidad = capacidad;
        this.stock = capacidad < 0 ? Integer.MAX_VALUE : capacidad;
    }

    public boolean infinito() {
        return capacidad < 0;
    }

    public void recargar() {
        if (!infinito()) stock = capacidad;
    }
}
