package pe.pucp.paqrap.planner.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.Vehiculo;

/**
 * Entrada de una ejecucion del planificador dentro del ciclo programado: el
 * estado vigente de la operacion (posicion, carga y turno de cada unidad, saldo
 * de los almacenes y bloqueos aplicados sobre la reticula) y la cartera de
 * pedidos pendientes, que incluye los nuevos de la ventana Sc y los que
 * quedaron sin planificar.
 */
public final class InstanciaPlanificacion {

    private final MapaReticula mapa;
    private final List<Almacen> almacenes;
    private final List<Vehiculo> flota;
    private final List<Pedido> pendientes;
    private final LocalDateTime instante;
    private final Configuracion cfg;

    public InstanciaPlanificacion(MapaReticula mapa, List<Almacen> almacenes, List<Vehiculo> flota,
                                  List<Pedido> pendientes, LocalDateTime instante, Configuracion cfg) {
        this.mapa = mapa;
        this.almacenes = new ArrayList<>(almacenes);
        this.flota = new ArrayList<>(flota);
        this.pendientes = new ArrayList<>(pendientes);
        this.instante = instante;
        this.cfg = cfg;
    }

    public MapaReticula getMapa() {
        return mapa;
    }

    public List<Almacen> getAlmacenes() {
        return almacenes;
    }

    public List<Vehiculo> getFlota() {
        return flota;
    }

    public List<Pedido> getPendientes() {
        return pendientes;
    }

    public LocalDateTime getInstante() {
        return instante;
    }

    public Configuracion getConfiguracion() {
        return cfg;
    }
}
