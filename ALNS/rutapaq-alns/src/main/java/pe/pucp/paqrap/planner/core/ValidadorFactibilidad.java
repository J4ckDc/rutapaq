package pe.pucp.paqrap.planner.core;

import java.time.Duration;
import java.time.LocalDateTime;

import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.EstadoVehiculo;
import pe.pucp.paqrap.planner.model.NodoRed;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TurnoConductor;
import pe.pucp.paqrap.planner.model.Vehiculo;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/**
 * Implementacion unica del procedimiento ValidarFactibilidadRuta. Concentra la
 * totalidad de las restricciones duras del caso PaqRap y es invocada tanto por
 * el ALNS como por el HGS/GA, de modo que ambas metaheuristicas produzcan
 * soluciones comparables bajo un mismo criterio de validez.
 *
 * <p>Restricciones verificadas:</p>
 * <ul>
 *   <li>R1 capacidad de la unidad (24 AUTO, 8 MOTO, 4 BICI).</li>
 *   <li>R2 saldo disponible en el almacen de origen (el central es infinito;
 *       los intermedios se restituyen a 1000 en la recarga de las 23:59:59).</li>
 *   <li>R3 recorrido factible sobre arcos vigentes (bloqueos en ambos sentidos).</li>
 *   <li>R4 hora obligatoria de alimentacion insertada dentro de la jornada.</li>
 *   <li>R5 plazo comprometido: 4, 8, 12, 18 o 36 horas, mas 1 h de servicio.</li>
 *   <li>R6 jornada laboral de ocho horas (turnos de 07:00, 15:00 y 23:00).</li>
 *   <li>R7 el refrigerio debe caber en [inicio + 1 h, fin - 1 h].</li>
 * </ul>
 *
 * <p>Efecto de borde intencional: anota en cada VisitaCliente de la ruta
 * evaluada su hora de llegada, su hora de salida y su holgura, de modo que el
 * plan resultante quede listo para el componente visualizador. Por ello se
 * invoca siempre sobre la ruta que se va a conservar o sobre una copia de
 * trabajo, nunca sobre una ruta compartida entre soluciones.</p>
 */
public final class ValidadorFactibilidad {

    private final Configuracion cfg;

    public ValidadorFactibilidad(Configuracion cfg) {
        this.cfg = cfg;
    }

