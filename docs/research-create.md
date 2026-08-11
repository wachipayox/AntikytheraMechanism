# Investigación local: Create 6.0.10-280 para Minecraft 1.21.1

## Alcance y procedencia

Esta nota se limita a la API y la implementación reales del artefacto local:

```text
com.simibubi.create:create-1.21.1:6.0.10-280
```

Se inspeccionaron, sin ejecutar Gradle, los siguientes artefactos de la caché de Gradle:

```text
C:\Users\Wachii\.gradle\caches\modules-2\files-2.1\com.simibubi.create\create-1.21.1\6.0.10-280\23e1219501c0debfa0bb56c30ef8e0193341aae5\create-1.21.1-6.0.10-280-sources.jar
SHA-256 376DE15CA5ACF720106A075CA4EB2EF53E63E0E5D9EC93523A1ABBDD0F9F0CB4

C:\Users\Wachii\.gradle\caches\modules-2\files-2.1\com.simibubi.create\create-1.21.1\6.0.10-280\62c085fd48fc84b62f88d7bc98b7c4206492c080\create-1.21.1-6.0.10-280.jar
SHA-256 192C7B0BE13F523DCEC9A7A2393BBC2614C3C9BD3EDB1F7720A97BCD4F0B87A7
```

Las visibilidades y firmas críticas también se contrastaron con `javap` sobre el JAR compilado. No se usó documentación web ni código de otra versión de Create. La API de Sable y la conversión concreta a su `logicalPose` quedan fuera de esta nota; aquí sólo se documenta qué entrega Create y dónde debe conectarse el adaptador.

## Resultado ejecutivo

La integración opcional es factible sin mixins de Create, pero debe dividirse en dos problemas diferentes:

1. **Transmisión cinética entre niveles:** Create nunca forma una sola red entre el `Level` padre y un Sable `SubLevel`. Se necesitan dos endpoints cinéticos reales, uno por nivel, y un coordinador del mod que replique RPM y contabilice la carga. Nunca se debe escribir un `source` o `network` que apunte al otro nivel.
2. **Movimiento como contraption:** registrar un `MovementBehaviour` para los Mechanism Frames permite seguir la transformación continua de la contraption. Los registros `MovedBlockTransformerRegistries` sirven para transformar estado/NBT al desmontar, pero no sustituyen el seguimiento por tick.

Las clases que hereden o implementen tipos de Create deben vivir en un paquete de compatibilidad que no se cargue cuando Create no está presente. El metadato `type="optional"` ya existente es necesario, pero no protege por sí solo frente al enlazado de clases de JVM.

## Propagación de rotación: ratios y signos exactos

La implementación está en:

```java
com.simibubi.create.content.kinetics.RotationPropagator
```

El método que calcula el factor es **privado**:

```java
private static float getRotationSpeedModifier(
    KineticBlockEntity from,
    KineticBlockEntity to
)
```

Tampoco es pública la función que calcula la velocidad transmitida. La relación usada internamente es:

```text
to.speed = from.getTheoreticalSpeed() * modifier
```

No conviene intentar llamar estos métodos por reflexión ni copiarlos como autoridad. Para componentes propios, el punto de extensión público es `KineticBlockEntity#propagateRotationTo`.

### Small cog -> small cog

Create sólo devuelve `-1` cuando se cumplen todas estas condiciones:

- ambos estados son small cogs según `ICogWheel.isSmallCog`;
- la distancia Manhattan entre posiciones es `1`;
- el desplazamiento está perpendicular al eje de rotación de origen;
- ambos ejes de rotación son iguales.

Resultado:

| Origen | Destino | Factor | Efecto |
|---|---|---:|---|
| small | small | `-1.0f` | mismas RPM absolutas, sentido opuesto |

Dos small cogs diagonales no engranan: aunque los vecinos diagonales puedan aparecer en la búsqueda de propagación, la rama small-small exige distancia Manhattan `1`.

