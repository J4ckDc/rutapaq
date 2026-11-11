package pe.pucp.paqrap.app.datos;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * S13v1: registro histórico de los pedidos que se registran en línea en la operación día a día.
 *
 * Cada pedido se agrega como una línea al archivo mensual ventas.aaaamm.txt de la carpeta datos/registrados/, con el
 * mismo formato de los archivos del profesor (##d##h##m:x,y,idCliente,cantidad,plazoHoras), de modo que también se
 * pueda usar como datos de una simulación. Está en una subcarpeta para que nunca se mezcle con los archivos del
 * profesor. Es solo un registro: si el servidor se reinicia, la operación en curso no se recupera desde aquí.
 * Si no se puede escribir, se informa en la consola y la operación sigue igual.
 */
public final class RegistroPedidos {
    private final Path carpeta;
    private boolean avisado;

    public RegistroPedidos(Path carpeta) {
        this.carpeta = carpeta;
    }

    /** Línea en el formato de los archivos de ventas, p. ej. 06d16h58m:45,43,c9167,12,36. */
    public static String linea(LocalDateTime registro, int x, int y, String cliente, int cantidad, int plazoHoras) {
        return String.format("%02dd%02dh%02dm:%d,%d,%s,%02d,%d", registro.getDayOfMonth(), registro.getHour(), registro.getMinute(),
                x, y, cliente, cantidad, plazoHoras);
    }

    public Path archivo(LocalDateTime registro) {
        return carpeta.resolve(Formato.archivoVentas(YearMonth.from(registro)));
    }

    /** Agrega el pedido al archivo de su mes; devuelve false si no se pudo escribir. */
    public synchronized boolean agregar(LocalDateTime registro, int x, int y, String cliente, int cantidad, int plazoHoras) {
        Path p = archivo(registro);
        try {
            Files.createDirectories(carpeta);
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(linea(registro, x, y, cliente, cantidad, plazoHoras));
                w.write(System.lineSeparator());
            }
            return true;
        } catch (IOException | RuntimeException e) {
            if (!avisado) System.err.println("No se pudo guardar el pedido en " + p + ": " + e);
            avisado = true;
            return false;
        }
    }
}
