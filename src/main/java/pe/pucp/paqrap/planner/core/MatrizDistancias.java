package pe.pucp.paqrap.planner.core;

import java.util.Arrays;

/**
 * Matriz compacta de distancias entre los nodos de interes de una ejecucion
 * (almacenes, posiciones de las unidades y destinos de los pedidos pendientes),
 * calculada con un recorrido en anchura por nodo sobre la reticula con los
 * bloqueos vigentes. Es inmutable, por lo que puede consultarse en paralelo.
 */
public final class MatrizDistancias {

    private final int[] nodos;
    private final int[][] km;

    private MatrizDistancias(int[] nodos, int[][] km) {
        this.nodos = nodos;
        this.km = km;
    }

    /** Construye la matriz para los nodos indicados (ya sin repetidos). */
    public static MatrizDistancias construir(MapaReticula mapa, int[] nodosInteres) {
        int m = nodosInteres.length;
        int[][] km = new int[m][m];
        for (int i = 0; i < m; i++) {
            int[] d = mapa.distanciasDesde(nodosInteres[i]);
            for (int j = 0; j < m; j++) {
                km[i][j] = d[nodosInteres[j]];
            }
        }
        return new MatrizDistancias(Arrays.copyOf(nodosInteres, m), km);
    }

    public int tamano() {
        return nodos.length;
    }

    public int nodoDe(int indiceLocal) {
        return nodos[indiceLocal];
    }

    /** Distancia en kilometros entre dos indices locales; -1 si no hay camino vigente. */
    public int km(int i, int j) {
        return km[i][j];
    }

    public boolean alcanzable(int i, int j) {
        return km[i][j] >= 0;
    }
}
