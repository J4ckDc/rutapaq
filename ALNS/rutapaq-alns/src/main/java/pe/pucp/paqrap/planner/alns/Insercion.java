package pe.pucp.paqrap.planner.alns;

import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.Ruta;

/**
 * Candidata de insercion de un pedido en una posicion de una ruta.
 * deltaCosto es el diferencial de costo en soles: (d(i,p) + d(p,j) - d(i,j))
 * multiplicado por el costo por kilometro de la unidad que ejecuta la ruta.
 */
public record Insercion(Ruta ruta, Pedido pedido, int posicion, double deltaCosto, boolean rutaNueva) {
}
