package pe.pucp.paqrap.planner.model;

import java.time.LocalDateTime;

/** Registro de una reasignacion de paquetes en transito entre pedidos o unidades. */
public record TransferenciaCarga(String idVehiculoOrigen,
                                 String idVehiculoDestino,
                                 String idPedidoOrigen,
                                 String idPedidoDestino,
                                 String idNodoEncuentro,
                                 int paquetes,
                                 LocalDateTime horaTransferencia) {
}
