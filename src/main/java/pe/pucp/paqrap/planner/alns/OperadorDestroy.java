package pe.pucp.paqrap.planner.alns;

import java.util.List;
import java.util.random.RandomGenerator;

import pe.pucp.paqrap.planner.core.SolucionRutas;

/** Operador de destruccion: retira q pedidos planificados y los devuelve al banco. */
public interface OperadorDestroy {

    String nombre();

    List<Integer> destruir(SolucionRutas s, int q, RandomGenerator rnd);
}
