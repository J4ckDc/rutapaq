package pe.pucp.paqrap.planner.model;

/** Unidad de transporte de la flota heterogenea. */
public final class Vehiculo {

    private final String idVehiculo;
    private final TipoVehiculo tipoVehiculo;
    private final Almacen almacenBase;
    private NodoRed ubicacionActual;
    private EstadoVehiculo estado;
    private TurnoConductor turnoActual;
    private int cargaActual;

    public Vehiculo(String idVehiculo, TipoVehiculo tipoVehiculo, Almacen almacenBase) {
        this.idVehiculo = idVehiculo;
        this.tipoVehiculo = tipoVehiculo;
        this.almacenBase = almacenBase;
        this.ubicacionActual = almacenBase.getNodo();
        this.estado = EstadoVehiculo.DISPONIBLE;
        this.cargaActual = 0;
    }

    public String getIdVehiculo() {
        return idVehiculo;
    }

    public TipoVehiculo getTipoVehiculo() {
        return tipoVehiculo;
    }

    public int getCapacidadMaxima() {
        return tipoVehiculo.getCapacidadMaxima();
    }

    public double getCostoPorKm() {
        return tipoVehiculo.getCostoPorKm();
    }

    public double getVelocidadPromedioKmH() {
        return tipoVehiculo.getVelocidadPromedioKmH();
    }

    public Almacen getAlmacenBase() {
        return almacenBase;
    }

    public NodoRed getUbicacionActual() {
        return ubicacionActual;
    }

    public void setUbicacionActual(NodoRed ubicacionActual) {
        this.ubicacionActual = ubicacionActual;
    }

    public EstadoVehiculo getEstado() {
        return estado;
    }

    public void setEstado(EstadoVehiculo estado) {
        this.estado = estado;
    }

    public TurnoConductor getTurnoActual() {
        return turnoActual;
    }

    public void setTurnoActual(TurnoConductor turnoActual) {
        this.turnoActual = turnoActual;
    }

    public int getCargaActual() {
        return cargaActual;
    }

    public void setCargaActual(int cargaActual) {
        this.cargaActual = cargaActual;
    }

    @Override
    public String toString() {
        return idVehiculo + "(" + tipoVehiculo + ")";
    }
}
