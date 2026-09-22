package pe.pucp.paqrap.planner.hgs;

/** Ruta decodificada: unidad (slot), almacen de origen y secuencia de pedidos del banco. */
record RutaHGS(int slot, int almacen, int[] paradas, RutaEval eval) {
}
