# Plan de pruebas de Antikythera Mechanism

Estado de referencia: 11 de agosto de 2026. Este documento describe el árbol local actual; no es un certificado de release. Cada resultado debe volver a registrarse después de cualquier cambio en assembly, Sable, evacuación, mixins o dependencias.

## Estado real cubierto por este plan

El core actual dispone de:

- un `MechanismFrame` con `BlockEntity`, carcasa conectable y ocho celdas de colocación;
- un `MechanismAssembly` persistido por componente conexa de frames;
- un Sable `ServerSubLevel` por assembly, creado con escala uniforme `0.5` y un anchor técnico;
- mapeo frame ↔ mini `2 x 2 x 2`, incluido `floorDiv`/`floorMod` para offsets negativos;
- unión por caras, unión por frame puente y separación mediante `FrameGraph`;
- traslado de BlockStates, BlockEntities y scheduled block/fluid ticks al unir o separar;
- evacuación transaccional de las ocho celdas de un frame, con herramienta, inventarios, explosión, rollback e idempotencia durante la eliminación;
- protección de escrituras fuera de `FrameMask` y preflight de pistones dentro del SubLevel;
- pose semántica persistente y driver cinemático que conduce Sable sin rotar BlockStates internos;
- movimiento transaccional por pistones vanilla, incluida la retracción sticky, conservando UUID, SubLevel y contenido;
- seguimiento de assemblies completos en Create contraptions, con journal persistente, recuperación y rechazo seguro de capturas parciales;
- whitelist COMMON/datapack/API con hard-deny irrompible de frames anidados;
- cuatro Transmission Boxes opcionales para Create, proxies internos reservados, covers por puerto y propagación cinética nativa de RPM, ratios, stress y conflictos.

No están concluidos todavía:

- recuperación completa de entidades de bloques que caen, que siguen denegados por defecto;
- rotación Create de assemblies de varios frames; la traslación sí está soportada y la rotación insegura falla cerrada;
- validación visual/multiplayer exhaustiva en cliente;
- una suite NeoForge GameTest. La entrega local usa JUnit, builds y smokes dirigidos.

Por tanto, las pruebas marcadas como **pendiente GameTest** o **fallo esperado** no deben convertirse en un PASS informal.

## Comandos reproducibles

Ejecutar desde la raíz del proyecto en PowerShell, con Java 21:

```powershell
.\gradlew.bat --no-daemon compileJava --console=plain
.\gradlew.bat --no-daemon test --console=plain
.\gradlew.bat --no-daemon runServer --console=plain
```

Para las comprobaciones visuales e interacción real:

```powershell
.\gradlew.bat --no-daemon runClient --console=plain
```

Smoke test opcional con Create presente en runtime:

```powershell
.\gradlew.bat -Pinclude_create=true --no-daemon runServer --console=plain
.\gradlew.bat -Pinclude_create=true --no-daemon runClient --console=plain
```

El perfil Create habilita las Transmission Boxes y el follower de contraptions. El simple comando de arranque no prueba por sí solo la cinética; los resultados dirigidos ya ejecutados se registran más abajo.

No ejecutar aún `gameTestServer` como criterio de aceptación: no hay clases `@GameTest` ni una estructura `data/antikytheramechanism/structure/empty.nbt`, y la configuración de NeoForge puede finalizar con error cuando no encuentra tests.

Antes de una sesión manual:

1. Usar un mundo de desarrollo nuevo o una copia recuperable.
2. Entrar como operador en creativo; esperar al menos 20 ticks (un segundo) tras cada merge/split antes de inspeccionar NBT.
3. Obtener frames con `/give @s antikytheramechanism:mechanism_frame 64`.
4. Conservar `run/logs/latest.log` y anotar las coordenadas usadas.
5. Tras una prueba persistente, ejecutar `/save-all flush`, detener el servidor limpiamente y arrancarlo de nuevo.

Comandos de diagnóstico útiles:

```text
/data get block <x> <y> <z> assembly_id
/data get block <x> <y> <z> occupied_mask
/sable storage find_all_sub_levels
```

`occupied_mask` usa ocho bits y debe valer `255` cuando las ocho celdas están ocupadas. El comando de Sable es diagnóstico de desarrollo: si cambia entre versiones, registrar UUIDs y recuento desde log/NBT en vez de asumir una sintaxis nueva.

