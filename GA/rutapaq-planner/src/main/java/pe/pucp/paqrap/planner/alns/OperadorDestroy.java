package pe.pucp.paqrap.planner.alns;

import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.model.Pedido;

/**
 * Operador de destruccion: remueve q pedidos de la solucion actual y los
 * devuelve al banco. Solo puede operar sobre visitas no congeladas.
 */
public interface OperadorDestroy {

    String nombre();

    List<Pedido> destruir(EstadoALNS estado, int q, RandomGenerator rnd);
}
