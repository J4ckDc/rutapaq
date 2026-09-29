package pe.pucp.paqrap.planner.core;

import java.util.ArrayList;
import java.util.List;

import pe.pucp.paqrap.planner.model.ParadaRuta;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;

/**
 * Representacion de una solucion del planificador, compartida por el ALNS y el
 * HGS: la secuencia de entregas de cada unidad, los pedidos que quedan sin
 * planificar y el consumo proyectado de cada almacen. Mantiene de forma
 * incremental el costo de cada ruta y expone las operaciones de insercion y de
 * remocion que ambos algoritmos necesitan.
 *
 * <p>El costo de una ruta lo valoriza el Penalizador: en modo duro (ALNS) una
 * ruta con cualquier violacion es inaceptable y la insercion se rechaza; en modo
 * blando (HGS) la violacion se penaliza.</p>
 */
public final class SolucionRutas {

    private static final int[] VACIA = new int[0];

    private final DatosPlanificacion d;
    private final Penalizador pen;
    private final int[][] rutas;
    private final ResultadoRuta[] eval;
    private final double[] costoRuta;
    private final int[] unidadDe;
    private final int[] consumoTotal;
    private double costoRutas;

    private SolucionRutas(DatosPlanificacion d, Penalizador pen) {
        this.d = d;
        this.pen = pen;
        this.rutas = new int[d.K][];
        this.eval = new ResultadoRuta[d.K];
        this.costoRuta = new double[d.K];
        this.unidadDe = new int[d.n];
        this.consumoTotal = new int[d.nAlm];
        java.util.Arrays.fill(unidadDe, -1);
        for (int k = 0; k < d.K; k++) {
            rutas[k] = VACIA;
            eval[k] = EvaluadorItinerario.evaluar(d, k, VACIA, d.saldo);
            costoRuta[k] = 0.0;
        }
    }

    public static SolucionRutas vacia(DatosPlanificacion d, Penalizador pen) {
        return new SolucionRutas(d, pen);
    }

    public SolucionRutas copiar() {
        SolucionRutas c = new SolucionRutas(d, pen);
        for (int k = 0; k < d.K; k++) {
            c.rutas[k] = rutas[k].clone();
            c.eval[k] = eval[k];
            c.costoRuta[k] = costoRuta[k];
        }
        System.arraycopy(unidadDe, 0, c.unidadDe, 0, unidadDe.length);
        System.arraycopy(consumoTotal, 0, c.consumoTotal, 0, consumoTotal.length);
        c.costoRutas = costoRutas;
        return c;
    }

    public DatosPlanificacion datos() {
        return d;
    }

    public Penalizador penalizador() {
        return pen;
    }

    public int[] secuencia(int unidad) {
        return rutas[unidad];
    }

    public ResultadoRuta evaluacion(int unidad) {
        return eval[unidad];
    }

    /** Costo valorizado de la ruta de una unidad (con penalizaciones en modo blando). */
    public double costoRuta(int unidad) {
        return costoRuta[unidad];
    }

    public int unidadDe(int pedido) {
        return unidadDe[pedido];
    }

    public boolean asignado(int pedido) {
        return unidadDe[pedido] >= 0;
    }

    public int numAsignados() {
        int c = 0;
        for (int p = 0; p < d.n; p++) {
            if (unidadDe[p] >= 0) {
                c++;
            }
        }
        return c;
    }

    public int numNoAsignados() {
        return d.n - numAsignados();
    }

    public List<Integer> noAsignados() {
        List<Integer> libres = new ArrayList<>();
        for (int p = 0; p < d.n; p++) {
            if (unidadDe[p] < 0) {
                libres.add(p);
            }
        }
        return libres;
    }

    /** Costo de las rutas mas la penalizacion de los pedidos que quedan sin planificar. */
    public double costoTotal() {
        double castigo = 0.0;
        for (int p = 0; p < d.n; p++) {
            if (unidadDe[p] < 0) {
                castigo += pen.getNoAtendido() * d.mu[p];
            }
        }
        return costoRutas + castigo;
    }

