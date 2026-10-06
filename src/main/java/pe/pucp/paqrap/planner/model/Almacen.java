package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Almacen de PaqRap sobre la reticula oficial. El central, en (27,14), tiene
 * inventario infinito; los intermedios Nor-Oeste (12,38) y Este (57,27) tienen
 * 1 000 unidades y se recargan de forma instantanea a las 23:59:59.
 */
public final class Almacen {

    public static final LocalTime HORA_RECARGA = LocalTime.of(23, 59, 59);

    private final String id;
    private final TipoAlmacen tipo;
    private final int nodo;
    private final int x;
    private final int y;
    private final int capacidadMaxima;
    private final boolean inventarioInfinito;
    private int stock;

    public Almacen(String id, TipoAlmacen tipo, int nodo, int x, int y, int capacidadMaxima) {
        this.id = id;
        this.tipo = tipo;
        this.nodo = nodo;
        this.x = x;
        this.y = y;
        this.inventarioInfinito = tipo == TipoAlmacen.CENTRAL;
        this.capacidadMaxima = inventarioInfinito ? Integer.MAX_VALUE : capacidadMaxima;
        this.stock = this.capacidadMaxima;
    }

    /** Recarga instantanea de las 23:59:59: el saldo vuelve a la capacidad maxima. */
    public void recargar() {
        stock = capacidadMaxima;
    }

    public boolean retirar(int cantidad) {
        if (inventarioInfinito) {
            return true;
        }
        if (cantidad > stock) {
            return false;
        }
        stock -= cantidad;
        return true;
    }

    public String getId() {
        return id;
    }

    public TipoAlmacen getTipo() {
        return tipo;
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

    public int getCapacidadMaxima() {
        return capacidadMaxima;
    }

    public boolean isInventarioInfinito() {
        return inventarioInfinito;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    @Override
    public String toString() {
        return id;
    }
}
