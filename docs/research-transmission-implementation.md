# Diseño ejecutable: Create Transmission Boxes (fases 7–9)

## Alcance y fuentes verificadas

Este documento diseña la implementación contra las versiones fijadas por el proyecto:

- Minecraft `1.21.1`;
- NeoForge `21.1.228`;
- Create `6.0.10-280`;
- Sable `2.0.3`;
- Sable Scale `1.2.0`.

Se contrastaron los sources JAR locales de Create y NeoForge, el código actual del mod y
`docs/research-sable.md`. No se ejecutó Minecraft ni se implementaron las cajas durante esta
investigación.

Clases de Create verificadas:

```text
com.simibubi.create.content.kinetics.RotationPropagator
com.simibubi.create.content.kinetics.KineticNetwork
com.simibubi.create.content.kinetics.TorquePropagator
com.simibubi.create.content.kinetics.base.KineticBlock
com.simibubi.create.content.kinetics.base.KineticBlockEntity
com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity
com.simibubi.create.content.kinetics.base.DirectionalKineticBlock
com.simibubi.create.content.kinetics.base.IRotate
com.simibubi.create.content.kinetics.simpleRelays.ICogWheel
com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity
com.simibubi.create.content.equipment.wrench.IWrenchable
com.simibubi.create.foundation.block.IBE
```

## Corrección arquitectónica importante

Un `ServerSubLevel` de Sable 2.0.3 **no es otro `Level`**. Sus chunks son chunks reales del
`ServerLevel` padre en coordenadas lejanas del plot-yard. Por ello, un BlockEntity mini observa:

```text
be.getLevel()    == ServerLevel padre
be.getBlockPos() == posición global real del plot
```

Esto está documentado y comprobado en `docs/research-sable.md`, secciones 2, 3 y 7. También
coincide con `TorquePropagator`: las redes se indexan por `LevelAccessor`, y tanto la caja macro
como el proxy mini terminan en el mismo mapa del `ServerLevel`.

Consecuencia: para estas versiones no hace falta, ni conviene, reflejar RPM/SU mediante dos
generadores virtuales. Es posible crear una arista cinética remota y validada entre la caja y sus
proxies del plot. Create mantiene entonces una sola `KineticNetwork` y conserva sus propias reglas
de fuentes, ratios, stress, sobrecarga y conflicto.

Esto sigue siendo una arquitectura bridge/proxy y **no une redes de Levels distintos**. Si una
versión futura de Sable entrega a los KBEs un `Level` distinto, esta estrategia deja de ser válida
y el bootstrap debe rechazar esa combinación de versiones en vez de copiar IDs de red entre Levels.

## Arquitectura propuesta

```text
red macro Create
      |
TransmissionBoxBlockEntity
      |  aristas remotas custom +1, con nonce y peer exactos
      +--------------------+--------------------+--------------------+
      |                    |                    |                    |
InternalPortBE 0     InternalPortBE 1     InternalPortBE 2     InternalPortBE 3
      | regla normal       | regla normal       | regla normal       | regla normal
      | shaft/cog Create   | shaft/cog Create   | shaft/cog Create   | shaft/cog Create
red(es) mini Create dentro de las celdas propiedad del FrameMask
```

El `TransmissionLinkCoordinator` administra topología, reserva posiciones, valida peers y
reconstruye enlaces. **No llama** a `setSpeed`, `setSource`, `setNetwork` ni
`updateFromNetwork` para simular energía. La propagación y el stress quedan bajo autoridad de
Create.

Las firmas reales usadas para la arista remota son:

```java
public List<BlockPos> addPropagationLocations(
    IRotate block, BlockState state, List<BlockPos> neighbours);

public float propagateRotationTo(
    KineticBlockEntity target,
    BlockState stateFrom,
    BlockState stateTo,
    BlockPos diff,
    boolean connectedViaAxes,
    boolean connectedViaCogs);

public boolean isCustomConnection(
    KineticBlockEntity other, BlockState state, BlockState otherState);
```

`RotationPropagator` acepta posiciones adicionales sin límite de distancia. Antes de añadir un
peer remoto, el código debe comprobar `level.isLoaded(peerPos)`/`hasChunkAt` para no forzar chunks.
La arista caja↔proxy devuelve `+1.0f` en ambos sentidos solamente cuando posición, UUID de
assembly, índice de puerto y nonce coinciden en ambos BlockEntities.