## Resultados ejecutados el 11 de agosto de 2026

- `compileJava` y la suite JUnit actual: **PASS** con Java 21.
- Servidor dedicado core, sin Create: **PASS**, incluyendo aplicación de ambos mixins y registro temprano del tipo de ticket persistente.
- Merge por puente de cuatro frames: **PASS**; los cuatro BlockEntities convergieron al mismo `assembly_id` y quedó un único SubLevel.
- Eliminación del puente central: **PASS**; quedaron dos componentes con UUID distintos y exactamente dos SubLevels a escala `0.5`.
- `/save-all flush`, `/stop` y nuevo proceso sobre el mismo mundo: **PASS**; ambos UUID y SubLevels se conservaron, sin recreación y sin `Unknown sub-level loading ticket type`.
- Migración del constraint fijo al driver de pose: **PASS** estacionario en servidor; tras guardar, ambos bounds permanecieron `0.5 × 0.5 × 0.5` en sus anclas.
- Pistón normal y retracción sticky: **PASS** en servidor dedicado; el mismo UUID sobrevivió a ambos desplazamientos y el journal confirmó traslación/reindexado sin recrear el SubLevel.
- Servidor dedicado con Create 6.0.10-280: **PASS**; la compatibilidad opcional y las Transmission Boxes se registraron sin afectar el perfil core.
- Four Shaft Transmission Box: **PASS**; motor, caja, proxy y shaft mini compartieron la misma red Create a `-16 RPM` (`1:1`).
- Four Small Cog / Two Large Cog / Two Small Cog: **PASS** dirigido; se observaron respectivamente los factores nativos `-1`, `-2` y `-0.5` (`+16`, `+32` y `+8 RPM` desde una caja a `-16 RPM`).
- Covers y diagonal A/B: **PASS** dirigido; cubrir un puerto retiró solo su proxy, descubrirlo lo reconstruyó, y cambiar diagonal trasladó el proxy sin dejar el anterior.
- Reinicio Create: **PASS**; caja, proxy, `LinkNonce`, red y velocidad mini se recuperaron automáticamente tras guardar y reiniciar.

Las pruebas de merge/split iniciales se realizaron con frames vacíos mediante RCON. Las pruebas posteriores cubrieron pistón y transmisión real, pero no certifican todavía interacción con mouse, render final, dos jugadores ni todos los BlockEntities de terceros; esas comprobaciones quedan como endurecimiento manual opcional.

## Pruebas automáticas existentes

`gradlew test` ejecuta la suite JUnit de lógica, journals y persistencia:

- `MiniCoordinateMapperTest`: ocho celdas únicas, round-trip, offsets negativos y bits `0..7`;
- `FrameMaskTest`: volumen exacto `2 x 2 x 2`, offsets negativos y actualización add/remove;
- `FrameGraphTest`: vecindad por seis caras, rechazo de contacto diagonal, puente y grafo vacío;
- `AssemblyPoseTest`: ancla estable, normalización, NBT, rebase rotado y entradas inválidas;
- `PendingPistonMoveTest`, `MechanismAssemblyTranslationTest` y `PendingContraptionMoveTest`: journals de movimiento, traducción/reindexado y NBT;
- `ContraptionPoseBindingTest` y `ContraptionRotationMathTest`: seguimiento rígido, rotación y entradas inválidas;
- `PendingFrameEvacuationTest`: snapshot/rollback y journal de recuperación;
- `KineticPortTypeTest`, `MiniKineticEndpointTest` y `TransmissionBridgeTest`: ratios/signos, identidad persistente, sobrestress y rechazo seguro de fuentes incompatibles;
- `TransmissionFaceOrientationTest` y `TransmissionPortLayoutTest`: orientación de caras y geometría determinista de los puertos.

Estas pruebas no arrancan Minecraft, no crean un Sable SubLevel y no sustituyen pruebas de render, networking, drops, ticks o persistencia real.

Registro recomendado para cada pasada:

| Fecha | Perfil | `compileJava` | `test` | Arranque | Sesión manual | Incidencias |
|---|---|---:|---:|---:|---:|---|
| YYYY-MM-DD | Core sin Create | PASS/FAIL | PASS/FAIL | PASS/FAIL | PASS/FAIL | enlace o resumen del log |
| YYYY-MM-DD | Con Create | PASS/FAIL | PASS/FAIL | PASS/FAIL | PASS/FAIL | enlace o resumen del log |

