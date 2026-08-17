# Conclusiones de la tarea de GameTests

Este documento resume los fallos de GameTests investigados y corregidos durante la tarea de estabilización de runtime en `agent/fix-runtime-gametests`, poniendo el foco en **qué provocaba realmente los fallos** y en **cómo evitar que futuros GameTests vuelvan a reproducir los mismos errores de diseño o de fixture**.

La conclusión general es que la mayoría de los fallos no eran errores aislados de un test concreto. Había varias ventanas transitorias reales del runtime —movimiento de Sable, colocación de Create y reutilización de plots— en las que las APIs aparentemente equivalentes podían observar estados distintos. Algunos GameTests, además, amplificaban esos problemas al inicializar el mini-mundo demasiado pronto o mediante una vista que no era la autoridad correcta en ese instante.

---

## 1. Pérdida temporal de soporte macro durante movimientos de Sable

### Síntomas observados

Tests como:

- `sableassemblykeepscarriedmacrosupport`
- `minibackedmacroattachmentsurvivessableassemblyanddisassembly`

fallaban porque bloques mini dependientes de un vecino macro podían romperse durante assembly/disassembly de Sable, aunque conceptualmente ese vecino siguiese formando parte de la misma estructura transportada.

El síntoma típico era que un bloque mini —por ejemplo redstone u otro bloque dependiente de soporte— veía desaparecer el bloque macro fuente durante unos instantes y reaccionaba como si el soporte se hubiese eliminado realmente.

### Causa raíz

Durante un `moveBlocks` de Sable, el mundo físico macro se modifica por fases. El bloque de origen puede desaparecer antes de que exista todavía una representación completa y consultable de su destino en el journal de la contraption.

El bridge mini→macro consultaba el estado actual del mundo en vez del estado lógico que debía permanecer estable durante la transacción de movimiento.

Por tanto había una ventana en la que:

1. el bloque macro original ya no estaba en el mundo;
2. el destino todavía no era una fuente fiable para las consultas de soporte;
3. el mini-mundo recibía una lectura falsa de "sin soporte";
4. Minecraft aplicaba inmediatamente la física normal y rompía el bloque mini.

### Corrección aplicada

Se introdujo un contexto transaccional de movimiento en `SableAssemblyMoveContext` que congela los `BlockState` macro relevantes antes de comenzar la mutación.

`MiniWorldEnvironment` consulta ese snapshot mientras la transacción está activa, de modo que el mini-mundo ve un estado macro lógico estable durante toda la operación.

### Recomendaciones para futuros GameTests

- Si un test reproduce una operación multi-etapa de movimiento, **no asumas que el mundo físico intermedio representa el estado lógico que debe percibir el gameplay**.
- Añade aserciones en las fases intermedias del movimiento, no solo antes/después. Es precisamente ahí donde aparecen estos bugs.
- Para bloques dependientes de soporte, prueba explícitamente:
  - antes de capturar;
  - durante captura/movimiento;
  - durante colocación;
  - después del commit.
- No "arregles" un test añadiendo delays para esperar a que el estado final llegue. Si la semántica exige que el soporte exista durante toda la transacción, el runtime debe proporcionarlo durante toda la transacción.

---

## 2. Pérdida de soporte durante el unwind de colocación de Create

### Síntoma observado

`minibackedmacroattachmentsurvivessableassemblyanddisassembly` y casos relacionados podían sobrevivir al movimiento pero perder soporte justo después de `finalizeContraptionPlacement`.

### Causa raíz

Create mantiene durante un corto intervalo un `CreateAssemblyPlacementContext.Target` síncrono alrededor de la colocación física. Sin embargo, `finalizeContraptionPlacement` elimina ya el pending move durable.

Eso generaba una ventana en la que:

- el pending move ya no existía;
- el wrapper de colocación todavía seguía activo;
- el Frame recién colocado era válido;
- algunas comprobaciones de soporte caían demasiado pronto al camino "committed normal" y exigían una alineación/estado del child que todavía podía ir una actualización por detrás.

