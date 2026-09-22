package pe.pucp.paqrap.planner.alns;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Ruleta adaptativa del ALNS. Cada operador tiene un peso que se actualiza al
 * cierre de cada segmento segun w(i, s+1) = lambda * w(i, s)
 * + (1 - lambda) * pi(i) / max(1, theta(i)), donde pi(i) es el puntaje
 * acumulado y theta(i) el numero de invocaciones en el segmento.
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
        java.util.Arrays.fill(this.pesos, 1.0);
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
        int ultimo = pesos.length - 1;
        invocaciones[ultimo]++;
        return operadores.get(ultimo);
    }

    public void acumular(T operador, double puntaje) {
        int i = indice(operador);
        if (i >= 0) {
            puntajes[i] += puntaje;
        }
    }

    /** Cierre de segmento: recalcula los pesos y reinicia puntajes e invocaciones. */
    public void actualizarPesos(double lambda) {
        for (int i = 0; i < pesos.length; i++) {
            double rendimiento = puntajes[i] / Math.max(1, invocaciones[i]);
            pesos[i] = lambda * pesos[i] + (1.0 - lambda) * rendimiento;
            pesos[i] = Math.max(pesos[i], 1.0E-3);
            puntajes[i] = 0.0;
            invocaciones[i] = 0;
        }
    }

    private int indice(T operador) {
        for (int i = 0; i < operadores.size(); i++) {
            if (operadores.get(i) == operador) {
                return i;
            }
        }
        return -1;
    }

    public List<T> getOperadores() {
        return operadores;
    }

    public double[] getPesos() {
        return pesos.clone();
    }

    /** Traza legible de los pesos vigentes, util para el analisis del IEN. */
    public String traza() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < operadores.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%s=%.3f", operadores.get(i), pesos[i]));
        }
        return sb.toString();
    }
}
