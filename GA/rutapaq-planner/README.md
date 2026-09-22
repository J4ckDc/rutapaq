# RUTAPAQ — Planificador de PaqRap: ALNS y HGS/GA

Las **dos soluciones algorítmicas** del componente planificador, programadas en
Java 17 sobre un mismo modelo, un mismo validador de factibilidad y un mismo
banco de pruebas, listas para la experimentación numérica del **IEN – Informe
de Diseño de Experimento** (requisitos no funcionales (a) y (b)):

1. **ALNS** — Búsqueda Local de Gran Vecindario Adaptativa (`pe.pucp.paqrap.planner.alns`).
2. **HGS/GA** — Algoritmo Genético Híbrido (`pe.pucp.paqrap.planner.hgs`).

Curso 1INF54 · Grupo 4D · Paquete raíz `pe.pucp.paqrap.planner`.

---

## 1. Cómo ejecutarlo

Con Maven:

```bash
mvn -q clean test            # compila y ejecuta la batería de pruebas
mvn -q exec:java             # ejecuta el banco de experimentos completo
```

Sin Maven (sólo JDK 17+, el proyecto no tiene dependencias en tiempo de ejecución):

```bash
javac -d target/classes $(find src/main/java -name '*.java')
cp src/main/resources/alns.properties target/classes/
java -cp target/classes pe.pucp.paqrap.planner.experimento.RunnerExperimentos --replicas=3
```

Salida: tabla por consola y `resultados/metricas.csv`, con una fila por
escenario × réplica × algoritmo × fase.

Argumentos del runner:

| Argumento | Efecto |
|---|---|
| `--algoritmos=ALNS,HGS` | algoritmos a evaluar (por defecto ambos, sobre instancias idénticas) |
| `--replicas=N` | número de réplicas por escenario (semilla = base + réplica) |
| `--escenarios=TIEMPO_REAL,SIMULACION_5D,COLAPSO` | subconjunto de escenarios |
| `--salida=ruta.csv` | archivo de métricas |
| `clave=valor` | sobrescribe cualquier parámetro de `alns.properties` |

Ejemplo de corrida factorial para el IEN (un punto del diseño):

```bash
java -cp target/classes pe.pucp.paqrap.planner.experimento.RunnerExperimentos \
     --replicas=10 alns.qMin=0.25 alns.kRegret=4 hgs.tamanoPoblacion=60 hgs.pm=0.20 \
     --salida=resultados/corrida-A.csv
```

La columna `parametros` del CSV registra los valores sobrescritos, de modo que
cada fila queda trazable con su punto del diseño experimental.

---

## 2. Estructura del código

```
pe.pucp.paqrap.planner
├── model/           Dominio: NodoRed, Arco, Almacen, Pedido, Vehiculo,
│                    TurnoConductor, Incidencia, VisitaCliente, Ruta,
│                    PlanDistribucion, TransferenciaCarga
├── core/            RedVial (Haversine + Dijkstra sobre calles de doble sentido),
│                    Configuracion, ContextoPlanificacion, InstanciaEscenario,
│                    ValidadorFactibilidad (R1–R7), Planificador (interfaz común)
│                    IndicadoresPlan (métricas comunes a ambos algoritmos)
├── alns/            EstadoALNS, Insercion, RuletaAdaptativa, ManejadorIncidencias,
│   │                ALNS_PAQRAP (procedimiento principal)
│   ├── destroy/     RemocionAleatoria, RemocionPeorCosto,
│   │                RemocionRelacionadaShaw, RemocionDeRuta
│   └── repair/      InsercionCostoMinimo, InsercionRegretK
├── hgs/             HGS_PAQRAP (procedimiento principal), DatosHGS, Individuo,
│                    SplitHeterogeneo, BusquedaLocalEducacion, OperadoresGeneticos,
│                    Poblacion, Penalizaciones, EvaluadorRuta, ConstructorPlanHGS
└── experimento/     GeneradorInstancias, RegistroMetricas,
                     VerificadorPlan, RunnerExperimentos
```

Ambos algoritmos implementan la interfaz `Planificador`; el runner, el generador
de instancias, el validador (`ValidadorFactibilidad`), el cálculo de indicadores
(`IndicadoresPlan`) y el verificador (`VerificadorPlan`) son los mismos, de modo
que la comparación del IEN se realiza en igualdad de condiciones.

---

## 3. ALNS — función objetivo y criterio de aceptación

**f(s) = Σ distancia(r) · costoPorKm(tipo(r)) + ω · Σ μ(p)**, con:

