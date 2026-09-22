package pe.pucp.paqrap.planner.hgs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pe.pucp.paqrap.planner.core.ContextoPlanificacion;
import pe.pucp.paqrap.planner.core.IndicadoresPlan;
import pe.pucp.paqrap.planner.core.ResultadoFactibilidad;
import pe.pucp.paqrap.planner.core.ValidadorFactibilidad;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.EstadoVehiculo;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.Vehiculo;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/**
 * Construccion del PlanDistribucion a partir del mejor individuo. Durante la
 * evolucion las restricciones de plazo, turno, refrigerio y stock son blandas;
 * aqui se exige la factibilidad dura de cada ruta con ValidadorFactibilidad,
 * el mismo procedimiento que usa el ALNS. Si una ruta resultase infactible, se
 * retiran sus visitas pendientes desde el final, se intenta reinsertarlas por
 * menor costo en las demas rutas o en una unidad libre y, de no ser posible, se
 * declaran no atendidas.
 */
final class ConstructorPlanHGS {

    private ConstructorPlanHGS() {
    }

    static PlanDistribucion construir(DatosHGS d, Individuo mejor, ValidadorFactibilidad validador) {
        ContextoPlanificacion ctx = d.ctx;
        List<Ruta> rutas = new ArrayList<>(d.rutasFijasAveriadas);
        Set<Integer> slotsUsados = new HashSet<>();
        List<Pedido> pendientes = new ArrayList<>();

        if (mejor != null) {
            for (RutaHGS r : mejor.rutas) {
                rutas.add(rutaModelo(d, r.slot(), r.almacen(), r.paradas()));
                slotsUsados.add(r.slot());
            }
            for (int p : mejor.noAsignados) {
                pendientes.add(d.pedidos[p]);
            }
        } else {
            for (int p = 0; p < d.n; p++) {
                pendientes.add(d.pedidos[p]);
            }
        }
        for (int k = 0; k < d.K; k++) {
            if (d.slots[k].tienePrefijo() && !slotsUsados.contains(k)) {
                rutas.add(rutaModelo(d, k, d.slots[k].almacenFijo, new int[0]));
                slotsUsados.add(k);
            }
        }

        // Factibilidad dura: se retiran visitas pendientes hasta restituirla.
        Map<String, Integer> consumo = consumo(rutas);
        boolean cambio = true;
        while (cambio) {
            cambio = false;
            for (Ruta ruta : rutas) {
                if (ruta.getVehiculo().getEstado() == EstadoVehiculo.AVERIADO) {
                    continue;
                }
                ResultadoFactibilidad rf = validar(validador, ctx, ruta, consumo);
                if (!rf.factible() && ruta.tieneVisitasNoCongeladas()) {
                    VisitaCliente ultima = ruta.getListaParadas().get(ruta.getTamano() - 1);
                    ruta.remover(ultima);
                    consumo.merge(ruta.getAlmacenOrigen().getIdAlmacen(), -ultima.getPaquetesEntregados(), Integer::sum);
                    pendientes.add(ultima.getPedido());
                    cambio = true;
                }
            }
        }
        rutas.removeIf(r -> r.getListaParadas().isEmpty());

        // Reinsercion de pendientes por menor costo, en orden de urgencia.
        pendientes.sort(Comparator.comparing(Pedido::getFechaHoraLimite));
        List<Pedido> noAtendidos = new ArrayList<>();
        Set<String> vehiculosEnUso = new HashSet<>();
        for (Ruta r : rutas) {
            vehiculosEnUso.add(r.getVehiculo().getIdVehiculo());
        }
        for (Pedido p : pendientes) {
            if (!reinsertar(d, validador, rutas, consumo, vehiculosEnUso, p)) {
                noAtendidos.add(p);
            }
        }

        // Validacion final, anotacion de horarios e identificadores.
        PlanDistribucion plan = new PlanDistribucion();
        int secuencia = 0;
        for (Ruta ruta : rutas) {
            ValidadorFactibilidad.aplicarResultado(ruta, validar(validador, ctx, ruta, consumo));
            if (ruta.getIdRuta() == null || ruta.getIdRuta().startsWith("H?")) {
                ruta.setIdRuta("H" + (++secuencia));
            }
            plan.getListaRutas().add(ruta);
        }
        return IndicadoresPlan.completar(plan, ctx, noAtendidos);
    }