### Large cog <-> small cog

La geometría válida exige:

- mismo eje de rotación para ambos engranajes;
- componente `0` del desplazamiento a lo largo de dicho eje;
- componente absoluta `1` en cada uno de los otros dos ejes.

Por ejemplo, con eje Y, el small cog está a `(±1, 0, ±1)` del large cog. Los factores son direccionales y recíprocos:

| Origen | Destino | Factor | Efecto |
|---|---|---:|---|
| large | small | `-2.0f` | el small gira al doble y al contrario |
| small | large | `-0.5f` | el large gira a la mitad y al contrario |

Para una transmisión half-scale que quiera imitar exactamente un engrane large-small, el par de factores debe ser `-2` / `-0.5`, no `2` / `0.5`. Si la caja representa un acoplamiento coaxial sin engrane visible, el factor físicamente deseado puede ser positivo; eso ya sería una regla propia, no la regla large-small de Create.

### Hooks de propagación personalizados

Firmas verificadas en `KineticBlockEntity`:

```java
public float propagateRotationTo(
    KineticBlockEntity target,
    BlockState stateFrom,
    BlockState stateTo,
    BlockPos diff,
    boolean connectedViaAxes,
    boolean connectedViaCogs
)

public List<BlockPos> addPropagationLocations(
    IRotate block,
    BlockState state,
    List<BlockPos> neighbours
)

public boolean isCustomConnection(
    KineticBlockEntity other,
    BlockState state,
    BlockState otherState
)
```

Reglas importantes:

- `propagateRotationTo` devuelve `0` para indicar «no aplicar factor custom; continuar con las reglas estándar». No sirve para vetar explícitamente una conexión estándar que ya existe.
- Para un par custom con ratio `r`, la dirección inversa debe devolver `1/r`; si `r` es negativo, su recíproco también lo es.
- `addPropagationLocations` sólo añade posiciones que el propagador buscará **en el mismo `Level`**.
- `isCustomConnection` declara que dos BEs deben consultar su factor aun cuando no estén unidos por shaft/cog. Create considera la relación simétrica para descubrir la conexión, pero el cálculo de velocidad sigue necesitando factores coherentes en ambos sentidos.
- La búsqueda normal ya incluye los seis vecinos ortogonales. Los small cogs añaden diagonales en su plano; `SimpleKineticBlockEntity` añade las diagonales necesarias para large cogs.
- Implementar `ICogWheel` activa toda la geometría estándar de engranajes. Una Transmission Box que no sea literalmente un cog debería usar propagación custom o shafts, no fingir ser `ICogWheel`.

## Bloque cinético y BlockEntity

### Interfaces/base verificadas

```java
public abstract class KineticBlock extends Block implements IRotate

public interface IRotate extends IWrenchable {
    boolean hasShaftTowards(
        LevelReader world, BlockPos pos, BlockState state, Direction face
    );

    Direction.Axis getRotationAxis(BlockState state);
}
```

`KineticBlock` aporta la invalidación/repropagación al cambiar estados y, en `onRemove`, llama a `IBE.onRemove`. No crea por sí solo el BlockEntity: el bloque concreto todavía debe implementar `EntityBlock`, normalmente mediante:

```java
public interface IBE<T extends BlockEntity> extends EntityBlock {
    Class<T> getBlockEntityClass();
    BlockEntityType<? extends T> getBlockEntityType();
}
```

`IBE` implementa `newBlockEntity` y, para subclasses de `SmartBlockEntity`, devuelve un `SmartBlockEntityTicker`. Una base factible para una caja con eje es `RotatedPillarKineticBlock` + `IBE<TransmissionBoxBlockEntity>`; reutilizar `AbstractShaftBlock` arrastra decisiones de agua, brackets y el tipo de BE de Create que no necesariamente corresponden al bloque propio.

Firmas mínimas de `KineticBlockEntity`:

