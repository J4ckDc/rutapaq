package pe.pucp.paqrap.planner.alns;

import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/** Par (ruta, visita) usado por los operadores de destruccion. */
public record VisitaUbicada(Ruta ruta, VisitaCliente visita) {
}
