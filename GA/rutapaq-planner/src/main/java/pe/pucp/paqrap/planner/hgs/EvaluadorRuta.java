package pe.pucp.paqrap.planner.hgs;

/**
 * Evaluacion incremental de rutas del HGS. Reproduce segundo a segundo el modelo
 * temporal de ValidadorFactibilidad (viaje redondeado al segundo, refrigerio en
 * el primer instante admisible, 1 h de servicio por cliente), de modo que una
 * ruta sin penalizaciones en el HGS resulta factible para el validador central.
 * Las distancias provienen de la matriz local precalculada en DatosHGS, lo que
 * vuelve la evaluacion segura para su ejecucion en paralelo.
 */
final class EvaluadorRuta {

    private EvaluadorRuta() {
    }

    /** Estado mutable de una ruta en construccion. */
    static final class Traza {
        int nodo;
        long t;
        double km;
        int carga;
        boolean refPendiente;
        double atraso;
        double refFuera;
        int paradas;
        boolean valida = true;

        Traza copia() {
            Traza c = new Traza();
            c.nodo = nodo;
            c.t = t;
            c.km = km;
            c.carga = carga;
            c.refPendiente = refPendiente;
            c.atraso = atraso;
            c.refFuera = refFuera;
            c.paradas = paradas;
            c.valida = valida;
            return c;
        }
    }

    static Traza iniciar(DatosHGS d, DatosHGS.Slot s, int almacen) {
        if (s.tienePrefijo()) {
            return s.trazaPrefijo.copia();
        }
        Traza tr = new Traza();
        tr.nodo = d.nodoAlmacen[almacen];
        tr.t = s.inicio;
        tr.refPendiente = s.refPendienteInicial;
        return tr;
    }

    static boolean extender(DatosHGS d, DatosHGS.Slot s, Traza tr, int pedido) {
        return extenderCrudo(d.dist, s, tr, d.nodoPedido[pedido], d.limite[pedido], d.mu[pedido],
                d.servicio[pedido], d.paq[pedido]);
    }

    static boolean extenderCrudo(double[][] dist, DatosHGS.Slot s, Traza tr, int nodoDestino,
                                 long limite, double mu, long servicio, int paquetes) {
        double km = dist[tr.nodo][nodoDestino];
        if (!tr.valida || Double.isInfinite(km)) {
            tr.valida = false;
            return false;
        }
        tr.km += km;
        tr.t += Math.round(km / s.velocidad * 3600.0);
        if (tr.refPendiente && tr.t >= s.refDesde) {
            if (tr.t > s.refHasta) {
                tr.refFuera += (tr.t - s.refHasta) / 3600.0;
            }
            tr.t += 3600L;
            tr.refPendiente = false;
        }
        long atraso = tr.t - limite;
        if (atraso > 0) {
            tr.atraso += mu * atraso / 3600.0;
        }
        tr.t += servicio;
        tr.nodo = nodoDestino;
        tr.carga += paquetes;
        tr.paradas++;
        return true;
    }

    /** Cierra la ruta sin modificar la traza (retorno opcional y refrigerio pendiente). */
    static RutaEval cerrar(DatosHGS d, DatosHGS.Slot s, int almacen, Traza tr) {
        if (!tr.valida) {
            return RutaEval.INVALIDA;
        }
        double km = tr.km;
        long t = tr.t;
        if (d.cfg.retornoAlmacen() && tr.paradas > 0) {
            double vuelta = d.dist[tr.nodo][d.nodoAlmacen[almacen]];
            if (Double.isInfinite(vuelta)) {
                return RutaEval.INVALIDA;
            }
            km += vuelta;
            t += Math.round(vuelta / s.velocidad * 3600.0);
        }
        double refFuera = tr.refFuera;
        if (tr.refPendiente) {
            long candidato = Math.max(t, s.refDesde);
            if (candidato > s.refHasta) {
                refFuera += (candidato - s.refHasta) / 3600.0;
            }
        }
        double exceso = Math.max(0L, t - s.turnoFin) / 3600.0;
        return new RutaEval(true, km, km * s.costoKm, tr.atraso, exceso, refFuera, tr.carga, t);
    }

    static RutaEval evaluar(DatosHGS d, DatosHGS.Slot s, int almacen, int[] paradas) {
        Traza tr = iniciar(d, s, almacen);
        for (int p : paradas) {
            if (tr.carga + d.paq[p] > s.capacidad || !extender(d, s, tr, p)) {
                return RutaEval.INVALIDA;
            }
        }
        return cerrar(d, s, almacen, tr);
    }
}
