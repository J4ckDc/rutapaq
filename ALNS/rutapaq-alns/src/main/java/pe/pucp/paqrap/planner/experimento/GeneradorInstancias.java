package pe.pucp.paqrap.planner.experimento;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.ContextoPlanificacion;
import pe.pucp.paqrap.planner.core.RedVial;
import pe.pucp.paqrap.planner.core.TipoEscenario;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.Arco;
import pe.pucp.paqrap.planner.model.Incidencia;
import pe.pucp.paqrap.planner.model.NodoRed;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.TipoAlmacen;
import pe.pucp.paqrap.planner.model.TipoNodo;
import pe.pucp.paqrap.planner.model.TipoVehiculo;
import pe.pucp.paqrap.planner.model.TurnoConductor;
import pe.pucp.paqrap.planner.model.Vehiculo;

/**
 * Generador de instancias sinteticas reproducibles para la experimentacion
 * numerica. La red vial se modela como una malla de calles de doble sentido
 * sobre coordenadas de Lima; los pedidos, la flota y las incidencias se derivan
 * de una semilla, de modo que toda corrida del IEN es repetible.
 */
public final class GeneradorInstancias {

    /** Ventanas comprometidas admitidas por la politica de PaqRap. */
    private static final int[] VENTANAS = {4, 8, 12, 18, 36};

    private final SplittableRandom rnd;
    private final Configuracion cfg;
    private final int filas;
    private final int columnas;
    private final double pasoKm;

    public GeneradorInstancias(Configuracion cfg, long semilla) {
        this.cfg = cfg;
        this.rnd = new SplittableRandom(semilla);
        this.filas = cfg.entero("instancia.filasMalla", 10);
        this.columnas = cfg.entero("instancia.columnasMalla", 10);
        this.pasoKm = cfg.numero("instancia.pasoMallaKm", 0.8);
    }

    /** Descripcion completa de una instancia lista para ser planificada. */
    public record Instancia(ContextoPlanificacion contexto,
                            List<Pedido> pedidos,
                            List<Incidencia> incidencias,
                            List<NodoRed> nodosCliente) {
    }

    public Instancia generar(TipoEscenario escenario) {
        LocalDateTime instante = LocalDate.of(2026, 9, 21).atTime(LocalTime.of(7, 0));
        RedVial red = construirMalla();

        List<NodoRed> nodos = new ArrayList<>(red.getNodos());
        NodoRed nodoCentral = red.nodo(id(filas / 2, columnas / 2));
        NodoRed nodoInt1 = red.nodo(id(1, 1));
        NodoRed nodoInt2 = red.nodo(id(filas - 2, columnas - 2));

        Almacen central = new Almacen("ALM-CENTRAL", TipoAlmacen.CENTRAL, nodoCentral, 0, 0, instante);
        Almacen inter1 = new Almacen("ALM-INT-1", TipoAlmacen.INTERMEDIO, nodoInt1,
                cfg.entero("instancia.capacidadIntermedio", 1000),
                cfg.entero("instancia.capacidadIntermedio", 1000), instante);
        Almacen inter2 = new Almacen("ALM-INT-2", TipoAlmacen.INTERMEDIO, nodoInt2,
                cfg.entero("instancia.capacidadIntermedio", 1000),
                cfg.entero("instancia.capacidadIntermedio", 1000), instante);
        List<Almacen> almacenes = List.of(central, inter1, inter2);

        List<NodoRed> nodosCliente = new ArrayList<>();
        for (NodoRed n : nodos) {
            if (!n.getIdNodo().equals(nodoCentral.getIdNodo())
                    && !n.getIdNodo().equals(nodoInt1.getIdNodo())
                    && !n.getIdNodo().equals(nodoInt2.getIdNodo())) {
                nodosCliente.add(n);
            }
        }

        List<Vehiculo> flota = construirFlota(escenario, almacenes, instante);
        List<Pedido> pedidos = construirPedidos(escenario, nodosCliente, instante);

        ContextoPlanificacion contexto = new ContextoPlanificacion(red, almacenes, flota, instante, cfg);
        return new Instancia(contexto, pedidos, new ArrayList<>(), nodosCliente);
    }

    private RedVial construirMalla() {
        RedVial red = new RedVial();
        double latBase = cfg.numero("instancia.latitudBase", -12.0464);
        double lonBase = cfg.numero("instancia.longitudBase", -77.0428);
        double dLat = pasoKm / 110.574;
        double dLon = pasoKm / (111.320 * Math.cos(Math.toRadians(latBase)));
        for (int f = 0; f < filas; f++) {
            for (int c = 0; c < columnas; c++) {
                red.agregarNodo(new NodoRed(id(f, c), TipoNodo.CLIENTE,
                        latBase + f * dLat, lonBase + c * dLon, 1.0));
            }
        }
        for (int f = 0; f < filas; f++) {
            for (int c = 0; c < columnas; c++) {
                if (c + 1 < columnas) {
                    red.agregarCalle(id(f, c), id(f, c + 1));
                }
                if (f + 1 < filas) {
                    red.agregarCalle(id(f, c), id(f + 1, c));
                }
            }
        }
        return red;
    }

    private static String id(int fila, int columna) {
        return "N" + fila + "-" + columna;
    }

