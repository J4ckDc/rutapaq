package pe.pucp.paqrap.planner.hgs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.InstanciaEscenario;
import pe.pucp.paqrap.planner.core.Planificador;
import pe.pucp.paqrap.planner.core.ValidadorFactibilidad;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/**
 * Algoritmo Genetico Hibrido (Hybrid Genetic Search, HGS/GA) para el componente
 * planificador de PaqRap.
 *
 * <p>Ciclo evolutivo (HGS_PAQRAP_PLANIFICAR): poblacion inicial diversificada de
 * 4N individuos; en cada generacion, seleccion por torneo binario, cruce ordenado
 * OX con probabilidad pc, herencia del gen de almacen, mutacion con probabilidad
 * pm, decodificacion por Split heterogeneo y educacion por busqueda local; los
 * descendientes infactibles se someten con probabilidad 0.5 a una reparacion
 * dirigida con penalizaciones multiplicadas por diez. La supervivencia usa
 * aptitud sesgada (costo + diversidad) y las penalizaciones se ajustan de forma
 * adaptativa segun la proporcion de descendientes factibles.</p>
 *
 * <p>La generacion y la educacion de los descendientes se ejecuta por lotes en
 * paralelo (parallelStream) con generadores aleatorios independientes derivados
 * mediante SplittableRandom.split(); el tamano del lote es un parametro fijo, de
 * modo que la corrida es reproducible para una semilla dada con independencia
 * del numero de nucleos del equipo.</p>
 */
public final class HGS_PAQRAP implements Planificador {

    private record Tarea(Individuo p1, Individuo p2, SplittableRandom rnd) {
    }

    private final Configuracion cfg;
    private final ValidadorFactibilidad validador;
    private final ParametrosHGS par;
    private final SplittableRandom rnd;

    private DatosHGS datos;
    private Penalizaciones pen;
    private SplitHeterogeneo split;
    private BusquedaLocalEducacion educacion;
    private int generaciones;
    /** Mejor individuo y datos de la ultima corrida (diagnostico y pruebas del paquete). */
    Individuo ultimoMejor;
    DatosHGS ultimosDatos;
    private long tiempoUltimaCorridaMs;

    public HGS_PAQRAP(Configuracion cfg) {
        this(cfg, new ValidadorFactibilidad(cfg), cfg.semilla());
    }

    public HGS_PAQRAP(Configuracion cfg, ValidadorFactibilidad validador, long semilla) {
        this.cfg = cfg;
        this.validador = validador;
        this.par = ParametrosHGS.desde(cfg);
        this.rnd = new SplittableRandom(semilla);
    }

    @Override
    public String nombre() {
        return "HGS";
    }

    @Override
    public int iteracionesEjecutadas() {
        return generaciones;
    }

    @Override
    public long tiempoUltimaCorridaMs() {
        return tiempoUltimaCorridaMs;
    }

