package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;

/**
 * Parada del itinerario expandido de una ruta. Las paradas de RECARGA y de
 * REFRIGERIO las inserta el evaluador; las de ENTREGA corresponden a los
 * pedidos planificados.
 */
public record ParadaRuta(TipoParada tipo,
                         int nodo,
                         String referencia,
                         int cantidad,
                         LocalDateTime llegada,
                         LocalDateTime salida,
                         double holguraHoras) {
}
