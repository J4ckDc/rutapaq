package pe.pucp.paqrap.planner.core;

/**
 * Reticula oficial de la ciudad: 70 km en el eje X por 50 km en el eje Y, con
 * nodos cada kilometro (71 x 51 = 3 621 nodos), calles de doble sentido y sin
 * diagonales. Cada arista mide 1 km, por lo que sin bloqueos la distancia entre
 * dos nodos coincide con la distancia Manhattan.
 *
 * <p>Los bloqueos de la municipalidad son <b>de nodo</b>: un nodo bloqueado no
 * puede atravesarse ni usarse para girar. Un cliente ubicado sobre un nodo
 * bloqueado sigue siendo alcanzable: la unidad llega, entrega y regresa por la
 * misma calle. Esto se modela en el recorrido en anchura permitiendo entrar a un
 * nodo bloqueado (destino) y salir del nodo de partida, pero nunca atravesarlo.</p>
 */
public final class MapaReticula {

    private final int ancho;
    private final int alto;
    private final boolean[] bloqueado;
    private int version;

    public MapaReticula(int anchoKm, int altoKm) {
        this.ancho = anchoKm + 1;
        this.alto = altoKm + 1;
        this.bloqueado = new boolean[ancho * alto];
    }

    public int getAncho() {
        return ancho;
    }

    public int getAlto() {
        return alto;
    }

    public int getNumNodos() {
        return ancho * alto;
    }

    public int indice(int x, int y) {
        if (x < 0 || y < 0 || x >= ancho || y >= alto) {
            throw new IllegalArgumentException("Nodo fuera de la reticula: (" + x + "," + y + ")");
        }
        return y * ancho + x;
    }

    public int x(int nodo) {
        return nodo % ancho;
    }

    public int y(int nodo) {
        return nodo / ancho;
    }

    public boolean dentro(int x, int y) {
        return x >= 0 && y >= 0 && x < ancho && y < alto;
    }

    public boolean estaBloqueado(int nodo) {
        return bloqueado[nodo];
    }

    public int getVersion() {
        return version;
    }

    public void limpiarBloqueos() {
        java.util.Arrays.fill(bloqueado, false);
        version++;
    }

    public void bloquear(int nodo) {
        if (!bloqueado[nodo]) {
            bloqueado[nodo] = true;
            version++;
        }
    }

    public void desbloquear(int nodo) {
        if (bloqueado[nodo]) {
            bloqueado[nodo] = false;
            version++;
        }
    }

    public int nodosBloqueados() {
        int n = 0;
        for (boolean b : bloqueado) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    /**
     * Distancias en kilometros desde un nodo a todos los demas (-1 si no hay
     * camino). Recorrido en anchura sobre la reticula: no se expande desde un
     * nodo bloqueado distinto del origen, de modo que los bloqueos no se
     * atraviesan pero los clientes sobre ellos siguen siendo alcanzables.
     */
    public int[] distanciasDesde(int origen) {
        int n = ancho * alto;
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, -1);
        int[] cola = new int[n];
        int cabeza = 0;
        int fin = 0;
        dist[origen] = 0;
        cola[fin++] = origen;
        while (cabeza < fin) {
            int u = cola[cabeza++];
            if (bloqueado[u] && u != origen) {
                continue;                       // se puede llegar, no atravesar
            }
            int ux = u % ancho;
            int uy = u / ancho;
            int du = dist[u] + 1;
            if (ux + 1 < ancho) {
                int v = u + 1;
                if (dist[v] < 0) {
                    dist[v] = du;
                    cola[fin++] = v;
                }
            }
            if (ux > 0) {
                int v = u - 1;
                if (dist[v] < 0) {
                    dist[v] = du;
                    cola[fin++] = v;
                }
            }
            if (uy + 1 < alto) {
                int v = u + ancho;
                if (dist[v] < 0) {
                    dist[v] = du;
                    cola[fin++] = v;
                }
            }
            if (uy > 0) {
                int v = u - ancho;
                if (dist[v] < 0) {
                    dist[v] = du;
                    cola[fin++] = v;
                }
            }
        }
        return dist;
    }

    /** Distancia puntual entre dos nodos (recorrido en anchura completo). */
    public int distancia(int origen, int destino) {
        return distanciasDesde(origen)[destino];
    }

    public static int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }
}