    @Override
    public PlanDistribucion resolver(InstanciaEscenario inst) {
        final long tIni = System.nanoTime();
        final long limiteNanos = (long) (cfg.limiteTiempoSegundos() * 1_000_000_000L);
        generaciones = 0;

        datos = DatosHGS.construir(inst.getContexto(), inst.getPlanVigente(), inst.getPedidosNuevos(),
                inst.getIncidencias(), cfg, par.vecinosGranulares());
        pen = new Penalizaciones(par);
        split = new SplitHeterogeneo(datos, par.maxParadasSplit());
        educacion = new BusquedaLocalEducacion(datos, par.maxPasadasEducacion());

        if (datos.n == 0 || datos.K == 0) {
            tiempoUltimaCorridaMs = (System.nanoTime() - tIni) / 1_000_000L;
            return ConstructorPlanHGS.construir(datos, null, validador);
        }

        // 01-07. Poblacion inicial diversificada de 4N individuos.
        Poblacion pob = new Poblacion(par);
        List<Individuo> iniciales = new ArrayList<>();
        Individuo semilla = semillaDesdePlanVigente(inst.getPlanVigente());
        if (semilla != null) {
            iniciales.add(semilla);
        }
        for (int i = iniciales.size(); i < 4 * par.tamanoPoblacion(); i++) {
            iniciales.add(individuoAleatorioSesgado(i));
        }
        Individuo mejor = null;
        long limiteInicial = (long) (limiteNanos * par.fraccionTiempoInicial());
        for (int desde = 0; desde < iniciales.size(); desde += par.loteParalelo()) {
            List<Tarea> lote = new ArrayList<>();
            for (Individuo ind : iniciales.subList(desde, Math.min(iniciales.size(), desde + par.loteParalelo()))) {
                lote.add(new Tarea(ind, null, rnd.split()));
            }
            lote.parallelStream().forEach(t -> decodificarYEducar(t.p1(), t.rnd()));
            for (Tarea t : lote) {
                pob.insertar(t.p1());
                mejor = elegirMejor(mejor, t.p1());
            }
            if (System.nanoTime() - tIni > limiteInicial) {
                break;
            }
        }

        // 09-40. Ciclo generacional con reproduccion paralela por lotes.
        int sinMejora = 0;
        while (generaciones < par.maxGeneraciones() && sinMejora < par.genSinMejora()
                && System.nanoTime() - tIni < limiteNanos) {
            pob.actualizarAptitudes();
            List<Tarea> lote = new ArrayList<>(par.loteParalelo());
            for (int k = 0; k < par.loteParalelo(); k++) {
                lote.add(new Tarea(pob.torneoBinario(rnd), pob.torneoBinario(rnd), rnd.split()));
            }
            List<Individuo> hijos = lote.parallelStream().map(this::generarHijo).toList();
            for (Individuo h : hijos) {
                pob.insertar(h);
                generaciones++;
                Individuo previo = mejor;
                mejor = elegirMejor(mejor, h);
                sinMejora = mejor != previo ? 0 : sinMejora + 1;
                if (pen.registrar(h.factible)) {
                    pen.ajustar();
                    pob.reevaluar(pen);
                }
            }
        }

        // 41. Plan de distribucion con factibilidad dura verificada.
        ultimoMejor = mejor;
        ultimosDatos = datos;
        PlanDistribucion plan = ConstructorPlanHGS.construir(datos, mejor, validador);
        tiempoUltimaCorridaMs = (System.nanoTime() - tIni) / 1_000_000L;
        return plan;
    }

    /** generarHijo: cruce OX, herencia de almacen, mutacion, Split y educacion (fase paralela). */
    private Individuo generarHijo(Tarea t) {
        SplittableRandom r = t.rnd();
        int[] pi;
        int[] gamma;
        int[] sigma;
        if (r.nextDouble() < par.pc()) {
            OperadoresGeneticos.ResultadoOX ox = OperadoresGeneticos.cruceOX(t.p1().pi, t.p2().pi, r);
            pi = ox.hijo();
            gamma = OperadoresGeneticos.heredarAlmacen(ox.delPadre1(), t.p1().gamma, t.p2().gamma);
            sigma = OperadoresGeneticos.cruceOX(t.p1().sigma, t.p2().sigma, r).hijo();
        } else {
            pi = t.p1().pi.clone();
            gamma = t.p1().gamma.clone();
            sigma = t.p1().sigma.clone();
        }
        Individuo hijo = new Individuo(pi, gamma, sigma);
        if (r.nextDouble() < par.pm()) {
            OperadoresGeneticos.mutar(hijo, datos, r);
        }
        decodificarYEducar(hijo, r);
        return hijo;
    }

    private void decodificarYEducar(Individuo ind, SplittableRandom r) {
        split.decodificar(ind, pen);
        ind.evaluar(datos, pen);
        if (r.nextDouble() < par.pEducacion()) {
            educacion.educar(ind, pen, 1.0, r);
            if (!ind.factible && r.nextDouble() < 0.5) {
                educacion.educar(ind, pen, par.factorReparacion(), r);      // reparacion dirigida
            }
        } else {
            ind.reconstruirCromosoma(datos);
        }
        ind.evaluar(datos, pen);
    }

    /** Solo un individuo factible puede reemplazar a la mejor solucion factible. */
    private Individuo elegirMejor(Individuo actual, Individuo candidato) {
        if (actual == null) {
            return candidato;
        }
        if (candidato.factible != actual.factible) {
            return candidato.factible ? candidato : actual;
        }
        double a = actual.factible ? actual.objetivo(pen) : actual.fitness;
        double c = candidato.factible ? candidato.objetivo(pen) : candidato.fitness;
        return c < a - 1.0E-6 ? candidato : actual;
    }

