package pe.pucp.paqrap.planner.model;

/** Flota heterogenea de PaqRap: capacidad, velocidad promedio y costo por kilometro. */
public enum TipoVehiculo {
    AUTO(24, 40.0, 8.0),
    MOTO(8, 25.0, 6.0),
    BICI(4, 12.0, 3.0);

    private final int capacidadMaxima;
    private final double velocidadPromedioKmH;
    private final double costoPorKm;

    TipoVehiculo(int capacidadMaxima, double velocidadPromedioKmH, double costoPorKm) {
        this.capacidadMaxima = capacidadMaxima;
        this.velocidadPromedioKmH = velocidadPromedioKmH;
        this.costoPorKm = costoPorKm;
    }

    public int getCapacidadMaxima() {
        return capacidadMaxima;
    }

    public double getVelocidadPromedioKmH() {
        return velocidadPromedioKmH;
    }

    public double getCostoPorKm() {
        return costoPorKm;
    }
}