    public double costoRutas() {
        return costoRutas;
    }

    /** Violaciones acumuladas (0 en modo duro, donde solo se aceptan rutas factibles). */
    public double atrasoTotal() {
        double s = 0.0;
        for (ResultadoRuta r : eval) {
            s += r.atrasoPonderadoH();
        }
        return s;
    }

    public double violacionTurnoTotal() {
        double s = 0.0;
        for (ResultadoRuta r : eval) {
            s += r.violacionTurno();
        }
        return s;
    }

    public boolean factible() {
        for (ResultadoRuta r : eval) {
            if (!r.valida() || !r.factible()) {
                return false;
            }
        }
        return true;
    }

    /** Saldo de cada almacen disponible para la unidad indicada (descuenta a las demas). */
    public int[] disponiblePara(int unidad) {
        int[] disp = new int[d.nAlm];
        for (int a = 0; a < d.nAlm; a++) {
            disp[a] = d.infinito[a] ? Integer.MAX_VALUE
                    : d.saldo[a] - (consumoTotal[a] - eval[unidad].consumo()[a]);
        }
        return disp;
    }

    public ResultadoRuta evaluarCon(int unidad, int[] secuencia) {
        return EvaluadorItinerario.evaluar(d, unidad, secuencia, disponiblePara(unidad));
    }

    private void aplicar(int unidad, int[] secuencia, ResultadoRuta r) {
        for (int a = 0; a < d.nAlm; a++) {
            consumoTotal[a] += r.consumo()[a] - eval[unidad].consumo()[a];
        }
        costoRutas += pen.costo(r) - costoRuta[unidad];
        rutas[unidad] = secuencia;
        eval[unidad] = r;
        costoRuta[unidad] = pen.costo(r);
        for (int i = 0; i < secuencia.length; i++) {
            unidadDe[secuencia[i]] = unidad;
        }
    }

    /** Inserta el pedido en la posicion indicada si la ruta resultante es aceptable. */
    public boolean insertar(int pedido, int unidad, int pos) {
        int[] nueva = insertarEn(rutas[unidad], pos, pedido);
        ResultadoRuta r = evaluarCon(unidad, nueva);
        if (!pen.aceptable(r)) {
            return false;
        }
        aplicar(unidad, nueva, r);
        return true;
    }

    public boolean remover(int pedido) {
        int unidad = unidadDe[pedido];
        if (unidad < 0) {
            return false;
        }
        int[] actual = rutas[unidad];
        int pos = indiceDe(actual, pedido);
        int[] nueva = quitarEn(actual, pos);
        ResultadoRuta r = evaluarCon(unidad, nueva);
        unidadDe[pedido] = -1;
        aplicar(unidad, nueva, r);
        return true;
    }

    /** Reemplaza por completo la secuencia de una unidad (usado por el Split del HGS). */
    public void fijarSecuencia(int unidad, int[] secuencia) {
        for (int p : rutas[unidad]) {
            if (unidadDe[p] == unidad) {
                unidadDe[p] = -1;          // el pedido pudo migrar ya a otra unidad
            }
        }
        ResultadoRuta r = evaluarCon(unidad, secuencia);
        aplicar(unidad, secuencia, r);
    }

    /**
     * Mejor insercion del pedido: {unidad, posicion} o null si ninguna es
     * aceptable. Primero evalua de forma exacta los pares preseleccionados por
     * el diferencial aproximado de kilometros y, si ninguno resulta aceptable,
     * recorre de forma exhaustiva todas las unidades y posiciones: asi la poda
     * acelera el caso habitual sin descartar la unica insercion viable de un
     * pedido con plazo ajustado.
     */
    public int[] mejorInsercion(int pedido) {
        int[] mejor = null;
        double mejorDelta = Double.POSITIVE_INFINITY;
        for (int[] c : candidatas(pedido, d.cfg.candidatosInsercion())) {
            double delta = deltaInsercion(pedido, c[0], c[1]);
            if (delta < mejorDelta) {
                mejorDelta = delta;
                mejor = new int[]{c[0], c[1]};
            }
        }
        if (mejor != null) {
            return mejor;
        }
        for (int u = 0; u < d.K; u++) {
            if (d.cantidad[pedido] > d.unidades[u].capacidad) {
                continue;
            }
            for (int pos = 0; pos <= rutas[u].length; pos++) {
                double delta = deltaInsercion(pedido, u, pos);
                if (delta < mejorDelta) {
                    mejorDelta = delta;
                    mejor = new int[]{u, pos};
                }
            }
        }
        return mejor;
    }