```java
public KineticBlockEntity(
    BlockEntityType<?> type,
    BlockPos pos,
    BlockState state
)

public void attachKinetics();
public void detachKinetics();
public void clearKineticInformation();

public float getSpeed();
public float getTheoreticalSpeed();
public void setSpeed(float speed);

public float getGeneratedSpeed();
public boolean isSource();
public boolean hasSource();
public void setSource(BlockPos source);
public void removeSource();

public boolean hasNetwork();
public void setNetwork(@Nullable Long network);
public KineticNetwork getOrCreateNetwork();
public void updateFromNetwork(float maxStress, float currentStress, int networkSize);
public boolean isOverStressed();
```

No se debe tratar `setSpeed` como una API de alto nivel para cambiar arbitrariamente una red ya conectada. La secuencia de alta/baja, fuente, red, notificación y sincronización está coordinada por el propagador. Para una fuente cuyo RPM cambia, la base prevista es:

```java
public abstract class GeneratingKineticBlockEntity extends KineticBlockEntity {
    public void updateGeneratedRotation();
    public void applyNewSpeed(float prevSpeed, float speed);
    public Long createNetworkId();
}
```

El endpoint receptor de un puente entre niveles encaja mejor como `GeneratingKineticBlockEntity`: `getGeneratedSpeed()` devuelve el RPM replicado y, cuando cambia, se llama a `updateGeneratedRotation()` en servidor. Esta clase resuelve convertirse en fuente, ser dominada por una fuente más rápida, desconectar y repropagar.

### Velocidad teórica frente a efectiva

- `getTheoreticalSpeed()` devuelve el campo de RPM de red aunque esté sobreestresada.
- `getSpeed()` devuelve `0` cuando `overStressed` es verdadero o el `Level` está congelado; en otro caso devuelve la teórica.
- `isSource()` equivale a `getGeneratedSpeed() != 0`.

Para animación/acción se usa normalmente `getSpeed()`. Para ratios y cálculo de stress, Create usa la velocidad teórica. Un puente debe evitar realimentar indefinidamente una velocidad teórica cuando el lado conductor ya da velocidad efectiva `0` por sobrestress.

## Redes y separación por Level

La arquitectura real es:

```text
Create.TORQUE_PROPAGATOR
  -> TorquePropagator
       Map<LevelAccessor, Map<Long, KineticNetwork>>
```

`TorquePropagator#getOrCreateNetworkFor(KineticBlockEntity)` elige primero el mapa por `be.getLevel()`. Además:

- `RotationPropagator` obtiene vecinos mediante `from.getLevel().getBlockState/getBlockEntity`;
- `KineticBlockEntity#setSource(BlockPos)` busca el BE fuente en su propio `level`;
- `source` sólo almacena `BlockPos`, sin dimensión ni identidad de Level;
- un ID de red repetido en dos levels sigue designando redes distintas.

Consecuencia firme: un KBE del mundo padre y uno del Sable SubLevel **no pueden ser miembros de la misma `KineticNetwork`**. Copiar `network`, usar el mismo `createNetworkId`, devolver la posición remota desde `addPropagationLocations` o llamar `setSource` con una posición del otro level produciría una red incorrecta o una fuente inexistente.

### `KineticNetwork` y stress

Firmas públicas relevantes:

```java
public void add(KineticBlockEntity be);
public void remove(KineticBlockEntity be);
public void updateCapacityFor(KineticBlockEntity be, float capacity);
public void updateStressFor(KineticBlockEntity be, float stress);
public void updateNetwork();
public float calculateCapacity();
public float calculateStress();
public float getActualCapacityOf(KineticBlockEntity be);
public float getActualStressOf(KineticBlockEntity be);
public int getSize();
```

Aunque `sources` y `members` son mapas públicos en 6.0.10, escribirlos directamente acoplaría el mod a detalles internos. Deben preferirse los métodos anteriores y la API de `KineticBlockEntity`/`GeneratingKineticBlockEntity`.

La fórmula implementada es:

```text
actual stress of member = base impact * abs(theoretical RPM)
actual source capacity  = base capacity * abs(generated RPM)
network overstressed    = total capacity < total stress
```

No hay una simulación separada de par. Los ratios de engranajes ya influyen porque cada miembro aporta stress a sus propias RPM transmitidas.

### Registro de impactos y capacidades

API pública:

```java
public final class BlockStressValues {
    public static final SimpleRegistry<Block, DoubleSupplier> IMPACTS;
    public static final SimpleRegistry<Block, DoubleSupplier> CAPACITIES;
    public static final SimpleRegistry<Block, GeneratedRpm> RPM;

    public static double getImpact(Block block);
    public static double getCapacity(Block block);
    public static NonNullConsumer<Block> setGeneratorSpeed(int value);
    public static NonNullConsumer<Block> setGeneratorSpeed(
        int value, boolean mayGenerateLess
    );
}
```

Para un bloque de addon, después de tener el objeto `Block` registrado:

```java
BlockStressValues.IMPACTS.register(block, ownImpactSupplier);
BlockStressValues.CAPACITIES.register(block, ownCapacitySupplier);
```

`SimpleRegistry#register(K,V)` es thread-safe, rechaza un segundo valor directo para la misma clave y los registros directos tienen prioridad sobre providers. Usar un `DoubleSupplier` respaldado por la configuración propia permite valores configurables.

No usar `CStress.setImpact` ni `CStress.setCapacity` para bloques de Antikythera. Aunque son métodos públicos, en esta versión ejecutan `assertFromCreate(builder)` y lanzan `IllegalStateException` si el owner de Registrate no tiene mod id `create`. Es una utilidad interna para construir los bloques de Create, no la API de addons.

`BlockStressValues.RPM` sólo alimenta tooltips; la velocidad funcional la decide `getGeneratedSpeed()`.

### Arquitectura factible del puente padre <-> SubLevel

Diseño recomendado por capas:

```text
Parent Level                         Sable SubLevel
-------------                        --------------
ParentTransmissionKBE                InnerTransmissionKBE
red cinética A                       red cinética B
        \                             /
         \--- BridgeCoordinator ----/
              (assembly UUID + port)
```

Cada endpoint participa normalmente en la red de su propio level. El coordinador, que pertenece al mod y no a Create, guarda referencias débiles/identidades persistentes y actualiza ambos lados en servidor.

Para una primera implementación segura:

1. Determinar un lado conductor sin contar como fuente la velocidad que el propio puente inyectó.
2. Aplicar al receptor `receiverRPM = driverRPM * ratio`; el receptor es un `GeneratingKineticBlockEntity` y llama `updateGeneratedRotation()` sólo si el valor cambió con epsilon.
3. Reflejar la carga del receptor en el lado conductor mediante el impacto dinámico del endpoint y `driverNetwork.updateStressFor(driverEndpoint, reflectedBaseImpact)`.
4. Con la convención de Create, una aproximación consistente es `reflectedBaseImpact = remoteActualStress / abs(driverTheoreticalRPM)`, excluyendo el stress aportado por el propio endpoint puente. Así el total añadido al conductor vuelve a ser `remoteActualStress`.
5. La fuente receptora necesita capacidad suficiente para que el SubLevel no se pare por una «fuente virtual» arbitrariamente pequeña. El sobrestress autoritativo se decide en la red conductora; cuando su `getSpeed()` pase a `0`, el coordinador lleva el receptor a `0`.
6. Al descargar cualquiera de los levels o romper un endpoint, llevar el otro a `0`, desmontarlo de la red y eliminar el vínculo idempotentemente.

Esto requiere getters protegidos expuestos por la subclass propia para sus campos heredados `capacity` y `stress`, o cálculos equivalentes controlados por el coordinador. No se recomienda recorrer `KineticNetwork.members` como contrato permanente.

