package pe.pucp.paqrap.planner.ien;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pe.pucp.paqrap.planner.core.CalendarioBloqueos;
import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.InstanciaPlanificacion;
import pe.pucp.paqrap.planner.core.MapaReticula;
import pe.pucp.paqrap.planner.core.Planificador;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.EstadoPedido;
import pe.pucp.paqrap.planner.model.ParadaRuta;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TipoAlmacen;
import pe.pucp.paqrap.planner.model.TipoParada;
import pe.pucp.paqrap.planner.model.TipoVehiculo;
import pe.pucp.paqrap.planner.model.TurnoConductor;
import pe.pucp.paqrap.planner.model.Vehiculo;

/**
 * Simulador del esquema de planificacion programada y detector de colapso.
 *
 * <p>Cada Sc de tiempo simulado invoca al planificador con el estado vigente de
 * la operacion (posicion, carga y turno de cada unidad, saldo de los almacenes y
 * bloqueos vigentes), los pedidos nuevos de la ventana y los pendientes; mide Ta
 * y avanza la operacion segun el plan, recalculando cada tramo con los bloqueos
 * realmente vigentes en el instante de salida.</p>
 *
 * <p>La corrida colapsa en el primer instante en que ocurre cualquiera de estas
 * situaciones: <b>P</b>, vence el plazo de un producto sin que haya sido
 * entregado; <b>C</b>, una ejecucion del planificador tarda Ta &ge; Sa. Si el
 * reloj alcanza t0 + H sin colapso, la corrida termina como no colapsada (N).
 * El detector es independiente del algoritmo evaluado.</p>
 */
public final class SimuladorColapso {

    private final Configuracion cfg;
    private final MapaReticula mapa;
    private final int horizonteHoras;
    private final int scMinutos;
    private final double saSegundos;
    private final boolean verboso;

    private final Map<Integer, int[]> cacheDistancias = new HashMap<>();
    private int versionCache = -1;

    public SimuladorColapso(Configuracion cfg, MapaReticula mapa) {
        this.cfg = cfg;
        this.mapa = mapa;
        this.horizonteHoras = cfg.entero("ien.horizonteHoras", 48);
        this.scMinutos = cfg.entero("ien.scMinutos", 60);
        this.saSegundos = cfg.numero("ien.saSegundos", 30.0);
        this.verboso = cfg.bandera("ien.verboso", false);
    }

