# RUTAPAQ — Planificador de PaqRap: ALNS y GA (versión 2.0, alineada al IEN)

Las **dos soluciones algorítmicas** del componente planificador y el **banco de
pruebas del IEN** (*Comparación ALNS vs. GA según el tiempo hasta el colapso
logístico*), en Java 17 y sin dependencias en tiempo de ejecución.

Curso 1INF54 · Grupo 4D · Paquete raíz `pe.pucp.paqrap.planner`.

---

## 1. Cómo ejecutarlo

```bash
# compilar
javac -d target/classes $(find src/main/java -name '*.java')
cp src/main/resources/planner.properties target/classes/

# 1) generar un historial de prueba (mientras no estén los archivos oficiales)
java -cp target/classes pe.pucp.paqrap.planner.ien.RunnerIEN --solo-generar

# 2) una corrida de prueba con un solo bloque, para medir cuánto dura
java -cp target/classes pe.pucp.paqrap.planner.ien.RunnerIEN --bloques=8

# 3) el experimento completo: 20 bloques x 2 algoritmos = 40 corridas
java -cp target/classes pe.pucp.paqrap.planner.ien.RunnerIEN
```

Con Maven: `mvn -q clean test` y `mvn -q exec:java`.

**Datos oficiales.** Copia los archivos `ventas.aaaamm.txt` y `bloqueo.aamm.txt`
del curso en la carpeta `datos/` (sobrescribiendo los sintéticos) y ejecuta sin
`--generar`. El lector usa exactamente los nombres y formatos del IEN.

| Argumento | Efecto |
|---|---|
| `--datos=dir` | carpeta del historial (por defecto `datos/`) |
| `--salida=dir` | carpeta de resultados (por defecto `resultados/`) |
| `--bloques=1-20` o `--bloques=8,14` | subconjunto de bloques |
| `--algoritmos=ALNS,GA` | algoritmos a evaluar |
| `--generar` / `--solo-generar` | crea el historial sintético (y sale) |
| `--clave=valor` | sobrescribe cualquier parámetro de `planner.properties` |
| `ien.verboso=true` | traza por ventana: pendientes, rutas, entregas, Ta y causa del colapso |

**Salidas** (carpeta `resultados/`):

- `datos_tratamiento.csv` — columnas `bloque, prod_dia, tc_alns, tc_ga`, que son
  exactamente las que espera el cuaderno del Anexo 4 del IEN.
- `detalle_corridas.csv` — Anexo 1: causa del colapso (P/C/N), Ta máximo y
  promedio, número de ejecuciones y productos entregados de cada corrida.

Al terminar, la consola reporta la **carga máxima sostenida L\*** y el **primer
nivel de carga con colapso L_c** de cada algoritmo (secciones 3.4 y 5.3).

---

## 2. Qué cambió respecto de la versión 1.0

La Tabla 9 del IEN reemplaza varios supuestos del banco de pruebas anterior.
Esta versión los implementa:

| Requisito de la Tabla 9 | Antes (v1.0) | Ahora (v2.0) |
|---|---|---|
| **Mapa** | malla sintética 10×10 con distancias Haversine | retícula oficial 70×50 km, nodos cada 1 km (3 621), sin diagonales; sin bloqueos la distancia es la Manhattan |
| **Bloqueos** | arcos bloqueados, leídos de incidencias generadas | **bloqueos de nodo** leídos de `bloqueo.aamm.txt`; no se atraviesan ni se gira en ellos, y un cliente sobre un nodo bloqueado sigue siendo alcanzable |
| **Flota** | 24/40/16 unidades sintéticas | 10 autos, 15 motos y 12 bicicletas, todas en el almacén central al inicio |
| **Viajes con recarga** | una ruta por unidad y turno | **varios viajes por turno**: el evaluador inserta la recarga en el almacén más cercano con saldo cada vez que la carga no alcanza |
| **Entregas parciales** | no modeladas | un pedido mayor que la capacidad se fracciona en subpedidos enlazados; cada parte suma su hora de acondicionamiento |
| **Ciclo** | una planificación + una reoptimización | **ciclo programado**: una ejecución cada Sc con el estado vigente de la flota, los pedidos nuevos y los pendientes |
| **Presupuesto** | por iteraciones y por tiempo, distinto por escenario | **P = 2 s** idéntico para ambos; ambos pueden terminar antes por convergencia |
| **Colapso** | no existía | detector independiente: **P** (vence un plazo sin entrega) y **C** (Ta ≥ Sa) |
| **Métrica** | costo, distancia, %SLA | **Tc**, tiempo hasta el colapso en horas simuladas |
| **Averías** | operador de destrucción dedicado | desactivadas, como pide el alcance del IEN |

