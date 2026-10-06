package pe.pucp.paqrap.app.modelo;

/**
 * Parada del itinerario que ejecuta una unidad: entregar, recargar o tomar el refrigerio.
 * Las paradas salen del plan de rutapaq-planner (ALNS o GA).
 */
public record Parada(Tipo tipo, Nodo nodo, String pedidoId, String almacenId, int cantidad) {
    public enum Tipo { ENTREGA, RECARGA, REFRIGERIO }

    public static Parada entrega(Nodo nodo, String pedidoId, int cantidad) {
        return new Parada(Tipo.ENTREGA, nodo, pedidoId, null, cantidad);
    }

    public static Parada recarga(Nodo nodo, String almacenId, int cantidad) {
        return new Parada(Tipo.RECARGA, nodo, null, almacenId, cantidad);
    }

    public static Parada refrigerio(Nodo nodo) {
        return new Parada(Tipo.REFRIGERIO, nodo, null, null, 0);
    }
}
