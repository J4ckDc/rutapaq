package pe.pucp.paqrap.planner.core;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.TurnoConductor;
import pe.pucp.paqrap.planner.model.Vehiculo;

/**
 * Instancia preprocesada e inmutable de una ejecucion del planificador: arreglos
 * de enteros y reales con los pedidos pendientes, las unidades disponibles con
 * su estado vigente, los almacenes con su saldo y la matriz local de distancias
 * sobre la reticula con los bloqueos vigentes. La comparten el ALNS y el HGS, lo
 * que garantiza que ambos resuelvan exactamente el mismo problema.
 */
public final class DatosPlanificacion {

    /** Unidad disponible con el estado con que llega a la ejecucion. */
    public static final class Unidad {
        public Vehiculo vehiculo;
        public int capacidad;
        public double velocidad;
        public double costoKm;
        public int nodo;                 // indice local de su posicion vigente
        public long inicio;              // segundos desde la base
        public int cargaInicial;
        public long horizonte;           // la ruta no puede extenderse mas alla
        public boolean refPendiente;
    }

    public Configuracion cfg;
    public LocalDateTime base;
    public MapaReticula mapa;
    public MatrizDistancias dist;

    public Pedido[] pedidos;
    public int n;
    public int[] nodoPedido;
    public int[] cantidad;
    public long[] limite;
    public double[] mu;
    public int[][] vecinos;

    public Almacen[] almacenes;
    public int nAlm;
    public int[] nodoAlmacen;
    public boolean[] infinito;
    public int[] saldo;

    public Unidad[] unidades;
    public int K;

    private DatosPlanificacion() {
    }

    public long seg(LocalDateTime t) {
        return Duration.between(base, t).getSeconds();
    }

    public LocalDateTime instanteDe(long segundos) {
        return base.plusSeconds(segundos);
    }

    /** Tiempo de viaje en segundos de un tramo de km kilometros a la velocidad de la unidad. */
    public static long viaje(int km, double velocidadKmH) {
        return Math.round(km / velocidadKmH * 3600.0);
    }

    public static DatosPlanificacion construir(InstanciaPlanificacion inst) {
        DatosPlanificacion d = new DatosPlanificacion();
        d.cfg = inst.getConfiguracion();
        d.mapa = inst.getMapa();
        d.base = inst.getInstante().toLocalDate().atStartOfDay();

        // Nodos de interes: almacenes, posiciones de las unidades y destinos pendientes.
        Map<Integer, Integer> local = new LinkedHashMap<>();
        List<Integer> nodos = new ArrayList<>();
        d.almacenes = inst.getAlmacenes().toArray(new Almacen[0]);
        d.nAlm = d.almacenes.length;
        d.nodoAlmacen = new int[d.nAlm];
        d.infinito = new boolean[d.nAlm];
        d.saldo = new int[d.nAlm];
        for (int a = 0; a < d.nAlm; a++) {
            d.nodoAlmacen[a] = registrar(d.almacenes[a].getNodo(), local, nodos);
            d.infinito[a] = d.almacenes[a].isInventarioInfinito();
            d.saldo[a] = d.infinito[a] ? Integer.MAX_VALUE : d.almacenes[a].getStock();
        }

        List<Pedido> pend = inst.getPendientes();
        d.n = pend.size();
        d.pedidos = pend.toArray(new Pedido[0]);
        d.nodoPedido = new int[d.n];
        d.cantidad = new int[d.n];
        d.limite = new long[d.n];
        d.mu = new double[d.n];
        for (int i = 0; i < d.n; i++) {
            Pedido p = d.pedidos[i];
            d.nodoPedido[i] = registrar(p.getNodo(), local, nodos);
            d.cantidad[i] = p.getCantidad();
            d.limite[i] = d.seg(p.getLimite());
            d.mu[i] = p.getPrioridad().getFactorMu();
        }

        List<Unidad> unidades = new ArrayList<>();
        for (Vehiculo v : inst.getFlota()) {
            LocalDateTime disponible = v.getDisponibleDesde().isBefore(inst.getInstante())
                    ? inst.getInstante() : v.getDisponibleDesde();
            TurnoConductor turno = v.getTurno() != null ? v.getTurno() : TurnoConductor.vigenteEn(disponible);
            Unidad u = new Unidad();
            u.vehiculo = v;
            u.capacidad = v.getCapacidad();
            u.velocidad = v.getVelocidadKmH();
            u.costoKm = v.getCostoPorKm();
            u.nodo = registrar(v.getNodoActual(), local, nodos);
            u.inicio = d.seg(disponible);
            u.cargaInicial = v.getCarga();
            u.horizonte = u.inicio + 3600L * d.cfg.entero("planificador.horizonteRutaHoras", 8);
            u.refPendiente = !turno.isRefrigerioConsumido();
            unidades.add(u);
        }
        d.unidades = unidades.toArray(new Unidad[0]);
        d.K = d.unidades.length;

        int[] interes = new int[nodos.size()];
        for (int i = 0; i < interes.length; i++) {
            interes[i] = nodos.get(i);
        }
        d.dist = MatrizDistancias.construir(inst.getMapa(), interes);

        // Vecindario granular: los Gamma pedidos mas cercanos a cada pedido.
        int gamma = Math.min(d.cfg.vecinosGranulares(), Math.max(0, d.n - 1));
        d.vecinos = new int[d.n][];
        Integer[] orden = new Integer[d.n];
        for (int i = 0; i < d.n; i++) {
            final int u = i;
            for (int j = 0; j < d.n; j++) {
                orden[j] = j;
            }
            Arrays.sort(orden, Comparator.comparingInt(j -> {
                int km = d.dist.km(d.nodoPedido[u], d.nodoPedido[j]);
                return km < 0 ? Integer.MAX_VALUE : km;
            }));
            int[] vec = new int[gamma];
            int c = 0;
            for (int j = 0; j < d.n && c < gamma; j++) {
                if (orden[j] != u) {
                    vec[c++] = orden[j];
                }
            }
            d.vecinos[i] = vec;
        }
        return d;
    }

    private static int registrar(int nodo, Map<Integer, Integer> local, List<Integer> nodos) {
        Integer i = local.get(nodo);
        if (i != null) {
            return i;
        }
        local.put(nodo, nodos.size());
        nodos.add(nodo);
        return nodos.size() - 1;
    }
}
