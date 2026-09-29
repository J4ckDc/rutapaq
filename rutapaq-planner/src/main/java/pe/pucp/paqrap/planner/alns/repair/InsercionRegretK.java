package pe.pucp.paqrap.planner.alns.repair;

import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.OperadorRepair;
import pe.pucp.paqrap.planner.core.DatosPlanificacion;
import pe.pucp.paqrap.planner.core.SolucionRutas;

/**
 * Insercion por arrepentimiento de orden k ponderada por la prioridad del
 * pedido: el arrepentimiento es la suma de las diferencias entre cada
 * alternativa y la mejor, multiplicada por mu(p) (5 para 4 y 8 h, 3 para 12 y
 * 18 h, 1 para 36 h). Un pedido con menos de k alternativas acumula una
 * penalizacion fija por cada alternativa faltante, de modo que los pedidos con
 * una sola opcion viable se insertan primero.
 */
public final class InsercionRegretK implements OperadorRepair {

    private final int k;
    private final double penalizacionSinAlternativa;

    public InsercionRegretK(int k, double penalizacionSinAlternativa) {
        this.k = k;
        this.penalizacionSinAlternativa = penalizacionSinAlternativa;
    }

    @Override
    public String nombre() {
        return "RegretKInsertion";
    }

    @Override
    public void reparar(SolucionRutas s, List<Integer> banco, RandomGenerator rnd) {
        DatosPlanificacion d = s.datos();
        while (!banco.isEmpty()) {
            int elegido = -1;
            int[] posElegida = null;
            double maxRegret = Double.NEGATIVE_INFINITY;
            for (int p : banco) {
                List<double[]> top = s.kMejoresInserciones(p, k);
                if (top.isEmpty()) {
                    continue;
                }
                double regret = (k - top.size()) * penalizacionSinAlternativa;
                for (int h = 1; h < top.size(); h++) {
                    regret += top.get(h)[0] - top.get(0)[0];
                }
                regret *= d.mu[p];
                if (regret > maxRegret
                        || (regret == maxRegret && elegido >= 0 && d.limite[p] < d.limite[elegido])) {
                    maxRegret = regret;
                    elegido = p;
                    posElegida = new int[]{(int) top.get(0)[1], (int) top.get(0)[2]};
                }
            }
            if (elegido < 0) {
                banco.clear();                      // ningun pedido admite insercion
                return;
            }
            s.insertar(elegido, posElegida[0], posElegida[1]);
            banco.remove(Integer.valueOf(elegido));
        }
    }

    @Override
    public String toString() {
        return nombre();
    }
}
