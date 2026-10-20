package pe.pucp.paqrap.planner.model;

public enum TipoAlmacen {
    CENTRAL,
    INTERMEDIO,
    /**
     * v03: unidad con averia tipo 2 o 3 que aun permanece en el lugar. Funciona como un deposito
     * temporal: otra unidad puede recoger sus paquetes (trasvase) mientras termine antes de que la
     * trasladen al almacen central. Tiene saldo limitado, hora de cierre y tiempo de atencion.
     */
    TRASVASE
}
