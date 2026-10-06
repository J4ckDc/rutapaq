package pe.pucp.paqrap.app.modelo;

/** Tipos de unidad del enunciado: capacidad (paquetes), velocidad (km/h) y costo (S/ por km). */
public enum TipoUnidad {
    AUTO("A", 24, 40.0, 8.0),
    MOTO("M", 8, 25.0, 6.0),
    BICI("B", 4, 12.0, 3.0);

    public final String prefijo;
    public final int capacidad;
    public final double velocidadKmh;
    public final double costoKm;

    TipoUnidad(String prefijo, int capacidad, double velocidadKmh, double costoKm) {
        this.prefijo = prefijo;
        this.capacidad = capacidad;
        this.velocidadKmh = velocidadKmh;
        this.costoKm = costoKm;
    }

    /** Segundos para recorrer km kilómetros. */
    public double segundos(double km) {
        return km / velocidadKmh * 3600.0;
    }
}