El resultado era un falso `false` de soporte durante el unwind de la misma llamada que estaba terminando correctamente el placement.

### Corrección aplicada

`FrameFaceSupport` mantiene una ruta post-commit estrecha mientras siga existiendo el `CreateAssemblyPlacementContext.Target`.

Esa ruta solo se acepta si el estado ya comprometido concuerda con los datos del target:

- UUID de assembly;
- orientación;
- conjunto de Frames;
- offset lógico;
- ausencia de recovery/piston conflictivo;
- cara realmente exterior.

### Recomendaciones para futuros GameTests

- En integraciones con Create, distingue siempre entre:
  - pre-capture;
  - captured;
  - placement pre-commit;
  - placement post-commit pero aún dentro del wrapper;
  - estado estable posterior.
- Un test que solo espera varios ticks después del placement puede no detectar una pérdida transitoria de soporte que ya rompió un bloque.
- Cuando un contexto síncrono representa una operación en curso, crea tests que consulten explícitamente dentro de ese contexto.
- No confundas "journal ya committed" con "toda la pila de colocación ya terminó".

---

## 3. Falso rechazo de soporte pre-commit de Create por volver a resolver el host físico

### Síntoma observado

`rotatedcreateplacementpreservesminimacrosupportbeforeandaftercommit` fallaba con:

> destination Frame lost mini-backed macro support before Create commit

### Causa raíz

Durante el pre-commit de placement ya existían dos fuentes muy fuertes de verdad:

1. el pending move de Antikythera;
2. el `CreateAssemblyPlacementContext.Target` exacto para el Frame destino.

Aun así, `FrameFaceSupport` volvía a resolver el host físico mediante las condiciones normales del mundo/Sable.

En esa ventana el Frame destino acaba de ser colocado y la clasificación espacial de Sable puede estar atravesando un estado transitorio. Esa segunda resolución podía rechazar un target que ya estaba correctamente validado por el journal y por Create.

### Corrección aplicada

En la ventana pre-commit, `FrameFaceSupport` usa como autoridad el target de Create **solo después de validarlo contra el pending move**:

- misma assembly;
- misma orientación;
- mismos target Frames;
- offset lógico esperado;
- pose final realmente docked;
- ausencia de conflicts de recovery/piston;
- cara consultada no ocupada por otro Frame de la misma assembly.

Se eliminó únicamente la segunda resolución redundante del host en esa ventana concreta.

### Recomendaciones para futuros GameTests

- Cuando ya existe un journal transaccional validado, no mezcles en el mismo instante varias autoridades que pueden tener distinta latencia de actualización.
- Diseña tests que comprueben explícitamente que el estado pre-commit es consistente con el target lógico, no con una re-resolución espacial posterior.
- En tests de rotación, valida siempre tanto el offset físico como el lógico. Una transformación puede verse correcta físicamente y seguir asociando el Frame a la región mini equivocada.

---

## 4. Uso incorrecto de coordenadas locales frente a coordenadas globales del plot

### Síntoma observado

Durante la investigación aparecieron fallos de fixtures que parecían contaminación del plot, pero una versión experimental empeoró el problema al convertir coordenadas mini-locales a globales antes de pasarlas al `EmbeddedPlotLevelAccessor`.

### Causa raíz

En Sable 2.0.3 hay que distinguir claramente:

- `child.getPlot().getEmbeddedLevelAccessor()` recibe **coordenadas mini-locales**;
- el `ServerLevel` padre recibe **coordenadas plot-globales**.

El `EmbeddedPlotLevelAccessor` ya suma internamente el centro del plot. Si se le entrega una posición previamente convertida a global, se aplica la traslación dos veces.

### Corrección aplicada

Los GameTests volvieron a usar coordenadas locales para el accessor embebido.

La conversión mediante `MechanismSubLevelService.toPlotPosition(...)` se reserva para operaciones realizadas directamente sobre el `ServerLevel` padre.