## Matriz manual: 1, 2 y N frames

| ID | Montaje y acción | Resultado exigido | Evidencia |
|---|---|---|---|
| F-01: un frame | Colocar un frame aislado, esperar un segundo y consultar su NBT. | UUID no nulo, `occupied_mask: 0`, un único SubLevel, escala visual `0.5`; el frame vacío sobrevive varios minutos. | NBT, comando Sable, captura y ausencia de recreación repetida en log. |
| F-02: ocho celdas | Desde las caras opuestas, colocar cuatro bloques distintos en cada capa hasta llenar las ocho posiciones. Repetir click sobre una celda ocupada. | Ocho bloques originales, cada stack baja exactamente una vez, `occupied_mask: 255`, `empty: false`; el noveno intento no consume ni reemplaza. | Vídeo corto, conteo de stacks y NBT. |
| F-03: dos frames | Colocar `[A][B]` por contacto de cara y construir bloques en ambos. | Mismo `assembly_id`, un SubLevel, espacio mini continuo de cuatro celdas por el eje compartido. Las barras y la colisión del separador desaparecen. | NBT de A/B, captura de modelo y recorrido/raycast por la unión. |
| F-04: N frames | Construir una L y luego un volumen irregular, por ejemplo `XX / X.`. Llenar celdas próximas al hueco. | Todos los frames conectados comparten UUID; el hueco no adquiere capacidad mini y no aparece una pared/barra interna donde sí hay frame vecino. | NBT de todos los frames, captura y prueba de máscara. |
| F-05: offset negativo | Colocar primero A y después un frame al oeste/abajo de A. Usar sus ocho celdas, guardar y recargar. | Las dos columnas mini negativas relativas pertenecen al frame correcto; no se desplazan al frame origen ni cambian tras reload. | Inventario/celdas antes y después; la aritmética también queda cubierta por JUnit. |
| F-06: puente múltiple | Crear frames aislados al oeste, este y norte de una posición vacía; colocar el frame central al final. | Los tres assemblies anteriores y el puente terminan con un solo UUID y un solo SubLevel. Ningún contenido se pierde o duplica. | Cuatro consultas NBT, recuento Sable e inventarios. |

### Colocación, raycast, colisión e interacción

1. En un frame vacío, apuntar a los cuatro cuadrantes de cada cara con stone, stairs, lever, repeater y chest. Verificar orientación normal de cada BlockState y consumo de un único item.
2. Apuntar a una celda mini ya existente. Sable/Sable Scale debe dar prioridad al bloque real cuando corresponda: abrir el chest, accionar el lever, romper el mini bloque y usar pick-block.
3. Caminar y saltar alrededor de la carcasa. El jugador normal solo colisiona con las barras visibles y con los minibloques escalados; no debe existir un cubo sólido invisible.
4. Observar desde dos clientes. Colocación, rotura, `occupied_mask`, GUI e inventario deben coincidir sin reentrar al mundo.

Estas cuatro comprobaciones son manuales porque requieren cliente, mouse, render, reach, collision y packets reales.

## Merge, continuidad y preservación

| ID | Montaje y acción | Resultado exigido |
|---|---|---|
| M-01: redstone | En `[A][B]`, tender lever/repeater/dust cruzando la frontera mini entre A y B. | La señal cruza como bloques adyacentes del mismo Level, sin connector especial. |
| M-02: BlockEntity | Poner un chest nombrado y con stacks distintos en un assembly aislado; unirlo mediante puente a otro assembly. | El mismo BE, nombre e inventario sobreviven una sola vez; el chest abre normalmente. |
| M-03: ticks | Programar una transición de repeater y un tick de fluido, y provocar el merge antes de que venzan. | Cada tick ocurre una vez en el destino y nunca en el SubLevel retirado. |
| M-04: vacíos | Unir dos grupos de frames completamente vacíos. | El `FrameGraph`, no el contenido interior, decide el merge; queda un assembly estable. |
| M-05: aislamiento Sable | Mantener además un SubLevel Sable ajeno al mod que pueda auto-dividirse. | El mixin solo suprime el heat-map split del SubLevel administrado por Antikythera; el ajeno conserva el comportamiento de Sable. **Pendiente GameTest**. |