    public ResultadoFactibilidad evaluarRuta(Ruta ruta, ContextoPlanificacion ctx, int consumoOtrasRutas) {
        Vehiculo vehiculo = ruta.getVehiculo();
        TurnoConductor turno = vehiculo.getTurnoActual();
        if (turno == null) {
            return ResultadoFactibilidad.infactible("R6: la unidad no tiene turno vigente");
        }
        if (vehiculo.getEstado() == EstadoVehiculo.AVERIADO) {
            return ResultadoFactibilidad.infactible("unidad averiada");
        }

        // R1. Capacidad de la unidad de transporte.
        int carga = ruta.getCarga();
        if (carga > vehiculo.getCapacidadMaxima()) {
            return ResultadoFactibilidad.infactible("R1: carga " + carga + " > capacidad "
                    + vehiculo.getCapacidadMaxima());
        }

        // R2. Disponibilidad de inventario en el almacen de origen.
        Almacen origen = ruta.getAlmacenOrigen();
        LocalDateTime inicio = ruta.getFechaHoraInicio().isBefore(turno.getHoraInicio())
                ? turno.getHoraInicio()
                : ruta.getFechaHoraInicio();
        if (!origen.isEsInventarioInfinito()) {
            int disponible = inicio.isAfter(origen.getHoraProximaRecarga())
                    ? origen.getCapacidadMaxima()
                    : origen.getCapacidadActual();
            if (consumoOtrasRutas + carga > disponible) {
                return ResultadoFactibilidad.infactible("R2: saldo insuficiente en " + origen.getIdAlmacen());
            }
        }

        RedVial red = ctx.getRedVial();
        LocalDateTime t = inicio;
        NodoRed anterior = origen.getNodo();
        double distancia = 0.0;
        double holguraMinima = Double.POSITIVE_INFINITY;
        boolean refrigerioPendiente = !turno.isRefrigerioConsumido();
        LocalDateTime inicioRefrigerio = turno.getInicioRefrigerio();

        // R3, R4 y R5. Recorrido, refrigerio y plazos comprometidos.
        for (VisitaCliente visita : ruta.getListaParadas()) {
            NodoRed destino = visita.getNodoCliente();
            if (!destino.isAccesible()) {
                return ResultadoFactibilidad.infactible("R3: nodo aislado " + destino.getIdNodo());
            }
            double km = red.distanciaKm(anterior, destino);
            if (Double.isInfinite(km)) {
                return ResultadoFactibilidad.infactible("R3: sin camino vigente hacia " + destino.getIdNodo());
            }
            distancia += km;
            t = avanzar(t, km / vehiculo.getVelocidadPromedioKmH());

            if (refrigerioPendiente && !t.isBefore(turno.getVentanaRefrigerioDesde())) {
                if (t.isAfter(turno.getVentanaRefrigerioHasta())) {
                    return ResultadoFactibilidad.infactible("R7: no cabe la hora de alimentacion");
                }
                inicioRefrigerio = t;
                t = t.plusHours(1);
                refrigerioPendiente = false;
            }

            visita.setHoraLlegadaEstimada(t);
            double holgura = horasEntre(t, visita.getPedido().getFechaHoraLimite());
            visita.setHolguraHoras(holgura);
            if (holgura < 0.0) {
                return ResultadoFactibilidad.infactible("R5: incumplimiento del plazo de "
                        + visita.getIdPedido());
            }
            holguraMinima = Math.min(holguraMinima, holgura);
            t = avanzar(t, destino.getTiempoServicioHoras());
            visita.setHoraSalidaEstimada(t);
            anterior = destino;
        }

        if (cfg.retornoAlmacen() && !ruta.getListaParadas().isEmpty()) {
            double km = red.distanciaKm(anterior, origen.getNodo());
            if (Double.isInfinite(km)) {
                return ResultadoFactibilidad.infactible("R3: sin retorno al almacen de origen");
            }
            distancia += km;
            t = avanzar(t, km / vehiculo.getVelocidadPromedioKmH());
        }

        // R7. Si la ruta termina antes de consumir el refrigerio, este debe caber en la jornada.
        if (refrigerioPendiente) {
            LocalDateTime candidato = t.isBefore(turno.getVentanaRefrigerioDesde())
                    ? turno.getVentanaRefrigerioDesde()
                    : t;
            if (candidato.isAfter(turno.getVentanaRefrigerioHasta())) {
                return ResultadoFactibilidad.infactible("R7: no cabe la hora de alimentacion en la jornada");
            }
            inicioRefrigerio = candidato;
        }

        // R6. Jornada laboral de ocho horas.
        if (t.isAfter(turno.getHoraFin())) {
            return ResultadoFactibilidad.infactible("R6: la ruta excede el fin del turno");
        }

        double tiempoHoras = horasEntre(inicio, t);
        double costo = distancia * vehiculo.getCostoPorKm();
        double holguraFinal = Double.isInfinite(holguraMinima) ? 0.0 : holguraMinima;
        return new ResultadoFactibilidad(true, "OK", holguraFinal, distancia, costo, tiempoHoras, t, inicioRefrigerio);
    }

    /** Traslada al objeto Ruta los agregados calculados por la validacion. */
    public static void aplicarResultado(Ruta ruta, ResultadoFactibilidad rf) {
        ruta.setFactible(rf.factible());
        if (rf.factible()) {
            ruta.setDistanciaTotalKm(rf.distanciaKm());
            ruta.setCostoTotalRuta(rf.costoSoles());
            ruta.setTiempoTotalHoras(rf.tiempoHoras());
            ruta.setFechaHoraFin(rf.fechaHoraFin());
            ruta.setInicioRefrigerio(rf.inicioRefrigerio());
        }
    }

    public static LocalDateTime avanzar(LocalDateTime t, double horas) {
        return t.plusSeconds(Math.round(horas * 3600.0));
    }

    public static double horasEntre(LocalDateTime desde, LocalDateTime hasta) {
        return Duration.between(desde, hasta).toSeconds() / 3600.0;
    }
}