### Recomendaciones para futuros GameTests

Usar esta regla como contrato:

```text
EmbeddedPlotLevelAccessor -> mini-local
ServerLevel               -> plot-global
```

Además:

- Nombra las variables con sufijos claros: `miniLocal`, `miniGlobal`, `framePos`, etc.
- Evita nombres genéricos como `pos` cuando una misma prueba usa varios espacios de coordenadas.
- No conviertas "por seguridad" antes de llamar a un accessor. Comprueba primero qué espacio espera la API.
- Si un test usa ambas vistas, incluye una aserción de equivalencia controlada entre local/global para detectar doble traslación rápidamente.

---

## 5. Bloques "fantasma" al reutilizar plots: cache stale de `ServerChunkCache`

### Síntomas observados

Este fue el problema más engañoso y el que explicaba gran parte de la aleatoriedad restante.

Tests como:

- `weststopkeepspreviouslylitrearlayerlit`
- `westfacingframekeepsallfrontminilampslitinflight`
- `sableassemblykeepscarriedmacrosupport`
- otros fixtures de lamps/redstone

fallaban con mensajes como:

- target already contains `redstone_lamp`;
- target already contains `stone`;
- target already contains `oak_slab`;
- `refreshFrame did not synchronize mini dust fixture`.

El bloque "preexistente" cambiaba entre perfiles y orden de ejecución, lo cual descartaba que hubiese un único bloque fijo mal colocado en la template.

### Causa raíz

Sable puede retirar un SubLevel vacío y reutilizar inmediatamente la misma coordenada de plot para otro SubLevel.

El nuevo `LevelPlot` sí crea un `LevelChunk` vacío nuevo y Sable actualiza sus propias estructuras. Sin embargo, Minecraft `ServerChunkCache` mantiene además un pequeño cache de los últimos chunks consultados, separado del `ChunkMap`.

Ese cache podía seguir conteniendo el `LevelChunk` perteneciente al SubLevel anterior en la misma coordenada global.

Por tanto una secuencia del mismo tick podía ser:

1. retirar SubLevel A;
2. reutilizar su plot para SubLevel B;
3. crear correctamente un chunk vacío nuevo para B;
4. consultar inmediatamente vía `ServerLevel`;
5. `ServerChunkCache` devolver todavía el chunk cacheado de A;
6. el GameTest observar un bloque residual aparentemente imposible.

Esto explicaba por qué la contaminación dependía del orden de tests y por qué aparecían bloques distintos.

### Corrección aplicada

Al crear contenido para un SubLevel administrado, `MechanismSubLevelService` invalida el cache de `ServerChunkCache` inmediatamente antes y después de instalar el chunk central nuevo del plot.

Se hace mediante un Mixin invoker a `ServerChunkCache.clearCache()`.

No se borran bloques manualmente y no se espera artificialmente varios ticks. Se corrige la autoridad real de la lectura.

### Recomendaciones para futuros GameTests

Esta es probablemente la recomendación más importante para fixtures mini:

- **No interpretes un bloque inesperado en un plot recién creado como "fixture sucio" sin comprobar primero qué chunk concreto se está leyendo.**
- Si el resultado cambia entre ejecuciones, perfiles o orden de tests, sospecha de cache/lifecycle antes que de coordenadas fijas.
- Los tests que crean y destruyen muchos SubLevels seguidos deben asumir que los plots pueden reutilizarse inmediatamente.
- Añade tests de regresión específicos de reutilización de plot, no solo de creación en un mundo limpio.
- Evita resolver un problema de stale state borrando el bloque inesperado. Eso oculta un bug real de aislamiento entre SubLevels.
- Mantén aserciones estrictas de "el target debe estar vacío". Fueron esas aserciones las que permitieron descubrir el problema de cache.

---

## 6. Por qué retrasar los fixtures varios ticks fue una mala solución

### Experimento realizado

