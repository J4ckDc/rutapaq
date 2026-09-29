package pe.pucp.paqrap.planner.hgs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.DatosPlanificacion;
import pe.pucp.paqrap.planner.core.InstanciaPlanificacion;
import pe.pucp.paqrap.planner.core.Penalizador;
import pe.pucp.paqrap.planner.core.Planificador;
import pe.pucp.paqrap.planner.core.SolucionRutas;
import pe.pucp.paqrap.planner.model.PlanDistribucion;

/**
 * Algoritmo Genetico Hibrido (Hybrid Genetic Search, HGS/GA) para el componente
 * planificador de PaqRap.
 *
 * <p>Ciclo evolutivo: poblacion inicial diversificada, seleccion por torneo
 * binario sobre aptitud sesgada, cruce ordenado OX sobre la gran ruta y sobre el
 * orden de unidades, mutacion, decodificacion por Split heterogeneo y educacion
 * por busqueda local; los descendientes infactibles se someten con probabilidad
 * 0.5 a una reparacion dirigida con penalizaciones multiplicadas por diez. Las
 * penalizaciones de plazo y jornada se ajustan de forma adaptativa segun la
 * proporcion de descendientes factibles.</p>
 *
 * <p>Los descendientes se generan por lotes de tamano fijo con parallelStream() y
 * un generador por tarea derivado con SplittableRandom.split(), de modo que la
 * corrida es reproducible con independencia del numero de nucleos. La busqueda
 * se detiene al agotar el presupuesto de computo P y devuelve el mejor
 * individuo factible hallado.</p>
 */
public final class HGS_PAQRAP implements Planificador {

    private record Tarea(Individuo p1, Individuo p2, SplittableRandom rnd) {
    }

    private final Configuracion cfg;
    private final ParametrosHGS par;
    private final SplittableRandom rnd;

    private DatosPlanificacion datos;
    private Penalizador pen;
    private SplitHeterogeneo split;
    private BusquedaLocalEducacion educacion;
    private int generaciones;
    private long tiempoBusquedaMs;

    public HGS_PAQRAP(Configuracion cfg, long semilla) {
        this.cfg = cfg;
        this.par = ParametrosHGS.desde(cfg);
        this.rnd = new SplittableRandom(semilla);
    }

    @Override
    public String nombre() {
        return "GA";
    }

    @Override
    public int iteracionesEjecutadas() {
        return generaciones;
    }

    @Override
    public long tiempoBusquedaMs() {
        return tiempoBusquedaMs;
    }