Limitación que debe convertirse en regla de gameplay inicial: si ambos lados contienen fuentes nativas simultáneas, la elección de conductor, el overpower y la reflexión de stress pueden formar un bucle. La versión inicial debería rechazar/aislar el estado dual-powered o elegir un único sentido de puerto explícito. La transmisión bidireccional automática con dos redes generadoras requiere una máquina de estados propia y pruebas de ciclos, signos incompatibles, unload/reload y sobrestress.

## Wrench hooks

La API exacta es:

```java
public interface IWrenchable {
    default InteractionResult onWrenched(BlockState state, UseOnContext context);
    default BlockState updateAfterWrenched(BlockState newState, UseOnContext context);
    default InteractionResult onSneakWrenched(BlockState state, UseOnContext context);
    default BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace);
}
```

Como `IRotate extends IWrenchable`, todo bloque cinético propio que implemente correctamente `IRotate` ya es reconocido por la wrench de Create. El comportamiento por defecto:

- rota el estado mediante `getRotatedBlockState`;
- valida `canSurvive`;
- llama `KineticBlockEntity.switchToBlockState`, que desmonta y repropaga la red si el eje cambió;
- shift+wrench publica `BlockEvent.BreakEvent`, entrega drops al inventario y destruye el bloque si el evento no se cancela.

Create también tiene `WrenchEventHandler` a prioridad `HIGH`: para herramientas de otros mods en el tag común `c:tools/wrench`, llama los mismos métodos si el bloque es `instanceof IWrenchable`. Excluye su propia wrench porque `WrenchItem#useOn` ya la procesa directamente.

No existe en los sources inspeccionados un registry que vuelva wrenchable a un bloque arbitrario. Por tanto:

- una Transmission Box registrada sólo con Create presente puede implementar/heredar `IWrenchable` normalmente;
- un Mechanism Frame core, cuya clase debe cargar sin Create, no puede implementar condicionalmente esa interfaz;
- para el frame core, las alternativas son un handler propio de `RightClickBlock`/tag de herramienta o el tag `create:wrench_pickup` para el caso limitado de shift-pickup. Este último no aporta rotación y sólo lo consulta `WrenchItem` cuando el bloque no es `IWrenchable`.

Si la orientación de la caja no coincide con las propiedades que el default reconoce (`AXIS`, `FACING`, `HORIZONTAL_FACING`, etc.), sobrescribir `getRotatedBlockState` es el hook correcto.

## Movimiento en contraptions

### Tres APIs distintas

1. `BlockMovementChecks` decide si un bloque puede/ debe moverse y cómo se adjunta.
2. `MovementBehaviour` ejecuta comportamiento continuo mientras el bloque está capturado dentro de una contraption.
3. `StructureTransform` y `MovedBlockTransformerRegistries` transforman posiciones, estados y BEs cuando la estructura se coloca de nuevo en el mundo.

No son intercambiables.

### Permitir movimiento sin permitir pickup

API útil:

```java
public enum ContraptionMovementSetting {
    MOVABLE, NO_PICKUP, UNMOVABLE;
    public static final SimpleRegistry<Block, Supplier<ContraptionMovementSetting>> REGISTRY;
}
```

Para Mechanism Frames con un SubLevel externo persistente, `NO_PICKUP` es la opción inicial prudente:

```java
ContraptionMovementSetting.REGISTRY.register(
    mechanismFrameBlock,
    () -> ContraptionMovementSetting.NO_PICKUP
);
```

Permite mover el bloque, pero impide convertir una minecart contraption que lo contiene en ítem. Create no sabe incluir el SubLevel de Sable dentro del NBT de su item; permitir pickup sin implementar esa transferencia dejaría datos huérfanos.

Para decisiones dinámicas está:

```java
BlockMovementChecks.registerMovementAllowedCheck(
    (state, level, pos) -> CheckResult.SUCCESS | FAIL | PASS
);
```

