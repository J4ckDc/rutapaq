package pe.pucp.paqrap.planner.hgs;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Educacion por busqueda local (Educate_LocalSearch). Explora los movimientos
 * Relocate (intra e inter ruta, incluida la insercion de pedidos no asignados),
 * Swap, 2-opt y 2-opt* restringidos al vecindario granular de los Gamma
 * clientes mas cercanos, con estrategia de primera mejora. Los diferenciales se
 * valorizan con el costo por kilometro de la unidad de cada ruta y con las
 * penalizaciones vigentes multiplicadas por el factor de educacion; la
 * capacidad de la unidad se trata como restriccion dura.
 */
final class BusquedaLocalEducacion {

    private static final double EPS = 1.0E-7;

    private final DatosHGS d;
    private final int maxPasadas;

    BusquedaLocalEducacion(DatosHGS d, int maxPasadas) {
        this.d = d;
        this.maxPasadas = maxPasadas;
    }

    void educar(Individuo ind, Penalizaciones pen, double factor, SplittableRandom rnd) {
        Estado e = new Estado(ind, pen, factor);
        e.ejecutar(rnd);
        e.volcar(ind);
        ind.reconstruirCromosoma(d);
        ind.evaluar(d, pen);
    }

    /** Estado de trabajo local a una invocacion: seguro para la educacion en paralelo. */
    private final class Estado {
        final Penalizaciones pen;
        final double f;
        final List<int[]> rutas = new ArrayList<>();
        final List<Integer> slot = new ArrayList<>();
        final List<Integer> almacen = new ArrayList<>();
        final List<RutaEval> eval = new ArrayList<>();
        final List<Double> costo = new ArrayList<>();
        final int[] rutaDe;
        final int[] posDe;
        final int[] consumo;

        Estado(Individuo ind, Penalizaciones pen, double f) {
            this.pen = pen;
            this.f = f;
            rutaDe = new int[d.n];
            posDe = new int[d.n];
            java.util.Arrays.fill(rutaDe, -1);
            consumo = d.consumoFijo.clone();
            for (RutaHGS r : ind.rutas) {
                int idx = rutas.size();
                rutas.add(r.paradas().clone());
                slot.add(r.slot());
                almacen.add(r.almacen());
                eval.add(r.eval());
                costo.add(costoMarginal(r.slot(), r.eval()));
                consumo[r.almacen()] += r.eval().carga() - d.cargaBase(r.slot());
                indexar(idx);
            }
        }

        double costoMarginal(int s, RutaEval e) {
            return e.valida() ? e.costo(pen, f) - d.costoBase(s, pen, f) : Double.POSITIVE_INFINITY;
        }

        void indexar(int r) {
            int[] s = rutas.get(r);
            for (int i = 0; i < s.length; i++) {
                rutaDe[s[i]] = r;
                posDe[s[i]] = i;
            }
        }

        RutaEval evaluar(int r, int[] paradas) {
            return EvaluadorRuta.evaluar(d, d.slots[slot.get(r)], almacen.get(r), paradas);
        }

        void aplicar(int r, int[] paradas, RutaEval e) {
            rutas.set(r, paradas);
            eval.set(r, e);
            costo.set(r, costoMarginal(slot.get(r), e));
            indexar(r);
        }

        /** Variacion de la penalizacion de stock al trasladar q unidades de un almacen a otro. */
        double deltaStock(int desde, int hacia, int q) {
            if (desde == hacia || q == 0) {
                return 0.0;
            }
            double antes = 0.0;
            double despues = 0.0;
            if (desde >= 0) {
                antes += d.exceso(desde, consumo[desde]);
                despues += d.exceso(desde, consumo[desde] - q);
            }
            if (hacia >= 0) {
                antes += d.exceso(hacia, consumo[hacia]);
                despues += d.exceso(hacia, consumo[hacia] + q);
            }
            return f * pen.stock * (despues - antes);
        }

        void trasladar(int desde, int hacia, int q) {
            if (desde >= 0) {
                consumo[desde] -= q;
            }
            if (hacia >= 0) {
                consumo[hacia] += q;
            }
        }

        int capacidad(int r) {
            return d.slots[slot.get(r)].capacidad;
        }

