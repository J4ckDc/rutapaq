package pe.pucp.paqrap.app.modelo;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;

/** Unidad de transporte y su estado de ejecución. */
public final class Unidad {
    public final String id;
    public final TipoUnidad tipo;

    public Nodo nodo;                                      // último nodo alcanzado
    public final Deque<Nodo> camino = new ArrayDeque<>();  // nodos que faltan hasta la próxima parada
    public double progreso;                                // fracción del tramo hacia camino.peekFirst()
    public final Deque<Parada> paradas = new ArrayDeque<>();
    public int carga;
    public double ocupadoHasta;                            // fin de una entrega o del refrigerio
    public String ocupacion = "";
    public LocalDateTime turnoConRefrigerio;               // inicio del turno en que ya tomó refrigerio
    public double km;

    public Unidad(String id, TipoUnidad tipo, Nodo inicio) {
        this.id = id;
        this.tipo = tipo;
        this.nodo = inicio;
    }

    public double x() {
        Nodo s = camino.peekFirst();
        return s == null ? nodo.x() : nodo.x() + (s.x() - nodo.x()) * progreso;
    }

    public double y() {
        Nodo s = camino.peekFirst();
        return s == null ? nodo.y() : nodo.y() + (s.y() - nodo.y()) * progreso;
    }
}
