package pe.pucp.paqrap.planner.hgs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import pe.pucp.paqrap.planner.core.DatosPlanificacion;
import pe.pucp.paqrap.planner.core.EvaluadorItinerario;
import pe.pucp.paqrap.planner.core.Penalizador;
import pe.pucp.paqrap.planner.core.SolucionRutas;

/**
 * Procedimiento Split_Heterogeneo_PaqRap: particion optima de la gran ruta pi
 * sobre la flota heterogenea limitada, resuelta por programacion dinamica sobre
 * un grafo aciclico auxiliar.
 *
 * <p>El estado (k, j) indica que los primeros j pedidos de pi ya fueron
 * decididos empleando las k primeras unidades de sigma. Desde cada estado hay
 * tres transiciones: (i) la unidad sigma[k] atiende el tramo contiguo
 * pi[j..j'-1], con el costo de su itinerario completo, incluidas las recargas
 * intermedias que inserta el evaluador; (ii) la unidad sigma[k] no sale en el
 * turno; (iii) el pedido pi[j] queda sin planificar, con penalizacion
 * omegaNoPlanificado * mu(p). El camino minimo de (0,0) a (K,n) decide a la vez
 * los cortes de ruta y que tipo de unidad (auto, moto o bicicleta) atiende cada
 * tramo, respetando que cada unidad salga a lo sumo una vez.</p>
 */
final class SplitHeterogeneo {

    private static final byte SALTA_UNIDAD = 1;
    private static final byte SALTA_PEDIDO = 2;
    private static final byte RUTA = 3;

    private final DatosPlanificacion d;
    private final int maxParadas;

    SplitHeterogeneo(DatosPlanificacion d, int maxParadas) {
        this.d = d;
        this.maxParadas = maxParadas;
    }

    SolucionRutas decodificar(Individuo ind, Penalizador pen) {
        final int n = d.n;
        final int K = d.K;
        final int[] pi = ind.pi;
        double[][] costo = new double[K + 1][n + 1];
        byte[][] tipo = new byte[K + 1][n + 1];
        int[][] inicio = new int[K + 1][n + 1];
        for (double[] fila : costo) {
            Arrays.fill(fila, Double.POSITIVE_INFINITY);
        }
        costo[0][0] = 0.0;

        for (int k = 0; k <= K; k++) {
            for (int j = 0; j <= n; j++) {
                double base = costo[k][j];
                if (base == Double.POSITIVE_INFINITY) {
                    continue;
                }
                if (j < n) {
                    relajar(costo, tipo, inicio, k, j + 1,
                            base + pen.getNoAtendido() * d.mu[pi[j]], SALTA_PEDIDO, j);
                }
                if (k == K) {
                    continue;
                }
                relajar(costo, tipo, inicio, k + 1, j, base, SALTA_UNIDAD, j);
                if (j == n) {
                    continue;
                }
                int unidad = ind.sigma[k];
                DatosPlanificacion.Unidad u = d.unidades[unidad];
                EvaluadorItinerario.Traza tr = EvaluadorItinerario.iniciar(d, u);
                for (int jj = j; jj < n && jj - j < maxParadas; jj++) {
                    if (!EvaluadorItinerario.extender(d, u, tr, pi[jj], d.saldo, null)) {
                        break;
                    }
                    double c = pen.costo(EvaluadorItinerario.cerrar(d, u, tr));
                    if (c == Double.POSITIVE_INFINITY) {
                        break;
                    }
                    relajar(costo, tipo, inicio, k + 1, jj + 1, base + c, RUTA, j);
                    if (tr.t > u.horizonte + 4L * 3600L) {
                        break;                       // extender mas no tiene sentido operativo
                    }
                }
            }
        }

        SolucionRutas s = SolucionRutas.vacia(d, pen);
        int k = K;
        int j = n;
        while (k > 0 || j > 0) {
            byte t = tipo[k][j];
            if (t == SALTA_PEDIDO) {
                j--;
            } else if (t == SALTA_UNIDAD) {
                k--;
            } else if (t == RUTA) {
                int i = inicio[k][j];
                s.fijarSecuencia(ind.sigma[k - 1], Arrays.copyOfRange(pi, i, j));
                k--;
                j = i;
            } else {
                throw new IllegalStateException("Split sin camino en (" + k + "," + j + ")");
            }
        }
        return s;
    }

    private static void relajar(double[][] costo, byte[][] tipo, int[][] inicio,
                                int k, int j, double valor, byte t, int desde) {
        if (valor < costo[k][j]) {
            costo[k][j] = valor;
            tipo[k][j] = t;
            inicio[k][j] = desde;
        }
    }

    /** Utilidad para pruebas: lista de unidades efectivamente usadas. */
    static List<Integer> unidadesUsadas(SolucionRutas s) {
        List<Integer> usadas = new ArrayList<>();
        for (int u = 0; u < s.datos().K; u++) {
            if (s.secuencia(u).length > 0) {
                usadas.add(u);
            }
        }
        return usadas;
    }
}
