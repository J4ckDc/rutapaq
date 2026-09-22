package pe.pucp.paqrap.planner.hgs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Procedimiento Split_Heterogeneo_PaqRap: particion optima de la gran ruta pi
 * sobre la flota heterogenea limitada, resuelta por programacion dinamica en un
 * grafo aciclico auxiliar.
 *
 * <p>El estado (k, j) indica que los primeros j pedidos de pi ya fueron
 * decididos empleando las k primeras unidades de sigma. Desde cada estado se
 * admiten tres transiciones: (i) la unidad sigma[k] atiende el tramo contiguo
 * pi[j .. j'-1] (arco de ruta, con su costo en soles por kilometro mas las
 * penalizaciones de plazo, turno y refrigerio); (ii) la unidad sigma[k] no sale
 * en el turno; (iii) el pedido pi[j] queda sin atender con penalizacion
 * omegaNoAtendido * mu(p). El camino minimo de (0, 0) a (K, n) es la particion
 * de menor costo para (pi, sigma, gamma): decide a la vez que tramos forman
 * cada ruta, que unidad (auto, moto o bicicleta) la ejecuta y respeta que cada
 * unidad salga a lo sumo una vez. Complejidad O(K * n * L), donde L es el
 * maximo de paradas por ruta, acotado por la capacidad de 24 paquetes.</p>
 */
final class SplitHeterogeneo {

    private static final byte SALTA_UNIDAD = 1;
    private static final byte SALTA_PEDIDO = 2;
    private static final byte RUTA = 3;

    private final DatosHGS d;
    private final int maxParadas;

    SplitHeterogeneo(DatosHGS d, int maxParadas) {
        this.d = d;
        this.maxParadas = maxParadas;
    }

    void decodificar(Individuo ind, Penalizaciones pen) {
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
                    relajar(costo, tipo, inicio, k, j + 1, base + pen.noAtendido * d.mu[pi[j]], SALTA_PEDIDO, j);
                }
                if (k == K) {
                    continue;
                }
                relajar(costo, tipo, inicio, k + 1, j, base, SALTA_UNIDAD, j);
                if (j == n) {
                    continue;
                }
                int slot = ind.sigma[k];
                DatosHGS.Slot s = d.slots[slot];
                int almacen = d.almacenDe(slot, pi[j], ind.gamma);
                double costoPrefijo = d.costoBase(slot, pen, 1.0);
                int topeAlmacen = d.infinito[almacen] ? Integer.MAX_VALUE : d.saldo[almacen] - d.consumoFijo[almacen];
                EvaluadorRuta.Traza tr = EvaluadorRuta.iniciar(d, s, almacen);
                int cargaTramo = 0;
                for (int jj = j; jj < n && jj - j < maxParadas; jj++) {
                    int p = pi[jj];
                    if (tr.carga + d.paq[p] > s.capacidad) {
                        break;                                   // 24, 8 o 4 paquetes
                    }
                    cargaTramo += d.paq[p];
                    if (cargaTramo > topeAlmacen) {
                        break;                                   // tope de 1 000 del intermedio
                    }
                    if (!EvaluadorRuta.extender(d, s, tr, p)) {
                        break;                                   // arco bloqueado sin desvio
                    }
                    double c = EvaluadorRuta.cerrar(d, s, almacen, tr).costo(pen, 1.0) - costoPrefijo;
                    relajar(costo, tipo, inicio, k + 1, jj + 1, base + c, RUTA, j);
                    if (tr.t > s.turnoFin + 4L * 3600L) {
                        break;                                   // extension sin sentido operativo
                    }
                }
            }
        }

        // Reconstruccion del camino minimo desde (K, n) hasta (0, 0).
        List<RutaHGS> rutas = new ArrayList<>();
        List<Integer> sinAtender = new ArrayList<>();
        int k = K;
        int j = n;
        while (k > 0 || j > 0) {
            byte t = tipo[k][j];
            if (t == SALTA_PEDIDO) {
                sinAtender.add(pi[j - 1]);
                j--;
            } else if (t == SALTA_UNIDAD) {
                k--;
            } else if (t == RUTA) {
                int i = inicio[k][j];
                int slot = ind.sigma[k - 1];
                int almacen = d.almacenDe(slot, pi[i], ind.gamma);
                int[] paradas = Arrays.copyOfRange(pi, i, j);
                rutas.add(new RutaHGS(slot, almacen, paradas,
                        EvaluadorRuta.evaluar(d, d.slots[slot], almacen, paradas)));
                k--;
                j = i;
            } else {
                throw new IllegalStateException("Split sin camino en (" + k + "," + j + ")");
            }
        }
        ind.rutas = rutas;
        ind.noAsignados = sinAtender.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void relajar(double[][] costo, byte[][] tipo, int[][] inicio,
                                int k, int j, double valor, byte t, int desde) {
        if (valor < costo[k][j]) {
            costo[k][j] = valor;
            tipo[k][j] = t;
            inicio[k][j] = desde;
        }
    }
}