- `costoPorKm` = S/ 8.00 auto, S/ 6.00 moto, S/ 3.00 bicicleta;
- `ω` = `alns.omegaUrgencia` (10 000 S/), penalización por pedido no atendido;
- `μ(p)` = 5 para ventanas de 4 y 8 h, 3 para 12 y 18 h, 1 para 36 h.

El plazo comprometido es **restricción dura**: ninguna solución factible entrega
fuera de su ventana, por lo que la penalización de urgencia actúa sobre la
*cobertura* y no sobre holguras negativas. Una candidata que deja más pedidos sin
atender que la solución actual se descarta antes de comparar costos, de modo que
el recocido nunca intercambia cobertura por ahorro en kilómetros.

Aceptación por recocido simulado: `exp(−Δ / T)` con `T ← α^(1/tamañoSegmento) · T`
y `T₀ = 0.05 · f(s₀)`, independiente de la escala de la instancia.

---

## 4. ALNS — trazabilidad con `21.dis.selec.algoritmos.v03`

| Documento (v03) | Implementación |
|---|---|
| Algoritmo 1 — `ALNS_PAQRAP_OPTIMIZAR` | `ALNS_PAQRAP.optimizar(...)` |
| Algoritmo 2 — `Destroy_WorstCostRemoval` | `destroy.RemocionPeorCosto` |
| Algoritmo 2 — `Destroy_TimeWindowRelatedRemoval` | `destroy.RemocionRelacionadaShaw` |
| Algoritmo 2 — `Destroy_StreetBlockRemoval` | `ManejadorIncidencias.bloqueoCalle` |
| Algoritmo 2 — `Destroy_BreakdownRemoval` | `ManejadorIncidencias.fallaMecanica` |
| Algoritmo 3 — `Repair_CostMinimizing_Insertion` | `repair.InsercionCostoMinimo` |
| Algoritmo 3 — `Repair_RegretK_Insertion` | `repair.InsercionRegretK` |
| Algoritmo 4 — `ValidarFactibilidadRuta` | `core.ValidadorFactibilidad.evaluarRuta` |
| Tablas 4–13 — estructuras de datos | paquete `model` + `core.RedVial` |
| Tabla 15 — parámetros de calibración | `src/main/resources/alns.properties` |
| `intentarReasignacionEnTransito` | `EstadoALNS.intentarReasignacionEnTransito` |
| Ruleta adaptativa w(i,s+1) | `alns.RuletaAdaptativa` |

**Ajuste respecto de la v03 (a documentar en el IEN).** Los operadores dirigidos
por eventos (`StreetBlockRemoval`, `BreakdownRemoval`) no participan de la ruleta
adaptativa: se invocan al aplicar las incidencias, antes del bucle de búsqueda,
porque su probabilidad de producir un vecindario no vacío depende del evento y no
del desempeño histórico. La ruleta opera sobre cuatro operadores de propósito
general: `RandomRemoval`, `WorstCostRemoval`, `TimeWindowRelatedRemoval` y
`RouteRemoval` (este último, nuevo respecto de la v03, libera una unidad completa
y es el que permite reasignar carga entre tipos de vehículo).

---

## 4b. HGS/GA — diseño

**Cromosoma I = (π, γ, σ).** π es la gran ruta (permutación de los pedidos
pendientes), γ el almacén de recarga de cada pedido y σ el orden en que Split
ofrece las unidades disponibles.

**Split heterogéneo con flota limitada** (`SplitHeterogeneo`). Programación
dinámica sobre el estado (k, j) — “los primeros j pedidos de π ya están decididos
usando las k primeras unidades de σ” — con tres transiciones: la unidad σ[k]
atiende el tramo π[j..j′−1]; la unidad σ[k] no sale; el pedido π[j] queda sin
atender. El camino mínimo decide a la vez los cortes de ruta, el tipo de unidad
(auto, moto o bicicleta) y respeta que cada unidad salga a lo sumo una vez.
Complejidad O(K · n · L).

**Aptitud** (Algoritmo 10):
f(I) = Σ distancia · costoPorKm + ωSLA · Σ μ(p) · horas de retraso
+ ωTurno · (horas sobre la jornada + horas de refrigerio fuera de ventana)
+ ωStock · exceso sobre el saldo de los intermedios + ωNoAtendido · Σ μ(p) no atendidos.
Durante la evolución el plazo, el turno, el refrigerio y el stock son
**restricciones blandas**; la capacidad es dura.

