package pe.pucp.paqrap.planner.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Ruta de una unidad dentro de una ejecucion del planificador: la secuencia de
 * pedidos que atiende y el itinerario expandido (recargas, refrigerio y
 * entregas) que produce el evaluador. Una unidad puede realizar varios viajes
 * con recarga dentro de su turno.
 */
public final class Ruta {

    private final String id;
    private final Vehiculo vehiculo;
    private final List<Pedido> pedidos = new ArrayList<>();
    private final List<ParadaRuta> itinerario = new ArrayList<>();
    private double distanciaKm;
    private double costo;
    private int viajes;
    private boolean factible = true;

    public Ruta(String id, Vehiculo vehiculo) {
        this.id = id;
        this.vehiculo = vehiculo;
    }

    public String getId() {
        return id;
    }

    public Vehiculo getVehiculo() {
        return vehiculo;
    }

    public List<Pedido> getPedidos() {
        return pedidos;
    }

    public List<ParadaRuta> getItinerario() {
        return itinerario;
    }

    public double getDistanciaKm() {
        return distanciaKm;
    }

    public void setDistanciaKm(double distanciaKm) {
        this.distanciaKm = distanciaKm;
    }

    public double getCosto() {
        return costo;
    }

    public void setCosto(double costo) {
        this.costo = costo;
    }

    public int getViajes() {
        return viajes;
    }

    public void setViajes(int viajes) {
        this.viajes = viajes;
    }

    public boolean isFactible() {
        return factible;
    }

    public void setFactible(boolean factible) {
        this.factible = factible;
    }

    @Override
    public String toString() {
        return id + " " + vehiculo + " entregas=" + pedidos.size() + " viajes=" + viajes;
    }
}
