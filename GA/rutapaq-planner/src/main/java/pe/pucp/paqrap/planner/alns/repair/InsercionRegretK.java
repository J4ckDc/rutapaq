package pe.pucp.paqrap.planner.alns.repair;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.alns.EstadoALNS;
import pe.pucp.paqrap.planner.alns.Insercion;
import pe.pucp.paqrap.planner.alns.OperadorRepair;
import pe.pucp.paqrap.planner.model.Pedido;

/**
 * Insercion por arrepentimiento de orden k ponderada por la prioridad del
 * pedido. El arrepentimiento es la suma de las diferencias entre cada
 * alternativa y la mejor, multiplicada por mu(p) (5 para ventanas de 4 y 8 h,
 * 3 para 12 y 18 h, 1 para 36 h). Un pedido con menos de k alternativas
 * factibles acumula una penalizacion fija por cada alternativa faltante, de modo
 * que los pedidos con una sola opcion viable se insertan primero.
 *
 * <p>Si ningun pedido del banco admite insercion, el operador intenta atender al
 * mas critico mediante reasignacion de carga en transito; de no lograrlo, el
 * banco pasa a pedidos no atendidos, lo que en el escenario de estres constituye
 * la senal de colapso logistico.</p>
 */
public final class InsercionRegretK implements OperadorRepair {

    @Override
    public String nombre() {
        return "RegretKInsertion";
    }

    @Override
    public void reparar(EstadoALNS estado, List<Pedido> banco, RandomGenerator rnd) {
        final int k = estado.getConfiguracion().kRegret();
        final double penalizacionFaltante = estado.getConfiguracion().penalizacionSinAlternativa();
        int reasignacionesPermitidas = banco.size();

        while (!banco.isEmpty()) {
            Pedido elegido = null;
            Insercion insElegida = null;
            double maxRegret = Double.NEGATIVE_INFINITY;

            for (Pedido p : banco) {
                List<Insercion> top = estado.kMejoresInserciones(p, k);
                if (top.isEmpty()) {
                    continue;
                }
                double regret = (k - top.size()) * penalizacionFaltante;
                for (int h = 1; h < top.size(); h++) {
                    regret += top.get(h).deltaCosto() - top.get(0).deltaCosto();
                }
                regret *= p.getPrioridad().getFactorMu();
                if (regret > maxRegret
                        || (regret == maxRegret && elegido != null
                            && p.getFechaHoraLimite().isBefore(elegido.getFechaHoraLimite()))) {
                    maxRegret = regret;
                    elegido = p;
                    insElegida = top.get(0);
                }
            }

            if (elegido == null) {
                Pedido critico = banco.stream()
                        .min(Comparator.comparing(Pedido::getFechaHoraLimite))
                        .orElseThrow();
                Optional<Pedido> desplazado = reasignacionesPermitidas-- > 0
                        ? estado.intentarReasignacionEnTransito(critico)
                        : Optional.empty();
                if (desplazado.isEmpty()) {
                    banco.forEach(estado::marcarNoAtendido);
                    banco.clear();
                    return;
                }
                banco.remove(critico);
                banco.add(desplazado.get());
                continue;
            }

            if (!estado.aplicar(insElegida)) {
                estado.marcarNoAtendido(elegido);
            }
            banco.remove(elegido);
        }
    }

    @Override
    public String toString() {
        return nombre();
    }
}