**Ciclo generacional** (Algoritmo 6): población inicial de 4N individuos
(ordenados por fecha límite, por ángulo polar o uniformes; σ por costo por km,
por capacidad o aleatorio), torneo binario sobre aptitud sesgada, cruce OX sobre
π y sobre σ (pc), herencia de γ por pedido, mutación swap / inversión / cambio de
almacén (pm), Split, educación por búsqueda local (Relocate, Swap, 2-opt, 2-opt*
en vecindario granular Γ = 20, primera mejora) y reparación dirigida con
penalizaciones ×10 para el 50 % de los infactibles. Subpoblaciones factible e
infactible, supervivencia por aptitud sesgada (rango de costo + distancia de
pares rotos a los 5 vecinos más próximos) y penalizaciones adaptativas cada 100
descendientes según la proporción de factibles (objetivo 20 %).

**Paralelismo reproducible.** Los descendientes se generan por lotes de tamaño
fijo (`hgs.loteParalelo`) con `parallelStream()` y un generador por tarea
derivado con `SplittableRandom.split()`; la evaluación usa una matriz local de
distancias precalculada, por lo que no hay estado compartido mutable.

**Plan final** (`ConstructorPlanHGS`). Se exige factibilidad dura con el mismo
`ValidadorFactibilidad` del ALNS; si una ruta la incumpliera, se retiran sus
visitas pendientes desde el final, se reinsertan por menor costo o en una unidad
libre y, de no ser posible, se declaran no atendidas.

**Reoptimización rodante.** Se aplican las incidencias, las visitas ejecutadas o
inminentes del plan vigente se congelan como **prefijo inmutable** de la unidad
que las atiende (Split y la educación solo añaden visitas después de él) y la
población se siembra con el plan vigente.

| Documento (v03) | Implementación |
|---|---|
| Algoritmo 5 — cromosoma | `hgs.Individuo` (π, γ y, como ajuste, σ) |
| Algoritmo 6 — `HGS_PAQRAP_PLANIFICAR` | `HGS_PAQRAP.resolver(...)` |
| Algoritmo 7 — `Split_Heterogeneo_PaqRap` | `hgs.SplitHeterogeneo.decodificar` |
| Algoritmo 8 — torneo, OX, mutación | `Poblacion.torneoBinario`, `OperadoresGeneticos` |
| Algoritmo 9 — `Educate_LocalSearch` | `hgs.BusquedaLocalEducacion.educar` |
| Algoritmo 10 — aptitud | `Individuo.evaluar`, `EvaluadorRuta` |
| Tabla 18 — parámetros | claves `hgs.*` de `alns.properties` |
| Selección de supervivientes | `Poblacion.seleccionarSupervivientes` |
| Penalizaciones adaptativas | `hgs.Penalizaciones` |

**Ajustes respecto de la v03 (a declarar en el IEN).**

1. Se añade el gen **σ** (orden de unidades). La v03 resolvía la flota limitada
   con una variante de Split con etiquetas no dominadas; con σ el límite de una
   salida por unidad queda garantizado dentro del propio camino mínimo, con
   complejidad polinomial y sin etiquetas.
2. La penalización por pedido no atendido se pondera por μ(p) (como en el ALNS),
   para que la cobertura priorice las ventanas de 4 y 8 h.
3. “Generación” se cuenta por descendiente (esquema *steady-state*): el ajuste de
   penalizaciones cada 100 descendientes y `genSinMejora` = 500 descendientes.
4. En la reoptimización el HGS no depende del ALNS: aplica él mismo las
   incidencias y el congelamiento, para que ambos algoritmos se comparen de forma
   independiente.
5. El presupuesto de tiempo es el del escenario (`escenario.*`), idéntico para
   ambos algoritmos; el HGS termina antes si alcanza `genSinMejora`.

---

## 5. Reglas de negocio implementadas

| Regla del enunciado | Mecanismo |
|---|---|
| Flota heterogénea 24/8/4 paquetes, 40/25/12 km/h, S/ 8.00/6.00/3.00 por km | `TipoVehiculo`; poda por capacidad antes del Δcosto; Δcosto por costoPorKm de la unidad |
| Almacén central de inventario infinito y 2 intermedios de 1 000 unidades con recarga instantánea a las 23:59:59 | `Almacen`, `EstadoALNS.consumoPorAlmacen`, R2 del validador |
| Tiempo de carga despreciable y 1 h de entrega en destino | `tiempoServicioHoras` = 0 en almacenes y depósitos temporales, 1.0 en clientes |
| Calles de doble sentido | `Arco` no dirigido; el bloqueo invalida ambos sentidos; Dijkstra bajo demanda |
| Turnos de 8 h (07:00, 15:00, 23:00) y 1 h de refrigerio con margen de 1 h | R6 y R7 del validador; `TurnoConductor` |
| Entregas regulares de 36 h y priorizadas de 4, 8, 12 y 18 h | R5 (restricción dura); banco ordenado por fecha límite; factor μ(p) en el regret |
| Bloqueos de vías y averías con reasignación de carga en tránsito | `ManejadorIncidencias`, depósitos temporales, `intentarReasignacionEnTransito` |
| Pedidos que exceden la capacidad del auto | `GeneradorInstancias.fraccionar` (subpedidos con `idPedidoPadre`) |
| Semáforo verde / ámbar / rojo con rangos configurables | `EstadoALNS.semaforo`, claves `semaforo.*` |
| Presupuesto de cómputo parametrizable e interrumpible | `escenario.maxIteraciones` y `escenario.limiteTiempoSegundos`; siempre retorna la incumbente |