    public ResultadoCorrida correr(Bloque bloque, Planificador planificador) {
        List<Almacen> almacenes = crearAlmacenes();
        List<Vehiculo> flota = crearFlota(almacenes.get(0), bloque.t0());
        Map<String, LocalDateTime> turnoDe = new HashMap<>();
        Map<String, Boolean> refrigerioConsumido = new HashMap<>();
        CalendarioBloqueos calendario = bloque.bloqueos();

        List<Pedido> cartera = bloque.pedidos();
        Set<Pedido> pendientes = new LinkedHashSet<>();
        int siguiente = 0;
        int productosEntregados = 0;
        int pedidosEntregados = 0;

        LocalDateTime t0 = bloque.t0();
        LocalDateTime fin = t0.plusHours(horizonteHoras);
        LocalDateTime reloj = t0;
        LocalDateTime ultimaRecarga = t0.minusDays(1);

        double tcHoras = horizonteHoras;
        String causa = "N";
        long taMax = 0;
        long taSuma = 0;
        int ejecuciones = 0;

        while (reloj.isBefore(fin)) {
            LocalDateTime finVentana = reloj.plusMinutes(scMinutos);
            // Pedidos registrados hasta el instante de la ejecucion.
            while (siguiente < cartera.size() && !cartera.get(siguiente).getRegistro().isAfter(reloj)) {
                pendientes.add(cartera.get(siguiente++));
            }
            // Recarga instantanea de los almacenes intermedios a las 23:59:59.
            LocalDateTime recarga = reloj.toLocalDate().atTime(Almacen.HORA_RECARGA);
            if (!recarga.isAfter(reloj) && recarga.isAfter(ultimaRecarga)) {
                almacenes.forEach(Almacen::recargar);
                ultimaRecarga = recarga;
            }
            aplicarBloqueos(calendario, reloj);
            List<Vehiculo> disponibles = new ArrayList<>();
            for (Vehiculo v : flota) {
                LocalDateTime desde = v.getDisponibleDesde().isBefore(reloj) ? reloj : v.getDisponibleDesde();
                TurnoConductor turno = TurnoConductor.vigenteEn(desde);
                LocalDateTime previo = turnoDe.get(v.getId());
                if (previo == null || !previo.equals(turno.getInicio())) {
                    turnoDe.put(v.getId(), turno.getInicio());
                    refrigerioConsumido.put(v.getId(), false);
                }
                turno.setRefrigerioConsumido(Boolean.TRUE.equals(refrigerioConsumido.get(v.getId())));
                v.setTurno(turno);
                v.setDisponibleDesde(desde);
                disponibles.add(v);
            }

            // Ejecucion del planificador y medicion de Ta (incluida la preparacion).
            List<Pedido> lista = new ArrayList<>(pendientes);
            long ini = System.nanoTime();
            PlanDistribucion plan = planificador.planificar(new InstanciaPlanificacion(
                    mapa, almacenes, disponibles, lista, reloj, cfg));
            long taMs = (System.nanoTime() - ini) / 1_000_000L;
            ejecuciones++;
            taMax = Math.max(taMax, taMs);
            taSuma += taMs;
            if (taMs >= saSegundos * 1000.0) {
                causa = "C";
                tcHoras = horas(t0, reloj);
                break;
            }

            // Avance de la operacion simulada durante la ventana.
            int[] entregas = ejecutarPlan(plan, calendario, almacenes, pendientes, reloj, finVentana,
                    refrigerioConsumido);
            productosEntregados += entregas[0];
            pedidosEntregados += entregas[1];

            if (verboso) {
                System.out.printf(java.util.Locale.US,
                        "  %s | pend=%3d rutas=%2d planif=%3d sinPlan=%3d entregas=%3d Ta=%4d ms%n",
                        reloj, pendientes.size() + entregas[1], plan.getRutas().size(),
                        plan.getPlanificados().size(), plan.getNoPlanificados().size(), entregas[1], taMs);
            }

            // Verificacion de vencimientos al cierre de la ventana.
            LocalDateTime vencimiento = null;
            Pedido culpable = null;
            for (Pedido p : pendientes) {
                if (!p.getLimite().isAfter(finVentana)
                        && (vencimiento == null || p.getLimite().isBefore(vencimiento))) {
                    vencimiento = p.getLimite();
                    culpable = p;
                }
            }
            if (vencimiento != null && verboso) {
                System.out.println("  COLAPSO P por " + culpable.getId() + " reg=" + culpable.getRegistro()
                        + " limite=" + culpable.getLimite() + " cant=" + culpable.getCantidad()
                        + " nodo=(" + culpable.getX() + "," + culpable.getY() + ")");
            }
            if (vencimiento != null) {
                causa = "P";
                tcHoras = Math.min(horizonteHoras, horas(t0, vencimiento));
                break;
            }
            reloj = finVentana;
        }

        return new ResultadoCorrida(bloque.definicion().numero(), planificador.nombre(),
                bloque.productosPorDia(), tcHoras, causa, taMax,
                ejecuciones == 0 ? 0 : taSuma / (double) ejecuciones, ejecuciones,
                productosEntregados, pedidosEntregados, cartera.size());
    }

