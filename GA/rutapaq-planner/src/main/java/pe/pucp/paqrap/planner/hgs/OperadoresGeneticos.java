package pe.pucp.paqrap.planner.hgs;

import java.util.SplittableRandom;

/** Operadores de cruce ordenado (OX), herencia del gen de almacen y mutacion (Algoritmo 8). */
final class OperadoresGeneticos {

    private OperadoresGeneticos() {
    }

    /** Resultado del cruce OX: permutacion hija y marca de los elementos heredados del bloque del padre 1. */
    record ResultadoOX(int[] hijo, boolean[] delPadre1) {
    }

    /**
     * Cruce ordenado OX: copia un bloque contiguo del primer progenitor y completa
     * las posiciones restantes con los elementos del segundo en su orden relativo,
     * preservando las subsecuencias de entrega que ya resultaban eficientes.
     */
    static ResultadoOX cruceOX(int[] p1, int[] p2, SplittableRandom r) {
        int n = p1.length;
        int[] hijo = new int[n];
        boolean[] marca = new boolean[n];
        if (n < 2) {
            System.arraycopy(p1, 0, hijo, 0, n);
            java.util.Arrays.fill(marca, true);
            return new ResultadoOX(hijo, marca);
        }
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
            marca[p1[i]] = true;
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
        return new ResultadoOX(hijo, marca);
    }

    /** El gen de almacen de cada pedido se hereda del progenitor que aporto su posicion. */
    static int[] heredarAlmacen(boolean[] delPadre1, int[] g1, int[] g2) {
        int[] g = new int[g1.length];
        for (int p = 0; p < g.length; p++) {
            g[p] = delPadre1[p] ? g1[p] : g2[p];
        }
        return g;
    }

    /** Mutacion swap o inversion sobre pi, cambio de almacen de un pedido e intercambio en sigma. */
    static void mutar(Individuo ind, DatosHGS d, SplittableRandom r) {
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
        if (n > 0 && d.nAlm > 1 && r.nextDouble() < 0.30) {
            int p = r.nextInt(n);
            int nuevo = r.nextInt(d.nAlm - 1);
            ind.gamma[p] = nuevo >= ind.gamma[p] ? nuevo + 1 : nuevo;
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