Los turnos se modelan ahora como **rotación de conductores sobre unidades
continuas**: la unidad no se detiene al cambiar el turno (07:00, 15:00, 23:00);
entra otro conductor con su propia hora de refrigerio. Una ruta sí está acotada
por `planificador.horizonteRutaHoras` (8 h por defecto), porque más allá el plan
se rehace en la siguiente ejecución.

---

## 3. Estructura del código

```
pe.pucp.paqrap.planner
├── model/     Pedido, Vehiculo, Almacen, TurnoConductor, Ruta, ParadaRuta, PlanDistribucion
├── core/      MapaReticula (retícula + bloqueos de nodo + BFS), CalendarioBloqueos,
│              MatrizDistancias, DatosPlanificacion, EvaluadorItinerario,
│              SolucionRutas, Penalizador, IndicadoresPlan, Planificador
├── alns/      ALNS_PAQRAP, RuletaAdaptativa, destroy/ (4 operadores), repair/ (2)
├── hgs/       HGS_PAQRAP, SplitHeterogeneo, BusquedaLocalEducacion,
│              OperadoresGeneticos, Poblacion, Individuo, PenalizacionesAdaptativas
└── ien/       LectorVentas, LectorBloqueos, CargadorBloque, SimuladorColapso,
               RunnerIEN, GeneradorDatosSinteticos, AuditorPlan
```

**Lo que ambos algoritmos comparten** (y que hace la comparación equitativa):
la instancia preprocesada (`DatosPlanificacion`), el evaluador de itinerarios
(`EvaluadorItinerario`, que decide las recargas, coloca el refrigerio y mide
plazos), la representación de la solución (`SolucionRutas`), los indicadores del
plan y el simulador con su detector de colapso. Lo único distinto es **cómo
busca cada uno**.

---

## 4. Los dos algoritmos

**ALNS** (`alns.ALNS_PAQRAP`). Construye una solución inicial por inserción
voraz ordenada por fecha límite y la mejora alternando operadores de destrucción
(aleatoria, peor costo, afinidad espacio-temporal de Shaw y ruta completa) y de
reparación (costo mínimo y regret-3 ponderado por μ(p)), elegidos por ruleta
adaptativa, con aceptación por recocido simulado. Trata **todas las
restricciones como duras**: una inserción que viole capacidad, plazo, horizonte
o refrigerio se rechaza y el pedido queda pendiente para la siguiente ejecución.

**GA / HGS** (`hgs.HGS_PAQRAP`). Cromosoma (π, σ): la gran ruta de pedidos y el
orden en que Split ofrece las unidades. `SplitHeterogeneo` parte π por
programación dinámica sobre (k, j) y decide a la vez los cortes de ruta y qué
tipo de unidad atiende cada tramo. Cada descendiente se educa con búsqueda local
(Relocate, Swap, 2-opt, 2-opt\*) en vecindario granular. Trata plazo y jornada
como **restricciones blandas** con penalizaciones adaptativas, lo que le permite
atravesar regiones infactibles; al final poda las entregas que no alcanzan su
plazo, de modo que el plan que ejecuta la operación solo contiene entregas
dentro de plazo.