        void ejecutar(SplittableRandom rnd) {
            int[] orden = new int[d.n];
            for (int i = 0; i < d.n; i++) {
                orden[i] = i;
            }
            boolean mejora = true;
            int pasadas = 0;
            while (mejora && pasadas < maxPasadas) {
                mejora = false;
                pasadas++;
                for (int i = d.n - 1; i > 0; i--) {
                    int j = rnd.nextInt(i + 1);
                    int t = orden[i];
                    orden[i] = orden[j];
                    orden[j] = t;
                }
                for (int u : orden) {
                    for (int v : d.vecinos[u]) {
                        if (relocate(u, v, false) || relocate(u, v, true) || swap(u, v)
                                || dosOpt(u, v) || dosOpt(v, u) || dosOptEstrella(u, v)) {
                            mejora = true;
                            break;
                        }
                    }
                }
            }
        }

        /** Relocate: extrae u de su ruta (o del banco de no asignados) y lo inserta junto a v. */
        boolean relocate(int u, int v, boolean antes) {
            int rv = rutaDe[v];
            if (rv < 0 || u == v) {
                return false;
            }
            int ru = rutaDe[u];
            int[] sv = rutas.get(rv);
            if (ru == rv) {
                int[] sin = quitar(sv, posDe[u]);
                int pv = indice(sin, v);
                int[] ns = poner(sin, antes ? pv : pv + 1, u);
                if (java.util.Arrays.equals(ns, sv)) {
                    return false;
                }
                RutaEval e = evaluar(rv, ns);
                double delta = costoMarginal(slot.get(rv), e) - costo.get(rv);
                if (delta < -EPS) {
                    aplicar(rv, ns, e);
                    return true;
                }
                return false;
            }
            if (eval.get(rv).carga() + d.paq[u] > capacidad(rv)) {
                return false;                                         // 24, 8 o 4 paquetes
            }
            int[] nsv = poner(sv, antes ? posDe[v] : posDe[v] + 1, u);
            RutaEval ev = evaluar(rv, nsv);
            double delta = costoMarginal(slot.get(rv), ev) - costo.get(rv);
            if (delta == Double.POSITIVE_INFINITY) {
                return false;
            }
            int[] nsu = null;
            RutaEval eu = null;
            if (ru >= 0) {
                nsu = quitar(rutas.get(ru), posDe[u]);
                eu = evaluar(ru, nsu);
                delta += costoMarginal(slot.get(ru), eu) - costo.get(ru);
                delta += deltaStock(almacen.get(ru), almacen.get(rv), d.paq[u]);
            } else {
                delta -= pen.noAtendido * d.mu[u];
                delta += deltaStock(-1, almacen.get(rv), d.paq[u]);
            }
            if (delta < -EPS) {
                trasladar(ru >= 0 ? almacen.get(ru) : -1, almacen.get(rv), d.paq[u]);
                if (ru >= 0) {
                    aplicar(ru, nsu, eu);
                }
                aplicar(rv, nsv, ev);
                return true;
            }
            return false;
        }

        /** Swap: intercambia las posiciones de u y v, en la misma ruta o entre rutas. */
        boolean swap(int u, int v) {
            int ru = rutaDe[u];
            int rv = rutaDe[v];
            if (ru < 0 || rv < 0 || u == v) {
                return false;
            }
            if (ru == rv) {
                int[] ns = rutas.get(ru).clone();
                ns[posDe[u]] = v;
                ns[posDe[v]] = u;
                RutaEval e = evaluar(ru, ns);
                double delta = costoMarginal(slot.get(ru), e) - costo.get(ru);
                if (delta < -EPS) {
                    aplicar(ru, ns, e);
                    return true;
                }
                return false;
            }
            if (eval.get(ru).carga() - d.paq[u] + d.paq[v] > capacidad(ru)
                    || eval.get(rv).carga() - d.paq[v] + d.paq[u] > capacidad(rv)) {
                return false;
            }
            int[] nsu = rutas.get(ru).clone();
            int[] nsv = rutas.get(rv).clone();
            nsu[posDe[u]] = v;
            nsv[posDe[v]] = u;
            RutaEval eu = evaluar(ru, nsu);
            RutaEval ev = evaluar(rv, nsv);
            double delta = costoMarginal(slot.get(ru), eu) - costo.get(ru)
                    + costoMarginal(slot.get(rv), ev) - costo.get(rv)
                    + deltaStock(almacen.get(ru), almacen.get(rv), d.paq[u] - d.paq[v]);
            if (delta < -EPS) {
                trasladar(almacen.get(ru), almacen.get(rv), d.paq[u] - d.paq[v]);
                aplicar(ru, nsu, eu);
                aplicar(rv, nsv, ev);
                return true;
            }
            return false;
        }

