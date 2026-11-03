package pe.pucp.paqrap.app.sim;

import pe.pucp.paqrap.app.Config;

/**
 * Clasificación en semáforo. Los umbrales se leen de config.properties (RNF d);
 * el visualizador solo pinta el nivel que recibe.
 *
 * v0.5: se agrega el nivel VACIO (semáforo + vacío de la rúbrica). Regla de colores del equipo: el rojo siempre
 * significa peligro. Para el stock de un almacén, la carga de una unidad y la ocupación de la flota: verde = bastante,
 * ámbar = poco, rojo = muy poco y VACIO = nada.
 */
public final class Indicadores {
    public enum Nivel { VERDE, AMBAR, ROJO, VACIO, NEUTRO }

    private final Config cfg;

    public Indicadores(Config cfg) {
        this.cfg = cfg;
    }

    /** Mayor es mejor: verde si v >= verde; ámbar si v >= ambar. */
    public Nivel mayorEsMejor(String clave, double v, double verde, double ambar) {
        return v >= umbral(clave, "verde", verde) ? Nivel.VERDE : v >= umbral(clave, "ambar", ambar) ? Nivel.AMBAR : Nivel.ROJO;
    }

    /** Menor es mejor: verde si v <= verde; ámbar si v <= ambar. */
    public Nivel menorEsMejor(String clave, double v, double verde, double ambar) {
        return v <= umbral(clave, "verde", verde) ? Nivel.VERDE : v <= umbral(clave, "ambar", ambar) ? Nivel.AMBAR : Nivel.ROJO;
    }

    /** v0.5: nivel de un stock o una carga en % (0 = VACIO; luego verde, ámbar o rojo, mayor es mejor). */
    public Nivel stock(String clave, double pct, double verde, double ambar) {
        return pct <= 0 ? Nivel.VACIO : mayorEsMejor(clave, pct, verde, ambar);
    }

    /** Urgencia de un pedido según la fracción de su plazo que aún le queda. */
    public Nivel pedido(double fraccionRestante) {
        return mayorEsMejor("pedido", fraccionRestante, 0.5, 0.2);
    }

    /** Umbral configurado (semaforo.clave.nivel) o el valor por defecto. */
    public double umbral(String clave, String nivel, double defecto) {
        return cfg.decimal("semaforo." + clave + "." + nivel, defecto);
    }

    // v0.5: valores por defecto, en un solo lugar (los usa también /api/config para las leyendas)
    public static final double PLAZO_VERDE = 95, PLAZO_AMBAR = 85;
    public static final double ALMACEN_VERDE = 30, ALMACEN_AMBAR = 10;
    public static final double CARGA_VERDE = 50, CARGA_AMBAR = 20;
    public static final double FLOTA_VERDE = 50, FLOTA_AMBAR = 20;
    public static final double PEDIDO_VERDE = 0.5, PEDIDO_AMBAR = 0.2;

    /** v0.5: umbrales vigentes en JSON, para que las leyendas del visualizador coincidan con la configuración. */
    public String umbralesJson() {
        return "{\"plazo\":" + par("plazo", PLAZO_VERDE, PLAZO_AMBAR)
                + ",\"almacen\":" + par("almacen", ALMACEN_VERDE, ALMACEN_AMBAR)
                + ",\"carga\":" + par("carga", CARGA_VERDE, CARGA_AMBAR)
                + ",\"flota\":" + par("flota", FLOTA_VERDE, FLOTA_AMBAR)
                + ",\"pedido\":" + par("pedido", PEDIDO_VERDE, PEDIDO_AMBAR) + "}";
    }

    private String par(String clave, double verde, double ambar) {
        return "{\"verde\":" + pe.pucp.paqrap.app.web.Json.num(umbral(clave, "verde", verde))
                + ",\"ambar\":" + pe.pucp.paqrap.app.web.Json.num(umbral(clave, "ambar", ambar)) + "}";
    }
}