    private static Ruta rutaModelo(DatosHGS d, int k, int almacen, int[] paradas) {
        DatosHGS.Slot s = d.slots[k];
        Ruta ruta = new Ruta("H?", s.vehiculo, d.almacenes[almacen], s.inicioModelo);
        for (VisitaCliente c : s.prefijo) {
            ruta.getListaParadas().add(c.copia());
        }
        for (int p : paradas) {
            ruta.getListaParadas().add(new VisitaCliente(d.pedidos[p], d.paq[p]));
        }
        ruta.reindexar();
        return ruta;
    }

    private static Map<String, Integer> consumo(List<Ruta> rutas) {
        Map<String, Integer> c = new HashMap<>();
        for (Ruta r : rutas) {
            c.merge(r.getAlmacenOrigen().getIdAlmacen(), r.getCarga(), Integer::sum);
        }
        return c;
    }

    private static ResultadoFactibilidad validar(ValidadorFactibilidad v, ContextoPlanificacion ctx,
                                                 Ruta ruta, Map<String, Integer> consumo) {
        int otras = consumo.getOrDefault(ruta.getAlmacenOrigen().getIdAlmacen(), 0) - ruta.getCarga();
        return v.evaluarRuta(ruta, ctx, otras);
    }

    private static boolean reinsertar(DatosHGS d, ValidadorFactibilidad validador, List<Ruta> rutas,
                                      Map<String, Integer> consumo, Set<String> enUso, Pedido p) {
        ContextoPlanificacion ctx = d.ctx;
        Ruta mejorRuta = null;
        int mejorPos = -1;
        double mejorDelta = Double.POSITIVE_INFINITY;
        for (Ruta ruta : rutas) {
            if (ruta.getVehiculo().getEstado() == EstadoVehiculo.AVERIADO
                    || ruta.getCarga() + p.getCantidadPaquetes() > ruta.getVehiculo().getCapacidadMaxima()) {
                continue;
            }
            int otras = consumo.getOrDefault(ruta.getAlmacenOrigen().getIdAlmacen(), 0) - ruta.getCarga();
            double costoActual = validador.evaluarRuta(ruta.copia(), ctx, otras).costoSoles();
            for (int pos = ruta.primeraPosicionLibre(); pos <= ruta.getTamano(); pos++) {
                Ruta cand = ruta.copia();
                cand.insertar(pos, new VisitaCliente(p, p.getCantidadPaquetes()));
                ResultadoFactibilidad rf = validador.evaluarRuta(cand, ctx, otras);
                if (rf.factible() && rf.costoSoles() - costoActual < mejorDelta) {
                    mejorDelta = rf.costoSoles() - costoActual;
                    mejorRuta = ruta;
                    mejorPos = pos;
                }
            }
        }
        if (mejorRuta != null) {
            mejorRuta.insertar(mejorPos, new VisitaCliente(p, p.getCantidadPaquetes()));
            consumo.merge(mejorRuta.getAlmacenOrigen().getIdAlmacen(), p.getCantidadPaquetes(), Integer::sum);
            return true;
        }
        // Apertura de una ruta con una unidad libre desde el almacen de menor costo.
        Ruta nueva = null;
        double mejorCosto = Double.POSITIVE_INFINITY;
        for (DatosHGS.Slot s : d.slots) {
            Vehiculo v = s.vehiculo;
            if (enUso.contains(v.getIdVehiculo()) || p.getCantidadPaquetes() > s.capacidad) {
                continue;
            }
            for (Almacen a : d.almacenes) {
                Ruta cand = new Ruta("H?", v, a, s.inicioModelo);
                cand.insertar(0, new VisitaCliente(p, p.getCantidadPaquetes()));
                ResultadoFactibilidad rf = validador.evaluarRuta(cand, ctx, consumo.getOrDefault(a.getIdAlmacen(), 0));
                if (rf.factible() && rf.costoSoles() < mejorCosto) {
                    mejorCosto = rf.costoSoles();
                    nueva = cand;
                }
            }
        }
        if (nueva != null) {
            rutas.add(nueva);
            enUso.add(nueva.getVehiculo().getIdVehiculo());
            consumo.merge(nueva.getAlmacenOrigen().getIdAlmacen(), p.getCantidadPaquetes(), Integer::sum);
            return true;
        }
        return false;
    }
}
