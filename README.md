# RUTAPAQ v02 — servidor, simulación y visualizador con rutapaq-planner (ALNS y GA)

Un solo proceso Java 17 **sin dependencias en ejecución**. Contiene el código de
**rutapaq-planner v2.0** (`pe.pucp.paqrap.planner`: ALNS_PAQRAP, HGS_PAQRAP, EvaluadorItinerario y
el banco del IEN) y, alrededor de él, la simulación y el visualizador web (`pe.pucp.paqrap.app`).

Alcance de la v02: el escenario **simulación de 5 días** funciona completo con los datos del profesor.
La operación día a día y la simulación hasta el colapso quedan para la siguiente versión.

## Cambios de la v02 respecto de la v01

| # | Cambio | Dónde |
|---|---|---|
| 1 | Se leen los archivos del profesor (formato `01d01h30m`). La v01 solo aceptaba el formato sintético `01D01H052` y **descartaba en silencio** todas las líneas oficiales: la simulación corría con 0 pedidos y 0 bloqueos. Ahora se aceptan ambos y las líneas inválidas se informan en consola y en el visualizador. | `app/datos/Formato.java`, `LectorMensual.java`; `planner/ien/LectorVentas.java`, `LectorBloqueos.java` |
| 2 | `datos/` trae los archivos oficiales v20260909 (ventas y bloqueos de ene-2026 a dic-2028) y el plan de mantenimiento preventivo. Se quitaron los archivos sintéticos. | `datos/` |
| 3 | Mantenimiento preventivo: la unidad no recibe rutas que crucen las 00:00 de su día de mantenimiento y ese día queda fuera de servicio en el almacén central. El plan bimensual se repite (setiembre = meses impares, octubre = pares). | `app/datos/PlanMantenimiento.java`, `Simulacion.java` |
| 4 | Códigos de unidad TTNN (TA01, TM01, TB01) según el curso; la v01 usaba A01, M01, B01. | `app/modelo/TipoUnidad.java` |
| 5 | Si el planificador falla en un ciclo, las unidades conservan su itinerario. En la v01 quedaban sin tareas hasta el ciclo siguiente. | `Simulacion.planificar()` y `restaurar()` |
| 6 | % a tiempo corregido: el denominador incluye los pedidos vencidos que aún no se entregan. En la v01 era a tiempo / completados, y un pedido atrasado que nunca llegaba no bajaba el %. | `Simulacion.pctATiempo()` |
| 7 | **Detener** deja la simulación en pausa y **Reanudar** la continúa desde el mismo instante. La v01 la terminaba sin posibilidad de continuar. | `Simulacion.pausar()/reanudar()`, `Servidor` (`/api/simulacion/detener`, `/reanudar`) |
| 8 | La simulación se inicia con fecha **y hora**. | `Servidor.simulacion()`, visualizador |
| 9 | Visualizador nuevo según el prototipo: barra superior con tiempos simulado y real, menú lateral, inventario con semáforo, línea de tiempo D1–D5, rastreo de entregas con buscador, detalle y tooltips de unidades, almacenes, pedidos y bloqueos, resumen al terminar, tema claro y oscuro. Las unidades se desplazan de forma continua siguiendo las calles. | `resources/web/` |
| 10 | Uso del visualizador: cada pedido muestra el ícono y código de la unidad que lo atiende; leyenda del mapa en el panel izquierdo; clic en una unidad (en el mapa o en un pedido) filtra sus pedidos y oculta las demás unidades; clic en un pedido muestra solo la unidad que lo atiende en ese momento. El filtro se quita con la × del buscador, con otro clic en la misma unidad o con Esc. También se filtra escribiendo el código (p. ej. `TA03`) en el buscador. | `resources/web/` |

Verificación: las 15 pruebas del planificador pasan; con los mismos datos y semilla, la v02 da exactamente los
mismos resultados que la v01 (pedidos, entregas, costo); un fallo forzado del planificador deja las
unidades con su plan (v01: 31 de 37 unidades quedaban sin tareas).

![Arquitectura](docs/arquitectura_minima.png)

## Ejecutar

```bash
mvn package                       # o ./compilar-sin-maven.sh
cp target/rutapaq.jar .
java -jar rutapaq.jar             # http://localhost:8080
```

En el navegador: elegir la fecha y hora de inicio (hay datos de enero 2026 a diciembre 2028) y
**Iniciar**. **Detener** pausa la simulación y **Reanudar** la continúa; **Iniciar** de nuevo descarta la
actual y empieza otra. Los 5 días duran `escenario.semanal.minutosReales` minutos reales (30 por defecto).

Sin interfaz (corre lo más rápido posible e imprime indicadores cada 12 h):

```bash
java -jar rutapaq.jar probar 2026-01-01 120            # 5 días
java -jar rutapaq.jar probar 2027-03-01 240 COLAPSO    # hasta el colapso (o 240 h)
```

El banco experimental del IEN sigue funcionando igual desde el mismo jar:

```bash
java -cp rutapaq.jar pe.pucp.paqrap.planner.ien.RunnerIEN --bloques=8
```

