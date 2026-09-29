package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;

/**
 * Unidad de transporte con su estado vigente: en que nodo esta (o estara al
 * concluir el tramo comprometido), desde cuando queda disponible, cuanta carga
 * lleva a bordo y en que turno opera.
 */
public final class Vehiculo {

    private final String id;
    private final TipoVehiculo tipo;
    private int nodoActual;
    private int carga;
    private LocalDateTime disponibleDesde;
    private TurnoConductor turno;

    public Vehiculo(String id, TipoVehiculo tipo, int nodoInicial, LocalDateTime disponibleDesde) {
        this.id = id;
        this.tipo = tipo;
        this.nodoActual = nodoInicial;
        this.disponibleDesde = disponibleDesde;
    }

    public String getId() {
        return id;
    }

    public TipoVehiculo getTipo() {
        return tipo;
    }

    public int getCapacidad() {
        return tipo.getCapacidad();
    }

    public double getVelocidadKmH() {
        return tipo.getVelocidadKmH();
    }

    public double getCostoPorKm() {
        return tipo.getCostoPorKm();
    }

    public int getNodoActual() {
        return nodoActual;
    }

    public void setNodoActual(int nodoActual) {
        this.nodoActual = nodoActual;
    }

    public int getCarga() {
        return carga;
    }

    public void setCarga(int carga) {
        this.carga = carga;
    }

    public LocalDateTime getDisponibleDesde() {
        return disponibleDesde;
    }

    public void setDisponibleDesde(LocalDateTime disponibleDesde) {
        this.disponibleDesde = disponibleDesde;
    }

    public TurnoConductor getTurno() {
        return turno;
    }

    public void setTurno(TurnoConductor turno) {
        this.turno = turno;
    }

    @Override
    public String toString() {
        return id + "(" + tipo + ")";
    }
}