El check debe devolver `PASS` para bloques ajenos. Puede devolver `FAIL` para un frame cuya assembly esté parcialmente capturada, en transacción, o no pueda garantizar seguimiento seguro.

### Seguimiento continuo con `MovementBehaviour`

Registro directo, sin Registrate:

```java
MovementBehaviour.REGISTRY.register(frameBlock, frameMovementBehaviour);
```

Hooks principales:

```java
default void startMoving(MovementContext context);
default void tick(MovementContext context);
default void visitNewPosition(MovementContext context, BlockPos pos);
default void onSpeedChanged(MovementContext context, Vec3 oldMotion, Vec3 motion);
default void stopMoving(MovementContext context);
default void writeExtraData(MovementContext context);
```

Campos públicos relevantes de `MovementContext`:

```java
Vec3 position;
Vec3 motion;
Vec3 relativeMotion;
UnaryOperator<Vec3> rotation;
Level world;
BlockState state;
BlockPos localPos;
CompoundTag blockEntityData;
CompoundTag data;
Contraption contraption;
Object temporaryData;
boolean firstMovement;
boolean stall;
boolean disabled;
```

Create actualiza por tick:

- `context.position`: centro global del actor;
- `context.rotation = v -> entity.applyRotation(v, 1)`;
- `context.motion`: delta global;
- `context.relativeMotion`: delta pasado por `reverseRotation`.

`visitNewPosition` sólo se llama al cambiar de celda global o en el primer movimiento aplicable. No basta para seguir una rotación suave que permanece dentro de la misma celda; el pose follower debe trabajar en `tick`.

La entidad expone:

```java
public Vec3 toGlobalVector(Vec3 localVec, float partialTicks);
public Vec3 toGlobalVector(Vec3 localVec, float partialTicks, boolean prevAnchor);
public Vec3 toLocalVector(Vec3 globalVec, float partialTicks);
public Vec3 toLocalVector(Vec3 globalVec, float partialTicks, boolean prevAnchor);
public abstract Vec3 applyRotation(Vec3 localPos, float partialTicks);
public abstract Vec3 reverseRotation(Vec3 localPos, float partialTicks);
public Vec3 getAnchorVec();
public Vec3 getPrevAnchorVec();
public Contraption getContraption();
```

Para obtener un transform completo del SubLevel, el adaptador puede transformar el origen local elegido con `toGlobalVector` y transformar los tres vectores base mediante `applyRotation`; con esa base ortonormal construye la representación que Sable requiera. `context.position` es un centro de bloque, así que no se debe usar como esquina/origen sin compensar el `0.5` y la posición del frame líder dentro de la assembly.

Recomendaciones de consistencia:

- actualizar la pose autoritativa sólo en servidor; dejar que Sable sincronice al cliente;
- elegir exactamente un frame líder por assembly (por ejemplo, el mínimo `BlockPos` original persistido) para evitar que cada frame escriba la misma pose;
- persistir en el NBT de cada frame `assembly UUID`, coordenada relativa dentro de la assembly y leader/origin revision;
- si una contraption captura sólo parte de una assembly conectada, cancelar el movimiento mediante `BlockMovementChecks` o definir una operación explícita de split; no permitir que dos entidades intenten dirigir el mismo SubLevel;
- hacer `tick` tolerante a `temporaryData == null` y reconstruir el vínculo desde `blockEntityData`/`data`.

Este último punto es obligatorio porque `Contraption.readNBT` reconstruye `MovementContext` con `MovementContext.readNBT`, pero no vuelve a llamar a `MovementBehaviour.startMoving`. Tras recargar mundo, el binding transitorio debe poder rehacerse perezosamente.

`writeExtraData` permite añadir datos a `context.data` antes de serializar el actor. Para datos que deben acabar en el BlockEntity recolocado, hay que mantener actualizado `context.blockEntityData`: es el NBT capturado del `StructureBlockInfo` que Create usa al volver a colocar el bloque. No confiar sólo en `temporaryData`, que no se serializa.

