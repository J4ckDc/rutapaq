package pe.pucp.paqrap.app.sim;

import pe.pucp.paqrap.app.Config;
import pe.pucp.paqrap.planner.model.TurnoConductor;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Reglas de las averías (v03), tomadas de las respuestas del curso (P&R, pregunta 3). Los valores se leen de
 * config.properties (claves averia.*), de modo que se ajustan sin recompilar si el curso amplía la respuesta:
 *
 *   Tipo 1 (menor):      no disponible averia.tipo1.horas (2 h). Se queda en el lugar con su carga y luego sigue.
 *   Tipo 2 (intermedia): permanece en el lugar averia.enLugar.horas (4 h) y luego pasa de forma instantánea al
 *                        almacén central. Disponible al final del turno siguiente al de la avería (turnos de
 *                        07:00, 15:00 y 23:00, los mismos del planificador).
 *   Tipo 3 (mayor):      permanece en el lugar 4 h y luego pasa al almacén central. Disponible tras al menos
 *                        averia.tipo3.diasMinimos (2 días), al inicio del turno de averia.tipo3.horaRetorno (15:00):
 *                        se toma el primer inicio de ese turno que llega cuando ya pasaron los 2 días.
 *   Trasvase (2 y 3):    mientras la unidad sigue en el lugar, otra unidad puede recoger sus paquetes; cada
 *                        trasvase toma averia.trasvase.minutos (30 min, nota de la pregunta 3). Lo que no se
 *                        trasvasa se lleva al almacén central junto con la unidad.
 */
public final class ReglasAveria {

    public final double horasTipo1;
    public final double horasEnLugar;
    public final double diasTipo3;
    public final LocalTime horaRetornoTipo3;
    public final double minutosTrasvase;
    public final boolean trasvaseHabilitado;

    public ReglasAveria(Config cfg) {
        this(cfg.decimal("averia.tipo1.horas", 2),
                cfg.decimal("averia.enLugar.horas", 4),
                cfg.decimal("averia.tipo3.diasMinimos", 2),
                LocalTime.parse(cfg.texto("averia.tipo3.horaRetorno", "15:00")),
                cfg.decimal("averia.trasvase.minutos", 30),
                Boolean.parseBoolean(cfg.texto("averia.trasvase.habilitado", "true")));
    }

    public ReglasAveria(double horasTipo1, double horasEnLugar, double diasTipo3, LocalTime horaRetornoTipo3,
                        double minutosTrasvase, boolean trasvaseHabilitado) {
        this.horasTipo1 = horasTipo1;
        this.horasEnLugar = horasEnLugar;
        this.diasTipo3 = diasTipo3;
        this.horaRetornoTipo3 = horaRetornoTipo3;
        this.minutosTrasvase = minutosTrasvase;
        this.trasvaseHabilitado = trasvaseHabilitado;
    }

    public static void validarTipo(int tipo) {
        if (tipo < 1 || tipo > 3) throw new IllegalArgumentException("El tipo de avería debe ser 1, 2 o 3");
    }

    public double segundosTrasvase() {
        return minutosTrasvase * 60.0;
    }

    /** Instante en que la unidad vuelve a estar disponible para recibir rutas. */
    public LocalDateTime disponible(int tipo, LocalDateTime inicio) {
        validarTipo(tipo);
        return switch (tipo) {
            case 1 -> inicio.plusSeconds(Math.round(horasTipo1 * 3600));
            // el turno vigente termina en getFin(); el turno siguiente empieza allí y termina 8 h después
            case 2 -> TurnoConductor.vigenteEn(TurnoConductor.vigenteEn(inicio).getFin()).getFin();
            default -> {
                LocalDateTime minimo = inicio.plusSeconds(Math.round(diasTipo3 * 86400));
                LocalDateTime retorno = minimo.toLocalDate().atTime(horaRetornoTipo3);
                yield retorno.isBefore(minimo) ? retorno.plusDays(1) : retorno;
            }
        };
    }

    /**
     * Instante en que la unidad deja el lugar de la avería: el tipo 1 sigue su camino al terminar las 2 h;
     * los tipos 2 y 3 pasan al almacén central al cumplir las 4 h (nunca después de volver a estar disponibles).
     */
    public LocalDateTime finEnLugar(int tipo, LocalDateTime inicio) {
        LocalDateTime disponible = disponible(tipo, inicio);
        if (tipo == 1) return disponible;
        LocalDateTime traslado = inicio.plusSeconds(Math.round(horasEnLugar * 3600));
        return traslado.isAfter(disponible) ? disponible : traslado;
    }
}
