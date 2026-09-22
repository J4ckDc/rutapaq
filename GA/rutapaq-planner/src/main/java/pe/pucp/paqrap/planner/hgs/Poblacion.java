package pe.pucp.paqrap.planner.hgs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Poblacion del HGS con dos subpoblaciones (factible e infactible). La aptitud
 * sesgada combina el rango de costo del individuo con su rango de contribucion a
 * la diversidad, medida como la distancia promedio de pares rotos a sus
 * nCercanos vecinos mas proximos. Cuando una subpoblacion supera N + lambda se
 * eliminan primero los clones y luego los individuos de peor aptitud sesgada.
 */
final class Poblacion {

    private final List<Individuo> factibles = new ArrayList<>();
    private final List<Individuo> infactibles = new ArrayList<>();
    private final int tamano;
    private final int lambda;
    private final int nElite;
    private final int nCercanos;

    Poblacion(ParametrosHGS p) {
        this.tamano = p.tamanoPoblacion();
        this.lambda = p.lambdaOffspring();
        this.nElite = p.nElite();
        this.nCercanos = p.nCercanos();
    }

    void insertar(Individuo ind) {
        List<Individuo> sub = ind.factible ? factibles : infactibles;
        for (Individuo o : sub) {
            double dd = ind.distanciaA(o);
            ind.distancias.put(o, dd);
            o.distancias.put(ind, dd);
        }
        sub.add(ind);
        if (sub.size() > tamano + lambda) {
            seleccionarSupervivientes(sub);
        }
    }

    /** Eliminacion de clones y reduccion a N individuos por aptitud sesgada. */
    private void seleccionarSupervivientes(List<Individuo> sub) {
        while (sub.size() > tamano) {
            actualizarAptitudSesgada(sub);
            Individuo peor = null;
            boolean peorEsClon = false;
            for (Individuo ind : sub) {
                boolean clon = false;
                for (double dd : ind.distancias.values()) {
                    if (dd < 1.0E-9) {
                        clon = true;
                        break;
                    }
                }
                if (peor == null || (clon && !peorEsClon)
                        || (clon == peorEsClon && ind.aptitudSesgada > peor.aptitudSesgada)) {
                    peor = ind;
                    peorEsClon = clon;
                }
            }
            sub.remove(peor);
            for (Individuo o : sub) {
                o.distancias.remove(peor);
            }
        }
    }

    private void actualizarAptitudSesgada(List<Individuo> sub) {
        int m = sub.size();
        if (m == 1) {
            sub.get(0).aptitudSesgada = 0.0;
            return;
        }
        List<Individuo> porCosto = new ArrayList<>(sub);
        porCosto.sort(Comparator.comparingDouble(i -> i.fitness));
        double[] contribucion = new double[m];
        for (int i = 0; i < m; i++) {
            Individuo ind = sub.get(i);
            double[] ds = ind.distancias.values().stream().mapToDouble(Double::doubleValue).toArray();
            Arrays.sort(ds);
            int c = Math.min(nCercanos, ds.length);
            double s = 0.0;
            for (int k = 0; k < c; k++) {
                s += ds[k];
            }
            contribucion[i] = c == 0 ? 0.0 : s / c;
        }
        Integer[] porDiversidad = new Integer[m];
        for (int i = 0; i < m; i++) {
            porDiversidad[i] = i;
        }
        Arrays.sort(porDiversidad, (a, b) -> Double.compare(contribucion[b], contribucion[a]));
        double[] rangoDiv = new double[m];
        for (int r = 0; r < m; r++) {
            rangoDiv[porDiversidad[r]] = r / (double) (m - 1);
        }
        double pesoDiversidad = 1.0 - Math.min(nElite, m) / (double) m;
        for (int r = 0; r < m; r++) {
            Individuo ind = porCosto.get(r);
            ind.aptitudSesgada = r / (double) (m - 1) + pesoDiversidad * rangoDiv[sub.indexOf(ind)];
        }
    }

    void actualizarAptitudes() {
        actualizarAptitudSesgada(factibles);
        actualizarAptitudSesgada(infactibles);
    }

    /** Torneo binario: de dos individuos al azar se elige el de menor aptitud sesgada. */
    Individuo torneoBinario(SplittableRandom rnd) {
        int total = factibles.size() + infactibles.size();
        Individuo a = elemento(rnd.nextInt(total));
        Individuo b = elemento(rnd.nextInt(total));
        return a.aptitudSesgada <= b.aptitudSesgada ? a : b;
    }

    private Individuo elemento(int i) {
        return i < factibles.size() ? factibles.get(i) : infactibles.get(i - factibles.size());
    }

    void reevaluar(Penalizaciones pen) {
        for (Individuo i : factibles) {
            i.recalcularAptitud(pen);
        }
        for (Individuo i : infactibles) {
            i.recalcularAptitud(pen);
        }
    }

    int tamanoTotal() {
        return factibles.size() + infactibles.size();
    }

    double proporcionFactibles() {
        int t = tamanoTotal();
        return t == 0 ? 0.0 : factibles.size() / (double) t;
    }
}