Se probó temporalmente a retrasar dos ticks la escritura de fixtures mini para esperar a que el plot se "estabilizase".

### Qué ocurrió

Un SubLevel administrado recién creado y todavía vacío puede ser considerado físicamente vacío y retirado por el lifecycle normal antes de que llegue la escritura retrasada.

Aparecieron errores del tipo:

- `could not seed managed mini lamp`;
- child retirado antes de inicializar payload.

### Conclusión

Un delay no soluciona la autoridad del estado. Solo desplaza la carrera temporal y puede crear otra diferente.

### Recomendación

- No uses `runAfterDelay` para inicializar el primer contenido de un SubLevel vacío salvo que exista una razón funcional real para ello.
- Si una operación debe funcionar en el mismo tick, haz que el runtime sea correcto en el mismo tick.
- Usa delays únicamente para comprobar comportamiento realmente diferido: ticks de redstone, settling de física, timers, etc.

---

## 7. Por qué forzar `ChunkMap.promoteChunkMap()` tampoco era la solución

### Experimento realizado

Se intentó publicar inmediatamente el holder de Sable invocando `ChunkMap.promoteChunkMap()` mediante Mixin.

### Problemas encontrados

Hubo dos lecciones:

1. una primera versión falló en runtime porque el método objetivo no coincidía con el nombre/firma esperados en el bytecode efectivo;
2. incluso una versión posterior capaz de ejecutarlo no atacaba la verdadera causa restante: el cache propio de `ServerChunkCache`.

### Conclusión

Forzar la publicación global del `ChunkMap` era demasiado amplio y no invalidaba necesariamente todas las capas de cache que podían devolver el chunk antiguo.

### Recomendación

- Antes de tocar estructuras internas amplias de Minecraft, identifica exactamente qué capa devuelve el dato stale.
- Un GameTest que detecta un bloque residual debería instrumentarse conceptualmente como:
  - ¿qué `LevelPlot` posee esta posición?;
  - ¿qué `PlotChunkHolder` la contiene?;
  - ¿qué `LevelChunk` devuelve Sable?;
  - ¿qué `LevelChunk` devuelve `ServerChunkCache`?;
- Corrige la capa mínima responsable.

---

## 8. No usar un `ServerSubLevel` como si fuese un `Level` vanilla

### Experimento descartado

Se intentó construir un `UseOnContext` directamente con el SubLevel para hacer que la colocación de items operase "dentro" del mini-mundo.

No compilaba porque `ServerSubLevel` no es un `Level` vanilla compatible con esa API.

### Recomendación

- No fuerces tipos para intentar saltarte el routing normal de Minecraft/NeoForge.
- Cuando una integración expone un mundo embebido mediante accessors/transformaciones, respeta esa capa y las APIs que realmente acepta.
- Un fixture de GameTest puede usar una ruta de inicialización controlada, pero los tests de interacción de jugador deben seguir atravesando la misma ruta de producción que un jugador real.

---

## 9. Diferenciar errores del mod de ruido de dependencias en el log

Durante las ejecuciones aparecieron mensajes que no representaban regresiones de Antikythera:

- Sable intentando cargar `ClientLevel` en dedicated server;
- warnings de `ApiStatus$ScheduledForRemoval`;
- ausencia de `server.properties` en el entorno de GameTests;
- `create:flywheel` desconocido cuando Create no está cargado;
- fault injection intencional usada para probar rollback.

### Recomendación

- Mantén el scanner de logs estricto.
- Excluye únicamente mensajes conocidos mediante patrones **exactos**, no categorías enteras.
- No ignores globalmente `ERROR`, `ClassNotFoundException` o errores de mixin por el hecho de que una dependencia produzca un falso positivo concreto.
- Cada exclusión debería documentar qué dependencia la genera y por qué es segura.

---

# Reglas prácticas para nuevos GameTests de Antikythera

## A. Inicialización de mini contenido