    /**
     * INDIVIDUO_ALEATORIO_SESGADO: una fraccion de las permutaciones se construye
     * ordenando los pedidos por fecha limite o por angulo polar respecto del almacen
     * central; el resto se genera de forma uniforme para asegurar la diversidad
     * inicial. El orden de las unidades alterna entre menor costo por kilometro,
     * mayor capacidad y aleatorio.
     */
    private Individuo individuoAleatorioSesgado(int i) {
        DatosHGS d = datos;
        Integer[] orden = new Integer[d.n];
        for (int p = 0; p < d.n; p++) {
            orden[p] = p;
        }
        switch (i % 4) {
            case 0 -> Arrays.sort(orden, Comparator.comparingLong(p -> d.limite[p]));
            case 1 -> {
                double giro = rnd.nextDouble() * 2 * Math.PI;
                Arrays.sort(orden, Comparator.comparingDouble(p -> (d.angulo[p] + giro) % (2 * Math.PI)));
            }
            default -> barajar(orden);
        }
        int[] pi = Arrays.stream(orden).mapToInt(Integer::intValue).toArray();
        int[] gamma = new int[d.n];
        for (int p = 0; p < d.n; p++) {
            gamma[p] = rnd.nextDouble() < 0.8 ? d.almacenCercano[p] : rnd.nextInt(d.nAlm);
        }
        Integer[] unidades = new Integer[d.K];
        for (int k = 0; k < d.K; k++) {
            unidades[k] = k;
        }
        switch (i % 3) {
            case 0 -> Arrays.sort(unidades, Comparator.comparingDouble(k -> d.slots[k].costoKm));
            case 1 -> Arrays.sort(unidades, Comparator.comparingInt(k -> -d.slots[k].capacidad));
            default -> barajar(unidades);
        }
        return new Individuo(pi, gamma, Arrays.stream(unidades).mapToInt(Integer::intValue).toArray());
    }

    /** En la reoptimizacion, la poblacion se siembra con el plan vigente (visitas pendientes). */
    private Individuo semillaDesdePlanVigente(PlanDistribucion vigente) {
        if (vigente == null) {
            return null;
        }
        DatosHGS d = datos;
        java.util.Map<String, Integer> idxPedido = new java.util.HashMap<>();
        for (int p = 0; p < d.n; p++) {
            idxPedido.put(d.pedidos[p].getIdPedido(), p);
        }
        java.util.Map<String, Integer> idxSlot = new java.util.HashMap<>();
        for (int k = 0; k < d.K; k++) {
            idxSlot.put(d.slots[k].vehiculo.getIdVehiculo(), k);
        }
        List<Integer> pi = new ArrayList<>();
        List<Integer> sigma = new ArrayList<>();
        boolean[] enPi = new boolean[d.n];
        boolean[] enSigma = new boolean[d.K];
        int[] gamma = d.almacenCercano.clone();
        for (Ruta r : vigente.getListaRutas()) {
            Integer k = idxSlot.get(r.getVehiculo().getIdVehiculo());
            if (k == null) {
                continue;
            }
            for (VisitaCliente v : r.getListaParadas()) {
                Integer p = idxPedido.get(v.getIdPedido());
                if (p != null && !enPi[p]) {
                    pi.add(p);
                    enPi[p] = true;
                    for (int a = 0; a < d.nAlm; a++) {
                        if (d.almacenes[a] == r.getAlmacenOrigen()) {
                            gamma[p] = a;
                        }
                    }
                }
            }
            if (!enSigma[k]) {
                sigma.add(k);
                enSigma[k] = true;
            }
        }
        for (int p = 0; p < d.n; p++) {
            if (!enPi[p]) {
                pi.add(p);
            }
        }
        for (int k = 0; k < d.K; k++) {
            if (!enSigma[k]) {
                sigma.add(k);
            }
        }
        return new Individuo(pi.stream().mapToInt(Integer::intValue).toArray(), gamma,
                sigma.stream().mapToInt(Integer::intValue).toArray());
    }

    private <T> void barajar(T[] a) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            T t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }
}