## Cómo se usa rutapaq-planner

En cada ciclo (cada `ien.scMinutos` minutos simulados), `Simulacion.planificar()` hace lo mismo que
el `SimuladorColapso` del IEN, pero sobre el estado en vivo:

1. Cada unidad conserva solo la parada hacia la que ya se está moviendo; el resto de su itinerario se libera.
2. Arma la `InstanciaPlanificacion`: un `Vehiculo` por unidad (nodo, hora y carga con que queda libre,
   turno vigente y si ya tomó refrigerio), los `Almacen` con su saldo descontando las recargas
   comprometidas, los `Pedido` con lo que falta asignar (fraccionados con `LectorVentas.fraccionar`
   en partes de `ien.maximoProductosPorEntrega`) y la `MapaReticula` con los bloqueos vigentes.
3. Llama a `Planificador.planificar()` del algoritmo elegido (`planificador.algoritmo=ALNS` o `GA`).
4. Convierte el `PlanDistribucion` en el itinerario de cada unidad: entregas, recargas y refrigerios
   tal como los colocó el `EvaluadorItinerario`.

Entre ciclos, las unidades recorren la **misma** `MapaReticula` nodo a nodo (camino mínimo que no
atraviesa bloqueos) y ejecutan su itinerario. Se aplican las mismas reglas que el planner: el plazo se
cumple con la hora de llegada, cada entrega toma 1 h, el refrigerio dura 1 h, las recargas no toman
tiempo y los intermedios vuelven a 1 000 unidades a las 23:59:59.

Colapso (escenario hasta el colapso, aún no habilitado en la interfaz): **P** cuando un pedido llega a su
límite sin entrega; **C** cuando una ejecución del planificador tarda `ien.saSegundos` o más.

Mantenimiento preventivo (`datos/mant.preventivo.*`): una unidad deja de recibir rutas cuando su
mantenimiento empieza antes de que termine el horizonte de ruta (`planificador.horizonteRutaHoras`, 8 h),
así ninguna ruta cruza las 00:00. Si aun así tuviera paradas a las 00:00, sus entregas vuelven a
planificarse y la unidad pasa al almacén central (traslado instantáneo, supuesto tomado de la regla de
averías tipo 2 y 3). A las 00:00 del día siguiente vuelve a estar disponible en el central.

## Configuración

| Archivo | Qué contiene |
|---|---|
| `planner.properties` (de rutapaq-planner, sin cambios) | Mapa, almacenes, flota, Sc, Sa, P, parámetros de ALNS y GA |
| `config.properties` | Servidor, algoritmo, escenarios (fecha y hora propuestas, duración del 5D), frecuencia de publicación y semáforo (`semaforo.plazo.*`, `semaforo.almacen.*`, `semaforo.carga.*`, `semaforo.pedido.*`) |

Un `config.properties` junto al jar sobrescribe cualquier clave de los dos archivos, sin recompilar.

## Estructura

| Paquete | Qué hace |
|---|---|
| `pe.pucp.paqrap.planner.*` | rutapaq-planner v2.0 (`model`, `core`, `alns`, `hgs`, `ien`); en la v02 solo cambió el formato que aceptan los lectores del IEN |
| `app.sim.Simulacion` | Reloj, estado en memoria, ciclo de planificación con rutapaq-planner, ejecución, colapso |
| `app.sim.Caminos` | Caminos nodo a nodo sobre la `MapaReticula` del planner |
| `app.sim.Planificadores` | Crea `ALNS_PAQRAP` o `HGS_PAQRAP` según la configuración |
| `app.sim.Indicadores` | Semáforo con umbrales configurables |
| `app.datos` | Lectura de ventas y bloqueos línea por línea, en el formato oficial, y plan de mantenimiento |
| `app.web.Servidor` | REST, SSE y archivos del visualizador |
| `resources/web` | Visualizador (Canvas 2D + EventSource) |

## Desplegar en la VM de AWS (Ubuntu 24.04)

Copiar a la VM `rutapaq.jar`, `config.properties`, `datos/` y `deploy/`, y ejecutar
`./deploy/instalar-en-vm.sh`. Instala Java 17 y Nginx, deja RUTAPAQ como servicio `systemd` en
`127.0.0.1:8080` y lo publica por Nginx en el puerto 80. Para HTTPS con dominio:
`sudo certbot --nginx -d su-dominio`.

## Diferencias con el SimuladorColapso del IEN (declararlas si se comparan resultados)

- El reloj avanza en pasos de 30 s y las unidades se mueven nodo a nodo; en el IEN, cada tramo se
  resuelve de una vez al ejecutar la ventana.
- El colapso P se detecta en el instante en que vence el plazo; en el IEN, al cerrar la ventana Sc.
- La unidad termina la parada hacia la que se mueve antes de recibir el nuevo plan; en el IEN termina
  el tramo en curso al cerrar la ventana.
- El evaluador cuenta el refrigerio antes de la siguiente llegada; aquí la unidad lo toma al llegar al
  nodo de esa parada, antes de atenderla (misma hora de atención).
