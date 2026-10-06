package pe.pucp.paqrap.planner.alns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Ruleta adaptativa del ALNS: w(i, s+1) = lambda * w(i, s)
 * + (1 - lambda) * pi(i) / max(1, theta(i)), donde pi(i) es el puntaje acumulado
 * del operador y theta(i) su numero de invocaciones en el segmento.
 */
public final class RuletaAdaptativa<T> {

    private final List<T> operadores;
    private final double[] pesos;
    private final double[] puntajes;
    private final int[] invocaciones;

    public RuletaAdaptativa(List<T> operadores) {
        this.operadores = new ArrayList<>(operadores);
        this.pesos = new double[operadores.size()];
        this.puntajes = new double[operadores.size()];
        this.invocaciones = new int[operadores.size()];
        Arrays.fill(pesos, 1.0);
    }

    public T seleccionar(RandomGenerator rnd) {
        double total = 0.0;
        for (double w : pesos) {
            total += w;
        }
        double corte = rnd.nextDouble() * total;
        double acumulado = 0.0;
        for (int i = 0; i < pesos.length; i++) {
            acumulado += pesos[i];
            if (corte <= acumulado) {
                invocaciones[i]++;
                return operadores.get(i);
            }
        }
        invocaciones[pesos.length - 1]++;
        return operadores.get(pesos.length - 1);
    }

    public void acumular(T operador, double puntaje) {
        int i = operadores.indexOf(operador);
        if (i >= 0) {
            puntajes[i] += puntaje;
        }
    }

    public void actualizarPesos(double lambda) {
        for (int i = 0; i < pesos.length; i++) {
            double rendimiento = puntajes[i] / Math.max(1, invocaciones[i]);
            pesos[i] = Math.max(1.0E-3, lambda * pesos[i] + (1.0 - lambda) * rendimiento);
            puntajes[i] = 0.0;
            invocaciones[i] = 0;
        }
    }

    public List<T> getOperadores() {
        return operadores;
    }

    public double[] getPesos() {
        return pesos.clone();
    }
}