## Jerarquía exacta de clases

### Bloque exterior

```java
final class TransmissionBoxBlock
    extends DirectionalKineticBlock
    implements IBE<TransmissionBoxBlockEntity>, TransformableBlock
```

Una sola clase se instancia cuatro veces con un `TransmissionBoxKind` inmutable:

```text
FOUR_SHAFTS
FOUR_SMALL_COGS
TWO_LARGE_COGS
TWO_SMALL_COGS
```

Estado de bloque:

```text
FACING       DirectionProperty  // cara MINI; dirección caja -> frame
FACE_ROLL    IntegerProperty    // 0..3; orientación completa de cuadrantes
DIAGONAL_B   BooleanProperty    // usada por las dos cajas de dos cogs
```

`FACING + FACE_ROLL` representa las 24 orientaciones del cubo. Guardar solamente `FACING` pierde
la permutación de covers/cuadrantes al girar alrededor de la normal de la cara.

Comportamiento cinético macro:

```java
hasShaftTowards(..., Direction face) = face != state.getValue(FACING);
getRotationAxis(state) = state.getValue(FACING).getAxis();
```

`RotationPropagator` decide una unión de shaft por los dos `hasShaftTowards`; no exige que el eje
devuelto por `getRotationAxis` coincida. Un solo KBE puede por tanto unir las otras cinco caras con
factor `+1`, que es exactamente la transmisión interna pedida. La renderización debe tratar cada
shaft exterior con su eje real; no debe usar el eje nominal del bloque para los cinco.

La BE exterior es pasiva:

```java
final class TransmissionBoxBlockEntity extends KineticBlockEntity
```

No debe extender `GeneratingKineticBlockEntity`: la caja no crea capacidad ni RPM. Su función es
relé/topología.

### Bloques internos del service shell

No tienen BlockItems ni loot tables:

```java
abstract class InternalTransmissionPortBlock
    extends KineticBlock
    implements IBE<InternalTransmissionPortBlockEntity>

final class InternalShaftPortBlock
    extends InternalTransmissionPortBlock

final class InternalCogPortBlock
    extends InternalTransmissionPortBlock
    implements ICogWheel
```

Se registran dos instancias de `InternalCogPortBlock`, una small y otra large, porque
`ICogWheel#isSmallCog/isLargeCog` clasifica por instancia de bloque, no por BlockState.

La BE común es:

```java
final class InternalTransmissionPortBlockEntity
    extends SimpleKineticBlockEntity
```

`SimpleKineticBlockEntity` ya añade las posiciones diagonales requeridas por un large cog. Para un
small cog delega en `KineticBlockEntity`, que añade las diagonales del plano de rotación.

Propiedades internas:

- shaft: `FACING` indica la única cara de shaft, desde el proxy hacia la celda mini;
- cog: `AXIS` indica el eje real del cog;
- selección y visual shape vacías;
- colisión vacía;
- piston reaction `BLOCK`;
- sin drops y sin item;
- toda interacción de jugador devuelve `PASS`/no encuentra shape.

`addPropagationLocations` del endpoint debe filtrar cualquier otro
`InternalTransmissionPortBlockEntity`. Esto evita engranes accidentales entre proxies contiguos de
la misma u otra caja. El target mini normal se conserva en la lista. La conexión entre proxies de
una caja ya existe a través de la estrella remota con el KBE exterior.

## Registros Create-only

Crear `CreateTransmissionRegistries` dentro del paquete aislado de compatibilidad:

```java
DeferredRegister.Blocks BLOCKS
DeferredRegister.Items ITEMS
DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES
```

IDs públicos:

```text
four_shaft_transmission_box
four_small_cog_transmission_box
two_large_cog_transmission_box
two_small_cog_transmission_box
mini_shaft_cover
```

IDs internos, sin BlockItem:

```text
internal_shaft_port
internal_small_cog_port
internal_large_cog_port
```

Un `BlockEntityType<TransmissionBoxBlockEntity>` admite los cuatro bloques exteriores. Un
`BlockEntityType<InternalTransmissionPortBlockEntity>` admite los tres internos.

