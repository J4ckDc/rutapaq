package pe.pucp.paqrap.planner.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import pe.pucp.paqrap.planner.model.Arco;
import pe.pucp.paqrap.planner.model.NodoRed;

/**
 * Grafo no dirigido de la ciudad. Las calles son de doble sentido, por lo que la
 * matriz de distancias es simetrica y un bloqueo invalida el arco en ambos
 * sentidos. Las distancias entre pares de nodos se obtienen como camino minimo
 * (Dijkstra) sobre los arcos vigentes, con longitudes calculadas por Haversine.
 */
public final class RedVial {

    private static final double RADIO_TIERRA_KM = 6371.0088;

    private final Map<String, NodoRed> nodos = new LinkedHashMap<>();
    private final Map<String, Map<String, Double>> adyacencia = new HashMap<>();
    private final Set<Arco> arcosBloqueados = new HashSet<>();
    private final Map<String, Integer> indice = new HashMap<>();
    private double[][] distancias;
    private boolean matrizVigente;

    public void agregarNodo(NodoRed nodo) {
        nodos.put(nodo.getIdNodo(), nodo);
        adyacencia.computeIfAbsent(nodo.getIdNodo(), k -> new HashMap<>());
        matrizVigente = false;
    }

    /** Agrega una calle de doble sentido con longitud calculada por Haversine. */
    public void agregarCalle(String idA, String idB) {
        NodoRed a = nodos.get(idA);
        NodoRed b = nodos.get(idB);
        if (a == null || b == null || idA.equals(idB)) {
            return;
        }
        double km = haversineKm(a, b);
        adyacencia.get(idA).merge(idB, km, Math::min);
        adyacencia.get(idB).merge(idA, km, Math::min);
        matrizVigente = false;
    }

    /** Conecta un deposito temporal a la posicion de la unidad averiada (0 km). */
    public void agregarNodoEnPosicionDe(NodoRed nuevo, String idNodoBase) {
        agregarNodo(nuevo);
        adyacencia.get(nuevo.getIdNodo()).put(idNodoBase, 0.0);
        adyacencia.get(idNodoBase).put(nuevo.getIdNodo(), 0.0);
        matrizVigente = false;
    }

    public void bloquear(Arco arco) {
        if (arcosBloqueados.add(arco)) {
            matrizVigente = false;
        }
    }

    public void desbloquear(Arco arco) {
        if (arcosBloqueados.remove(arco)) {
            matrizVigente = false;
        }
    }

    public boolean estaBloqueado(String idA, String idB) {
        return arcosBloqueados.contains(Arco.de(idA, idB));
    }

    public Set<Arco> getArcosBloqueados() {
        return arcosBloqueados;
    }

    public NodoRed nodo(String id) {
        return nodos.get(id);
    }

    public Collection<NodoRed> getNodos() {
        return nodos.values();
    }

    public List<Arco> getCalles() {
        List<Arco> calles = new ArrayList<>();
        Set<Arco> vistos = new HashSet<>();
        for (Map.Entry<String, Map<String, Double>> e : adyacencia.entrySet()) {
            for (String vecino : e.getValue().keySet()) {
                Arco a = Arco.de(e.getKey(), vecino);
                if (vistos.add(a)) {
                    calles.add(a);
                }
            }
        }
        return calles;
    }

    public double distanciaKm(NodoRed origen, NodoRed destino) {
        return distanciaKm(origen.getIdNodo(), destino.getIdNodo());
    }

    /** Distancia de camino minimo en km; Double.POSITIVE_INFINITY si no hay camino vigente. */
    public double distanciaKm(String idOrigen, String idDestino) {
        if (idOrigen.equals(idDestino)) {
            return 0.0;
        }
        asegurarMatriz();
        Integer i = indice.get(idOrigen);
        Integer j = indice.get(idDestino);
        if (i == null || j == null) {
            return Double.POSITIVE_INFINITY;
        }
        return distancias[i][j];
    }

    public boolean hayCamino(String idOrigen, String idDestino) {
        return !Double.isInfinite(distanciaKm(idOrigen, idDestino));
    }

    /** Invalida la matriz para forzar el recalculo de caminos minimos bajo demanda. */
    public void invalidarCaminos() {
        matrizVigente = false;
    }

    private void asegurarMatriz() {
        if (matrizVigente) {
            return;
        }
        List<String> ids = new ArrayList<>(nodos.keySet());
        indice.clear();
        for (int i = 0; i < ids.size(); i++) {
            indice.put(ids.get(i), i);
        }
        int n = ids.size();
        distancias = new double[n][n];
        for (int i = 0; i < n; i++) {
            dijkstra(ids, i);
        }
        matrizVigente = true;
    }

    private void dijkstra(List<String> ids, int origen) {
        int n = ids.size();
        double[] dist = distancias[origen];
        boolean[] visitado = new boolean[n];
        for (int i = 0; i < n; i++) {
            dist[i] = Double.POSITIVE_INFINITY;
        }
        dist[origen] = 0.0;
        PriorityQueue<int[]> cola = new PriorityQueue<>((x, y) -> Double.compare(dist[x[0]], dist[y[0]]));
        cola.add(new int[]{origen});
        while (!cola.isEmpty()) {
            int u = cola.poll()[0];
            if (visitado[u]) {
                continue;
            }
            visitado[u] = true;
            String idU = ids.get(u);
            for (Map.Entry<String, Double> arista : adyacencia.getOrDefault(idU, Map.of()).entrySet()) {
                String idV = arista.getKey();
                if (arcosBloqueados.contains(Arco.de(idU, idV))) {
                    continue;
                }
                NodoRed v = nodos.get(idV);
                if (v == null || !v.isAccesible()) {
                    continue;
                }
                Integer iv = indice.get(idV);
                if (iv == null) {
                    continue;
                }
                double nueva = dist[u] + arista.getValue();
                if (nueva < dist[iv]) {
                    dist[iv] = nueva;
                    cola.add(new int[]{iv});
                }
            }
        }
    }

    public static double haversineKm(NodoRed a, NodoRed b) {
        double dLat = Math.toRadians(b.getLatitud() - a.getLatitud());
        double dLon = Math.toRadians(b.getLongitud() - a.getLongitud());
        double lat1 = Math.toRadians(a.getLatitud());
        double lat2 = Math.toRadians(b.getLatitud());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2);
        return 2 * RADIO_TIERRA_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
