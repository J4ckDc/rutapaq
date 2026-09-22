package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Almacen de PaqRap. El almacen CENTRAL posee inventario infinito; los dos
 * almacenes INTERMEDIOS tienen capacidad de 1000 unidades y se recargan de forma
 * instantanea a las 23:59:59 de cada dia.
 */
public final class Almacen {

    public static final LocalTime HORA_RECARGA = LocalTime.of(23, 59, 59);

    private final String idAlmacen;
    private final TipoAlmacen tipoAlmacen;
    private final NodoRed nodo;
    private final int capacidadMaxima;
    private final boolean esInventarioInfinito;
    private int capacidadActual;
    private LocalDateTime horaUltimaRecarga;
    private LocalDateTime horaProximaRecarga;

    public Almacen(String idAlmacen, TipoAlmacen tipoAlmacen, NodoRed nodo,
                   int capacidadMaxima, int capacidadActual, LocalDateTime instante) {
        this.idAlmacen = idAlmacen;
        this.tipoAlmacen = tipoAlmacen;
        this.nodo = nodo;
        this.esInventarioInfinito = tipoAlmacen == TipoAlmacen.CENTRAL;
        this.capacidadMaxima = esInventarioInfinito ? Integer.MAX_VALUE : capacidadMaxima;
        this.capacidadActual = esInventarioInfinito ? Integer.MAX_VALUE : capacidadActual;
        this.horaUltimaRecarga = instante.toLocalDate().minusDays(1).atTime(HORA_RECARGA);
        this.horaProximaRecarga = proximaRecarga(instante);
    }

    public static LocalDateTime proximaRecarga(LocalDateTime instante) {
        LocalDateTime candidata = instante.toLocalDate().atTime(HORA_RECARGA);
        return candidata.isBefore(instante) ? candidata.plusDays(1) : candidata;
    }

    /** Recarga instantanea: restituye la capacidad actual al valor maximo. */
    public void recargar(LocalDateTime instante) {
        if (!esInventarioInfinito) {
            this.capacidadActual = capacidadMaxima;
        }
        this.horaUltimaRecarga = instante;
        this.horaProximaRecarga = proximaRecarga(instante.plusSeconds(1));
    }

    public String getIdAlmacen() {
        return idAlmacen;
    }

    public TipoAlmacen getTipoAlmacen() {
        return tipoAlmacen;
    }

    public NodoRed getNodo() {
        return nodo;
    }

    public int getCapacidadMaxima() {
        return capacidadMaxima;
    }

    public int getCapacidadActual() {
        return capacidadActual;
    }

    public void setCapacidadActual(int capacidadActual) {
        this.capacidadActual = capacidadActual;
    }

    public boolean isEsInventarioInfinito() {
        return esInventarioInfinito;
    }

    public LocalDateTime getHoraUltimaRecarga() {
        return horaUltimaRecarga;
    }

    public LocalDateTime getHoraProximaRecarga() {
        return horaProximaRecarga;
    }

    @Override
    public String toString() {
        return idAlmacen;
    }
}
