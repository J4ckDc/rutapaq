package pe.pucp.paqrap.planner.alns;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import pe.pucp.paqrap.planner.core.Configuracion;
import pe.pucp.paqrap.planner.core.ContextoPlanificacion;
import pe.pucp.paqrap.planner.core.RedVial;
import pe.pucp.paqrap.planner.core.ResultadoFactibilidad;
import pe.pucp.paqrap.planner.core.ValidadorFactibilidad;
import pe.pucp.paqrap.planner.model.Almacen;
import pe.pucp.paqrap.planner.model.EstadoPedido;
import pe.pucp.paqrap.planner.model.EstadoVehiculo;
import pe.pucp.paqrap.planner.model.NivelSemaforo;
import pe.pucp.paqrap.planner.model.NodoRed;
import pe.pucp.paqrap.planner.model.Pedido;
import pe.pucp.paqrap.planner.model.PlanDistribucion;
import pe.pucp.paqrap.planner.model.Ruta;
import pe.pucp.paqrap.planner.model.TipoAlmacen;
import pe.pucp.paqrap.planner.model.TransferenciaCarga;
import pe.pucp.paqrap.planner.model.Vehiculo;
import pe.pucp.paqrap.planner.model.VisitaCliente;

/**
 * Representacion interna de una solucion del ALNS: el conjunto de rutas
 * vigentes, el banco de pedidos no atendidos y el saldo proyectado de cada
 * almacen. Mantiene el costo operativo de forma incremental (solo se reevalua la
 * ruta modificada) y expone construirPlan() para producir un PlanDistribucion
 * de estructura identica al que entrega el HGS/GA, lo que hace comparables
 * ambos algoritmos en el IEN.
 */
public final class EstadoALNS {

    private final ContextoPlanificacion contexto;
    private final ValidadorFactibilidad validador;
    private final Configuracion cfg;
    private final List<Ruta> rutas = new ArrayList<>();
    private final List<Pedido> pedidosNoAtendidos = new ArrayList<>();
    private final Map<String, Integer> consumoPorAlmacen = new HashMap<>();
    private final Set<String> vehiculosOcupados = new HashSet<>();
    private final List<TransferenciaCarga> transferencias = new ArrayList<>();
    private final int[] secuenciaRuta;
    private double costoOperativo;

    private EstadoALNS(ContextoPlanificacion contexto, ValidadorFactibilidad validador,
                       int[] secuenciaRuta) {
        this.contexto = contexto;
        this.validador = validador;
        this.cfg = contexto.getConfiguracion();
        this.secuenciaRuta = secuenciaRuta;
    }

    public static EstadoALNS inicial(ContextoPlanificacion contexto, ValidadorFactibilidad validador) {
        return new EstadoALNS(contexto, validador, new int[]{0});
    }

    /** Construye el estado a partir del plan vigente (entrada de la reoptimizacion rodante). */
    public static EstadoALNS desde(PlanDistribucion plan, ContextoPlanificacion contexto,
                                   ValidadorFactibilidad validador) {
        EstadoALNS estado = inicial(contexto, validador);
        if (plan == null) {
            return estado;
        }
        int maxId = 0;
        for (Ruta ruta : plan.getListaRutas()) {
            Ruta copia = ruta.copia();
            estado.rutas.add(copia);
            estado.vehiculosOcupados.add(copia.getVehiculo().getIdVehiculo());
            estado.consumoPorAlmacen.merge(copia.getAlmacenOrigen().getIdAlmacen(), copia.getCarga(), Integer::sum);
            try {
                maxId = Math.max(maxId, Integer.parseInt(copia.getIdRuta().substring(1)));
            } catch (RuntimeException ignorada) {
                // identificador no numerico: se conserva el contador actual
            }
        }
        // Segunda pasada: el consumo por almacen ya esta completo, por lo que la
        // reevaluacion de cada ruta verifica el saldo con el estado real del plan.
        for (Ruta copia : estado.rutas) {
            estado.recalcular(copia);
            estado.costoOperativo += copia.getCostoTotalRuta();
        }
        estado.secuenciaRuta[0] = maxId;
        estado.pedidosNoAtendidos.addAll(plan.getPedidosNoAtendidos());
        estado.transferencias.addAll(plan.getTransferencias());
        return estado;
    }

