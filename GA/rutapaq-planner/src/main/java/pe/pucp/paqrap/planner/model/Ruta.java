package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Ruta de reparto: una unidad de transporte parte de un almacen de origen y
 * ejecuta una secuencia ordenada de visitas a clientes.
 */
public final class Ruta {

    private String idRuta;
    private final Vehiculo vehiculo;
    private final Almacen almacenOrigen;
    private final List<VisitaCliente> listaParadas = new ArrayList<>();
    private LocalDateTime fechaHoraInicio;
    private LocalDateTime fechaHoraFin;
    private double distanciaTotalKm;
    private double costoTotalRuta;
    private double tiempoTotalHoras;
    private LocalDateTime inicioRefrigerio;
    private boolean factible = true;

    public Ruta(String idRuta, Vehiculo vehiculo, Almacen almacenOrigen, LocalDateTime fechaHoraInicio) {
        this.idRuta = idRuta;
        this.vehiculo = vehiculo;
        this.almacenOrigen = almacenOrigen;
        this.fechaHoraInicio = fechaHoraInicio;
        this.fechaHoraFin = fechaHoraInicio;
    }

    public Ruta copia() {
        Ruta c = new Ruta(idRuta, vehiculo, almacenOrigen, fechaHoraInicio);
        for (VisitaCliente v : listaParadas) {
            c.listaParadas.add(v.copia());
        }
        c.fechaHoraFin = fechaHoraFin;
        c.distanciaTotalKm = distanciaTotalKm;
        c.costoTotalRuta = costoTotalRuta;
        c.tiempoTotalHoras = tiempoTotalHoras;
        c.inicioRefrigerio = inicioRefrigerio;
        c.factible = factible;
        return c;
    }

    public void insertar(int posicion, VisitaCliente visita) {
        listaParadas.add(posicion, visita);
        reindexar();
    }

    public VisitaCliente removerEn(int posicion) {
        VisitaCliente v = listaParadas.remove(posicion);
        reindexar();
        return v;
    }

    public boolean remover(VisitaCliente visita) {
        boolean removida = listaParadas.remove(visita);
        if (removida) {
            reindexar();
        }
        return removida;
    }

    public void reindexar() {
        for (int i = 0; i < listaParadas.size(); i++) {
            listaParadas.get(i).setOrdenEnRuta(i);
        }
    }

    /** Suma de paquetes transportados por la unidad en esta ruta. */
    public int getCarga() {
        int carga = 0;
        for (VisitaCliente v : listaParadas) {
            carga += v.getPaquetesEntregados();
        }
        return carga;
    }

    /** Primera posicion insertable: ninguna insercion puede preceder a una visita congelada. */
    public int primeraPosicionLibre() {
        int pos = 0;
        for (VisitaCliente v : listaParadas) {
            if (v.isCongelada()) {
                pos = v.getOrdenEnRuta() + 1;
            }
        }
        return pos;
    }

    public List<VisitaCliente> visitasNoCongeladas() {
        List<VisitaCliente> libres = new ArrayList<>();
        for (VisitaCliente v : listaParadas) {
            if (!v.isCongelada()) {
                libres.add(v);
            }
        }
        return libres;
    }

    public boolean tieneVisitasNoCongeladas() {
        for (VisitaCliente v : listaParadas) {
            if (!v.isCongelada()) {
                return true;
            }
        }
        return false;
    }

    public String getIdRuta() {
        return idRuta;
    }

    /** El identificador definitivo se asigna cuando la ruta se incorpora al plan. */
    public void setIdRuta(String idRuta) {
        this.idRuta = idRuta;
    }

    public Vehiculo getVehiculo() {
        return vehiculo;
    }

    public Almacen getAlmacenOrigen() {
        return almacenOrigen;
    }

    public List<VisitaCliente> getListaParadas() {
        return listaParadas;
    }

    public int getTamano() {
        return listaParadas.size();
    }

    public LocalDateTime getFechaHoraInicio() {
        return fechaHoraInicio;
    }

    public void setFechaHoraInicio(LocalDateTime fechaHoraInicio) {
        this.fechaHoraInicio = fechaHoraInicio;
    }

    public LocalDateTime getFechaHoraFin() {
        return fechaHoraFin;
    }

    public void setFechaHoraFin(LocalDateTime fechaHoraFin) {
        this.fechaHoraFin = fechaHoraFin;
    }

    public double getDistanciaTotalKm() {
        return distanciaTotalKm;
    }

    public void setDistanciaTotalKm(double distanciaTotalKm) {
        this.distanciaTotalKm = distanciaTotalKm;
    }

    public double getCostoTotalRuta() {
        return costoTotalRuta;
    }

    public void setCostoTotalRuta(double costoTotalRuta) {
        this.costoTotalRuta = costoTotalRuta;
    }

    public double getTiempoTotalHoras() {
        return tiempoTotalHoras;
    }

    public void setTiempoTotalHoras(double tiempoTotalHoras) {
        this.tiempoTotalHoras = tiempoTotalHoras;
    }

    public LocalDateTime getInicioRefrigerio() {
        return inicioRefrigerio;
    }

    public void setInicioRefrigerio(LocalDateTime inicioRefrigerio) {
        this.inicioRefrigerio = inicioRefrigerio;
    }

    public boolean isFactible() {
        return factible;
    }

    public void setFactible(boolean factible) {
        this.factible = factible;
    }

    @Override
    public String toString() {
        return idRuta + " " + vehiculo + " " + almacenOrigen + " paradas=" + listaParadas.size();
    }
}