1. Materializa el child mediante la ruta de producción correspondiente.
2. Usa posiciones mini-locales con `EmbeddedPlotLevelAccessor`.
3. Usa posiciones plot-globales únicamente al acceder al `ServerLevel` padre.
4. No introduzcas delays arbitrarios para "esperar a Sable".
5. Mantén las precondiciones estrictas: si esperas aire y hay otro bloque, falla el test y diagnostica la autoridad del chunk.

## B. Tests de Create

Un test completo debería verificar cuando corresponda:

1. estado estable inicial;
2. captura;
3. in-flight;
4. placement pre-commit;
5. post-commit dentro del placement context;
6. estado estable algunos ticks después.

No basta con comprobar solo el resultado final.

## C. Tests de soporte macro↔mini

- Comprueba soporte desde varias orientaciones.
- Incluye rotaciones de 90/180 grados.
- Incluye assemblies de varios Frames y formas irregulares.
- Comprueba caras exteriores y caras internas entre Frames por separado.
- Valida que durante captura/movimiento los bridges que deban estar desactivados realmente lo estén, pero que el soporte lógico congelado necesario para la transacción siga disponible.

## D. Tests de lifecycle de Sable

Añadir casos que creen, vacíen, retiren y vuelvan a crear SubLevels consecutivamente es especialmente útil.

Los bugs de aislamiento suelen no aparecer cuando cada test empieza siempre con una coordenada de plot nunca usada.

## E. Diagnóstico cuando un test es order-dependent

Si un GameTest pasa aislado pero falla en la suite completa:

1. no asumas flaky timing;
2. comprueba reutilización de recursos globales;
3. comprueba caches;
4. comprueba static/thread-local contexts no limpiados;
5. comprueba SubLevels/plots retirados;
6. comprueba que el test anterior no dejó estado en una autoridad distinta a la que limpia la template.

Un cambio de `stone` a `lamp` o `slab` entre ejecuciones es una pista fuerte de estado heredado, no de una coordenada matemática equivocada.

---

# Resumen de causas raíz corregidas

| Problema | Causa raíz | Solución aplicada |
|---|---|---|
| Bloques mini pierden soporte durante movimiento Sable | el macro origen desaparecía antes de que el destino fuese consultable | snapshot transaccional del estado macro durante `moveBlocks` |
| Soporte desaparece durante unwind de Create | pending move termina antes que el placement context síncrono | ruta post-commit estrecha validada contra el target de Create |
| Soporte pre-commit falla en rotaciones | re-resolución redundante del host físico durante una ventana transitoria | usar target + journal ya validados como autoridad en pre-commit |
| Fixtures apuntan a posiciones equivocadas | mezcla de coordenadas mini-locales y plot-globales | local para Embedded accessor, global para ServerLevel |
| Aparecen blocks residuales aleatorios en plots nuevos | `ServerChunkCache` conserva el `LevelChunk` del SubLevel anterior reutilizado | invalidar `ServerChunkCache` alrededor de la creación del chunk del plot |
| Delays hacen desaparecer children vacíos | lifecycle retira SubLevels antes del seed diferido | no retrasar el primer payload; corregir same-tick correctness |
| `promoteChunkMap` no resuelve contaminación | se atacaba una capa distinta al cache stale real | invalidar la capa mínima correcta: `ServerChunkCache` |

---

# Resultado de validación de la tarea

Tras las correcciones, la matriz completa quedó verde:

- JUnit: OK
- CORE GameTests: **49/49**
- Create GameTests: **49/49**
- Create + Simulated GameTests: **49/49**
- Build con Java 21: OK

La principal lección para futuras regresiones es evitar tratar Sable/Create como operaciones atómicas desde el punto de vista de un GameTest. Hay ventanas transitorias reales y varias capas de representación —mundo físico, mapping lógico, journal de movimiento, plot de Sable y caches vanilla—. Los tests más útiles son los que hacen explícitas esas fases y verifican cuál de ellas debe ser la autoridad en cada instante.
