package pe.pucp.paqrap.app.sim;

import pe.pucp.paqrap.app.modelo.Nodo;
import pe.pucp.paqrap.planner.core.MapaReticula;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Caminos nodo a nodo sobre la MISMA MapaReticula que usa el planificador, para que las unidades
 * recorran exactamente las distancias con las que se planificó. Usa MapaReticula.distanciasDesde
 * (BFS que no atraviesa bloqueos pero permite llegar a un cliente bloqueado) y guarda en caché
 * cada destino hasta que cambien los bloqueos.
 */
final class Caminos {
    private final MapaReticula mapa;
    private final Map<Integer, int[]> cache = new HashMap<>();
    private int version = -1;

    Caminos(MapaReticula mapa) {
        this.mapa = mapa;
    }

    private int[] campo(int destino) {
        if (mapa.getVersion() != version) {
            cache.clear();
            version = mapa.getVersion();
        }
        return cache.computeIfAbsent(destino, mapa::distanciasDesde);   // calles de doble sentido
    }

    int indice(Nodo n) {
        return mapa.indice(n.x(), n.y());
    }

    Nodo nodo(int indice) {
        return new Nodo(mapa.x(indice), mapa.y(indice));
    }

    /** km entre dos nodos respetando bloqueos; -1 si no hay camino. */
    int distancia(Nodo a, Nodo b) {
        return a.equals(b) ? 0 : campo(indice(b))[indice(a)];
    }

    /** Nodos desde a (excluido) hasta b (incluido); null si no hay camino. */
    List<Nodo> camino(Nodo a, Nodo b) {
        List<Nodo> r = new ArrayList<>();
        if (a.equals(b)) return r;
        int destino = indice(b);
        int[] c = campo(destino);
        int v = indice(a);
        if (c[v] < 0) return null;
        int ancho = mapa.getAncho();
        while (v != destino) {
            int siguiente = -1;
            int vx = v % ancho, vy = v / ancho;
            int[][] vecinos = {{vx + 1, vy}, {vx - 1, vy}, {vx, vy + 1}, {vx, vy - 1}};
            for (int[] w : vecinos) {
                if (!mapa.dentro(w[0], w[1])) continue;
                int iw = mapa.indice(w[0], w[1]);
                if (c[iw] == c[v] - 1 && (iw == destino || !mapa.estaBloqueado(iw))) {
                    siguiente = iw;
                    break;
                }
            }
            if (siguiente < 0) return null;
            v = siguiente;
            r.add(nodo(v));
        }
        return r;
    }

    boolean bloqueado(Nodo n) {
        return mapa.estaBloqueado(indice(n));
    }
}