    /** Ejecuta el plan hasta el cierre de la ventana; devuelve {productos, pedidos} entregados. */
    private int[] ejecutarPlan(PlanDistribucion plan, CalendarioBloqueos calendario, List<Almacen> almacenes,
                               Set<Pedido> pendientes, LocalDateTime inicio, LocalDateTime finVentana,
                               Map<String, Boolean> refrigerioConsumido) {
        int productos = 0;
        int pedidos = 0;
        Map<String, Pedido> porId = new HashMap<>();
        for (Pedido p : pendientes) {
            porId.put(p.getId(), p);
        }
        for (Ruta ruta : plan.getRutas()) {
            Vehiculo v = ruta.getVehiculo();
            LocalDateTime relojUnidad = v.getDisponibleDesde().isBefore(inicio) ? inicio : v.getDisponibleDesde();
            for (ParadaRuta parada : ruta.getItinerario()) {
                if (!relojUnidad.isBefore(finVentana)) {
                    break;                                   // la unidad continuara en la proxima ventana
                }
                aplicarBloqueos(calendario, relojUnidad);
                int km = distancia(v.getNodoActual(), parada.nodo());
                if (km < 0) {
                    break;                                   // tramo sin camino vigente: se descarta el resto
                }
                relojUnidad = relojUnidad.plusSeconds(Math.round(km / v.getVelocidadKmH() * 3600.0));
                v.setNodoActual(parada.nodo());
                if (parada.tipo() == TipoParada.RECARGA) {
                    Almacen a = buscar(almacenes, parada.referencia());
                    int carga = a.isInventarioInfinito() ? v.getCapacidad()
                            : Math.min(v.getCapacidad(), a.getStock());
                    a.retirar(carga);
                    v.setCarga(carga);
                } else if (parada.tipo() == TipoParada.REFRIGERIO) {
                    relojUnidad = relojUnidad.plusHours(1);
                    refrigerioConsumido.put(v.getId(), true);
                } else {
                    Pedido p = porId.get(parada.referencia());
                    if (p == null || v.getCarga() < p.getCantidad()) {
                        break;                               // sin carga suficiente: el resto se replanifica
                    }
                    p.setEstado(EstadoPedido.ENTREGADO);
                    p.setHoraEntrega(relojUnidad);
                    pendientes.remove(p);
                    productos += p.getCantidad();
                    pedidos++;
                    v.setCarga(v.getCarga() - p.getCantidad());
                    relojUnidad = relojUnidad.plusHours(1);  // acondicionamiento
                }
            }
            v.setDisponibleDesde(relojUnidad);
        }
        return new int[]{productos, pedidos};
    }

    private void aplicarBloqueos(CalendarioBloqueos calendario, LocalDateTime instante) {
        calendario.aplicarEn(mapa, instante);
        if (mapa.getVersion() != versionCache) {
            cacheDistancias.clear();
            versionCache = mapa.getVersion();
        }
    }

    private int distancia(int origen, int destino) {
        int[] d = cacheDistancias.computeIfAbsent(origen, mapa::distanciasDesde);
        return d[destino];
    }

    private List<Almacen> crearAlmacenes() {
        List<Almacen> l = new ArrayList<>();
        l.add(almacen("ALM-CENTRAL", TipoAlmacen.CENTRAL, "ien.almacenCentral", "27,14", 0));
        l.add(almacen("ALM-NOROESTE", TipoAlmacen.INTERMEDIO, "ien.almacenNorOeste", "12,38",
                cfg.entero("ien.capacidadIntermedio", 1000)));
        l.add(almacen("ALM-ESTE", TipoAlmacen.INTERMEDIO, "ien.almacenEste", "57,27",
                cfg.entero("ien.capacidadIntermedio", 1000)));
        return l;
    }

    private Almacen almacen(String id, TipoAlmacen tipo, String clave, String pordefecto, int capacidad) {
        String[] xy = cfg.texto(clave, pordefecto).split(",");
        int x = Integer.parseInt(xy[0].trim());
        int y = Integer.parseInt(xy[1].trim());
        return new Almacen(id, tipo, mapa.indice(x, y), x, y, capacidad);
    }

    private List<Vehiculo> crearFlota(Almacen central, LocalDateTime t0) {
        List<Vehiculo> flota = new ArrayList<>();
        agregar(flota, TipoVehiculo.AUTO, cfg.entero("ien.autos", 10), central, t0);
        agregar(flota, TipoVehiculo.MOTO, cfg.entero("ien.motos", 15), central, t0);
        agregar(flota, TipoVehiculo.BICI, cfg.entero("ien.bicicletas", 12), central, t0);
        return flota;
    }

    private void agregar(List<Vehiculo> flota, TipoVehiculo tipo, int cantidad, Almacen central,
                         LocalDateTime t0) {
        for (int i = 1; i <= cantidad; i++) {
            flota.add(new Vehiculo(tipo.name().charAt(0) + String.format("%03d", flota.size() + 1),
                    tipo, central.getNodo(), t0));
        }
    }

    private static Almacen buscar(List<Almacen> almacenes, String id) {
        for (Almacen a : almacenes) {
            if (a.getId().equals(id)) {
                return a;
            }
        }
        throw new IllegalStateException("Almacen desconocido: " + id);
    }

    private static double horas(LocalDateTime desde, LocalDateTime hasta) {
        return Duration.between(desde, hasta).toSeconds() / 3600.0;
    }
}
