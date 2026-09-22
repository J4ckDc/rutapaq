package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contrato de salida del componente planificador: el plan vigente que consumen
 * el componente visualizador y el registro de pedidos.
 */
public final class PlanDistribucion {

    private final List<Ruta> listaRutas = new ArrayList<>();
    private final List<Pedido> pedidosAtendidos = new ArrayList<>();
    private final List<Pedido> pedidosNoAtendidos = new ArrayList<>();
    private final Map<String, Integer> estadoAlmacenes = new LinkedHashMap<>();
    private final List<TransferenciaCarga> transferencias = new ArrayList<>();
    private double costoTotalGlobal;
    private double distanciaTotalGlobalKm;
    private double indicadorPuntualidadSLA;
    private NivelSemaforo semaforoOperativo = NivelSemaforo.VERDE;
    private LocalDateTime timestampGeneracion;

    public List<Ruta> getListaRutas() {
        return listaRutas;
    }

    public List<Pedido> getPedidosAtendidos() {
        return pedidosAtendidos;
    }

    public List<Pedido> getPedidosNoAtendidos() {
        return pedidosNoAtendidos;
    }

    public Map<String, Integer> getEstadoAlmacenes() {
        return estadoAlmacenes;
    }

    public List<TransferenciaCarga> getTransferencias() {
        return transferencias;
    }

    public double getCostoTotalGlobal() {
        return costoTotalGlobal;
    }

    public void setCostoTotalGlobal(double costoTotalGlobal) {
        this.costoTotalGlobal = costoTotalGlobal;
    }

    public double getDistanciaTotalGlobalKm() {
        return distanciaTotalGlobalKm;
    }

    public void setDistanciaTotalGlobalKm(double distanciaTotalGlobalKm) {
        this.distanciaTotalGlobalKm = distanciaTotalGlobalKm;
    }

    public double getIndicadorPuntualidadSLA() {
        return indicadorPuntualidadSLA;
    }

    public void setIndicadorPuntualidadSLA(double indicadorPuntualidadSLA) {
        this.indicadorPuntualidadSLA = indicadorPuntualidadSLA;
    }

    public NivelSemaforo getSemaforoOperativo() {
        return semaforoOperativo;
    }

    public void setSemaforoOperativo(NivelSemaforo semaforoOperativo) {
        this.semaforoOperativo = semaforoOperativo;
    }

    public LocalDateTime getTimestampGeneracion() {
        return timestampGeneracion;
    }

    public void setTimestampGeneracion(LocalDateTime timestampGeneracion) {
        this.timestampGeneracion = timestampGeneracion;
    }

    @Override
    public String toString() {
        return String.format("Plan[rutas=%d, costo=S/ %.2f, dist=%.2f km, SLA=%.3f, noAtendidos=%d, %s]",
                listaRutas.size(), costoTotalGlobal, distanciaTotalGlobalKm,
                indicadorPuntualidadSLA, pedidosNoAtendidos.size(), semaforoOperativo);
    }
}