### Transform discreto al desmontar

`StructureTransform` representa un offset, mirror y una sola rotación axial, cuantizada a múltiplos de 90 grados en su constructor de ángulos. Firmas:

```java
public Vec3 apply(Vec3 localVec);
public BlockPos apply(BlockPos localPos);
public BlockPos unapply(BlockPos globalPos);
public Vec3 applyWithoutOffset(Vec3 localVec);
public Vec3 unapplyWithoutOffset(Vec3 globalVec);
public BlockState apply(BlockState state);
public void apply(BlockEntity be);
public Direction rotateFacing(Direction facing);
public Direction.Axis rotateAxis(Direction.Axis axis);
```

Para un bloque core que no puede implementar interfaces de Create, usar los registries opcionales:

```java
MovedBlockTransformerRegistries.BLOCK_TRANSFORMERS.register(
    frameBlock,
    (state, transform) -> transformedState
);

MovedBlockTransformerRegistries.BLOCK_ENTITY_TRANSFORMERS.register(
    frameBlockEntityType,
    (be, transform) -> { /* transformar referencias persistentes */ }
);
```

Alternativas por herencia, sólo apropiadas para clases Create-only:

```java
interface TransformableBlock {
    BlockState transform(BlockState state, StructureTransform transform);
}

interface TransformableBlockEntity {
    void transform(BlockEntity blockEntity, StructureTransform transform);
}
```

En el desmontaje, Create transforma primero `BlockState`, coloca el bloque, carga su NBT con coordenadas de destino y después llama `transform.apply(blockEntity)`. El transformer de BE puede ajustar referencias direccionales/offsets una vez existe el BE de destino.

`StructureTransform` no representa la rotación continua intermedia de una bearing/train/contraption. Para seguirla se usa `MovementBehaviour` + `AbstractContraptionEntity` como se explicó arriba.

### Límites funcionales del follower

Mover los frames como Create contraption no hace que los bloques mini del SubLevel sean bloques de esa contraption. Por tanto, sin trabajo adicional:

- Create no incorpora los mini bloques a su `Contraption.blocks`;
- sus movement actors no actúan contra el terreno del mundo padre;
- sus colliders no pasan a `ContraptionCollider`;
- una contraption Create ensamblada dentro del SubLevel sigue siendo una entidad del SubLevel, no una subcontraption nativa de la entidad exterior.

El follower sólo puede actualizar la pose espacial del SubLevel. Colisión, interacción y render a esa pose dependen de Sable/Sable Scale y deben verificarse aparte.

## Classloading y registro condicional

El `neoforge.mods.toml` del proyecto ya declara correctamente:

```toml
[[dependencies.antikytheramechanism]]
modId="create"
type="optional"
versionRange="[6.0.10,7.0)"
ordering="AFTER"
side="BOTH"
```

Esto expresa compatibilidad y orden cuando Create existe, pero NeoForge no elimina referencias JVM inválidas. Una clase core que `extends KineticBlock`, `implements IWrenchable`, tiene campos/parámetros Create o inicializadores estáticos que leen registries de Create puede fallar al cargarse sin el mod.

Firma local de NeoForge/FML 1.21.1 verificada:

```java
ModList.get().isLoaded("create")
```

Estructura recomendada:

```text
dev.antikytheramechanism
  compat/
    OptionalIntegrations        # cero imports/tipos de Create
    create/
      CreateCompat              # se carga sólo tras el check
      CreateKinetics
      TransmissionBoxBlock
      TransmissionBoxBlockEntity
      FrameMovementBehaviour
```

Secuencia:

1. El constructor/core consulta `ModList.get().isLoaded("create")`.
2. Sólo en la rama verdadera carga e invoca el bootstrap de `compat.create`.
3. Ese bootstrap registra su `DeferredRegister` Create-only y, una vez disponibles los objetos core, registra `MovementBehaviour`, transformers, movement setting y stress values exactamente una vez.
4. Ninguna firma, annotation estática, field genérico ni superclass de una clase que se cargue siempre debe mencionar `com.simibubi.create.*`.

