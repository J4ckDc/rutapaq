package pe.pucp.paqrap.planner.ien;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pe.pucp.paqrap.planner.core.MapaReticula;
import pe.pucp.paqrap.planner.model.Pedido;

/**
 * Lector del historial de ventas. Cada registro tiene la forma
 * {@code ddDhhHmmm:x,y,idCliente,qq,hl}, donde dd, hh y mm son el dia, la hora y
 * el minuto de registro dentro del mes del archivo; (x, y) la ubicacion de
 * entrega; qq la cantidad de productos y hl el plazo comprometido en horas.
 */
public final class LectorVentas {

    private static final Pattern PATRON =
            Pattern.compile("^\\s*(\\d+)D(\\d+)H(\\d+):([^,]+),([^,]+),([^,]+),(\\d+),(\\d+)\\s*$");

    private LectorVentas() {
    }

    public static String nombreArchivo(int anio, int mes) {
        return String.format("ventas.%04d%02d.txt", anio, mes);
    }

    /** Lee los pedidos de un archivo mensual; el mes lo define (anio, mes). */
    public static List<Pedido> leer(Path archivo, int anio, int mes, MapaReticula mapa) {
        List<Pedido> pedidos = new ArrayList<>();
        if (!Files.exists(archivo)) {
            return pedidos;
        }
        LocalDate primerDia = LocalDate.of(anio, mes, 1);
        try (var lineas = Files.lines(archivo, StandardCharsets.UTF_8)) {
            int[] contador = {0};
            lineas.forEach(linea -> {
                if (linea.isBlank()) {
                    return;
                }
                Matcher m = PATRON.matcher(linea.trim());
                if (!m.matches()) {
                    throw new IllegalArgumentException("Registro de ventas invalido: " + linea);
                }
                int dia = Integer.parseInt(m.group(1));
                int hora = Integer.parseInt(m.group(2));
                int minuto = Integer.parseInt(m.group(3));
                int x = Integer.parseInt(m.group(4).trim());
                int y = Integer.parseInt(m.group(5).trim());
                String idCliente = m.group(6).trim();
                int cantidad = Integer.parseInt(m.group(7));
                int plazo = Integer.parseInt(m.group(8));
                LocalDateTime registro = primerDia.plusDays(dia - 1L).atStartOfDay()
                        .plusHours(hora).plusMinutes(minuto);
                String id = String.format("%04d%02d-%05d", anio, mes, ++contador[0]);
                pedidos.add(new Pedido(id, idCliente, mapa.indice(x, y), x, y, cantidad, registro, plazo, null));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return pedidos;
    }

    /**
     * Fracciona un pedido cuya cantidad excede la capacidad indicada en
     * subpedidos enlazados por idPedidoPadre; cada subpedido hereda la fecha
     * limite y suma su propia hora de acondicionamiento (entrega parcial).
     */
    public static List<Pedido> fraccionar(Pedido p, int maximoPorEntrega) {
        List<Pedido> partes = new ArrayList<>();
        if (p.getCantidad() <= maximoPorEntrega) {
            partes.add(p);
            return partes;
        }
        int restante = p.getCantidad();
        int i = 1;
        while (restante > 0) {
            int lote = Math.min(maximoPorEntrega, restante);
            partes.add(new Pedido(p.getId() + "-" + i, p.getIdCliente(), p.getNodo(), p.getX(), p.getY(),
                    lote, p.getRegistro(), p.getPlazoHoras(), p.getId()));
            restante -= lote;
            i++;
        }
        return partes;
    }
}
