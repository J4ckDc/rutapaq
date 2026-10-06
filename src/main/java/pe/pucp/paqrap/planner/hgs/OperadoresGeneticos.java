package pe.pucp.paqrap.planner.hgs;

import java.util.SplittableRandom;

/** Cruce ordenado (OX) y mutacion por intercambio o inversion. */
final class OperadoresGeneticos {

    private OperadoresGeneticos() {
    }

    /**
     * Cruce ordenado OX: copia un bloque contiguo del primer progenitor y
     * completa las posiciones restantes con los elementos del segundo en su
     * orden relativo, preservando las subsecuencias de entrega eficientes.
     */
    static int[] cruceOX(int[] p1, int[] p2, SplittableRandom r) {
        int n = p1.length;
        if (n < 2) {
            return p1.clone();
        }
        int[] hijo = new int[n];
        int c1 = r.nextInt(n);
        int c2 = r.nextInt(n);
        if (c1 > c2) {
            int t = c1;
            c1 = c2;
            c2 = t;
        }
        boolean[] presente = new boolean[n];
        for (int i = c1; i <= c2; i++) {
            hijo[i] = p1[i];
            presente[p1[i]] = true;
        }
        int pos = (c2 + 1) % n;
        for (int k = 0; k < n; k++) {
            int e = p2[(c2 + 1 + k) % n];
            if (!presente[e]) {
                hijo[pos] = e;
                presente[e] = true;
                pos = (pos + 1) % n;
            }
        }
        return hijo;
    }

    /** Mutacion swap o inversion sobre pi, e intercambio de dos unidades en sigma. */
    static void mutar(Individuo ind, SplittableRandom r) {
        int n = ind.pi.length;
        if (n >= 2) {
            int i = r.nextInt(n);
            int j = r.nextInt(n);
            if (r.nextDouble() < 0.5) {
                int t = ind.pi[i];
                ind.pi[i] = ind.pi[j];
                ind.pi[j] = t;
            } else {
                int a = Math.min(i, j);
                int b = Math.max(i, j);
                while (a < b) {
                    int t = ind.pi[a];
                    ind.pi[a++] = ind.pi[b];
                    ind.pi[b--] = t;
                }
            }
        }
        if (ind.sigma.length >= 2 && r.nextDouble() < 0.30) {
            int i = r.nextInt(ind.sigma.length);
            int j = r.nextInt(ind.sigma.length);
            int t = ind.sigma[i];
            ind.sigma[i] = ind.sigma[j];
            ind.sigma[j] = t;
        }
    }
}