Función objetivo común: `Σ distancia · costoPorKm + ω · Σ μ(p)` sobre los
pedidos que quedan sin planificar, con μ = 5 / 3 / 1 según la urgencia. El costo
por kilómetro (S/ 8.00, 6.00 y 3.00) sigue guiando la búsqueda aunque el IEN no
lo use como criterio de decisión. Las penalizaciones del GA por atraso y por
jornada arrancan en 10 000, el mismo valor que ω: como el colapso se dispara al
vencer el plazo, una entrega tardía no vale más que dejar el pedido para la
siguiente ejecución, y no debe verse como una alternativa barata durante la
búsqueda.

---

## 5. Supuestos del banco de pruebas (declararlos en el IEN)

1. **Bloqueos conocidos al planificar.** Cada ejecución usa los bloqueos
   vigentes en su instante; el simulador aplica la vigencia real tramo a tramo y
   recalcula el camino en el momento de la salida. Si un bloqueo empieza durante
   un tramo ya iniciado, el desvío se paga en la ejecución, no en el plan.
2. **Unidad mid-tramo al cerrar la ventana.** La unidad termina el tramo en
   curso y queda disponible en el nodo de llegada; el resto de su plan se
   rehace en la siguiente ejecución.
3. **Refrigerio del conductor.** Se coloca en el primer instante admisible a
   partir de inicio + 1 h. Si su ventana ya pasó cuando la unidad inicia la
   ruta, se asume tomado mientras la unidad estaba inactiva.
4. **Política de recarga común.** Almacén más cercano con saldo suficiente; el
   central (inventario infinito) es siempre la alternativa de respaldo, de modo
   que quedarse sin stock en un intermedio nunca hace infactible una ruta,
   solo la encarece.
5. **Horizonte de ruta de 8 h** (`planificador.horizonteRutaHoras`), para acotar
   el tamaño del plan entre ejecuciones consecutivas.
6. **Historial sintético** mientras no estén los archivos oficiales: reproduce
   las características de las Tablas 5 y 6 (crecimiento sostenido, qq 1–10,
   plazos 15/15/15/15/40 %, quince poligonales, ≈19.8 bloqueos por día), pero
   **sus cargas por bloque no coinciden con la Tabla 7**. Los resultados
   definitivos deben obtenerse con los archivos del curso.

---

## 6. Verificación

15 pruebas, todas correctas, alineadas con la columna "Cómo verificarlo" de la
Tabla 9:

| Requisito | Prueba |
|---|---|
| Mapa | 3 621 nodos; sin bloqueos la distancia es la Manhattan |
| Bloqueos | un muro no se atraviesa; un cliente sobre nodo bloqueado sigue alcanzable; la poligonal bloquea extremos y vértices |
| Viajes con recarga | con más pedidos que la capacidad de una salida, el itinerario incluye una recarga por viaje |
| Almacenes | la recarga usa el más cercano con saldo y cae al central cuando el intermedio no alcanza |
| Plazos | el cumplimiento se mide con la hora de llegada, no con el fin del acondicionamiento |
| Turnos y refrigerio | el refrigerio cae en [inicio + 1 h, fin − 1 h] y el turno rota sin detener la unidad |
| Entregas parciales | un pedido de 50 productos se fracciona en tres partes que heredan la fecha límite |
| Lector de bloques | solo carga pedidos con registro dentro de [t₀, t₀ + 48 h) |
| Ciclo programado | no excede H / Sc ejecuciones por corrida |
| Detector de colapso | un pedido imposible produce colapso P exactamente al vencer su plazo |
| Plan de cada algoritmo | la auditoría no encuentra capacidad excedida, pedidos duplicados ni extraviados |

Antes de las corridas oficiales conviene además verificar el lector con los
controles del propio IEN: 21 725 registros de bloqueo en total, 15 poligonales
distintas, y 51 pedidos / 256 productos / 39 bloqueos en el bloque 1.