`CreateIntegration.register(IEventBus)` debe llamar a los tres `DeferredRegister#register(modBus)`
durante la construcción del mod, después del check reflectivo de `ModList`, no durante common setup.
Common setup instala registries simples de Create, movement checks y el coordinador exactamente una
vez.

No mover estos registros a `ModRegistries`: una clase cargada siempre no debe tener superclasses,
campos genéricos ni factories que mencionen tipos de Create.

## Geometría exacta de los cuatro puertos

Definiciones:

```text
miniFace = dirección desde la caja hacia el Mechanism Frame
outward  = miniFace.opposite(), desde el frame hacia la caja
u, v     = base ortonormal discreta de la cara, transformada por FACE_ROLL
q        = (uBit, vBit), cada bit en 0..1
```

La base debe ser determinista para las seis caras y cumplir una convención de mano fija. Todas las
rotaciones/mirrors se calculan transformando `miniFace` y `u`, y luego recuperando `FACE_ROLL`; no
se escriben tablas parciales independientes para blockstate, renderer e hit test.

Para un frame, la celda interior del cuadrante usa:

- bit normal `1` si `outward` es positivo, `0` si es negativo;
- bit tangente `q` si la dirección base es positiva, `1-q` si es negativa.

El proxy se coloca una celda fuera:

```text
servicePos(q) = insideBoundaryCell(q).relative(outward)
```

### Four Shafts

```text
quadrants    = 00, 10, 01, 11
service type = SHAFT
target       = insideBoundaryCell(q)
shaft axis   = outward.axis
factor       = +1
```

El target es válido si es `IRotate` y expone shaft hacia `outward`.

### Four Small Cogs

```text
quadrants    = 00, 10, 01, 11
service type = SMALL_COG
target       = insideBoundaryCell(q)
cog axis     = u.axis
diff         = -outward
factor       = -1
```

Esto cumple literalmente la rama small↔small de Create: distancia Manhattan 1, desplazamiento
perpendicular al eje y ejes iguales.

### Two Large Cogs

```text
configuration A = 00, 11
configuration B = 10, 01
service type    = LARGE_COG
target type     = SMALL_COG
target(q)       = insideBoundaryCell(uBit, 1-vBit)
cog axis        = u.axis
diff            = -outward ± v
service -> target factor = -2
target -> service factor = -0.5
```

El desplazamiento tiene componente 0 en `u` y valor absoluto 1 en los otros dos ejes, exactamente
la geometría `large↔small` de `RotationPropagator`.

### Two Small Cogs

Misma geometría diagonal, intercambiando tipos:

```text
service type    = SMALL_COG
target type     = LARGE_COG
service -> target factor = -0.5
target -> service factor = -2
```

La escala espacial `0.5` no participa en ningún ratio.

La tabla central `KineticPortType` ya contiene los cuatro factores correctos. La implementación
física debe validarlos en tests, pero dejar que las ramas estándar de Create los apliquen; no
repetir `-1`, `-2` o `-0.5` en el coordinador.

## Service shell y reserva de posiciones

Crear un `TransmissionServiceShell` con un índice autoritativo:

```text
(assembly UUID, mini local BlockPos) -> (box nonce, port index, expected internal block)
```

Reglas del guard:

- un bloque interno solo se coloca con un bypass específico del service shell;
- una escritura normal no puede poner bloques en una posición reservada;
- el endpoint no cuenta como celda del frame ni se evacua/dropea como contenido;
- al cubrir/desvincular un puerto se retira idempotentemente;
- una posición con un bloque ajeno nunca se sobrescribe: la caja entra en estado de recuperación;
- cada posición se valida con `MechanismSubLevelService.canAddressMiniPosition` antes de reservar.

El anchor actual usa inicialmente `(0,-1,0)`, que colisiona con el cuadrante inferior de una caja
bajo el frame de origen. Debe migrarse a una segunda capa segura (por ejemplo, dos celdas bajo el
frame globalmente más bajo) o asignarse mediante el mismo allocator. La reconciliación debe mover
un anchor antiguo antes de instalar un endpoint, nunca sobrescribirlo.

## Coordinador y estados

`TransmissionLinkCoordinator` es server-authoritative y trabaja por `ServerLevel`. Mantiene
`TransmissionTopologySavedData` y no fuerza chunks.

Estado sugerido por caja:

