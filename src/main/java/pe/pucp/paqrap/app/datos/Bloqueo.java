package pe.pucp.paqrap.app.datos;

import pe.pucp.paqrap.app.modelo.Nodo;

import java.time.LocalDateTime;
import java.util.List;

/** Calle bloqueada durante un intervalo: todos los nodos de la poligonal quedan bloqueados. */
public record Bloqueo(LocalDateTime inicio, LocalDateTime fin, List<Nodo> nodos) {
}
