package pe.pucp.paqrap.app.datos;

import pe.pucp.paqrap.app.modelo.Nodo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Calle bloqueada durante un intervalo: todos los nodos de la poligonal quedan bloqueados.
 * vertices: los puntos tal como vienen en el archivo (para dibujar el bloqueo en el mapa).
 * nodos:    todos los nodos de la poligonal, extremos incluidos (los que se bloquean en la retícula).
 */
public record Bloqueo(LocalDateTime inicio, LocalDateTime fin, List<Nodo> vertices, List<Nodo> nodos) {
}