Para M-02 y M-03, revisar el log en busca de `failed verification`, `attempting rollback`, `Could not merge` o recreaciones de SubLevel. Cualquiera de esos mensajes invalida el PASS aunque el aspecto final parezca correcto.

## Split y eliminación del frame central

Montaje base:

```text
[A][B][C]
```

1. Poner un chest con un inventario identificable en A, ocho bloques recuperables en B y otro BE/inventario en C.
2. Anotar los UUID y el número de SubLevels.
3. Romper B y esperar al menos 20 ticks.
4. Consultar A y C de nuevo.

Resultado exigido:

- B evacua exclusivamente sus ocho celdas y deja exactamente los drops correspondientes;
- A y C conservan BlockStates, BlockEntities, inventarios y scheduled ticks;
- A y C tienen UUID distintos entre sí y hay exactamente dos SubLevels a escala `0.5`;
- el SubLevel retirado no queda huérfano;
- ninguna operación interna rota BlockStates;
- repetir save/reload no vuelve a evacuar ni duplica contenido.

Variantes obligatorias:

- romper primero un frame extremo: el assembly restante no debe dividirse;
- quitar varios frames con `/fill ... air`: cada frame debe evacuarse una vez y el grafo final debe ser correcto;
- quitar el último frame: sus ocho celdas se recuperan y el SubLevel/anchor técnico se elimina;
- ejecutar veinte ciclos colocar–llenar–romper y comparar conteos exactos para detectar duplicación rara.

La eliminación masiva aún no se agrupa en una transacción de grafo de fin de tick. Debe tratarse como prueba agresiva pendiente, no como garantía, porque varios `onRemove` consecutivos pueden producir splits intermedios.

## Drops, herramienta, inventario y explosión

| ID | Acción | Resultado exigido |
|---|---|---|
| D-01: herramienta normal | Colocar stone y un bloque con requisito de herramienta dentro de B; romper el frame con pico adecuado y luego con herramienta inadecuada en otra copia. | Cada mini bloque usa `canHarvestBlock` y su loot real con la herramienta capturada. No aparecen drops de un bloque no cosechable. |
| D-02: Silk Touch | Colocar stone/ore sensible a Silk; romper el frame con un pico Silk Touch. | Se obtiene el drop Silk exacto, no la variante sin Silk. |
| D-03: Fortune | Llenar copias equivalentes con ore y romper con Fortune; repetir muestra suficiente. | Se usa la loot table Fortune real. La aserción determinista debe convertirse en GameTest con RNG controlado. |
| D-04: inventario | Llenar un chest con varios stacks, romper el frame. | Chest y contenido aparecen una sola vez; ninguna copia queda en el plot. |
| D-05: posición visual | Romper un frame cargado y observar dónde nacen los items. | Cada item nace en la posición padre transformada de su celda mini, no en coordenadas lejanas del plot. |
| D-06: explosión | Rodear un frame con una barrera de recogida, llenarlo con ocho contenidos contables y detonarlo con TNT varias veces. | Se recupera el 100 % del contenido mini de ese frame, sin decay ni duplicación; A/C sobreviven o se separan correctamente según la explosión. XP de evacuación no es requisito para explosión. |
| D-07: comando | Ejecutar `/setblock <frame> air`. | El fallback genérico evacua las ocho celdas antes de retirar el frame. El comando no tiene por qué dropear el item del propio frame. |
| D-08: idempotencia | Forzar una ruta que invoque hook de jugador/explosión y después `onRemove`. | El total de contenido es uno, nunca dos. |

La comprobación de explosión debe hacerse primero sin otros mods que cancelen `BlockDropsEvent`. El código actual respeta la cancelación de ese evento; una cancelación externa puede suprimir drops y debe registrarse como incompatibilidad, no confundirse con decay vanilla.

## FrameMask y pruebas negativas

