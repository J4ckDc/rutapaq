package pe.pucp.paqrap.app.modelo;

import pe.pucp.paqrap.planner.model.TipoVehiculo;

/**
 * Tipos de unidad del enunciado. El prefijo forma el código TTNN definido por el curso (TA01, TM03, TB10).
 *
 * v0.5: capacidad, velocidad y costo ya no están escritos aquí: se leen de TipoVehiculo del planificador, que es
 * la única fuente y se configura con las claves flota.* (planner.properties o config.properties). Así la
 * simulación y el planificador nunca usan valores distintos.
 */
public enum TipoUnidad {
    AUTO("TA"),
    MOTO("TM"),
    BICI("TB");

    public final String prefijo;

    TipoUnidad(String prefijo) {
        this.prefijo = prefijo;
    }

    public TipoVehiculo vehiculo() {
        return TipoVehiculo.valueOf(name());
    }

    /** Capacidad en paquetes. */
    public int capacidad() {
        return vehiculo().getCapacidad();
    }

    public double velocidadKmh() {
        return vehiculo().getVelocidadKmH();
    }

    /** Costo en soles por km recorrido. */
    public double costoKm() {
        return vehiculo().getCostoPorKm();
    }

    /** Segundos para recorrer km kilómetros. */
    public double segundos(double km) {
        return km / velocidadKmh() * 3600.0;
    }

    /** Tipo de una unidad a partir de su código TTNN (TA03 → AUTO); null si el prefijo no corresponde. */
    public static TipoUnidad deCodigo(String codigo) {
        if (codigo == null) return null;
        for (TipoUnidad t : values()) if (codigo.toUpperCase().startsWith(t.prefijo)) return t;
        return null;
    }
}
