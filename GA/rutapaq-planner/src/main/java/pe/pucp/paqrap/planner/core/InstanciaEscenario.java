package pe.pucp.paqrap.planner.core;

import java.util.ArrayList;
import java.util.List;

import pe.pucp.paqrap.planner.model.Incidencia;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;

/** Instancia de entrada del planificador para una invocacion concreta. */
public final class InstanciaEscenario {

    private final String idInstancia;
    private final ContextoPlanificacion contexto;
    private final List<Pedido> pedidosNuevos = new ArrayList<>();
    private final List<Incidencia> incidencias = new ArrayList<>();
    private PlanDistribucion planVigente;

    public InstanciaEscenario(String idInstancia, ContextoPlanificacion contexto,
                              List<Pedido> pedidosNuevos, List<Incidencia> incidencias,
                              PlanDistribucion planVigente) {
        this.idInstancia = idInstancia;
        this.contexto = contexto;
        this.pedidosNuevos.addAll(pedidosNuevos);
        this.incidencias.addAll(incidencias);
        this.planVigente = planVigente;
    }

    public String getIdInstancia() {
        return idInstancia;
    }

    public ContextoPlanificacion getContexto() {
        return contexto;
    }

    public List<Pedido> getPedidosNuevos() {
        return pedidosNuevos;
    }

    public List<Incidencia> getIncidencias() {
        return incidencias;
    }

    public PlanDistribucion getPlanVigente() {
        return planVigente;
    }

    public void setPlanVigente(PlanDistribucion planVigente) {
        this.planVigente = planVigente;
    }
}