Una llamada directa a una clase aislada suele resolverse perezosamente sólo al ejecutar la rama, pero el aislamiento más robusto es un bootstrap reflectivo cuyo nombre de clase sea `String`, o una capa equivalente sin descriptors Create en el core. La reflexión debe limitarse a cargar el bootstrap; dentro de compat se usan llamadas tipadas normales.

Para compilar esas clases opcionales, el build necesitará Create en `compileOnly`; el `localRuntime` condicional actual sólo lo hace disponible al ejecutar. El coordinador principal debe conservar el runtime normal sin Create para probar classloading. No se validó la configuración Gradle exacta porque esta investigación tenía prohibido ejecutar Gradle.

Los bloques/ítems/BEs cuya clase herede tipos de Create deben registrarse únicamente cuando Create esté cargado. Consecuencia: sus IDs no existen en una ejecución sin Create. Si se desea que una Transmission Box colocada sobreviva al quitar Create, hace falta un bloque placeholder core siempre registrado y una arquitectura distinta; no se puede cambiar dinámicamente la superclass de un bloque ya registrado.

No se necesita un mixin de Create para ratios, wrench, movement behaviours ni transforms: todos tienen hooks públicos suficientes. Si en el futuro apareciera un caso sin hook, el mixin Create-only necesitaría un plugin de aplicación condicional que no importe clases de Create; no debe añadirse a la configuración core confiando sólo en que el target ausente sea inocuo.

## Plan de implementación sugerido

1. Añadir dependencia de compilación opcional y mantener dos runtimes de prueba: sin Create y con 6.0.10-280.
2. Crear bootstrap aislado `compat.create` y registrar `NO_PICKUP`, `MovementBehaviour` y transformers para Mechanism Frame.
3. Implementar primero el follower de una assembly inmóvil internamente: líder único, rebind tras reload y desmontaje transformado.
4. Añadir una Transmission Box Create-only con `KineticBlock`/`IBE` y endpoint por level.
5. Implementar puente unidireccional RPM + stress; rechazar dual-powered.
6. Sólo después diseñar arbitraje bidireccional.

Pruebas indispensables:

- arranque servidor y cliente sin Create: ninguna clase `compat.create` cargada;
- small-small `-1`, large->small `-2`, small->large `-0.5`, incluyendo geometrías inválidas;
- source attach/detach, cambio de signo, max RPM y destrucción por incompatibilidad;
- stress reflejado con distintos ratios, over-stress y `getSpeed()==0`;
- unload/reload independiente de parent Level y SubLevel;
- rotación continua por bearing, traslación por piston/gantry, train y desmontaje a 90/180/270 grados;
- guardado con la contraption en movimiento y rebind sin `startMoving`;
- rechazo de captura parcial y de minecart pickup;
- rotura de cualquiera de los endpoints sin drops/datos duplicados.

## Limitaciones de esta investigación

- No se ejecutó Gradle, Minecraft ni GameTests; las firmas son reales, pero la arquitectura propuesta todavía debe validarse en runtime con Sable.
- No se verificó cómo Sable espera recibir una base/quaternion ni si su pose puede actualizarse con seguridad desde el tick de un movement actor.
- No se verificó si Sable Scale ya intercepta específicamente `AbstractContraptionEntity`; duplicar una corrección existente podría aplicar dos veces la transformación.
- El modelo exacto de stress bidireccional no está resuelto por Create porque Create no soporta redes cross-level. La propuesta de reflexión es una capa propia y debe tratar dual-powered como no soportado inicialmente.
- Las clases `content.*` de Create utilizadas para cinética y contraptions no están anotadas como una API estable formal, aunque son las clases públicas reales necesarias para addons en 6.0.10. Deben encapsularse detrás del adaptador para reducir el coste de una actualización a Create 6.1/7.x.