    /** Las k mejores inserciones aceptables, ordenadas por costo marginal. */
    public List<double[]> kMejoresInserciones(int pedido, int k) {
        List<double[]> res = new ArrayList<>();
        for (int[] c : candidatas(pedido, Math.max(k, d.cfg.candidatosInsercion()))) {
            double delta = deltaInsercion(pedido, c[0], c[1]);
            if (delta < Double.POSITIVE_INFINITY) {
                res.add(new double[]{delta, c[0], c[1]});
            }
        }
        if (res.isEmpty()) {
            for (int u = 0; u < d.K; u++) {
                if (d.cantidad[pedido] > d.unidades[u].capacidad) {
                    continue;
                }
                for (int pos = 0; pos <= rutas[u].length; pos++) {
                    double delta = deltaInsercion(pedido, u, pos);
                    if (delta < Double.POSITIVE_INFINITY) {
                        res.add(new double[]{delta, u, pos});
                    }
                }
            }
        }
        res.sort((a, b) -> Double.compare(a[0], b[0]));
        // Una sola alternativa por unidad, para que el arrepentimiento compare unidades distintas.
        List<double[]> filtradas = new ArrayList<>();
        boolean[] vista = new boolean[d.K];
        for (double[] r : res) {
            int u = (int) r[1];
            if (!vista[u]) {
                vista[u] = true;
                filtradas.add(r);
            }
            if (filtradas.size() == k) {
                break;
            }
        }
        return filtradas;
    }

    /** Costo marginal exacto de insertar el pedido; infinito si la ruta no resulta aceptable. */
    public double deltaInsercion(int pedido, int unidad, int pos) {
        int[] nueva = insertarEn(rutas[unidad], pos, pedido);
        ResultadoRuta r = evaluarCon(unidad, nueva);
        if (!pen.aceptable(r)) {
            return Double.POSITIVE_INFINITY;
        }
        return pen.costo(r) - costoRuta[unidad];
    }

    /**
     * Preseleccion de pares (unidad, posicion) por el diferencial aproximado de
     * kilometros, para no evaluar de forma exacta todas las posiciones de todas
     * las unidades en las instancias grandes.
     */
    private List<int[]> candidatas(int pedido, int limite) {
        double[] mejores = new double[limite];
        int[][] pares = new int[limite][];
        java.util.Arrays.fill(mejores, Double.POSITIVE_INFINITY);
        int nodoP = d.nodoPedido[pedido];
        for (int u = 0; u < d.K; u++) {
            if (d.cantidad[pedido] > d.unidades[u].capacidad) {
                continue;
            }
            int[] seq = rutas[u];
            for (int pos = 0; pos <= seq.length; pos++) {
                int anterior = pos == 0 ? d.unidades[u].nodo : d.nodoPedido[seq[pos - 1]];
                int ida = d.dist.km(anterior, nodoP);
                if (ida < 0) {
                    continue;
                }
                double aprox = ida;
                if (pos < seq.length) {
                    int siguiente = d.nodoPedido[seq[pos]];
                    int vuelta = d.dist.km(nodoP, siguiente);
                    int directo = d.dist.km(anterior, siguiente);
                    if (vuelta < 0) {
                        continue;
                    }
                    aprox = ida + vuelta - Math.max(0, directo);
                }
                aprox *= d.unidades[u].costoKm;
                int peor = 0;
                for (int i = 1; i < limite; i++) {
                    if (mejores[i] > mejores[peor]) {
                        peor = i;
                    }
                }
                if (aprox < mejores[peor]) {
                    mejores[peor] = aprox;
                    pares[peor] = new int[]{u, pos};
                }
            }
        }
        List<int[]> res = new ArrayList<>();
        for (int i = 0; i < limite; i++) {
            if (pares[i] != null) {
                res.add(pares[i]);
            }
        }
        return res;
    }