---

## 6. Supuestos del modelo (declararlos en el IEN; comunes a ambos algoritmos)

1. **Una ruta por unidad y turno.** No se modelan viajes múltiples con recarga
   intermedia dentro del mismo turno; ampliarlo exigiría insertar retornos al
   almacén como nodos de la secuencia.
2. **Sin retorno obligatorio al almacén.** La ruta concluye en el último cliente;
   `escenario.retornoAlmacen=true` activa el retorno y lo suma al costo.
3. **Refrigerio determinista.** Se coloca en el primer instante admisible a partir
   de `horaInicio + 1 h`; debe terminar a más tardar en `horaFin − 1 h`.
4. **Red vial sintética.** Malla de 10×10 nodos con calles de 0.8 km sobre
   coordenadas de Lima. Al sustituirla por la red real sólo cambia el cargador de
   instancias: `RedVial` ya opera sobre un grafo arbitrario.
5. **Velocidades promedio constantes** (40/25/12 km/h), sin congestión horaria.
6. **Reasignación en tránsito entre clientes** de la misma unidad (ALNS): el
   producto “P” tiene presentación única, por lo que los paquetes son
   intercambiables; se exige holgura estrictamente mayor en el pedido donante.
7. **Incidencias independientes del plan.** Los bloqueos y averías se generan a
   partir de la semilla y no del plan vigente, de modo que ambos algoritmos
   enfrentan exactamente los mismos eventos.
8. **Unidades sin visitas congeladas en la reoptimización.** El ALNS conserva el
   instante de salida original de la ruta; el HGS la reprograma desde el almacén
   en el instante de replanificación. La diferencia solo afecta la holgura
   temporal, no la distancia.

---

## 7. Variables de respuesta que exporta el banco de pruebas

`resultados/metricas.csv`:

`escenario, algoritmo, fase, replica, semilla, pedidos, vehiculos, rutas,
costoSoles, distanciaKm, pctSLA, noAtendidos, iteraciones, tiempoMs, semaforo, parametros`

Cada réplica ejecuta dos fases sobre la misma instancia:

- **planificacion** — construcción del plan del turno (costo, distancia, SLA);
- **reoptimizacion** — 3 bloqueos de calles y 2 averías inyectados 90 min después,
  con horizonte de congelamiento activo (mide el **tiempo de respuesta por
  reoptimización** y la degradación de la solución).

El escenario `COLAPSO` satura deliberadamente la flota: su variable de interés es
`noAtendidos` (tasa de resolución ante saturación / señal de colapso logístico).

`VerificadorPlan` audita cada plan de forma independiente al algoritmo —
factibilidad de cada ruta, ausencia de pedidos duplicados o extraviados, saldo de
almacenes— y reporta hallazgos por consola: una mejora de costo sobre un plan
inválido no pasa inadvertida.

---

## 8. Estado de verificación

- **15 pruebas correctas**: R1, R2, R3, R5, R6/R7 del validador; factibilidad,
  reproducibilidad y reoptimización de cada algoritmo; fraccionamiento de pedidos;
  Split elige la bicicleta cuando el plazo lo permite; el cruce OX produce
  permutaciones válidas; y la evaluación penalizada del HGS coincide con el
  validador central en 300 rutas aleatorias (misma factibilidad y mismo costo).
- 3 réplicas × 3 escenarios × 2 algoritmos × 2 fases sin hallazgos del verificador.
- Reproducibilidad: con la misma semilla y cuando la corrida no termina por
  límite de tiempo, ambos algoritmos producen resultados idénticos. Si el corte es
  por tiempo, el número de iteraciones depende del equipo; para el IEN conviene
  fijar el presupuesto por iteraciones/generaciones y usar el tiempo como cota.