    private List<Vehiculo> construirFlota(TipoEscenario escenario, List<Almacen> almacenes,
                                          LocalDateTime instante) {
        int autos = cfg.entero("instancia." + escenario + ".autos", escenario == TipoEscenario.TIEMPO_REAL ? 8 : 16);
        int motos = cfg.entero("instancia." + escenario + ".motos", escenario == TipoEscenario.TIEMPO_REAL ? 10 : 20);
        int bicis = cfg.entero("instancia." + escenario + ".bicis", escenario == TipoEscenario.TIEMPO_REAL ? 6 : 12);
        List<Vehiculo> flota = new ArrayList<>();
        agregarUnidades(flota, TipoVehiculo.AUTO, autos, almacenes, instante);
        agregarUnidades(flota, TipoVehiculo.MOTO, motos, almacenes, instante);
        agregarUnidades(flota, TipoVehiculo.BICI, bicis, almacenes, instante);
        return flota;
    }

    private void agregarUnidades(List<Vehiculo> flota, TipoVehiculo tipo, int cantidad,
                                 List<Almacen> almacenes, LocalDateTime instante) {
        for (int i = 1; i <= cantidad; i++) {
            Almacen base = almacenes.get(rnd.nextInt(almacenes.size()));
            String idVehiculo = tipo.name().charAt(0) + String.format("%03d", flota.size() + 1);
            Vehiculo v = new Vehiculo(idVehiculo, tipo, base);
            // Turno de ocho horas con inicio a las 07:00 (cambios a las 07:00, 15:00 y 23:00).
            v.setTurnoActual(new TurnoConductor("T-" + idVehiculo, "C-" + idVehiculo, idVehiculo, instante));
            flota.add(v);
        }
    }

    private List<Pedido> construirPedidos(TipoEscenario escenario, List<NodoRed> nodosCliente,
                                          LocalDateTime instante) {
        int cantidad = cfg.entero("instancia." + escenario + ".pedidos", switch (escenario) {
            case TIEMPO_REAL -> 40;
            case SIMULACION_5D -> 120;
            case COLAPSO -> 260;
        });
        int maxPaquetes = cfg.entero("instancia.maxPaquetesPedido", 8);
        double probGrande = cfg.numero("instancia.probPedidoGrande", 0.02);
        int maxPaquetesGrande = cfg.entero("instancia.maxPaquetesPedidoGrande", 40);
        List<Pedido> pedidos = new ArrayList<>();
        for (int i = 1; i <= cantidad; i++) {
            NodoRed destino = nodosCliente.get(rnd.nextInt(nodosCliente.size()));
            int paquetes = rnd.nextDouble() < probGrande
                    ? 25 + rnd.nextInt(Math.max(1, maxPaquetesGrande - 24))
                    : 1 + rnd.nextInt(maxPaquetes);
            int ventana = VENTANAS[rnd.nextInt(VENTANAS.length)];
            LocalDateTime registro = instante.minusMinutes(rnd.nextInt(120));
            String idPedido = String.format("P%04d", i);
            pedidos.addAll(fraccionar(idPedido, "CLI-" + destino.getIdNodo(), destino, paquetes,
                    registro, ventana));
        }
        return pedidos;
    }

    /**
     * Fracciona un pedido cuya cantidad excede la capacidad de la unidad de mayor
     * porte (24 paquetes del auto) en subpedidos enlazados por idPedidoPadre;
     * cada subpedido hereda la fecha limite del pedido original.
     */
    public static List<Pedido> fraccionar(String idPedido, String idCliente, NodoRed destino,
                                          int paquetes, LocalDateTime registro, int ventana) {
        List<Pedido> resultado = new ArrayList<>();
        int capacidadMaxima = TipoVehiculo.AUTO.getCapacidadMaxima();
        if (paquetes <= capacidadMaxima) {
            resultado.add(new Pedido(idPedido, idCliente, destino, paquetes, registro, ventana, null));
            return resultado;
        }
        int restante = paquetes;
        int parte = 1;
        while (restante > 0) {
            int lote = Math.min(capacidadMaxima, restante);
            resultado.add(new Pedido(idPedido + "-" + parte, idCliente, destino, lote, registro, ventana, idPedido));
            restante -= lote;
            parte++;
        }
        return resultado;
    }

    /**
     * Incidencias para la fase de reoptimizacion: bloqueos de calles sobre arcos
     * de la malla y fallas mecanicas de unidades de la flota. Se generan sin
     * consultar el plan vigente, de modo que el ALNS y el HGS/GA enfrentan
     * exactamente los mismos eventos disruptivos para una misma semilla.
     */
    public List<Incidencia> generarIncidencias(ContextoPlanificacion ctx, int bloqueos, int averias,
                                               LocalDateTime instante) {
        List<Incidencia> incidencias = new ArrayList<>();
        List<Arco> calles = ctx.getRedVial().getCalles();
        calles.sort(java.util.Comparator.comparing(Arco::toString));
        for (int i = 1; i <= bloqueos && !calles.isEmpty(); i++) {
            Arco arco = calles.remove(rnd.nextInt(calles.size()));
            incidencias.add(Incidencia.bloqueoCalle("BLQ-" + i, arco, instante,
                    cfg.numero("instancia.duracionBloqueoHoras", 2.0)));
        }
        List<Vehiculo> flota = new ArrayList<>(ctx.getFlota());
        for (int i = 1; i <= averias && !flota.isEmpty(); i++) {
            Vehiculo v = flota.remove(rnd.nextInt(flota.size()));
            incidencias.add(Incidencia.fallaMecanica("FAL-" + i, v.getIdVehiculo(), instante,
                    cfg.numero("instancia.duracionAveriaHoras", 3.0)));
        }
        return incidencias;
    }
}