    /** Ahorro en soles de retirar un pedido de su ruta. */
    public double ahorroRemocion(int pedido) {
        int unidad = unidadDe[pedido];
        if (unidad < 0) {
            return 0.0;
        }
        int[] nueva = quitarEn(rutas[unidad], indiceDe(rutas[unidad], pedido));
        ResultadoRuta r = evaluarCon(unidad, nueva);
        if (!r.valida()) {
            return 0.0;
        }
        return costoRuta[unidad] - pen.costo(r);
    }

    /**
     * Retira de cada ruta las entregas que no alcanzan su plazo o que exceden el
     * horizonte, hasta dejar solo rutas factibles. Los pedidos retirados vuelven
     * a la cartera pendiente y se replanifican en la siguiente ejecucion: una
     * entrega fuera de plazo no evita el colapso y solo consume capacidad.
     */
    public int podarEntregasTardias() {
        int retirados = 0;
        for (int u = 0; u < d.K; u++) {
            while (rutas[u].length > 0 && (!eval[u].valida() || !eval[u].factible())) {
                int[] seq = rutas[u];
                long[] llegadas = EvaluadorItinerario.llegadas(d, u, seq, disponiblePara(u));
                int peor = seq.length - 1;
                long peorHolgura = Long.MAX_VALUE;
                for (int i = 0; i < seq.length; i++) {
                    long holgura = llegadas[i] == Long.MAX_VALUE
                            ? Long.MIN_VALUE : d.limite[seq[i]] - llegadas[i];
                    if (holgura < peorHolgura) {
                        peorHolgura = holgura;
                        peor = i;
                    }
                }
                remover(seq[peor]);
                retirados++;
            }
        }
        return retirados;
    }

    /** Construye el plan con el itinerario expandido de cada unidad. */
    public PlanDistribucion construirPlan(java.time.LocalDateTime instante) {
        PlanDistribucion plan = new PlanDistribucion();
        int secuencia = 0;
        for (int u = 0; u < d.K; u++) {
            if (rutas[u].length == 0) {
                continue;
            }
            List<ParadaRuta> itinerario = new ArrayList<>();
            ResultadoRuta r = EvaluadorItinerario.evaluar(d, u, rutas[u], disponiblePara(u), itinerario);
            Ruta ruta = new Ruta("R" + (++secuencia), d.unidades[u].vehiculo);
            for (int p : rutas[u]) {
                ruta.getPedidos().add(d.pedidos[p]);
            }
            ruta.getItinerario().addAll(itinerario);
            ruta.setDistanciaKm(r.km());
            ruta.setCosto(r.costo());
            ruta.setViajes(r.recargas());
            ruta.setFactible(r.factible());
            plan.getRutas().add(ruta);
        }
        List<Pedido> sinPlanificar = new ArrayList<>();
        for (int p = 0; p < d.n; p++) {
            if (unidadDe[p] < 0) {
                sinPlanificar.add(d.pedidos[p]);
            }
        }
        return IndicadoresPlan.completar(plan, d, sinPlanificar, instante);
    }

    public static int[] insertarEn(int[] s, int pos, int valor) {
        int[] r = new int[s.length + 1];
        System.arraycopy(s, 0, r, 0, pos);
        r[pos] = valor;
        System.arraycopy(s, pos, r, pos + 1, s.length - pos);
        return r;
    }

    public static int[] quitarEn(int[] s, int pos) {
        int[] r = new int[s.length - 1];
        System.arraycopy(s, 0, r, 0, pos);
        System.arraycopy(s, pos + 1, r, pos, s.length - pos - 1);
        return r;
    }

    public static int indiceDe(int[] s, int valor) {
        for (int i = 0; i < s.length; i++) {
            if (s[i] == valor) {
                return i;
            }
        }
        return -1;
    }
}
