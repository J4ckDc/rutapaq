package pe.pucp.paqrap.planner.hgs;

import java.util.SplittableRandom;

import pe.pucp.paqrap.planner.core.DatosPlanificacion;
import pe.pucp.paqrap.planner.core.ResultadoRuta;
import pe.pucp.paqrap.planner.core.SolucionRutas;

/**
 * Educacion por busqueda local (Educate_LocalSearch) sobre la solucion
 * decodificada: Relocate (intra e inter ruta, incluida la insercion de pedidos
 * no planificados), Swap, 2-opt y 2-opt*, restringidos al vecindario granular de
 * los Gamma pedidos mas cercanos y con estrategia de primera mejora. Los
 * diferenciales se valorizan con el evaluador comun, de modo que incluyen el
 * efecto de las recargas intermedias y de las penalizaciones vigentes.
 */
final class BusquedaLocalEducacion {

    private static final double EPS = 1.0E-7;

    private final DatosPlanificacion d;
    private final int maxPasadas;
    private final int maxEvaluaciones;
    private int evaluaciones;

    BusquedaLocalEducacion(DatosPlanificacion d, int maxPasadas, int maxEvaluaciones) {
        this.d = d;
        this.maxPasadas = maxPasadas;
        this.maxEvaluaciones = maxEvaluaciones;
    }

    void educar(SolucionRutas s, SplittableRandom rnd) {
        int[] orden = new int[d.n];
        for (int i = 0; i < d.n; i++) {
            orden[i] = i;
        }
        boolean mejora = true;
        int pasadas = 0;
        evaluaciones = 0;
        while (mejora && pasadas < maxPasadas && evaluaciones < maxEvaluaciones) {
            mejora = false;
            pasadas++;
            for (int i = d.n - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = orden[i];
                orden[i] = orden[j];
                orden[j] = t;
            }
            for (int u : orden) {
                if (evaluaciones >= maxEvaluaciones) {
                    break;                               // presupuesto de educacion agotado
                }
                for (int v : d.vecinos[u]) {
                    if (relocate(s, u, v) || swap(s, u, v) || dosOpt(s, u, v) || dosOptEstrella(s, u, v)) {
                        mejora = true;
                        break;
                    }
                }
            }
        }
    }

    /** Relocate: mueve u junto a v (desde otra ruta o desde el banco de no planificados). */
    private boolean relocate(SolucionRutas s, int u, int v) {
        int rv = s.unidadDe(v);
        if (rv < 0 || u == v) {
            return false;
        }
        int ru = s.unidadDe(u);
        int[] sv = s.secuencia(rv);
        int pv = SolucionRutas.indiceDe(sv, v);
        if (ru == rv) {
            int[] sin = SolucionRutas.quitarEn(sv, SolucionRutas.indiceDe(sv, u));
            int[] nueva = SolucionRutas.insertarEn(sin, SolucionRutas.indiceDe(sin, v) + 1, u);
            return aplicarSiMejora(s, rv, nueva, null, null);
        }
        int[] nsv = SolucionRutas.insertarEn(sv, pv + 1, u);
        int[] nsu = ru >= 0 ? SolucionRutas.quitarEn(s.secuencia(ru), SolucionRutas.indiceDe(s.secuencia(ru), u)) : null;
        double bonoAsignacion = ru >= 0 ? 0.0 : -s.penalizador().getNoAtendido() * d.mu[u];
        return aplicarSiMejora(s, rv, nsv, ru >= 0 ? ru : null, nsu, bonoAsignacion);
    }

    /** Swap: intercambia las posiciones de u y v. */
    private boolean swap(SolucionRutas s, int u, int v) {
        int ru = s.unidadDe(u);
        int rv = s.unidadDe(v);
        if (ru < 0 || rv < 0 || u == v) {
            return false;
        }
        if (ru == rv) {
            int[] nueva = s.secuencia(ru).clone();
            int pu = SolucionRutas.indiceDe(nueva, u);
            int pv = SolucionRutas.indiceDe(nueva, v);
            nueva[pu] = v;
            nueva[pv] = u;
            return aplicarSiMejora(s, ru, nueva, null, null);
        }
        int[] nsu = s.secuencia(ru).clone();
        int[] nsv = s.secuencia(rv).clone();
        nsu[SolucionRutas.indiceDe(nsu, u)] = v;
        nsv[SolucionRutas.indiceDe(nsv, v)] = u;
        return aplicarSiMejora(s, ru, nsu, rv, nsv);
    }

    /** 2-opt: invierte el segmento entre el sucesor de u y v dentro de una ruta. */
    private boolean dosOpt(SolucionRutas s, int u, int v) {
        int r = s.unidadDe(u);
        if (r < 0 || r != s.unidadDe(v)) {
            return false;
        }
        int[] sec = s.secuencia(r);
        int pu = SolucionRutas.indiceDe(sec, u);
        int pv = SolucionRutas.indiceDe(sec, v);
        if (pv - pu < 2) {
            return false;
        }
        int[] nueva = sec.clone();
        for (int a = pu + 1, b = pv; a < b; a++, b--) {
            int t = nueva[a];
            nueva[a] = nueva[b];
            nueva[b] = t;
        }
        return aplicarSiMejora(s, r, nueva, null, null);
    }

    /** 2-opt*: intercambia las colas de dos rutas, redistribuyendo entre tipos de unidad. */
    private boolean dosOptEstrella(SolucionRutas s, int u, int v) {
        int ru = s.unidadDe(u);
        int rv = s.unidadDe(v);
        if (ru < 0 || rv < 0 || ru == rv) {
            return false;
        }
        int[] a = s.secuencia(ru);
        int[] b = s.secuencia(rv);
        int pu = SolucionRutas.indiceDe(a, u);
        int pv = SolucionRutas.indiceDe(b, v);
        if (pu == a.length - 1 && pv == b.length - 1) {
            return false;
        }
        int[] na = unir(a, pu, b, pv + 1);
        int[] nb = unir(b, pv, a, pu + 1);
        return aplicarSiMejora(s, ru, na, rv, nb);
    }

    private boolean aplicarSiMejora(SolucionRutas s, int u1, int[] s1, Integer u2, int[] s2) {
        return aplicarSiMejora(s, u1, s1, u2, s2, 0.0);
    }

    private boolean aplicarSiMejora(SolucionRutas s, int u1, int[] s1, Integer u2, int[] s2, double bono) {
        evaluaciones++;
        ResultadoRuta r1 = s.evaluarCon(u1, s1);
        if (!r1.valida()) {
            return false;
        }
        double delta = s.penalizador().costo(r1) - s.costoRuta(u1) + bono;
        ResultadoRuta r2 = null;
        if (u2 != null) {
            r2 = s.evaluarCon(u2, s2);
            if (!r2.valida()) {
                return false;
            }
            delta += s.penalizador().costo(r2) - s.costoRuta(u2);
        }
        if (delta >= -EPS) {
            return false;
        }
        if (u2 != null) {
            s.fijarSecuencia(u2, s2);
        }
        s.fijarSecuencia(u1, s1);
        return true;
    }

    /** Prefijo s1[0..hasta] seguido de la cola s2[desde..]. */
    private static int[] unir(int[] s1, int hasta, int[] s2, int desde) {
        int[] r = new int[hasta + 1 + s2.length - desde];
        System.arraycopy(s1, 0, r, 0, hasta + 1);
        System.arraycopy(s2, desde, r, hasta + 1, s2.length - desde);
        return r;
    }
}
