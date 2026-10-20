package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Almacen de PaqRap sobre la reticula oficial. El central, en (27,14), tiene
 * inventario infinito; los intermedios Nor-Oeste (12,38) y Este (57,27) tienen
 * 1 000 unidades y se recargan de forma instantanea a las 23:59:59.
 *
 * <p>v03, averias: una unidad con averia tipo 2 o 3 que sigue en el lugar se
 * entrega al planificador como un deposito temporal de tipo TRASVASE, con saldo
 * igual a sus paquetes a bordo. Solo se puede usar hasta {@code disponibleHasta}
 * (cuando la trasladan al almacen central) y cada recogida toma
 * {@code segundosAtencion} (30 min de trasvase). En los almacenes del caso ambos
 * valores quedan por defecto (sin cierre y carga instantanea), por lo que el
 * planificador y el banco del IEN se comportan igual que en la v02.</p>
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
    private LocalDateTime disponibleHasta;      // v03: null = sin hora de cierre
    private long segundosAtencion;              // v03: 0 = carga instantanea

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

    /** v03: ultimo instante en que puede terminar una recogida en este punto (null: sin limite). */
    public LocalDateTime getDisponibleHasta() {
        return disponibleHasta;
    }

    public void setDisponibleHasta(LocalDateTime disponibleHasta) {
        this.disponibleHasta = disponibleHasta;
    }

    /** v03: tiempo que toma cargar en este punto (30 min en un trasvase); 0 en los almacenes del caso. */
    public long getSegundosAtencion() {
        return segundosAtencion;
    }

    public void setSegundosAtencion(long segundosAtencion) {
        this.segundosAtencion = Math.max(0L, segundosAtencion);
    }

    @Override
    public String toString() {
        return id;
    }
}
