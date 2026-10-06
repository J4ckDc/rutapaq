package pe.pucp.paqrap.planner.hgs;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import pe.pucp.paqrap.planner.core.DatosPlanificacion;
import pe.pucp.paqrap.planner.core.SolucionRutas;

/**
 * Individuo del HGS: I = (pi, sigma).
 * <ul>
 *   <li>pi: gran ruta (giant tour), permutacion de los pedidos pendientes sin
 *       delimitadores de vehiculo ni de ruta;</li>
 *   <li>sigma: orden en que el procedimiento Split ofrece las unidades
 *       disponibles, lo que garantiza que cada unidad salga a lo sumo una vez.</li>
 * </ul>
 * Las rutas, el costo y la aptitud son atributos derivados de la decodificacion.
 * El almacen de recarga ya no forma parte del cromosoma: lo decide el evaluador
 * comun eligiendo el almacen mas cercano con saldo suficiente, de modo que ambos
 * algoritmos usen la misma politica de recarga.
 */
final class Individuo {

    int[] pi;
    int[] sigma;
    SolucionRutas solucion;
    double fitness;
    double objetivo;
    boolean factible;
    double aptitudSesgada;
    int[] sucesor;
    int[] predecesor;
    final Map<Individuo, Double> distancias = new IdentityHashMap<>();

    Individuo(int[] pi, int[] sigma) {
        this.pi = pi;
        this.sigma = sigma;
    }

    void evaluar(DatosPlanificacion d) {
        fitness = solucion.costoTotal();
        objetivo = solucion.costoRutas() + solucion.penalizador().getNoAtendido() * muNoAsignados(d);
        factible = solucion.factible();
        calcularAdyacencias(d);
    }

    private double muNoAsignados(DatosPlanificacion d) {
        double s = 0.0;
        for (int p = 0; p < d.n; p++) {
            if (!solucion.asignado(p)) {
                s += d.mu[p];
            }
        }
        return s;
    }

    /** Reconstruye pi y sigma a partir de las rutas, para que Split pueda reproducirlas. */
    void reconstruirCromosoma(DatosPlanificacion d) {
        int[] posSigma = new int[d.K];
        for (int i = 0; i < sigma.length; i++) {
            posSigma[sigma[i]] = i;
        }
        List<Integer> unidadesUsadas = new ArrayList<>();
        for (int u = 0; u < d.K; u++) {
            if (solucion.secuencia(u).length > 0) {
                unidadesUsadas.add(u);
            }
        }
        unidadesUsadas.sort((a, b) -> Integer.compare(posSigma[a], posSigma[b]));
        int[] nuevoPi = new int[d.n];
        int c = 0;
        for (int u : unidadesUsadas) {
            for (int p : solucion.secuencia(u)) {
                nuevoPi[c++] = p;
            }
        }
        for (int p = 0; p < d.n; p++) {
            if (!solucion.asignado(p)) {
                nuevoPi[c++] = p;
            }
        }
        int[] nuevoSigma = new int[d.K];
        int cs = 0;
        boolean[] usado = new boolean[d.K];
        for (int u : unidadesUsadas) {
            nuevoSigma[cs++] = u;
            usado[u] = true;
        }
        for (int u : sigma) {
            if (!usado[u]) {
                nuevoSigma[cs++] = u;
            }
        }
        pi = nuevoPi;
        sigma = nuevoSigma;
    }

    private void calcularAdyacencias(DatosPlanificacion d) {
        sucesor = new int[d.n];
        predecesor = new int[d.n];
        java.util.Arrays.fill(sucesor, -2);
        java.util.Arrays.fill(predecesor, -2);
        for (int u = 0; u < d.K; u++) {
            int[] s = solucion.secuencia(u);
            for (int i = 0; i < s.length; i++) {
                predecesor[s[i]] = i == 0 ? -1 : s[i - 1];
                sucesor[s[i]] = i == s.length - 1 ? -1 : s[i + 1];
            }
        }
    }

    /** Distancia de pares rotos (broken pairs), normalizada en [0, 1]. */
    double distanciaA(Individuo otro) {
        int n = sucesor.length;
        if (n == 0) {
            return 0.0;
        }
        int diferencias = 0;
        for (int i = 0; i < n; i++) {
            if (sucesor[i] != otro.sucesor[i] && sucesor[i] != otro.predecesor[i]) {
                diferencias++;
            }
        }
        return diferencias / (double) n;
    }
}
