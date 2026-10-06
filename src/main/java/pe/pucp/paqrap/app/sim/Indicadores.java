package pe.pucp.paqrap.app.sim;

import pe.pucp.paqrap.app.Config;

/**
 * Clasificación en semáforo. Los umbrales se leen de config.properties (RNF d);
 * el visualizador solo pinta el nivel que recibe.
 */
public final class Indicadores {
    public enum Nivel { VERDE, AMBAR, ROJO, NEUTRO }

    private final Config cfg;

    public Indicadores(Config cfg) {
        this.cfg = cfg;
    }

    /** Mayor es mejor: verde si v >= verde; ámbar si v >= ambar. */
    public Nivel mayorEsMejor(String clave, double v, double verde, double ambar) {
        double g = cfg.decimal("semaforo." + clave + ".verde", verde);
        double a = cfg.decimal("semaforo." + clave + ".ambar", ambar);
        return v >= g ? Nivel.VERDE : v >= a ? Nivel.AMBAR : Nivel.ROJO;
    }

    /** Menor es mejor: verde si v <= verde; ámbar si v <= ambar. */
    public Nivel menorEsMejor(String clave, double v, double verde, double ambar) {
        double g = cfg.decimal("semaforo." + clave + ".verde", verde);
        double a = cfg.decimal("semaforo." + clave + ".ambar", ambar);
        return v <= g ? Nivel.VERDE : v <= a ? Nivel.AMBAR : Nivel.ROJO;
    }

    /** Urgencia de un pedido según la fracción de su plazo que aún le queda. */
    public Nivel pedido(double fraccionRestante) {
        return mayorEsMejor("pedido", fraccionRestante, 0.5, 0.2);
    }
}
