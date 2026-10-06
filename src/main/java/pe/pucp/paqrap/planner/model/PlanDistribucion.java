package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plan que el planificador entrega en cada ejecucion del ciclo programado. */
public final class PlanDistribucion {

    private final List<Ruta> rutas = new ArrayList<>();
    private final List<Pedido> planificados = new ArrayList<>();
    private final List<Pedido> noPlanificados = new ArrayList<>();
    private final Map<String, Integer> consumoAlmacenes = new LinkedHashMap<>();
    private double costoTotal;
    private double distanciaTotalKm;
    private double indicadorPuntualidadSLA;
    private NivelSemaforo semaforo = NivelSemaforo.VERDE;
    private LocalDateTime timestamp;

    public List<Ruta> getRutas() {
        return rutas;
    }

    public List<Pedido> getPlanificados() {
        return planificados;
    }

    public List<Pedido> getNoPlanificados() {
        return noPlanificados;
    }

    public Map<String, Integer> getConsumoAlmacenes() {
        return consumoAlmacenes;
    }

    public double getCostoTotal() {
        return costoTotal;
    }

    public void setCostoTotal(double costoTotal) {
        this.costoTotal = costoTotal;
    }

    public double getDistanciaTotalKm() {
        return distanciaTotalKm;
    }

    public void setDistanciaTotalKm(double distanciaTotalKm) {
        this.distanciaTotalKm = distanciaTotalKm;
    }

    public double getIndicadorPuntualidadSLA() {
        return indicadorPuntualidadSLA;
    }

    public void setIndicadorPuntualidadSLA(double indicadorPuntualidadSLA) {
        this.indicadorPuntualidadSLA = indicadorPuntualidadSLA;
    }

    public NivelSemaforo getSemaforo() {
        return semaforo;
    }

    public void setSemaforo(NivelSemaforo semaforo) {
        this.semaforo = semaforo;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return String.format("Plan[rutas=%d, costo=S/ %.2f, dist=%.1f km, planificados=%d, pendientes=%d]",
                rutas.size(), costoTotal, distanciaTotalKm, planificados.size(), noPlanificados.size());
    }
}
