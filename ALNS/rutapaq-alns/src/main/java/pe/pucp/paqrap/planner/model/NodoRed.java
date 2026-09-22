package pe.pucp.paqrap.planner.model;

/** Nodo del grafo de la red vial (almacen, cliente o deposito temporal). */
public final class NodoRed {

    private final String idNodo;
    private final TipoNodo tipoNodo;
    private final double latitud;
    private final double longitud;
    private final double tiempoServicioHoras;
    private final boolean esDepositoTemporal;
    private boolean accesible;
    private int stockTemporal;

    public NodoRed(String idNodo, TipoNodo tipoNodo, double latitud, double longitud,
                   double tiempoServicioHoras) {
        this(idNodo, tipoNodo, latitud, longitud, tiempoServicioHoras, false, 0);
    }

    private NodoRed(String idNodo, TipoNodo tipoNodo, double latitud, double longitud,
                    double tiempoServicioHoras, boolean esDepositoTemporal, int stockTemporal) {
        this.idNodo = idNodo;
        this.tipoNodo = tipoNodo;
        this.latitud = latitud;
        this.longitud = longitud;
        this.tiempoServicioHoras = tiempoServicioHoras;
        this.esDepositoTemporal = esDepositoTemporal;
        this.stockTemporal = stockTemporal;
        this.accesible = true;
    }

    /** Deposito temporal creado en la posicion de una unidad averiada (carga despreciable). */
    public static NodoRed depositoTemporal(String idNodo, NodoRed posicion, int stockTemporal) {
        return new NodoRed(idNodo, TipoNodo.DEPOSITO_TEMPORAL, posicion.latitud, posicion.longitud,
                0.0, true, stockTemporal);
    }

    public String getIdNodo() {
        return idNodo;
    }

    public TipoNodo getTipoNodo() {
        return tipoNodo;
    }

    public double getLatitud() {
        return latitud;
    }

    public double getLongitud() {
        return longitud;
    }

    public double getTiempoServicioHoras() {
        return tiempoServicioHoras;
    }

    public boolean isEsDepositoTemporal() {
        return esDepositoTemporal;
    }

    public boolean isAccesible() {
        return accesible;
    }

    public void setAccesible(boolean accesible) {
        this.accesible = accesible;
    }

    public int getStockTemporal() {
        return stockTemporal;
    }

    public void setStockTemporal(int stockTemporal) {
        this.stockTemporal = stockTemporal;
    }

    @Override
    public String toString() {
        return idNodo;
    }
}