    @Override
    public PlanDistribucion planificar(InstanciaPlanificacion instancia) {
        final long tIni = System.nanoTime();
        final long limiteNanos = (long) (cfg.presupuestoSegundos() * 1_000_000_000L);
        generaciones = 0;

        datos = DatosPlanificacion.construir(instancia);
        pen = new Penalizador(par.omegaSLA(), par.omegaTurno(), cfg.omegaNoPlanificado(), false);
        split = new SplitHeterogeneo(datos, par.maxParadasSplit());
        educacion = new BusquedaLocalEducacion(datos, par.maxPasadasEducacion(), par.maxEvaluacionesEducacion());
        PenalizacionesAdaptativas adaptacion = new PenalizacionesAdaptativas(par);

        if (datos.n == 0 || datos.K == 0) {
            tiempoBusquedaMs = (System.nanoTime() - tIni) / 1_000_000L;
            return SolucionRutas.vacia(datos, pen).construirPlan(instancia.getInstante());
        }

        // Poblacion inicial diversificada.
        Poblacion pob = new Poblacion(par);
        Individuo mejor = null;
        long limiteInicial = (long) (limiteNanos * par.fraccionTiempoInicial());
        List<Individuo> iniciales = new ArrayList<>();
        for (int i = 0; i < 4 * par.tamanoPoblacion(); i++) {
            iniciales.add(individuoSesgado(i));
        }
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

        // Ciclo generacional con reproduccion en paralelo por lotes.
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
                if (adaptacion.registrar(h.factible)) {
                    adaptacion.ajustar(pen);
                    pob.reevaluar();
                }
            }
        }

        // El plan entregado solo contiene entregas dentro de plazo: la busqueda
        // admite soluciones penalizadas, pero el plan que ejecuta la operacion no.
        SolucionRutas resultado = mejor != null ? mejor.solucion : SolucionRutas.vacia(datos, pen);
        resultado.podarEntregasTardias();
        PlanDistribucion plan = resultado.construirPlan(instancia.getInstante());
        tiempoBusquedaMs = (System.nanoTime() - tIni) / 1_000_000L;
        return plan;
    }

    private Individuo generarHijo(Tarea t) {
        SplittableRandom r = t.rnd();
        int[] pi;
        int[] sigma;
        if (r.nextDouble() < par.pc()) {
            pi = OperadoresGeneticos.cruceOX(t.p1().pi, t.p2().pi, r);
            sigma = OperadoresGeneticos.cruceOX(t.p1().sigma, t.p2().sigma, r);
        } else {
            pi = t.p1().pi.clone();
            sigma = t.p1().sigma.clone();
        }
        Individuo hijo = new Individuo(pi, sigma);
        if (r.nextDouble() < par.pm()) {
            OperadoresGeneticos.mutar(hijo, r);
        }
        decodificarYEducar(hijo, r);
        return hijo;
    }

    private void decodificarYEducar(Individuo ind, SplittableRandom r) {
        ind.solucion = split.decodificar(ind, pen);
        if (r.nextDouble() < par.pEducacion()) {
            educacion.educar(ind.solucion, r);
            ind.reconstruirCromosoma(datos);
        }
        ind.evaluar(datos);
        if (!ind.factible && r.nextDouble() < 0.5) {
            Penalizador severo = new Penalizador(pen.getSla() * par.factorReparacion(),
                    pen.getTurno() * par.factorReparacion(), pen.getNoAtendido(), false);
            SolucionRutas reparada = copiaCon(ind.solucion, severo);
            new BusquedaLocalEducacion(datos, par.maxPasadasEducacion(), par.maxEvaluacionesEducacion())
                    .educar(reparada, r);
            SolucionRutas devuelta = copiaCon(reparada, pen);
            if (devuelta.costoTotal() < ind.solucion.costoTotal() || (!ind.solucion.factible() && devuelta.factible())) {
                ind.solucion = devuelta;
                ind.reconstruirCromosoma(datos);
                ind.evaluar(datos);
            }
        }
    }

    /** Copia la solucion cambiando el valorizador (reparacion dirigida con penalizaciones x10). */
    private SolucionRutas copiaCon(SolucionRutas s, Penalizador otro) {
        SolucionRutas c = SolucionRutas.vacia(datos, otro);
        for (int u = 0; u < datos.K; u++) {
            if (s.secuencia(u).length > 0) {
                c.fijarSecuencia(u, s.secuencia(u).clone());
            }
        }
        return c;
    }

    /** Solo un individuo factible puede desplazar a la mejor solucion factible. */
    private Individuo elegirMejor(Individuo actual, Individuo candidato) {
        if (actual == null) {
            return candidato;
        }
        if (candidato.factible != actual.factible) {
            return candidato.factible ? candidato : actual;
        }
        double a = actual.factible ? actual.objetivo : actual.fitness;
        double c = candidato.factible ? candidato.objetivo : candidato.fitness;
        return c < a - 1.0E-6 ? candidato : actual;
    }

    /**
     * Individuos iniciales sesgados: una fraccion ordena los pedidos por fecha
     * limite y otra por cercania al almacen central; el resto se genera de forma
     * uniforme. El orden de unidades alterna entre menor costo por kilometro,
     * mayor capacidad y aleatorio.
     */
    private Individuo individuoSesgado(int i) {
        DatosPlanificacion d = datos;
        Integer[] orden = new Integer[d.n];
        for (int p = 0; p < d.n; p++) {
            orden[p] = p;
        }
        switch (i % 3) {
            case 0 -> Arrays.sort(orden, Comparator.comparingLong(p -> d.limite[p]));
            case 1 -> Arrays.sort(orden, Comparator.comparingInt(p -> {
                int km = d.dist.km(d.nodoAlmacen[0], d.nodoPedido[p]);
                return km < 0 ? Integer.MAX_VALUE : km;
            }));
            default -> barajar(orden);
        }
        Integer[] unidades = new Integer[d.K];
        for (int k = 0; k < d.K; k++) {
            unidades[k] = k;
        }
        switch (i % 3) {
            case 0 -> Arrays.sort(unidades, Comparator.comparingDouble(k -> d.unidades[k].costoKm));
            case 1 -> Arrays.sort(unidades, Comparator.comparingInt(k -> -d.unidades[k].capacidad));
            default -> barajar(unidades);
        }
        return new Individuo(Arrays.stream(orden).mapToInt(Integer::intValue).toArray(),
                Arrays.stream(unidades).mapToInt(Integer::intValue).toArray());
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
