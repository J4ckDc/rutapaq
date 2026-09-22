package pe.pucp.paqrap.planner.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.Vehiculo;

/** Estado observable de la operacion en el instante en que se invoca al planificador. */
public final class ContextoPlanificacion {

    private final RedVial redVial;
    private final Map<String, Almacen> almacenes = new LinkedHashMap<>();
    private final List<Vehiculo> flota = new ArrayList<>();
    private final Configuracion cfg;
    private LocalDateTime instante;

    public ContextoPlanificacion(RedVial redVial, List<Almacen> almacenes, List<Vehiculo> flota,
                                 LocalDateTime instante, Configuracion cfg) {
        this.redVial = redVial;
        for (Almacen a : almacenes) {
            this.almacenes.put(a.getIdAlmacen(), a);
        }
        this.flota.addAll(flota);
        this.instante = instante;
        this.cfg = cfg;
    }

    public RedVial getRedVial() {
        return redVial;
    }

    public Almacen almacen(String id) {
        return almacenes.get(id);
    }

    public List<Almacen> getAlmacenes() {
        return new ArrayList<>(almacenes.values());
    }

    public List<Vehiculo> getFlota() {
        return flota;
    }

    public Vehiculo vehiculo(String id) {
        for (Vehiculo v : flota) {
            if (v.getIdVehiculo().equals(id)) {
                return v;
            }
        }
        return null;
    }

    public LocalDateTime getInstante() {
        return instante;
    }

    public void setInstante(LocalDateTime instante) {
        this.instante = instante;
    }

    public Configuracion getConfiguracion() {
        return cfg;
    }
}
