package pe.pucp.paqrap.planner.hgs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Individuo del HGS: I = (pi, gamma, sigma).
 * <ul>
 *   <li>pi: gran ruta (giant tour), permutacion de los n pedidos del banco sin
 *       delimitadores de vehiculo ni de ruta;</li>
 *   <li>gamma: almacen de recarga asignado a cada pedido (CENTRAL, INTERMEDIO_1
 *       o INTERMEDIO_2), heredado por pedido;</li>
 *   <li>sigma: orden de las unidades disponibles en que el procedimiento Split
 *       ofrece los tramos de la gran ruta (flota heterogenea limitada).</li>
 * </ul>
 * Las rutas, los componentes de costo y la aptitud son atributos derivados.
 */
final class Individuo {

    int[] pi;
    int[] gamma;
    int[] sigma;

    List<RutaHGS> rutas = List.of();
    int[] noAsignados = new int[0];

    double costoKm;
    double atraso;
    double violacionTurno;
    double excesoStock;
    double noAtendidoPonderado;
    boolean valido;
    boolean factible;
    double fitness;
    double aptitudSesgada;

    int[] sucesor;
    int[] predecesor;
    final Map<Individuo, Double> distancias = new IdentityHashMap<>();

    Individuo(int[] pi, int[] gamma, int[] sigma) {
        this.pi = pi;
        this.gamma = gamma;
        this.sigma = sigma;
    }

    /** Agrega los componentes de la aptitud a partir de las rutas decodificadas (Algoritmo 10). */
    void evaluar(DatosHGS d, Penalizaciones pen) {
        costoKm = 0.0;
        atraso = 0.0;
        violacionTurno = 0.0;
        valido = true;
        int[] consumo = d.consumoFijo.clone();
        for (RutaHGS r : rutas) {
            RutaEval e = r.eval();
            if (!e.valida()) {
                valido = false;
                continue;
            }
            DatosHGS.Slot s = d.slots[r.slot()];
            RutaEval base = s.tienePrefijo() ? s.evalPrefijo : null;
            costoKm += e.costoKm() - (base == null ? 0.0 : base.costoKm());
            atraso += Math.max(0.0, e.atrasoPonderadoH() - (base == null ? 0.0 : base.atrasoPonderadoH()));
            violacionTurno += Math.max(0.0, e.violacionTurno() - (base == null ? 0.0 : base.violacionTurno()));
            consumo[r.almacen()] += e.carga() - d.cargaBase(r.slot());
        }
        excesoStock = 0.0;
        for (int a = 0; a < d.nAlm; a++) {
            excesoStock += d.exceso(a, consumo[a]);
        }
        noAtendidoPonderado = 0.0;
        for (int p : noAsignados) {
            noAtendidoPonderado += d.mu[p];
        }
        factible = valido && atraso < 1.0E-9 && violacionTurno < 1.0E-9 && excesoStock < 1.0E-9;
        recalcularAptitud(pen);
    }

    void recalcularAptitud(Penalizaciones pen) {
        fitness = !valido ? Double.POSITIVE_INFINITY
                : costoKm + pen.sla * atraso + pen.turno * violacionTurno + pen.stock * excesoStock
                  + pen.noAtendido * noAtendidoPonderado;
    }

    /** Objetivo sin penalizaciones adaptativas: costo en soles mas pedidos no atendidos. */
    double objetivo(Penalizaciones pen) {
        return costoKm + pen.noAtendido * noAtendidoPonderado;
    }

    /**
     * Concatena las rutas educadas en una gran ruta coherente con la codificacion:
     * las rutas se ordenan segun la posicion de su unidad en sigma, los pedidos no
     * asignados se ubican al final de pi y las unidades no usadas al final de sigma.
     * Con este orden, Split puede reproducir exactamente la solucion educada.
     */
    void reconstruirCromosoma(DatosHGS d) {
        int[] posSigma = new int[d.K];
        for (int i = 0; i < sigma.length; i++) {
            posSigma[sigma[i]] = i;
        }
        List<RutaHGS> ordenadas = new ArrayList<>(rutas);
        ordenadas.sort(Comparator.comparingInt(r -> posSigma[r.slot()]));
        int[] nuevoPi = new int[d.n];
        int[] nuevoSigma = new int[d.K];
        boolean[] usado = new boolean[d.K];
        int c = 0;
        int cs = 0;
        for (RutaHGS r : ordenadas) {
            for (int p : r.paradas()) {
                nuevoPi[c++] = p;
                if (d.slots[r.slot()].almacenFijo < 0) {
                    gamma[p] = r.almacen();
                }
            }
            nuevoSigma[cs++] = r.slot();
            usado[r.slot()] = true;
        }
        for (int p : noAsignados) {
            nuevoPi[c++] = p;
        }
        for (int k : sigma) {
            if (!usado[k]) {
                nuevoSigma[cs++] = k;
            }
        }
        pi = nuevoPi;
        sigma = nuevoSigma;
        rutas = ordenadas;
        calcularAdyacencias(d.n);
    }

    void calcularAdyacencias(int n) {
        sucesor = new int[n];
        predecesor = new int[n];
        java.util.Arrays.fill(sucesor, -2);
        java.util.Arrays.fill(predecesor, -2);
        for (RutaHGS r : rutas) {
            int[] s = r.paradas();
            for (int i = 0; i < s.length; i++) {
                predecesor[s[i]] = i == 0 ? -1 : s[i - 1];
                sucesor[s[i]] = i == s.length - 1 ? -1 : s[i + 1];
            }
        }
    }

    /** Distancia de pares rotos (broken pairs distance), normalizada en [0, 1]. */
    double distanciaA(Individuo otro) {
        int n = sucesor.length;
        if (n == 0) {
            return 0.0;
        }
        int diferencias = 0;
        for (int i = 0; i < n; i++) {
            int s = sucesor[i];
            if (s != otro.sucesor[i] && s != otro.predecesor[i]) {
                diferencias++;
            } else if (predecesor[i] == -1 && otro.predecesor[i] != -1 && otro.sucesor[i] != -1) {
                diferencias++;
            }
        }
        return diferencias / (double) n;
    }
}
