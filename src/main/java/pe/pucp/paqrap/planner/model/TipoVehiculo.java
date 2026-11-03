package pe.pucp.paqrap.planner.model;

import pe.pucp.paqrap.planner.core.Configuracion;

import java.util.Locale;

/**
 * Flota heterogenea de PaqRap: capacidad en productos, velocidad y costo por kilometro.
 *
 * <p>v0.5: es la <b>unica fuente</b> de estos valores para todo el sistema (planificador, simulacion y
 * visualizador; app.modelo.TipoUnidad los lee de aqui). Se configuran sin recompilar con las claves
 * {@code flota.auto.capacidad}, {@code flota.auto.velocidadKmh}, {@code flota.auto.costoKm} (y lo mismo para
 * {@code moto} y {@code bici}). Los valores por defecto son los del enunciado. Los campos son volatiles para
 * que un cambio en caliente (P&amp;R preguntas 6, 15 y 16, pendiente) sea visible en todos los hilos.</p>
 */
public enum TipoVehiculo {
    AUTO(24, 40.0, 8.0),
    MOTO(8, 25.0, 6.0),
    BICI(4, 12.0, 3.0);

    private volatile int capacidad;
    private volatile double velocidadKmH;
    private volatile double costoPorKm;

    TipoVehiculo(int capacidad, double velocidadKmH, double costoPorKm) {
        this.capacidad = capacidad;
        this.velocidadKmH = velocidadKmH;
        this.costoPorKm = costoPorKm;
    }

    /** Clave de configuracion de este tipo: flota.auto, flota.moto o flota.bici. */
    public String clave() {
        return "flota." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * Lee capacidad, velocidad y costo de cada tipo desde la configuracion; si una clave no esta, se conserva el
     * valor vigente. Lanza IllegalArgumentException si un valor no es positivo.
     */
    public static synchronized void configurar(Configuracion cfg) {
        for (TipoVehiculo t : values()) {
            int cap = cfg.entero(t.clave() + ".capacidad", t.capacidad);
            double vel = cfg.numero(t.clave() + ".velocidadKmh", t.velocidadKmH);
            double costo = cfg.numero(t.clave() + ".costoKm", t.costoPorKm);
            if (cap <= 0 || vel <= 0 || costo < 0) {
                throw new IllegalArgumentException("Valores invalidos para " + t.clave()
                        + ": capacidad y velocidad deben ser positivas y el costo no negativo");
            }
            t.capacidad = cap;
            t.velocidadKmH = vel;
            t.costoPorKm = costo;
        }
    }

    /** v0.5: cambio de velocidad en caliente (pantalla Configuracion); la simulacion lo aplica al iniciar un ciclo. */
    public void setVelocidadKmH(double velocidad) {
        if (velocidad <= 0) throw new IllegalArgumentException("La velocidad debe ser positiva");
        this.velocidadKmH = velocidad;
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