```text
UNBOUND
INSTALLING_LOCAL
PREFLIGHT
ACTIVE
SUSPENDED_UNLOADED
SUSPENDED_MOVING
CONFLICT
RECOVERY_REQUIRED
```

Activación transaccional:

1. Resolver el frame exactamente en `boxPos.relative(FACING)`.
2. Resolver assembly y SubLevel existentes; nunca crearlos como efecto lateral.
3. Rechazar locks de content recovery, piston, merge/split o contraption capture.
4. Calcular cuadrantes, service positions y targets.
5. Reservar todas las posiciones; si una falla, no instalar ninguna.
6. Colocar/reconciliar proxies con enlace remoto todavía desactivado.
7. Dejar que cada proxy se adjunte a su red mini local.
8. Comparar el RPM teórico del KBE macro y de cada proxy no-cero. Los proxies ya expresan la
   velocidad interna canónica porque el engrane local aplica el ratio antes de llegar a ellos.
9. Si hay velocidades incompatibles, no activar peers y marcar `CONFLICT`; nunca elegir una fuente.
10. Si son compatibles, habilitar el peer remoto en ambos extremos, reconstruir cinética y marcar
    `ACTIVE`.

Al cambiar topología, usar el mismo orden que Create emplea al sustituir un bloque:

```text
remove from existing KineticNetwork (si existe)
detachKinetics()
removeSource()/clearKineticInformation()
updateSpeed = true
```

Encapsularlo en una sola utilidad y ejecutarlo solo en servidor. No dejar memberships huérfanas
en `KineticNetwork.members`.

El flag que permite una arista remota debe ser transitorio. Después de reload, primero se limpian
los datos cinéticos derivados de nuestros KBEs, se reconstruyen las conexiones locales, se hace
preflight y solo entonces se reactiva el enlace remoto. Esto evita aceptar ciegamente un `Source`
persistido que ahora apunta a un peer incompatible.

## RPM, stress y conflictos

Con la arista remota activa, Create calcula de forma nativa:

```text
actual stress   = base impact   * abs(theoretical RPM)
actual capacity = base capacity * abs(generated RPM)
overstressed    = total capacity < total stress
```

Los KBEs de caja/proxy son relés pasivos con impacto/capacidad base cero. Las fuentes y consumers
reales, macro y mini, quedan en la misma red y aportan sus valores una sola vez. Los cambios de
ratio de cogs alteran el RPM de sus miembros y por tanto el SU exactamente como Create.

Ventajas frente a un espejo manual:

- no hay realimentación de RPM inyectado;
- no hay que inventar capacidad virtual;
- no se recorren/escriben los mapas `sources`/`members`;
- fuentes compatibles comparten capacidad;
- fuentes incompatibles siguen la lógica normal de `RotationPropagator`;
- over-stress detiene toda la red de forma coherente en el mismo tick de red.

El preflight evita que Create rompa un proxy invisible al unir redes ya incompatibles. Si una fuente
nueva se vuelve incompatible después de activar el enlace, Create debe conservar su comportamiento
normal con el bloque que introdujo/cambió la incompatibilidad. Si un proxy desaparece por una
incompatibilidad inesperada, el coordinador **no lo reconstruye en bucle**: pasa a `CONFLICT` y
requiere que desaparezca la causa o que se reconfigure la caja.

## Covers y wrench

`Mini Shaft Cover` es un `Item` normal Create-only. Solo la caja Four Shafts acepta covers.

Persistencia de la caja:

```text
coveredPortsMask  4 bits
bindingNonce      UUID o long aleatorio
boundAssemblyId   UUID, validado/reconciliado
linkStatus        valor para cliente/diagnóstico
```

`FACING`, `FACE_ROLL` y `DIAGONAL_B` ya persisten en el BlockState.

Hit testing:

1. exigir que la cara clicada sea `FACING`;
2. proyectar `hitLocation - blockPos` sobre la misma base `(u,v)` usada por renderer/mapping;
3. obtener un índice `uBit | (vBit << 1)`;
4. el servidor vuelve a validar item, mask, binding y permisos.

Instalar cover:

- reservar cambio;
- suspender enlace;
- retirar el proxy correspondiente;
- confirmar que quedó air;
- actualizar mask, `setChanged()`, `sendData()` y consumir item;
- reactivar los puertos restantes.

Retirar cover con wrench:

- solo al golpear un cuadrante cubierto de la cara mini;
- reconstruir primero el proxy y confirmar activación segura;
- después limpiar el bit y devolver el item (salvo creative);
- si no se puede reconstruir, mantener el cover y no entregar item.

Para las cajas de dos cogs, wrench sobre la cara mini alterna `DIAGONAL_B`; wrench en otra cara
rota la orientación mediante `KineticBlockEntity.switchToBlockState`. La transformación es
transaccional: suspender, prevalidar nuevas posiciones, cambiar estado, reconstruir. Shift+wrench
conserva la semántica normal de pickup/break.

Los covers instalados se añaden en `TransmissionBoxBlock#getDrops` leyendo el BE desde
`LootParams`. No se llaman drops manuales también en `onRemove`; así wrench, jugador y explosión
usan una sola ruta y no duplican.

## Persistencia de enlaces

`TransmissionTopologySavedData` guarda por caja:

```text
box BlockPos global
binding nonce
assembly UUID
frame BlockPos esperado
box kind
lista de (port index, mini service local pos, mini target local pos, KineticPortType)
estado de recuperación si lo hubiera
```

El BE exterior repite solo nonce, mask y assembly para recuperación local. Cada BE interno guarda:

```text
parent box global BlockPos
binding nonce
assembly UUID
port index
```

La duplicación se valida en ambos sentidos antes de exponer una arista. Un proxy obsoleto nunca se
enlaza a una caja nueva colocada en la misma posición porque el nonce cambia.

Las posiciones globales del plot se derivan de `subLevel.getPlot().getCenterBlock() + miniLocal`;
SavedData conserva mini-local para sobrevivir a una reasignación/rebuild del plot.

## Merge, split, evacuación y movimiento

Este es el principal requisito transversal antes de declarar completas las fases 7–9.

### Merge/split

Los KBEs Create guardan `Speed`, `Source` y `Network`, todos derivados de su posición global en el
plot. Copiar ese NBT sin quiesce a otro plot deja sources e IDs obsoletos.

El manager necesita hooks de lifecycle Create-independent:

```text
beforeAssemblyTransfer
afterAssemblyTransfer
onAssemblyTransferRollback
beforeFrameEvacuation
afterFrameGraphChanged
```

El adapter Create, en `beforeAssemblyTransfer`, debe:

1. suspender cajas/proxies afectados;
2. retirar proxies del service shell;
3. quitar cada mini `KineticBlockEntity` afectado de su red;
4. `detachKinetics`, limpiar source/network/speed y poner `updateSpeed = true`;
5. permitir que el snapshot transaccional capture ese NBT cinético limpio.

Tras commit o rollback, los bloques reales reconstruyen sus redes por propagación normal. La
configuración funcional de cada máquina Create permanece en su NBT; solo se invalida el cache
cinético derivado.

Sin este hook, un test de merge/split con Create activo no es seguro aunque los bloques visualmente
aparezcan en el destino.

### Evacuación

Los proxies están fuera del `FrameMask`, por lo que la evacuación de las ocho celdas no los mueve ni
los dropea. `beforeFrameEvacuation` debe suspender y retirar todos los proxies ligados a ese frame.
Si no puede hacerlo/verificarlo, la evacuación se rechaza y conserva el frame.

### Pistones y contraptions

La caja exterior debe:

- registrarse `NO_PICKUP`;
- declararse attached al frame solo por su cara mini;
- suspender su enlace antes de que Create retire bloques del mundo;
- no transmitir a redes externas mientras viaja como actor;
- re-resolver frame/assembly y reconstruir proxies al desmontar.

No persistir un `boundFramePos` absoluto como única autoridad; se deriva de la posición final de la
caja y `FACING`. El nonce y la relación local sobreviven a traslación.

El soporte actual de assembly todavía rechaza/desaconseja desmontaje rotado si no puede conservar
el mapping frame↔mini. Las Transmission Boxes no deben prometer rotación discreta hasta que ese
bloqueo de fase 6 esté resuelto. Traslación sí puede rebindearse.

## Render y cliente

Registrar en `EntityRenderersEvent.RegisterRenderers`:

```java
event.registerBlockEntityRenderer(
    CreateTransmissionRegistries.TRANSMISSION_BOX_BE.get(),
    TransmissionBoxRenderer::new);
```

