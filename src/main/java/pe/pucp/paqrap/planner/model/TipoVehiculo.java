package pe.pucp.paqrap.planner.model;

/** Flota heterogenea de PaqRap: capacidad en productos, velocidad y costo por kilometro. */
public enum TipoVehiculo {
    AUTO(24, 40.0, 8.0),
    MOTO(8, 25.0, 6.0),
    BICI(4, 12.0, 3.0);

    private final int capacidad;
    private final double velocidadKmH;
    private final double costoPorKm;

    TipoVehiculo(int capacidad, double velocidadKmH, double costoPorKm) {
        this.capacidad = capacidad;
        this.velocidadKmH = velocidadKmH;
        this.costoPorKm = costoPorKm;
    }

    public int getCapacidad() {
        return capacidad;
    }

    public double getVelocidadKmH() {
        return velocidadKmH;
    }

    public double getCostoPorKm() {
        return costoPorKm;
    }
}