    public EstadoALNS copiar() {
        EstadoALNS c = new EstadoALNS(contexto, validador, secuenciaRuta);
        for (Ruta r : rutas) {
            c.rutas.add(r.copia());
        }
        c.pedidosNoAtendidos.addAll(pedidosNoAtendidos);
        c.consumoPorAlmacen.putAll(consumoPorAlmacen);
        c.vehiculosOcupados.addAll(vehiculosOcupados);
        c.transferencias.addAll(transferencias);
        c.costoOperativo = costoOperativo;
        return c;
    }

    // ------------------------------------------------------------------
    // Funcion objetivo
    // ------------------------------------------------------------------

    /** Costo operativo por kilometro recorrido, diferenciado por tipo de unidad. */
    public double costoOperativo() {
        return costoOperativo;
    }

    /**
     * f(s) = costo operativo + omegaUrgencia * suma de mu(p) sobre los pedidos
     * no atendidos. Al ser el plazo una restriccion dura, la penalizacion de
     * urgencia actua sobre la cobertura y no sobre holguras negativas.
     */
    public double costoTotal() {
        double penalizacion = 0.0;
        for (Pedido p : pedidosNoAtendidos) {
            penalizacion += cfg.omegaUrgencia() * p.getPrioridad().getFactorMu();
        }
        return costoOperativo + penalizacion;
    }

    public int numNoAtendidos() {
        return pedidosNoAtendidos.size();
    }

    public List<Pedido> getPedidosNoAtendidos() {
        return pedidosNoAtendidos;
    }

    public List<Ruta> getRutas() {
        return rutas;
    }

    public ContextoPlanificacion getContexto() {
        return contexto;
    }

    public Configuracion getConfiguracion() {
        return cfg;
    }

    public List<TransferenciaCarga> getTransferencias() {
        return transferencias;
    }

    public List<Ruta> rutasModificables() {
        List<Ruta> modificables = new ArrayList<>();
        for (Ruta r : rutas) {
            if (r.getVehiculo().getEstado() != EstadoVehiculo.AVERIADO) {
                modificables.add(r);
            }
        }
        return modificables;
    }

    public List<VisitaUbicada> visitasLibres() {
        List<VisitaUbicada> libres = new ArrayList<>();
        for (Ruta r : rutasModificables()) {
            for (VisitaCliente v : r.getListaParadas()) {
                if (!v.isCongelada()) {
                    libres.add(new VisitaUbicada(r, v));
                }
            }
        }
        return libres;
    }