El registro vive en `compat.create.client` y solo se carga en Dist.CLIENT después del bootstrap de
Create. No usar un `@EventBusSubscriber` siempre escaneado que enlace clases de Create cuando el mod
está ausente.

`TransmissionBoxRenderer extends KineticBlockEntityRenderer<TransmissionBoxBlockEntity>`:

- renderiza cinco `AllPartialModels.SHAFT_HALF`, excluyendo `FACING`;
- usa el eje de cada cara y el mismo RPM escalar;
- renderiza 4 shafts/4 small/2 large/2 small a escala visual 0.5 en los cuadrantes;
- omite shaft cubierto y muestra una tapa estática;
- lee mask/status sincronizados por `sendData()`;
- no hace early-return solo porque Flywheel soporte visualización, salvo que exista además un
  visual propio registrado para este BE.

Un renderer BER correcto es suficiente para la primera versión. Un Visual de Flywheel puede
optimizarse después sin cambiar la lógica.

## Recursos y recipes

Archivos de recursos públicos por caja:

```text
blockstates/<id>.json
models/block/<id>.json
models/item/<id>.json
loot_table/blocks/<id>.json
recipe/<id>.json
```

El cuerpo puede usar modelos propios placeholder y referenciar texturas runtime de Create sin
copiarlas. Los shafts/cogs animados se dibujan con partial models de Create.

Todas las recipes deben llevar:

```json
"neoforge:conditions": [
  { "type": "neoforge:mod_loaded", "modid": "create" }
]
```

Ingredientes previstos:

```text
create:shaft
create:cogwheel
create:large_cogwheel
create:andesite_alloy
create:brass_ingot (solo si la receta elegida usa tier brass)
```

Los bloques interiores no tienen recipe, item model, BlockItem ni loot.

## Classloading opcional

La secuencia segura queda:

```text
AntikytheraMechanism
  -> CreateCompatBootstrap (cero tipos Create en descriptors)
     -> ModList.isLoaded("create")
        -> Class.forName("...CreateIntegration")
           -> CreateTransmissionRegistries.register(modBus)
           -> common setup de hooks
           -> bootstrap cliente solo en Dist.CLIENT
```

El perfil normal debe arrancar y ejecutar tests sin Create en runtime. El perfil
`-Pinclude_create=true` valida las clases de compatibilidad y el runtime real.

## Lista precisa de archivos de implementación

Create-only, Java:

```text
compat/create/transmission/TransmissionBoxKind.java
compat/create/transmission/TransmissionFaceOrientation.java
compat/create/transmission/TransmissionPortLayout.java
compat/create/transmission/TransmissionBoxBlock.java
compat/create/transmission/TransmissionBoxBlockEntity.java
compat/create/transmission/InternalTransmissionPortBlock.java
compat/create/transmission/InternalShaftPortBlock.java
compat/create/transmission/InternalCogPortBlock.java
compat/create/transmission/InternalTransmissionPortBlockEntity.java
compat/create/transmission/TransmissionServiceShell.java
compat/create/transmission/TransmissionLinkRecord.java
compat/create/transmission/TransmissionLinkState.java
compat/create/transmission/TransmissionTopologySavedData.java
compat/create/transmission/TransmissionLinkCoordinator.java
compat/create/transmission/CreateKineticRebuild.java
compat/create/transmission/TransmissionLifecycleAdapter.java
compat/create/transmission/CreateTransmissionRegistries.java
compat/create/transmission/client/CreateTransmissionClient.java
compat/create/transmission/client/TransmissionBoxRenderer.java
```

Core, sin imports Create:

```text
api/assembly/AssemblyLifecycleListener.java
api/assembly/AssemblyLifecycleEvents.java
sublevel/ServiceShellReservation.java (o una API equivalente genérica)
```

Cambios necesarios en clases existentes:

```text
CreateIntegration.java                  registrar bloques/hooks/client
FrameMaskWriteGuard.java                proteger reservas internas
MechanismSubLevelService.java           allocator/migración del anchor
MechanismAssemblyManager.java           lifecycle antes/después de transfer/evacuación
AssemblyContentTransferService.java     quiesce cinético antes del snapshot
CreateFrameMovementRules.java           attached/NO_PICKUP para cajas
KineticPortType.java                    seguir siendo la única tabla de ratios
MiniKineticEndpoint.java                usar identidad mini-local estable
TransmissionBridge.java                 documentar efectivo; no usar como escritor de RPM
```