        /** 2-opt: invierte el segmento comprendido entre el sucesor de u y v dentro de una ruta. */
        boolean dosOpt(int u, int v) {
            int r = rutaDe[u];
            if (r < 0 || r != rutaDe[v] || posDe[v] - posDe[u] < 2) {
                return false;
            }
            int[] ns = rutas.get(r).clone();
            for (int a = posDe[u] + 1, b = posDe[v]; a < b; a++, b--) {
                int t = ns[a];
                ns[a] = ns[b];
                ns[b] = t;
            }
            RutaEval e = evaluar(r, ns);
            double delta = costoMarginal(slot.get(r), e) - costo.get(r);
            if (delta < -EPS) {
                aplicar(r, ns, e);
                return true;
            }
            return false;
        }

        /** 2-opt*: intercambia las colas de dos rutas, redistribuyendo clientes entre tipos de unidad. */
        boolean dosOptEstrella(int u, int v) {
            int ru = rutaDe[u];
            int rv = rutaDe[v];
            if (ru < 0 || rv < 0 || ru == rv) {
                return false;
            }
            int[] a = rutas.get(ru);
            int[] b = rutas.get(rv);
            int pu = posDe[u];
            int pv = posDe[v];
            if (pu == a.length - 1 && pv == b.length - 1) {
                return false;
            }
            int colaU = suma(a, pu + 1);
            int colaV = suma(b, pv + 1);
            if (eval.get(ru).carga() - colaU + colaV > capacidad(ru)
                    || eval.get(rv).carga() - colaV + colaU > capacidad(rv)) {
                return false;
            }
            int[] na = unir(a, pu, b, pv + 1);
            int[] nb = unir(b, pv, a, pu + 1);
            RutaEval ea = evaluar(ru, na);
            RutaEval eb = evaluar(rv, nb);
            double delta = costoMarginal(slot.get(ru), ea) - costo.get(ru)
                    + costoMarginal(slot.get(rv), eb) - costo.get(rv)
                    + deltaStock(almacen.get(ru), almacen.get(rv), colaU - colaV);
            if (delta < -EPS) {
                trasladar(almacen.get(ru), almacen.get(rv), colaU - colaV);
                aplicar(ru, na, ea);
                aplicar(rv, nb, eb);
                return true;
            }
            return false;
        }

        int suma(int[] s, int desde) {
            int t = 0;
            for (int i = desde; i < s.length; i++) {
                t += d.paq[s[i]];
            }
            return t;
        }

        void volcar(Individuo ind) {
            List<RutaHGS> resultado = new ArrayList<>();
            for (int r = 0; r < rutas.size(); r++) {
                if (rutas.get(r).length > 0) {
                    resultado.add(new RutaHGS(slot.get(r), almacen.get(r), rutas.get(r), eval.get(r)));
                }
            }
            ind.rutas = resultado;
            int c = 0;
            for (int p = 0; p < d.n; p++) {
                if (rutaDe[p] < 0) {
                    c++;
                }
            }
            int[] libres = new int[c];
            c = 0;
            for (int p = 0; p < d.n; p++) {
                if (rutaDe[p] < 0) {
                    libres[c++] = p;
                }
            }
            ind.noAsignados = libres;
        }
    }

    private static int[] quitar(int[] s, int pos) {
        int[] r = new int[s.length - 1];
        System.arraycopy(s, 0, r, 0, pos);
        System.arraycopy(s, pos + 1, r, pos, s.length - pos - 1);
        return r;
    }

    private static int[] poner(int[] s, int pos, int valor) {
        int[] r = new int[s.length + 1];
        System.arraycopy(s, 0, r, 0, pos);
        r[pos] = valor;
        System.arraycopy(s, pos, r, pos + 1, s.length - pos);
        return r;
    }

    private static int indice(int[] s, int valor) {
        for (int i = 0; i < s.length; i++) {
            if (s[i] == valor) {
                return i;
            }
        }
        return -1;
    }

    /** Prefijo s1[0..hasta] seguido de la cola s2[desde..]. */
    private static int[] unir(int[] s1, int hasta, int[] s2, int desde) {
        int[] r = new int[hasta + 1 + s2.length - desde];
        System.arraycopy(s1, 0, r, 0, hasta + 1);
        System.arraycopy(s2, desde, r, hasta + 1, s2.length - desde);
        return r;
    }
}