    public int numVisitasLibres() {
        int n = 0;
        for (Ruta r : rutas) {
            n += r.visitasNoCongeladas().size();
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Operaciones estructurales
    // ------------------------------------------------------------------

    /** Remueve una visita no congelada y devuelve su pedido al banco. */
    public Pedido remover(Ruta ruta, VisitaCliente visita) {
        if (visita.isCongelada() || !ruta.remover(visita)) {
            return null;
        }
        costoOperativo -= ruta.getCostoTotalRuta();
        consumoPorAlmacen.merge(ruta.getAlmacenOrigen().getIdAlmacen(),
                -visita.getPaquetesEntregados(), Integer::sum);
        if (ruta.getListaParadas().isEmpty()) {
            rutas.remove(ruta);
            vehiculosOcupados.remove(ruta.getVehiculo().getIdVehiculo());
        } else {
            recalcular(ruta);
            costoOperativo += ruta.getCostoTotalRuta();
        }
        return visita.getPedido();
    }

    /** Aplica una insercion previamente evaluada; revierte si resultase infactible. */
    public boolean aplicar(Insercion ins) {
        Ruta ruta = ins.ruta();
        Pedido pedido = ins.pedido();
        String idAlmacen = ruta.getAlmacenOrigen().getIdAlmacen();
        VisitaCliente visita = new VisitaCliente(pedido, pedido.getCantidadPaquetes());
        double costoPrevio = ruta.getCostoTotalRuta();

        ruta.insertar(ins.posicion(), visita);
        if (ins.rutaNueva()) {
            ruta.setIdRuta(siguienteIdRuta());
            rutas.add(ruta);
            vehiculosOcupados.add(ruta.getVehiculo().getIdVehiculo());
        } else {
            costoOperativo -= costoPrevio;
        }
        consumoPorAlmacen.merge(idAlmacen, visita.getPaquetesEntregados(), Integer::sum);

        ResultadoFactibilidad rf = recalcular(ruta);
        if (!rf.factible()) {
            ruta.remover(visita);
            consumoPorAlmacen.merge(idAlmacen, -visita.getPaquetesEntregados(), Integer::sum);
            if (ins.rutaNueva()) {
                rutas.remove(ruta);
                vehiculosOcupados.remove(ruta.getVehiculo().getIdVehiculo());
            } else {
                recalcular(ruta);
                costoOperativo += ruta.getCostoTotalRuta();
            }
            return false;
        }
        costoOperativo += ruta.getCostoTotalRuta();
        pedidosNoAtendidos.remove(pedido);
        return true;
    }

    public void marcarNoAtendido(Pedido pedido) {
        if (!pedidosNoAtendidos.contains(pedido)) {
            pedidosNoAtendidos.add(pedido);
        }
    }

    public void registrar(TransferenciaCarga transferencia) {
        transferencias.add(transferencia);
    }

    /** Reevalua una ruta vigente tras un cambio del entorno (bloqueo o averia). */
    public ResultadoFactibilidad revalidar(Ruta ruta) {
        costoOperativo -= ruta.getCostoTotalRuta();
        ResultadoFactibilidad rf = recalcular(ruta);
        costoOperativo += ruta.getCostoTotalRuta();
        return rf;
    }

    /** Ruta en ejecucion de una unidad, o null si la unidad no tiene ruta asignada. */
    public Ruta rutaDe(String idVehiculo) {
        for (Ruta r : rutas) {
            if (r.getVehiculo().getIdVehiculo().equals(idVehiculo)) {
                return r;
            }
        }
        return null;
    }

    /** Rutas cuyo recorrido emplea alguno de los arcos indicados. */
    public List<Ruta> rutasQueAtraviesan(java.util.Collection<pe.pucp.paqrap.planner.model.Arco> arcos) {
        List<Ruta> afectadas = new ArrayList<>();
        for (Ruta r : rutas) {
            NodoRed anterior = r.getAlmacenOrigen().getNodo();
            boolean tocada = false;
            for (VisitaCliente v : r.getListaParadas()) {
                if (arcos.contains(pe.pucp.paqrap.planner.model.Arco.de(
                        anterior.getIdNodo(), v.getNodoCliente().getIdNodo()))) {
                    tocada = true;
                    break;
                }
                anterior = v.getNodoCliente();
            }
            if (tocada) {
                afectadas.add(r);
            }
        }
        return afectadas;
    }

    private ResultadoFactibilidad recalcular(Ruta ruta) {
        ResultadoFactibilidad rf = validador.evaluarRuta(ruta, contexto, consumoOtrasRutas(ruta));
        ValidadorFactibilidad.aplicarResultado(ruta, rf);
        return rf;
    }

    /** Consumo del almacen de origen atribuible a las demas rutas del plan. */
    public int consumoOtrasRutas(Ruta ruta) {
        String id = ruta.getAlmacenOrigen().getIdAlmacen();
        return consumoPorAlmacen.getOrDefault(id, 0) - ruta.getCarga();
    }

    public int consumoAlmacen(String idAlmacen) {
        return consumoPorAlmacen.getOrDefault(idAlmacen, 0);
    }

    // ------------------------------------------------------------------
    // Evaluacion de inserciones
    // ------------------------------------------------------------------

    /** Diferencial de kilometros de insertar un nodo en una posicion de la ruta. */
    public double deltaKm(Ruta ruta, NodoRed nuevo, int posicion) {
        RedVial red = contexto.getRedVial();
        List<VisitaCliente> paradas = ruta.getListaParadas();
        NodoRed anterior = posicion == 0
                ? ruta.getAlmacenOrigen().getNodo()
                : paradas.get(posicion - 1).getNodoCliente();
        double dEntrada = red.distanciaKm(anterior, nuevo);
        if (Double.isInfinite(dEntrada)) {
            return Double.POSITIVE_INFINITY;
        }
        if (posicion < paradas.size()) {
            NodoRed siguiente = paradas.get(posicion).getNodoCliente();
            double dSalida = red.distanciaKm(nuevo, siguiente);
            if (Double.isInfinite(dSalida)) {
                return Double.POSITIVE_INFINITY;
            }
            return dEntrada + dSalida - red.distanciaKm(anterior, siguiente);
        }
        return dEntrada;
    }

    /** Ahorro en soles de retirar una visita de su ruta. */
    public double ahorroRemocion(Ruta ruta, VisitaCliente visita) {
        RedVial red = contexto.getRedVial();
        List<VisitaCliente> paradas = ruta.getListaParadas();
        int pos = paradas.indexOf(visita);
        if (pos < 0) {
            return 0.0;
        }
        NodoRed anterior = pos == 0
                ? ruta.getAlmacenOrigen().getNodo()
                : paradas.get(pos - 1).getNodoCliente();
        NodoRed actual = visita.getNodoCliente();
        double km = red.distanciaKm(anterior, actual);
        if (pos + 1 < paradas.size()) {
            NodoRed siguiente = paradas.get(pos + 1).getNodoCliente();
            km += red.distanciaKm(actual, siguiente) - red.distanciaKm(anterior, siguiente);
        }
        return km * ruta.getVehiculo().getCostoPorKm();
    }

    /** Mejor insercion factible del pedido sobre las rutas existentes. */
    public Insercion mejorInsercion(Pedido pedido) {
        Insercion mejor = null;
        for (Ruta ruta : rutasModificables()) {
            Vehiculo v = ruta.getVehiculo();
            if (ruta.getCarga() + pedido.getCantidadPaquetes() > v.getCapacidadMaxima()) {
                continue;
            }
            int otras = consumoOtrasRutas(ruta);
            for (int pos = ruta.primeraPosicionLibre(); pos <= ruta.getTamano(); pos++) {
                double delta = deltaKm(ruta, pedido.getNodoDestino(), pos) * v.getCostoPorKm();
                if (Double.isInfinite(delta) || (mejor != null && delta >= mejor.deltaCosto())) {
                    continue;
                }
                if (esFactibleCon(ruta, pedido, pos, otras)) {
                    mejor = new Insercion(ruta, pedido, pos, delta, false);
                }
            }
        }
        return mejor;
    }

    /** Las k mejores inserciones factibles, incluida la apertura de una ruta nueva. */
    public List<Insercion> kMejoresInserciones(Pedido pedido, int k) {
        List<Insercion> candidatas = new ArrayList<>();
        for (Ruta ruta : rutasModificables()) {
            Vehiculo v = ruta.getVehiculo();
            if (ruta.getCarga() + pedido.getCantidadPaquetes() > v.getCapacidadMaxima()) {
                continue;
            }
            int otras = consumoOtrasRutas(ruta);
            Insercion mejorEnRuta = null;
            for (int pos = ruta.primeraPosicionLibre(); pos <= ruta.getTamano(); pos++) {
                double delta = deltaKm(ruta, pedido.getNodoDestino(), pos) * v.getCostoPorKm();
                if (Double.isInfinite(delta) || (mejorEnRuta != null && delta >= mejorEnRuta.deltaCosto())) {
                    continue;
                }
                if (esFactibleCon(ruta, pedido, pos, otras)) {
                    mejorEnRuta = new Insercion(ruta, pedido, pos, delta, false);
                }
            }
            if (mejorEnRuta != null) {
                candidatas.add(mejorEnRuta);
            }
        }
        Insercion nueva = abrirRutaNueva(pedido);
        if (nueva != null) {
            candidatas.add(nueva);
        }
        candidatas.sort(Comparator.comparingDouble(Insercion::deltaCosto));
        return candidatas.size() <= k ? candidatas : new ArrayList<>(candidatas.subList(0, k));
    }

    private boolean esFactibleCon(Ruta ruta, Pedido pedido, int posicion, int consumoOtras) {
        Ruta candidata = ruta.copia();
        candidata.insertar(posicion, new VisitaCliente(pedido, pedido.getCantidadPaquetes()));
        return validador.evaluarRuta(candidata, contexto, consumoOtras).factible();
    }

    /**
     * Abre una ruta nueva evaluando las unidades libres de los tres tipos desde
     * cada almacen con saldo suficiente, y retiene la alternativa de menor costo.
     */
    public Insercion abrirRutaNueva(Pedido pedido) {
        Insercion mejor = null;
        for (Vehiculo v : contexto.getFlota()) {
            if (vehiculosOcupados.contains(v.getIdVehiculo())
                    || v.getEstado() == EstadoVehiculo.AVERIADO
                    || v.getTurnoActual() == null
                    || pedido.getCantidadPaquetes() > v.getCapacidadMaxima()) {
                continue;
            }
            for (Almacen almacen : contexto.getAlmacenes()) {
                int usado = consumoPorAlmacen.getOrDefault(almacen.getIdAlmacen(), 0);
                if (!almacen.isEsInventarioInfinito()
                        && usado + pedido.getCantidadPaquetes() > almacen.getCapacidadActual()) {
                    continue;
                }
                LocalDateTime inicio = inicioRuta(v);
                Ruta prueba = new Ruta("tmp", v, almacen, inicio);
                prueba.insertar(0, new VisitaCliente(pedido, pedido.getCantidadPaquetes()));
                ResultadoFactibilidad rf = validador.evaluarRuta(prueba, contexto, usado);
                if (!rf.factible()) {
                    continue;
                }
                if (mejor == null || rf.costoSoles() < mejor.deltaCosto()) {
                    Ruta vacia = new Ruta("R?", v, almacen, inicio);
                    mejor = new Insercion(vacia, pedido, 0, rf.costoSoles(), true);
                }
            }
        }
        return mejor;
    }

    private LocalDateTime inicioRuta(Vehiculo v) {
        LocalDateTime inicioTurno = v.getTurnoActual().getHoraInicio();
        return contexto.getInstante().isAfter(inicioTurno) ? contexto.getInstante() : inicioTurno;
    }

    private String siguienteIdRuta() {
        return "R" + (++secuenciaRuta[0]);
    }

    /**
     * Reasignacion de carga en transito: al ser el producto "P" de una unica
     * presentacion, los paquetes son intercambiables entre clientes. Si el
     * pedido critico no admite insercion, se busca una unidad que ya transporte
     * paquetes destinados a un pedido de holgura estrictamente mayor y se
     * reemplaza su destino, devolviendo el pedido desplazado al banco.
     */
    public Optional<Pedido> intentarReasignacionEnTransito(Pedido critico) {
        for (Ruta ruta : rutasModificables()) {
            int otras = consumoOtrasRutas(ruta);
            for (VisitaCliente visita : ruta.visitasNoCongeladas()) {
                Pedido donante = visita.getPedido();
                if (donante.getCantidadPaquetes() < critico.getCantidadPaquetes()
                        || donante.getPrioridad().getFactorMu() >= critico.getPrioridad().getFactorMu()
                        || visita.getHolguraHoras() <= 0.0) {
                    continue;
                }
                int posicion = ruta.getListaParadas().indexOf(visita);
                Ruta alternativa = ruta.copia();
                alternativa.removerEn(posicion);
                alternativa.insertar(posicion, new VisitaCliente(critico, critico.getCantidadPaquetes()));
                if (!validador.evaluarRuta(alternativa, contexto, otras).factible()) {
                    continue;
                }
                costoOperativo -= ruta.getCostoTotalRuta();
                consumoPorAlmacen.merge(ruta.getAlmacenOrigen().getIdAlmacen(),
                        critico.getCantidadPaquetes() - donante.getCantidadPaquetes(), Integer::sum);
                ruta.removerEn(posicion);
                ruta.insertar(posicion, new VisitaCliente(critico, critico.getCantidadPaquetes()));
                recalcular(ruta);
                costoOperativo += ruta.getCostoTotalRuta();
                pedidosNoAtendidos.remove(critico);
                registrar(new TransferenciaCarga(ruta.getVehiculo().getIdVehiculo(),
                        ruta.getVehiculo().getIdVehiculo(), donante.getIdPedido(), critico.getIdPedido(),
                        ruta.getVehiculo().getUbicacionActual().getIdNodo(),
                        critico.getCantidadPaquetes(), contexto.getInstante()));
                return Optional.of(donante);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Horizonte rodante y salida
    // ------------------------------------------------------------------

    /**
     * Congela las visitas ya ejecutadas o inminentes: quedan inmutables para los
     * operadores de destruccion y reducen el tamano efectivo del problema.
     */
    public int congelarAsignaciones(int horizonteMinutos) {
        LocalDateTime corte = contexto.getInstante().plusMinutes(horizonteMinutos);
        int congeladas = 0;
        for (Ruta ruta : rutas) {
            for (VisitaCliente v : ruta.getListaParadas()) {
                if (v.getHoraLlegadaEstimada() != null && !v.getHoraLlegadaEstimada().isAfter(corte)) {
                    v.setCongelada(true);
                    congeladas++;
                }
            }
        }
        return congeladas;
    }

    public PlanDistribucion construirPlan() {
        PlanDistribucion plan = new PlanDistribucion();
        double costo = 0.0;
        double distancia = 0.0;
        int total = 0;
        int enPlazo = 0;
        for (Ruta ruta : rutas) {
            Ruta copia = ruta.copia();
            plan.getListaRutas().add(copia);
            costo += copia.getCostoTotalRuta();
            distancia += copia.getDistanciaTotalKm();
            for (VisitaCliente v : copia.getListaParadas()) {
                Pedido p = v.getPedido();
                p.setEstado(EstadoPedido.ASIGNADO);
                p.setIdVehiculoAsignado(copia.getVehiculo().getIdVehiculo());
                plan.getPedidosAtendidos().add(p);
                total++;
                if (v.getHolguraHoras() >= 0.0) {
                    enPlazo++;
                }
            }
        }
        for (Pedido p : pedidosNoAtendidos) {
            p.setEstado(EstadoPedido.NO_ATENDIDO);
            plan.getPedidosNoAtendidos().add(p);
            total++;
        }
        plan.getTransferencias().addAll(transferencias);
        plan.setCostoTotalGlobal(costo);
        plan.setDistanciaTotalGlobalKm(distancia);
        double sla = total == 0 ? 1.0 : enPlazo / (double) total;
        plan.setIndicadorPuntualidadSLA(sla);
        plan.setSemaforoOperativo(semaforo(sla));
        for (Almacen a : contexto.getAlmacenes()) {
            if (a.getTipoAlmacen() == TipoAlmacen.INTERMEDIO) {
                plan.getEstadoAlmacenes().put(a.getIdAlmacen(),
                        a.getCapacidadActual() - consumoAlmacen(a.getIdAlmacen()));
            }
        }
        plan.setTimestampGeneracion(contexto.getInstante());
        return plan;
    }

    /** Semaforo operativo con umbrales configurables (requisito no funcional d). */
    private NivelSemaforo semaforo(double sla) {
        double saldoCritico = 1.0;
        for (Almacen a : contexto.getAlmacenes()) {
            if (a.getTipoAlmacen() == TipoAlmacen.INTERMEDIO && a.getCapacidadMaxima() > 0) {
                double ratio = (a.getCapacidadActual() - consumoAlmacen(a.getIdAlmacen()))
                        / (double) a.getCapacidadMaxima();
                saldoCritico = Math.min(saldoCritico, ratio);
            }
        }
        double indicador = Math.min(sla, saldoCritico);
        if (indicador >= cfg.umbralSemaforoVerde()) {
            return NivelSemaforo.VERDE;
        }
        return indicador >= cfg.umbralSemaforoAmbar() ? NivelSemaforo.AMBAR : NivelSemaforo.ROJO;
    }
}