| ID | Acción | Resultado exigido o estado actual |
|---|---|---|
| G-01: fuera de máscara | Intentar escribir en las seis celdas inmediatamente externas a un único frame mediante fixture de servidor. | El `LevelChunk#setBlockState` administrado devuelve `null` (fallo vanilla); ninguna escritura non-air aparece. **Pendiente GameTest**, porque el plot no tiene interfaz de operador estable. |
| G-02: bypass | Dentro de un fixture, ejecutar merge/split/evacuación con bypass y después lanzar una excepción deliberada. | La operación controlada puede escribir; al salir, incluso por excepción, una escritura externa vuelve a rechazarse. **Pendiente GameTest**. |
| G-03: piston contenido | En dos frames, usar un piston mini para empujar un bloque de una celda válida a otra válida. | Movimiento normal y contenido intacto. |
| G-04: piston al vacío | Orientar piston y sticky piston para que empujen desde la última celda válida hacia un frame inexistente. | `PistonEvent.Pre` cancela antes de mover; source, BE e inventario quedan intactos. |
| G-05: agua/lava | Verter cada fluido en un frame parcialmente vacío junto a un borde sin frame y esperar muchos fluid ticks. | El fluido puede ocupar celdas válidas, pero nunca materializa un BlockState fuera de máscara. |
| G-06: bloque de caída | Soltar sand/gravel/concrete powder hacia un hueco exterior. | No debe quedar bloque fuera de máscara. **Fallo esperado actual:** la escritura queda protegida, pero todavía no existe recuperación garantizada de la `FallingBlockEntity`; puede perderse o quedar en un estado incorrecto. |
| G-07: mod externo | Sostener un BlockItem de un mod no registrado y usarlo en el frame. | Rechazo sin consumo. El registro actual deniega namespaces no vanilla. |
| G-08: recursión | Intentar usar un Mechanism Frame como minibloque y repetir mediante escritura low-level. | Ambas rutas se rechazan sin consumo ni mutación. El guard comprueba el frame antes incluso del bypass interno. **Pendiente GameTest** para certificar el hook real. |
| G-09: bloques internos | Probar portal, moving piston, structure/jigsaw, command blocks, barrier, light y anchor técnico mediante el camino de colocación disponible. | Rechazo; nunca deben convertirse en contenido mini. |

La whitelist actual es conservadora y deniega por defecto los bloques no registrados. Falling blocks, camas, shulkers, puertas, TNT y otros comportamientos complejos permanecen en deny hasta superar pruebas explícitas.

## Persistencia y reinicio completo

Preparar en un mismo mundo:

- un frame con las ocho celdas ocupadas;
- un `[A][B]` con redstone cruzando el límite;
- un assembly irregular con offset relativo negativo;
- dos componentes producidos al romper el puente de `[A][B][C]`;
- chests con nombres e inventarios diferentes en cada assembly.

Anotar `assembly_id`, `occupied_mask`, conteo de SubLevels e inventarios. Ejecutar:

```text
/save-all flush
/stop
```

Arrancar de nuevo con `runServer` y verificar:

1. Los UUID de cada componente son los mismos.
2. Existe exactamente un SubLevel por assembly y no se crea uno nuevo cada 20 ticks.
3. Escala, pose estacionaria y celdas mini no cambian.
4. BlockStates, BlockEntities, inventarios y redstone siguen funcionando.
5. `occupied_mask` se resincroniza con el contenido real.
6. El anchor técnico no es visible/seleccionable y mantiene vivo un assembly vacío.
7. Después de unload/reload de chunks no aparecen drops, duplicados ni `Managed Sable SubLevel ... removed`.

Esta prueba requiere proceso de servidor realmente detenido y arrancado; desconectar y volver a entrar no es equivalente.

## Multiplayer manual

Con dos clientes conectados al mismo servidor dedicado:

1. Ambos miran el mismo assembly mientras P1 llena las ocho celdas; P2 debe ver modelos/outline y poder interactuar sin relog.
2. P1 mantiene abierto un chest mini mientras P2 añade o rompe un frame no propietario del chest; no debe haber desync ni dupe.
3. P1 crea el puente de tres assemblies y P2 consulta/usa contenido durante la convergencia.
4. P2 rompe el frame central con Silk Touch; ambos ven un único lote de drops y dos componentes.
5. Ambos se alejan hasta descargar chunks y regresan desde direcciones distintas.

El servidor es la autoridad: diferencias solo visuales, `occupied_mask` distinto o GUI fantasma son FAIL aunque se arreglen al reconectar.

## Estado de las fases 6–9

### Movimiento

Vanilla 1.21.1 rechaza de forma nativa mover bloques con BlockEntity, por lo que el mod usa una ruta estrecha con preflight y journal persistente. El pistón mueve todos los frames del assembly como unidad lógica, traduce el índice y actualiza únicamente la pose del SubLevel. Empuje y retracción sticky conservaron UUID y SubLevel en servidor dedicado.

