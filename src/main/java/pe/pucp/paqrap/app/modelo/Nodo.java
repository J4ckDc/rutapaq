package pe.pucp.paqrap.app.modelo;

/** Nodo de la retícula (coordenadas en km, enteras). */
public record Nodo(int x, int y) {
    public int manhattan(Nodo o) {
        return Math.abs(x - o.x) + Math.abs(y - o.y);
    }
}