Recursos:

```text
assets/antikytheramechanism/blockstates/*.json
assets/antikytheramechanism/models/block/*.json
assets/antikytheramechanism/models/item/*.json
assets/antikytheramechanism/lang/en_us.json
assets/antikytheramechanism/lang/es_es.json
data/antikytheramechanism/loot_table/blocks/*.json
data/antikytheramechanism/recipe/*.json
```

## Pruebas obligatorias

### Unitarias puras

- seis caras × cuatro rolls: base `(u,v)`, quadrant round-trip y transform 90/180/270/mirror;
- mapping del service shell con assemblies que crecen en coordenadas negativas;
- patrones all/A/B y cover mask de 4 bits;
- small↔small: Manhattan 1, ejes iguales, desplazamiento perpendicular, factor `-1`;
- large↔small: componente 0 en eje y `abs(1)` en las otras dos, factores `-2/-0.5`;
- NBT round-trip de box/link/endpoint y rechazo de nonce obsoleto;
- idempotencia de suspend/remove/rebuild.

### GameTests con Create

1. Cinco shafts macro se unen a la misma red y conservan el mismo RPM escalar.
2. Shaft macro → Four Shafts → shaft mini, y sentido inverso.
3. Los cuatro puertos unen cuatro redes mini compatibles y acumulan SU una sola vez.
4. Fuentes incompatibles no eligen ganador ni provocan rebuild infinito.
5. Cada cover desconecta solo su cuadrante; save/reload y drops exactos.
6. Four Small: ratio/signo `-1` en los cuatro puertos.
7. Two Large: `-2` hacia small y `-0.5` inverso, configuraciones A y B.
8. Two Small: `-0.5` hacia large y `-2` inverso, configuraciones A y B.
9. Eje, tipo o posición incorrectos no conectan.
10. Capacity/stress/over-stress coinciden con una red Create equivalente sin SubLevel.
11. Unload/reload independiente de chunk macro y plot: cero force-load accidental y cero stale source.
12. Reinicio completo: bindings, covers, diagonal y redes reconstruidos.
13. Merge/split con shafts, cogs, inventarios y fuentes activas: sin source global obsoleto.
14. Evacuación/break/explosion/wrench: cero proxy/drop huérfano o duplicado.
15. Traslación por contraption: suspende durante movimiento y rebind al colocar.
16. Dos clientes: mask, diagonal, status y animación coinciden con servidor.

### Perfiles

```text
./gradlew test build
./gradlew -Pinclude_create=true cleanTest test build
servidor dedicado sin Create
servidor dedicado con Create 6.0.10-280
cliente con Create/Flywheel
```

## Orden de implementación recomendado

1. Lifecycle genérico y quiesce/rebuild de KBEs Create durante merge/split.
2. Allocator/protección del service shell y migración de anchor.
3. Una sola caja Four Shafts, un puerto, arista remota +1.
4. Cuatro shafts, preflight, SavedData, unload/reload y stress.
5. Covers, drops y renderer.
6. Four Small Cogs.
7. Two Large Cogs + diagonal wrench.
8. Two Small Cogs + diagonal wrench.
9. Contraption suspend/rebind, multiplayer y polish.

## Bloqueos técnicos honestos

- El transfer actual de NBT exacto necesita un hook de quiesce previo; de lo contrario los KBEs
  Create conservan `Source/Network` globales del plot anterior.
- El anchor actual puede ocupar una posición necesaria para un endpoint inferior.
- La captura de contraptions debe suspender cajas antes de que Create retire sus bloques; hacerlo
  solo desde `MovementBehaviour#startMoving` puede ser demasiado tarde.
- El desmontaje rotado de assemblies sigue limitado por el mapping frame↔mini de fase 6. No marcar
  rotación de Transmission Boxes como completa antes de resolverlo.
- La arista remota depende deliberadamente del modelo de Sable 2.0.3 (mismo `ServerLevel`). Debe
  existir un test de startup/runtime que detecte un cambio de esa premisa en futuras versiones.
- Un BER placeholder es funcional pero no prueba integración Flywheel optimizada; no confundir
  render correcto con Visual instanciado.