Create usa un lifecycle separado: valida que la contraption haya capturado el assembly completo, suspende la evacuación normal mientras está transportado, sigue su transform y finaliza el reindexado al colocarlo. Las traslaciones de assemblies completos y las rotaciones de un frame están soportadas; una rotación de varios frames se rechaza antes de capturar porque todavía no existe un remapeo discreto seguro para esa topología.

### Create

El core arranca sin Create y ninguna clase core carga tipos Create. Cuando Create está presente se registran cuatro cajas públicas y un cover: Four Shaft, Four Small Cog, Two Large Cog y Two Small Cog Transmission Box. Los puertos reservan proxies internos protegidos dentro del mismo `ServerLevel` usado por Sable, de modo que Create forma una única `KineticNetwork` real y conserva sus reglas nativas de RPM, dirección, stress, over-stress y conflicto.

Cada puerto es independiente. Los covers retiran o reconstruyen solo el proxy seleccionado, las cajas diagonales permiten alternar A/B con wrench, y NBT persiste máscara, orientación, nonce y binding. Merge, split y evacuación quiescen la red antes de transferir y la reconstruyen al completar o hacer rollback. El registro, recetas, loot y modelos están condicionados a Create, por lo que quitar la dependencia opcional no rompe el arranque core.

## Backlog NeoForge GameTest

Crear una clase `MechanismFrameGameTests` con `@GameTestHolder("antikytheramechanism")`, `@PrefixGameTestTemplate(false)` y la plantilla singular:

```text
src/main/resources/data/antikytheramechanism/structure/empty.nbt
```

Prioridad P0:

- `frame_creation_scale_half`
- `normal_blockitem_placement_consumes_once`
- `mask_allows_eight_and_rejects_outside`
- `nested_frame_low_level_rejected`
- `adjacent_merge` y `bridge_merge_multiple_neighbors`
- `remove_middle_splits`
- `evacuates_only_eight`
- `merge_split_preserve_block_entity_and_inventory`
- `silk_touch_evacuation`
- `explosion_no_decay`
- `idempotent_no_dupe`
- `saved_data_and_full_sublevel_reload`

Prioridad P1:

- `quadrants_all_faces`
- `piston_inside_mask` y `piston_crossing_mask_cancelled`
- `water_and_lava_contained`
- `falling_block_outside_recovered`
- `merge_preserves_scheduled_block_and_fluid_ticks`
- `redstone_crosses_frame_boundary`
- `generic_setblock_removal`
- `foreign_sable_sublevel_still_auto_splits`

Las pruebas de Silk/Fortune deben construir encantamientos mediante holders del registry de 1.21.1. `GameTestHelper.destroyBlock` no transporta una herramienta, de modo que la prueba estable debe llamar la evacuación con una herramienta copiada y mantener aparte una prueba pequeña del orden de hooks del jugador.

Mantener manuales, incluso después de añadir GameTests: render a `0.5`, outline, mouse ray priority, reach, player collision, GUI real, dos jugadores, restart de proceso, Flywheel y movimiento suave de contraption.

## Criterio de cierre local

Esta entrega experimental se considera cerrada localmente cuando:

- la suite JUnit y los builds core/Create pasan en el árbol actual;
- los smokes dedicados confirman arranque, merge/split/reload, movimiento por pistón y los ratios Create principales;
- no queda una ruta conocida que duplique o elimine silenciosamente el payload durante transferencias;
- las limitaciones de caída, validación visual/multiplayer y rotación Create multi-frame permanecen explícitas.

El backlog GameTest y la matriz manual completa son endurecimiento para una release posterior, no un bloqueo para este cierre local solicitado.

## Triage de logs

Invalidan la sesión:

- fallos de aplicación de mixin;
- `ClassNotFoundException`/`NoClassDefFoundError` de Create en perfil core;
- `Failed to evacuate`, `failed verification`, `Could not merge/split`;
- eliminación o recreación continua de un SubLevel administrado;
- más o menos SubLevels que assemblies después de estabilizar;
- items/BEs encontrados en coordenadas del plot tras la operación.

Sable puede emitir avisos propios sobre IDs Create ausentes en el perfil sin Create, y Sable/Sable Scale pueden emitir avisos client-only durante servidor dedicado. Guardarlos como ruido de dependencia separado, pero no usarlos para ocultar una excepción, un fallo de mixin o una pérdida de estado de Antikythera.
