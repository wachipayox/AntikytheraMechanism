# Investigación de APIs: Sable 2.0.3 y Sable Scale 1.2.0

Fecha de contraste: 2026-08-11. Alcance: base técnica de las fases 2 a 6 de Antikythera Mechanism. Este documento describe lo que hacen las versiones resueltas por Gradle; separa API pública, implementación observada e inferencias de diseño. No presupone métodos que no existan.

## 1. Fuentes y versión realmente inspeccionadas

### Sable

- Binario exacto: `dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.3`.
- SHA-256 del binario: `DA6C3B66238586603D1DCAA2AFB012D36815FBCE0A2D5938FBB2936701D42279`.
- SHA-256 del source JAR: `CE2D966EFC4BDAF069338F711C469FEEDCA1FA97A01BE3B7A4DDBDD88584DFC7`.
- El source JAR coincide con el commit oficial `17d29f5b99d50e3cc13b536b23f9181ade99f14f`, etiquetado tanto `mc1.21.1-2.0.3-neoforge` como `mc1.21.1-2.0.3-fabric`.
- Fuente oficial fijada: [ryanhcode/sable, tag NeoForge 2.0.3](https://github.com/ryanhcode/sable/tree/mc1.21.1-2.0.3-neoforge).

Artefactos locales:

```text
C:\Users\Wachii\.gradle\caches\modules-2\files-2.1\dev.ryanhcode.sable\
  sable-neoforge-1.21.1\2.0.3\
    f3ba9a497122ba29d9e94ebe6f9a3a3aaa5fa756\sable-neoforge-1.21.1-2.0.3.jar
    f194998c747c486bf8c35b0bd978cfedfddfa23b\sable-neoforge-1.21.1-2.0.3-sources.jar
```

La implementación Rapier procede del módulo `sable_rapier` 2.0.3. `Pose3d`/`Pose3dc` proceden de Sable Companion 1.6.0, transitivo de Sable 2.0.3.

### Sable Scale

- Artefacto exacto resuelto: `curse.maven:sable-scaling-1607775:8564797`.
- Su manifest y `neoforge.mods.toml` declaran versión `1.2.0`.
- SHA-256: `E453F29741DDD90546401CB54853EC89FC7FDFDB8070B66EB9A55CD543B3775C`.
- No contiene source JAR; se contrastó el bytecode exacto con `javap` y Vineflower.

```text
C:\Users\Wachii\.gradle\caches\modules-2\files-2.1\curse.maven\
  sable-scaling-1607775\8564797\9d0ab34cc09e71fbca4129f725e072bbea03cf1c\
  sable-scaling-1607775-8564797.jar
```

Advertencia de procedencia: [falling-colud/sable-scale](https://github.com/falling-colud/sable-scale) no tiene tags y el `main` inspeccionado (`2cb9b8859ac7c7ac1bd22bf45fa79c48fe0ddff1`) todavía declara `1.1.6`. Además, el JAR 1.2.0 contiene al menos `SublevelRenderOffsetScaleMixin` que no debe atribuirse sin más a ese commit. Para cualquier diferencia, el JAR 1.2.0 anterior es la autoridad.

## 2. Veredictos que condicionan la arquitectura

1. Un `SubLevel` no es un `Level`. Es un objeto que posee un `LevelPlot`; sus chunks reales viven en coordenadas lejanas dentro del `ServerLevel` padre.
2. Se puede y se debe asignar con `Pose3d.scale = (0.5, 0.5, 0.5)` antes de `allocateNewSubLevel`. Scale 1.2.0 detecta ese cuerpo desde su alta en Rapier.
3. Un `SubLevel` literalmente vacío no es estable: masa cero y bounds vacíos provocan su eliminación. La fase 2 necesita poblarlo sincrónicamente con contenido técnico de masa positiva antes de ceder el control al siguiente tick.
4. Sable no expone un merge estructural. Su split automático está basado en conectividad de bloques, no en frames, y solo se puede activar/desactivar globalmente. Antikythera necesita su propio FrameGraph, transferencias explícitas y una supresión estrecha del split automático para sus UUID.
5. `SubLevelAssemblyHelper.moveBlocks` conserva BlockState y NBT de BlockEntity, pero no scheduled ticks y no es transaccional.
6. Sable Scale 1.2.0 ya corrige persistencia de escala, protocolo de pose, collider Rapier, masa/inercia, raycast, reach, collision de entidad, render y embedding Flywheel. No se deben duplicar esos sistemas.
7. Para mover un cuerpo activo hay que cambiar el cuerpo nativo con `PhysicsPipeline.teleport` o `RigidBodyHandle.teleport`. Mutar solo `logicalPose()` será sobrescrito por física.

## 3. Modelo real: Level, SubLevel, plot y coordenadas

### Clases principales

`dev.ryanhcode.sable.sublevel.SubLevel`:

```java
public Level getLevel();
public Pose3d logicalPose();
public Pose3dc lastPose();
public BoundingBox3dc boundingBox();
public LevelPlot getPlot();
public boolean isRemoved();
public void markRemoved();
public UUID getUniqueId();
public @Nullable String getName();
public void setName(@Nullable String name);
public void updateLastPose();
public void updateBoundingBox();
```

`ServerSubLevel` especializa:

```java
public ServerLevel getLevel();
public ServerLevelPlot getPlot();
public @Nullable CompoundTag getUserDataTag();
public void setUserDataTag(CompoundTag tag);

public void setSplitFrom(ServerSubLevel source, Pose3d originalPose);
public @Nullable UUID getSplitFromSubLevel();
public @Nullable Pose3d getSplitFromPose();
public void clearSplitFrom();
```

Fuentes:

- [SubLevel.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/sublevel/SubLevel.java)
- [ServerSubLevel.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/sublevel/ServerSubLevel.java)

Consecuencia importante: un BlockEntity mini sigue viendo como `getLevel()` el `ServerLevel` padre y como `getBlockPos()` la coordenada global del plot-yard. No existe un segundo objeto `ServerLevel` por assembly.

### Container y asignación

`dev.ryanhcode.sable.api.sublevel.SubLevelContainer`:

```java
public static @Nullable SubLevelContainer getContainer(Level level);
public static @Nullable ServerSubLevelContainer getContainer(ServerLevel level);
public static @Nullable ClientSubLevelContainer getContainer(ClientLevel level);

public SubLevel allocateNewSubLevel(Pose3d pose);
public SubLevel allocateSubLevel(UUID uuid, int localPlotX, int localPlotZ, Pose3d pose);
public @Nullable SubLevel getSubLevel(UUID uuid);
public @Nullable SubLevel getSubLevel(int localPlotX, int localPlotZ);
public List<? extends SubLevel> getAllSubLevels();
public @Nullable LevelPlot getPlot(ChunkPos globalChunkPos);
public void removeSubLevel(SubLevel subLevel, SubLevelRemovalReason reason);
```

`ServerSubLevelContainer` añade:

```java
public List<ServerSubLevel> getAllSubLevels();
public SubLevelPhysicsSystem physicsSystem();
public SubLevelTrackingSystem trackingSystem();
```

`allocateSubLevel` construye una copia (`new Pose3d(pose)`), registra UUID/plot, notifica inmediatamente a `SubLevelObserver` y el observer físico hace `buildMassTracker()` y `pipeline.add(...)`. Por ello:

- preparar escala, posición y orientación antes de pasar el pose;
- no esperar que mutar después el objeto `pose` original cambie el cuerpo;
- poblar el plot sincrónicamente después de asignarlo, antes del siguiente tick.

Fuente: [SubLevelContainer.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/sublevel/SubLevelContainer.java) y [SubLevelPhysicsSystem.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem.java).

### LevelPlot y chunks

`LevelPlot` expone:

```java
public BlockPos getCenterBlock();
public ChunkPos getCenterChunk();
public void newEmptyChunk(ChunkPos globalChunkPos);
public EmbeddedPlotLevelAccessor getEmbeddedLevelAccessor();
public ChunkPos toLocal(ChunkPos global);
public ChunkPos toGlobal(ChunkPos local);
public LevelChunk getChunk(ChunkPos local); // la implementación puede devolver null
public @Nullable PlotChunkHolder getChunkHolder(ChunkPos local);
public Collection<PlotChunkHolder> getLoadedChunks();
public BoundingBox3ic getBoundingBox();
```

La asimetría es real:

- `getCenterChunk()` devuelve coordenada global del plot-yard;
- `newEmptyChunk(...)` recibe coordenada global;
- `getChunk(...)` y `getChunkHolder(...)` reciben coordenada local al plot;
- `SubLevelContainer.getChunk(...)` recibe coordenada global.

Antes del primer `setBlock` hay que crear al menos el chunk central:

```java
ServerLevelPlot plot = subLevel.getPlot();
plot.newEmptyChunk(plot.getCenterChunk());
```

Fuente: [LevelPlot.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/sublevel/plot/LevelPlot.java).

### Accessor embebido

`EmbeddedPlotLevelAccessor implements CommonLevelAccessor, ServerLevelAccessor` y centra `BlockPos.ZERO` en `plot.getCenterBlock()`:

```java
public BlockState getBlockState(BlockPos local);
public @Nullable BlockEntity getBlockEntity(BlockPos local);
public boolean setBlock(BlockPos local, BlockState state, int flags, int recursion);
public boolean removeBlock(BlockPos local, boolean moving);
public boolean destroyBlock(BlockPos local, boolean drops, @Nullable Entity entity, int recursion);
public ServerLevel getLevel();
```

Internamente suma `plot.getCenterBlock()` a posiciones de bloques, BEs, entidades y chunks.

Hay una excepción peligrosa: `getBlockTicks()` y `getFluidTicks()` devuelven directamente los tick access del nivel padre sin trasladar posiciones. No se debe programar manualmente un tick con una coordenada mini relativa a través del accessor. Usar la coordenada global real:

```java
BlockPos plotPos = plot.getCenterBlock().offset(miniPos);
serverLevel.scheduleTick(plotPos, blockOrFluid, delay);
```

Fuente: [EmbeddedPlotLevelAccessor.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/sublevel/plot/EmbeddedPlotLevelAccessor.java).

## 4. Creación directa a escala 0.5

Patrón mínimo respaldado por `SableTestHelper.spawnSubLevel`, extendido con la escala antes de asignar:

```java
ServerSubLevelContainer container =
        Objects.requireNonNull(SubLevelContainer.getContainer(serverLevel));

Pose3d pose = new Pose3d();
pose.position().set(worldAnchorX, worldAnchorY, worldAnchorZ);
pose.scale().set(0.5, 0.5, 0.5);

ServerSubLevel subLevel =
        (ServerSubLevel) container.allocateNewSubLevel(pose);

ServerLevelPlot plot = subLevel.getPlot();
plot.newEmptyChunk(plot.getCenterChunk());

EmbeddedPlotLevelAccessor miniLevel = plot.getEmbeddedLevelAccessor();
boolean created = miniLevel.setBlock(SERVICE_ANCHOR_LOCAL, anchorState, 3);
if (!created) {
    container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
    throw new IllegalStateException("Could not populate the new Sable SubLevel");
}

subLevel.updateLastPose();
```

No hace falta llamar `SubLevelScale.apply` para el nacimiento. El mixin exacto `RapierPhysicsPipelineMixin` de Scale inyecta al final de:

```java
RapierPhysicsPipeline.add(ServerSubLevel, Pose3dc)
```

Si algún componente de `pose.scale()` es distinto de 1, `ScaledColliders.isManaged` lo detecta, marca el cuerpo dirty y `ScaleRuntime` reconstruye el collider escalado al final del tick servidor. Los cambios de bloques posteriores también marcan su collider.

`SubLevelScale.apply(ServerSubLevel, double)` es la API para redimensionar un cuerpo ya existente. Además de cambiar la escala, reseata el casco, actualiza bounds/collider/masa, despierta el cuerpo y escala toda la cadena devuelta por `SubLevelHelper.getConnectedChain`. No es necesaria ni deseable para la asignación inicial fija a 0.5.

## 5. El problema de los assemblies vacíos

No hay API `keepEmpty`. Existen dos rutas de eliminación:

1. `SubLevelContainer.processSubLevelRemovals()` destruye y elimina cualquier `ServerSubLevel` con `getMassTracker().isInvalid()`; `MassData.isInvalid()` significa `mass <= 0.0`.
2. `ServerSubLevel.onPlotBoundsChanged()` hace `markRemoved()` si el plot tiene bounds `EMPTY` o volumen no positivo. Retirar el último bloque puede activar esto durante el propio `LevelChunk#setBlockState`, sin esperar al tick siguiente.

Un ticket de carga, un chunk vacío o un constraint no resuelven masa/bounds.

### Diseño mínimo viable

Para fase 2, crear un bloque técnico de anclaje:

- `!state.isAir()`;
- masa Sable positiva;
- colocado sincrónicamente después de `allocateNewSubLevel`;
- reservado por el bypass interno y fuera de las ocho posiciones del jugador;
- nunca seleccionable, dropeable ni permitido por whitelist.

Matiz de recuperación: el updater incremental de masa cuenta cualquier non-air usando `PhysicsBlockPropertyHelper.getMass`, incluso con collision shape vacía. La carga normal también vuelve a alimentar ese updater. Sin embargo, `MassTracker.build()` filtra por `VoxelNeighborhoodState.isSolid` y, por tanto, por collision shape no vacía; esa reconstrucción se usa en recuperación física. Un anchor sin colisión funciona en alta/reload normal, pero puede no sobrevivir a `recoverSubLevel`.

Opciones, en orden de robustez:

1. anchor técnico con una collision shape pequeña situado dentro del volumen ya ocupado por la carcasa del frame; probar que su collider escalado no invade la zona útil;
2. anchor sin colisión y masa positiva, aceptando una prueba obligatoria de recovery y documentando el riesgo;
3. parche adicional de reconstrucción de masa, solo si el spike demuestra que las dos anteriores no bastan.

Para producción conviene mantener el anchor solo mientras no haya ningún bloque mini real: al colocar el primero, retirar anchor después de crear el bloque; antes de retirar el último bloque real, restaurar anchor primero. El orden evita cualquier instante de masa/bounds cero.

No usar fillers non-air en las ocho celdas vacías: aunque evitarían masa cero y algunos splits, dejan de ser aire para fluidos, placement, redstone y lógica modded. Alterarían justamente la semántica que se quiere preservar.

El anchor externo amplía los bounds del plot. Debe estar adyacente, no lejos en el service shell, y esa expansión debe incluirse en pruebas de tracking/collision.

Fuentes:

- [MassData.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/physics/mass/MassData.java)
- [MassTracker.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/physics/mass/MassTracker.java)
- [VoxelNeighborhoodState.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/physics/chunk/VoxelNeighborhoodState.java)

## 6. Pose3d y transformaciones correctas

`Pose3dc` define:

```java
Vector3dc position();
Quaterniondc orientation();
Vector3dc rotationPoint();
Vector3dc scale();

Vector3d transformPosition(Vector3dc local, Vector3d dest);
Vector3d transformPositionInverse(Vector3dc global, Vector3d dest);
Vector3d transformNormal(Vector3dc local, Vector3d dest);
Vector3d transformNormalInverse(Vector3dc global, Vector3d dest);
Matrix4d bakeIntoMatrix(Matrix4d dest);
```

La fórmula exacta es:

```text
world = orientation * ((plotPosition - rotationPoint) * scale) + position
```

La inversa:

```text
plot = inverse(orientation) * (world - position) / scale + rotationPoint
```

`rotationPoint` suele acabar siendo el centro de masa en coordenadas del plot. `position` es el punto mundial al que se proyecta ese rotation point; no es necesariamente el origen lógico del assembly.

### Mini local, plot global y mundo

Elegir una única convención:

```java
BlockPos miniOriginPlot = plot.getCenterBlock();
BlockPos blockInPlot = miniOriginPlot.offset(miniPos);
```

Centro visual del minibloque:

```java
Vector3d plotCenter = new Vector3d(
        blockInPlot.getX() + 0.5,
        blockInPlot.getY() + 0.5,
        blockInPlot.getZ() + 0.5);
Vector3d worldCenter =
        subLevel.logicalPose().transformPosition(plotCenter, new Vector3d());
```

Mundo a posición mini:

```java
Vector3d plotHit = subLevel.logicalPose()
        .transformPositionInverse(worldHit, new Vector3d());
BlockPos hitPlotBlock = BlockPos.containing(plotHit.x, plotHit.y, plotHit.z);
BlockPos miniPos = hitPlotBlock.subtract(miniOriginPlot);
```

No transformar `miniPos` crudo con el pose: Sable transforma coordenadas reales del plot-yard. Primero hay que sumar el origen real del plot.

Para direcciones:

```java
Vector3d worldNormal = pose.transformNormal(localNormal, new Vector3d()).normalize();
```

Nunca reescribir `BlockState` internos para reflejar una orientación mundial. El mapping de frame es:

```text
mini = frameCell * 2 + offset(0|1)
frameCell = floorDiv(mini, 2)
offset = floorMod(mini, 2)
```

Usar `Math.floorDiv`/`Math.floorMod` en los tres ejes; la división Java normal es incorrecta para assemblies que crecen hacia coordenadas negativas.

Fuente: `Pose3dc.java` del source JAR `sable-companion-common-1.21.1:1.6.0`.

## 7. Bloques, BlockEntities y ticking

Los bloques se escriben en el `ServerLevel` padre en sus posiciones globales del plot. Se puede usar:

```java
plot.getEmbeddedLevelAccessor().setBlock(miniPos, state, flags);
```

o:

```java
BlockPos plotPos = plot.getCenterBlock().offset(miniPos);
serverLevel.setBlock(plotPos, state, flags);
serverLevel.getBlockEntity(plotPos);
```

Sable inserta `PlotChunkHolder` en el `ChunkMap`, ejecuta post-load, registra BEs y tick containers. Su mixin sobre `ServerLevel.shouldTickBlocksAt(long)` devuelve `true` para chunks de plot. Por eso:

- BlockEntities vanilla/modded usan sus tickers normales;
- scheduled block/fluid ticks se ejecutan en el nivel padre;
- menús e inventarios siguen apuntando al BlockEntity real;
- Sable añade además ticks especiales para `BlockEntitySubLevelActor`.

Eventos API especializados:

```java
public interface BlockEntitySubLevelActor {
    default void sable$tick(ServerSubLevel subLevel) {}
    default void sable$physicsTick(ServerSubLevel subLevel,
                                   RigidBodyHandle handle, double timeStep) {}
    @Nullable
    default Iterable<@NotNull SubLevel> sable$getLoadingDependencies() {
        return this.sable$getConnectionDependencies();
    }
    @Nullable
    default Iterable<@NotNull SubLevel> sable$getConnectionDependencies() {
        return null;
    }
}

public interface BlockSubLevelAssemblyListener {
    default void beforeMove(ServerLevel origin, ServerLevel result,
                            BlockState state, BlockPos oldPos, BlockPos newPos) {}
    void afterMove(ServerLevel origin, ServerLevel result,
                   BlockState state, BlockPos oldPos, BlockPos newPos);
}
```

Un transfer propio de merge/split debe respetar `BlockSubLevelAssemblyListener` o delegar en un adapter de compatibilidad; Sable lo invoca en `moveBlocks`.

Fuentes:

- [ServerLevelPlot.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/sublevel/plot/ServerLevelPlot.java)
- [ServerLevelMixin.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/mixin/plot/ServerLevelMixin.java)
- [BlockSubLevelAssemblyListener.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/block/BlockSubLevelAssemblyListener.java)

## 8. Persistencia real

`SubLevelSerializer` guarda:

```text
uuid
plot
pose
world_bounds
linear_velocity
angular_velocity
display_name
loading_dependencies
user_data
```

`ServerLevelPlot.save/load` incluye:

- BlockStates por sección;
- BlockEntities con metadata/componentes;
- block ticks y fluid ticks;
- luz y heightmaps;
- attachments de chunk mediante la plataforma;
- biome y chunks cargados.

La carga vuelve a asignar el mismo UUID/plot, carga contenido, aplica el pose, hace `pipeline.teleport`, restaura velocidades y user data.

Scale 1.2.0 mezcla `SableNBTUtils`:

```text
pose["sablescale:scale"] = {x, y, z}
```

y mezcla `SableBufferUtils` para escribir/leer tres `double` adicionales por pose. Servidor y cliente deben usar Scale. Sin Scale, el save sigue cargando, pero Sable base devuelve escala 1.

El `userDataTag` solo está en el serializer de servidor; no se sincroniza al cliente. Usarlo para recovery, no como estado visual cliente.

Fuente: [SubLevelSerializer.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/sublevel/storage/serialization/SubLevelSerializer.java). Scale exacto: `dev/sablescale/scale/mixin/SableNBTUtilsMixin.class` y `SableBufferUtilsMixin.class`.

### Ownership persistente recomendado

```text
Assembly SavedData:
  assembly UUID
  dimension
  Sable SubLevel UUID
  Set<FrameCell>
  assembly origin / mapping revision
  movement/connector metadata

ServerSubLevel.userDataTag:
  antikytheramechanism:managed = true
  antikytheramechanism:assembly = UUID
  antikytheramechanism:data_version = int
```

El SavedData es autoridad. `userDataTag` es índice de recuperación/consistencia. En cliente, sincronizar explícitamente el conjunto o mapping de UUID administrados; el user tag no llega por red.

## 9. Lifecycle, observers y force-load

`SubLevelObserver`:

```java
default void onSubLevelAdded(SubLevel subLevel) {}
default void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {}
default void tick(SubLevelContainer container) {}
```

Se registra con `container.addObserver(observer)`.

Eventos NeoForge exactos:

```java
ForgeSableSubLevelContainerReadyEvent
    .getLevel()
    .getContainer()

ForgeSablePrePhysicsTickEvent
    .getPhysicsSystem()
    .getTimeStep()

ForgeSablePostPhysicsTickEvent
    .getPhysicsSystem()
    .getTimeStep()
```

El ready event se publica después de instalar observers de física/tracking, pero antes de `ServerSubLevelContainer.initialize()` y de cargar los force-loaded sublevels. Es el lugar apropiado para añadir el observer propio.

Caveat de carga: `allocateSubLevel` dispara `onSubLevelAdded` antes de `ServerLevelPlot.load` y antes de que `SubLevelSerializer.fullyLoad` aplique `user_data`. No intentar recuperar ownership solo dentro de `onSubLevelAdded`; diferir la reconciliación a `SubLevelObserver.tick` o a una cola de “added pending”.

### Tickets persistentes

```java
public record SubLevelLoadingTicketType<T>(ResourceLocation name, Codec<T> codec) {
    public static <T> SubLevelLoadingTicketType<T> create(
            ResourceLocation name, Codec<T> codec);
}

public <T> boolean ServerSubLevelContainer.addForceLoadTicket(
        ServerSubLevel subLevel, SubLevelLoadingTicketType<T> type, T key);

public <T> boolean ServerSubLevelContainer.removeForceLoadTicket(
        ServerSubLevel subLevel, SubLevelLoadingTicketType<T> type, T key);
```

Crear una única instancia estática, por ejemplo con key `assembly UUID`, antes de que se construyan/carguen mundos. La registry de tipos es un mapa estático por `ResourceLocation`; si el tipo no existe al deserializar, Sable descarta ese ticket como desconocido.

Los tickets y punteros se guardan en `sable_sub_level_force_load_tickets`. En startup, `ServerSubLevelContainer.initialize()` carga los sublevels con tickets. Al borrar un assembly, retirar ticket y eliminar el sublevel con `SubLevelRemovalReason.REMOVED`.

Defecto observado en 2.0.3 que requiere prueba de restart: `SubLevelTicketLoadingSystem.onSubLevelRemoved(UNLOADED)` consulta `allTickets.get(serverSubLevel)` aunque el mapa está indexado por UUID. Un ticket activo normalmente evita esa ruta y el save usa el objeto cargado, pero no se debe asumir sin test que todos los punteros de unload se actualizan.

Fuentes:

- [ServerSubLevelContainer.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer.java)
- [SubLevelLoadingTicketType.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/sublevel/ticket/SubLevelLoadingTicketType.java)
- [SubLevelTicketLoadingSystem.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/sublevel/SubLevelTicketLoadingSystem.java)

## 10. Raycast, reach, interacción, collision y Flywheel

Usar el flujo normal:

```java
BlockHitResult hit = level.clip(new ClipContext(
        worldFrom, worldTo,
        ClipContext.Block.OUTLINE,
        ClipContext.Fluid.ANY,
        entity));
```

Scale 1.2.0 sobrescribe `BlockGetter.clip` con prioridad 2100. Para cada sublevel:

1. transforma `from/to` con `Pose3dc.transformPositionInverse`;
2. ejecuta el clip vanilla dentro del plot;
3. transforma la location del hit a mundo para comparar distancias;
4. devuelve el `BlockHitResult` ganador.

El resultado mini se devuelve intencionadamente en coordenadas de plot:

```java
SubLevel hitSubLevel = Sable.HELPER.getContaining(level, hit.getBlockPos());
BlockPos miniPos = hit.getBlockPos()
        .subtract(hitSubLevel.getPlot().getCenterBlock());
Vec3 worldHitLocation =
        hitSubLevel.logicalPose().transformPosition(hit.getLocation());
```

Eso permite que use/break/place vanilla opere sobre el bloque real. Sable parchea contextos de placement, dirección del jugador, distancias, punching, menús y entidades; Scale añade reach escalado y collision escalada.

`Sable.HELPER` (`ActiveSableCompanion`) ofrece, entre otros:

```java
@Nullable SubLevel getContaining(Level level, Vec3i/Position/Vector3dc/ChunkPos...);
@Nullable SubLevel getContaining(BlockEntity blockEntity);
Iterable<SubLevel> getAllIntersecting(Level level, BoundingBox3dc bounds);
Vector3d projectOutOfSubLevel(Level level, Vector3dc pos, Vector3d dest);
@Nullable SubLevel getTrackingSubLevel(Entity entity);
```

`getContaining` decide por X/Z del plot, no por Y ni por si la posición contiene un bloque.

Scale también incluye:

- `PlayerReachScaleMixin`;
- `SubLevelEntityCollisionMixin`;
- renderers vanilla/fancy;
- `SublevelRenderOffsetScaleMixin`;
- `SableFlywheelEmbeddingScaleMixin`, activado solo cuando Sable y Flywheel están presentes.

Por ello no implementar raycast/collision/reach/Flywheel paralelos. El único raycast propio necesario en fase 2 es el bootstrap sobre la carcasa vacía para seleccionar uno de sus cuatro cuadrantes; una vez existe un bloque mini, delegar en Sable/Scale.

### Drops

Los drops individuales normales ya son proyectados: el mixin de Sable sobre `ServerLevel.addFreshEntity` detecta entidades creadas en el plot y `EntitySubLevelUtil.kickEntity` transforma posición, movimiento y orientación con el pose.

Para evacuación centralizada, obtener stacks con las APIs vanilla de loot/tool y generarlos en una posición mundial explícita calculada desde el pose. Así el orden transaccional no depende de que el bloque siga presente cuando se crea el `ItemEntity`.

Fuentes:

- [ActiveSableCompanion.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/ActiveSableCompanion.java)
- [EntitySubLevelUtil.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/entity/EntitySubLevelUtil.java)
- Scale exacto: `dev/sablescale/scale/mixin/BlockGetterClipScaleMixin.class`.

## 11. Cómo identificar exclusivamente un SubLevel de Antikythera

Para una posición de plot:

```java
SubLevel any = Sable.HELPER.getContaining(level, plotPos);
if (any != null && managedSubLevels.containsKey(any.getUniqueId())) {
    // es nuestro
}
```

Para un UUID:

```java
SubLevel loaded = container.getSubLevel(sableUuid);
```

No usar:

- `scale == 0.5`: otros mods pueden crear cuerpos a esa escala;
- “estar dentro del plot-grid”: incluye todos los Sable SubLevels;
- `level instanceof SubLevel`: `SubLevel` no es `Level`;
- `getName()`: no es identidad estable.

Usar un índice O(1) por Sable UUID en el manager, restaurado desde SavedData y corroborado con `userDataTag`. Esta misma comprobación debe gobernar FrameMask y la supresión de split.

## 12. Movimiento y pose

### API real

`PhysicsPipeline`:

```java
void teleport(PhysicsPipelineBody body,
              Vector3dc position, Quaterniondc orientation);
void resetVelocity(PhysicsPipelineBody body);
void wakeUp(PhysicsPipelineBody body);
@Nullable <T extends PhysicsConstraintHandle> T addConstraint(
        @Nullable PhysicsPipelineBody bodyA,
        @Nullable PhysicsPipelineBody bodyB,
        PhysicsConstraintConfiguration<T> config);
```

También:

```java
RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
handle.teleport(position, orientation);
```

`RapierPhysicsPipeline.teleport` actualiza el cuerpo nativo y, para `ServerSubLevel`, `logicalPose.position/orientation`. No cambia escala ni rotation point.

Mutar solo:

```java
subLevel.logicalPose().position().set(...);
```

no es suficiente: `SubLevelPhysicsSystem.updateAllPoses` vuelve a leer posición/orientación desde Rapier.

### Timing

`ForgeSablePostPhysicsTickEvent` se emite por cada substep después de `pipeline.physicsTick` y después de `updateAllPoses`. Para un assembly conducido por pistón/contraption:

1. filtrar por `event.getPhysicsSystem().getLevel()`;
2. obtener transform objetivo del carrier para ese substep;
3. `pipeline.teleport(subLevel, targetPosition, targetOrientation)`;
4. `pipeline.resetVelocity(subLevel)` si se quiere un hijo puramente cinemático;
5. `subLevel.updateBoundingBox()` para que tracking/query no quede un tick atrás.

El tracking observer se ejecuta después del observer físico, así que verá el pose impuesto en el post-physics event.

### Assembly estacionario

Sable permite un fixed constraint a mundo:

```java
public record FixedConstraintConfiguration(
        Vector3dc pos1,
        Vector3dc pos2,
        Quaterniondc orientation);
```

`pipeline.addConstraint(subLevel, null, config)` fija el cuerpo al mundo. `pos1` debe estar dentro del plot del primer cuerpo; `pos2` debe ser mundo y no plot-grid. Scale 1.2.0 parchea anchors de constraints escalados.

El handle no aparece en `SubLevelSerializer`: hay que guardarlo solo runtime y recrearlo después de load/merge/split. Para el spike, conducir el pose en post-physics es más simple; el fixed constraint es la opción a validar para estado estacionario estable.

### Create contraptions

`KinematicContraption` de Sable describe colliders cinemáticos de contraptions:

```java
public interface KinematicContraption {
    Vector3dc sable$getPosition(double partialTick);
    Quaterniond sable$getOrientation(double partialTick);
    MassTracker sable$getMassTracker();
    // bounds, block getter, lift providers, validity...
}
```

No extiende `PhysicsPipelineBody` y no es una API de parentado de un `ServerSubLevel`. No existe `subLevel.setParent(contraption)`. La integración de fase 6 debe muestrear el transform real de Create y conducir el pose Sable; las firmas concretas de Create deben contrastarse por separado.

Composición que conserva BlockPos/BlockState:

```text
qRelative = inverse(qCarrierAtMount) * qSubLevelAtMount
pRelative = inverse(qCarrierAtMount) * (pSubLevelAtMount - pCarrierAtMount)

qTarget = qCarrierNow * qRelative
pTarget = pCarrierNow + qCarrierNow * pRelative
```

Solo se envían `pTarget/qTarget` a `teleport`. `logicalPose.scale` y `rotationPoint` permanecen; los BlockStates internos nunca rotan.

Fuentes:

- [PhysicsPipeline.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/physics/PhysicsPipeline.java)
- [RigidBodyHandle.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/physics/handle/RigidBodyHandle.java)
- [KinematicContraption.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/sublevel/KinematicContraption.java)
- [ForgeSablePostPhysicsTickEvent.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/neoforge/src/main/java/dev/ryanhcode/sable/neoforge/event/ForgeSablePostPhysicsTickEvent.java)

## 13. Split automático de Sable frente a FrameGraph

`ServerSubLevel.tick()` llama:

```java
if (SableConfig.SUB_LEVEL_SPLITTING.getAsBoolean()) {
    heatMapManager.tick();
}
```

El config es global. `SubLevelHeatMapManager` considera sólido cualquier `!state.isAir()` y decide componentes por caras y aristas. Su listener:

```java
public static void addSplitListener(SplitListener listener);

@FunctionalInterface
public interface SplitListener {
    void addBlocks(Level level,
                   BoundingBox3ic assemblyBounds,
                   Collection<BlockPos> blocks);
}
```

solo permite añadir bloques a la región antes de ensamblarla; no cancela el split ni sustituye su topología.

Esto viola el invariante de Antikythera: frames vacíos conectados son un assembly y dos mecanismos interiores desconectados dentro de esos frames también lo son.

Solución mínima: mixin de compatibilidad estrecho sobre la llamada `SubLevelHeatMapManager.tick()` dentro de `ServerSubLevel.tick()`, que no la ejecute si `managedSubLevels.contains(subLevel.getUniqueId())`. No cambiar `SableConfig.SUB_LEVEL_SPLITTING` globalmente.

Recomendación de mantenimiento:

- target exacto `ServerSubLevel#tick`;
- wrap/redirect solo de la invocación a `SubLevelHeatMapManager#tick()V`;
- `require = 1` para fallar claramente si una actualización de Sable cambia el punto;
- comprobar UUID del manager, no escala ni coordenada;
- GameTest que demuestre que otro Sable SubLevel sigue auto-splitting.

Fuente: [SubLevelHeatMapManager.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/sublevel/plot/heat/SubLevelHeatMapManager.java).

## 14. Merge/split explícitos

### Lo que Sable sí ofrece

```java
public static ServerSubLevel SubLevelAssemblyHelper.assembleBlocks(
        ServerLevel level, BlockPos anchor,
        Iterable<BlockPos> blocks, BoundingBox3ic bounds);

public static void SubLevelAssemblyHelper.moveBlocks(
        ServerLevel originLevel,
        AssemblyTransform transform,
        Iterable<BlockPos> blocks);

public AssemblyTransform(
        BlockPos anchorPos,
        BlockPos resultingAnchorPos,
        int angle,
        Rotation rotation,
        ServerLevel resultingLevel);
```

Con `angle = 0` y `Rotation.NONE` no rota BlockStates.

Scale redirige la allocation interna de `assembleBlocks`: si el anchor está dentro de un `ServerSubLevel`, copia su escala al pose nuevo. Esto ayuda al split físico de Sable, pero no aporta topología de frames ni transacción.

`moveBlocks`:

- obtiene BlockState;
- guarda BE con `saveWithFullMetadata`;
- reescribe `x/y/z`;
- llama `BlockSubLevelAssemblyListener.beforeMove/afterMove`;
- crea state/BE destino;
- limpia inventario/source;
- finalmente escribe aire en origen.

Cada bloque está envuelto en `try/catch` individual. Puede continuar tras fallo parcial. No es una operación atómica. Tampoco copia scheduled block/fluid ticks.

No existe `mergeSubLevels` público.

Fuente: [SubLevelAssemblyHelper.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/api/SubLevelAssemblyHelper.java).

### Merge de frames

Diseño mínimo:

1. elegir winner por regla determinista;
2. congelar ambas revisiones de FrameGraph y adquirir lock;
3. calcular mapping de todas las mini posiciones del loser al espacio continuo del winner;
4. crear chunks destino y preflight de colisiones/máscara;
5. snapshot de states, BEs, adapters y scheduled ticks;
6. copiar y verificar destino bajo bypass;
7. limpiar origen solo después de verificar;
8. actualizar SavedData/user tag/ticket;
9. eliminar loser con `SubLevelRemovalReason.REMOVED`;
10. liberar lock.

### Split por eliminación de frame

1. evacuar solo sus ocho celdas;
2. recalcular componentes en FrameGraph;
3. mantener una componente en el SubLevel original;
4. para cada componente adicional, asignar un SubLevel nuevo directamente a escala 0.5;
5. transferir exactamente sus mini posiciones con `Rotation.NONE`;
6. poner anchor técnico en cualquier componente sin contenido;
7. mover tickets/ownership;
8. opcionalmente usar `setSplitFrom` solo para suavizar la primera interpolación cliente; no es persistencia ni topología.

### Transferencia de scheduled ticks

Minecraft 1.21.1 expone en `LevelTicks<T>`:

```java
public void copyArea(BoundingBox area, Vec3i offset);
public void clearArea(BoundingBox area);
```

`ServerLevel.getBlockTicks()` y `getFluidTicks()` devuelven `LevelTicks<Block/Fluid>`. Como source y destination plots están en el mismo `ServerLevel` y cada mapping usa una traslación entera:

1. crear todos los chunks destino;
2. por cada posición fuente seleccionada, `copyArea(new BoundingBox(sourcePos), delta)` en block y fluid ticks;
3. verificar contenido destino;
4. `clearArea(new BoundingBox(sourcePos))` en origen;
5. en rollback, limpiar también las áreas destino copiadas.

Usar una caja de un bloque evita copiar ticks de otra componente dentro de un bounding box rectangular. `copyArea` preserva type, trigger tick y priority y reasigna sub-tick order.

No combinar esta copia con `EmbeddedPlotLevelAccessor.getBlockTicks()` usando posiciones relativas.

## 15. Hook real de escrituras y FrameMask

Sable ya mezcla `LevelChunk`:

```java
@Mixin(LevelChunk.class)
public class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    ...

    @WrapOperation(
        method = "setBlockState",
        at = @At(
            value = "INVOKE",
            target = "LevelChunkSection.setBlockState(...)"))
    ...

    @Inject(method = "setBlockState", at = @At("RETURN"))
    ...
}
```

La firma vanilla/Sable objetivo es:

```java
@Nullable BlockState LevelChunk.setBlockState(
        BlockPos pos, BlockState state, boolean isMoving);
```

`SableCommonEvents.handleBlockChange` actualiza bounds, heatmap, masa, collider y física. Un veto de FrameMask debe ocurrir antes de esa mutación.

Mixin mínimo recomendado:

- inyección cancellable en HEAD, prioridad explícita superior a la default de Sable;
- solo servidor;
- obtener `SubLevel owner = Sable.HELPER.getContaining(level, pos)`;
- continuar si owner es null o su UUID no está en el manager;
- convertir `pos - plot.getCenterBlock()` a mini y `floorDiv(mini, 2)`;
- permitir FrameMask o service shell;
- en caso contrario devolver `null`, que es el resultado vanilla de escritura fallida;
- bypass privado con profundidad `ThreadLocal<Integer>` y `try/finally` para merge/split/migration/evacuation.

No interceptar otros Sable SubLevels.

Límites honestos:

- una escritura directa a `LevelChunkSection` evita tanto el hook de Sable como este; no se busca un sandbox universal;
- un veto de bajo nivel puede encontrarse a mitad de una operación multibloque externa. Pistones/fluidos/falling blocks/Create necesitan tests de no pérdida, no solo “el bloque externo no apareció”;
- el hard deny de Mechanism Frame anidado debe seguir existiendo en whitelist/placement aunque un bypass esté activo;
- operaciones internas deben abrir bypass solo alrededor de targets exactos y cerrarlo siempre en `finally`.

Fuente: [LevelChunkMixin.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/mixin/plot/LevelChunkMixin.java) y [SableCommonEvents.java](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.3-neoforge/common/src/main/java/dev/ryanhcode/sable/SableCommonEvents.java).

## 16. Diseño mínimo por fase

### Fase 2: spike

- `MechanismFrameBlock/BE` mínimo.
- `MechanismAssemblyManager` con índice assembly UUID ↔ Sable UUID.
- allocation directa con `Pose3d.scale().set(0.5)`.
- chunk central + anchor técnico antes de retornar.
- una FrameCell canónica y ocho posiciones `2*frame + {0,1}`.
- force-load ticket propio.
- user data marker.
- bootstrap placement por cuadrante solo cuando se golpea carcasa/espacio vacío.
- interacciones posteriores por `level.clip` normal.
- conducción de pose en post-physics o fixed constraint para que gravedad no separe el SubLevel del frame.
- verificar reload, BE ticker, dos jugadores, drops, collision y Create/Flywheel.

### Fase 3: FrameMask

- Set de FrameCell, lookup O(1).
- hook `LevelChunk#setBlockState` solo para UUID gestionados.
- service shell separado.
- bypass scoped.
- testear también resultado/inventario después de piston, sticky piston, water, lava y falling blocks.

### Fase 4: connected frames

- FrameGraph como única autoridad.
- origen/mapping lógico estable; no basarlo en CoM.
- winner determinista y transfer journal.
- `Rotation.NONE` en transfers.
- copiar scheduled ticks mediante `LevelTicks`.
- suppress estrecho del heatmap Sable.
- redstone/Create cruzando boundary son bloques adyacentes reales en el mismo plot.

### Fase 5: split y evacuación

- lock/revision por assembly;
- loot con tool original; explosión produce 100 % por política propia;
- obtener drops antes de borrar, pero publicarlos solo cuando el commit sea irreversible;
- copiar/verificar nuevos componentes antes de limpiar source;
- ticks y BE NBT incluidos en journal;
- anchors para componentes vacíos;
- actualizar tickets y mappings en el mismo commit lógico;
- pruebas crash-like/duplicación: repetir handler, doble packet, explosión+break mismo tick, fallo de adapter.

### Fase 6: movimiento

- target pose derivado de frame/piston/carrier, nunca de reubicar minibloques;
- `RigidBodyHandle.teleport`/`PhysicsPipeline.teleport`;
- post-physics por substep para carrier dinámico;
- reset de velocidad si debe ser cinemático;
- bounds actualizados;
- scale y rotationPoint intactos;
- BlockPos y BlockState internos invariantes antes/después;
- al estacionar, recrear fixed constraint si se adopta esa estrategia;
- persistir solo relación/offset del carrier, no handles nativos.

## 17. Riesgos que deben convertirse en pruebas

1. Creación y retiro del último bloque sin eliminar accidentalmente el SubLevel.
2. Recovery físico con el anchor técnico elegido.
3. Bounds/tracking extra causados por service anchor.
4. Force-load y UUID tras dos reinicios consecutivos.
5. Defecto de pointer en ruta `UNLOADED`.
6. Scale 0.5 presente en save y en ambos poses iniciales de red.
7. Hit de Scale devuelve plot location; outlines/drops propios la proyectan una sola vez.
8. FrameMask no afecta un Sable SubLevel ajeno.
9. Sable auto-split sigue funcionando en cuerpos ajenos y queda suprimido en los gestionados.
10. Transfer de BE con inventario no duplica ni pierde ante excepción intermedia.
11. Scheduled block y fluid tick mantienen delay/priority tras merge y split.
12. Teleport post-physics no deja bounds un tick atrás ni produce jitter cliente.
13. Fixed constraints se recrean tras load y sus anchors funcionan a 0.5.
14. Flywheel renderiza escala 0.5 con el JAR 1.2.0 exacto.
15. No hay ninguna mutación de BlockState durante rotación de assembly.

## 18. Índice de fuentes exactas

Sable `common/src/main/java`:

- `dev/ryanhcode/sable/api/sublevel/SubLevelContainer.java`
- `dev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer.java`
- `dev/ryanhcode/sable/api/sublevel/SubLevelObserver.java`
- `dev/ryanhcode/sable/api/sublevel/SubLevelTicketLoadingSystem.java`
- `dev/ryanhcode/sable/api/sublevel/ticket/SubLevelLoadingTicketType.java`
- `dev/ryanhcode/sable/api/SubLevelAssemblyHelper.java`
- `dev/ryanhcode/sable/api/physics/PhysicsPipeline.java`
- `dev/ryanhcode/sable/api/physics/handle/RigidBodyHandle.java`
- `dev/ryanhcode/sable/api/physics/constraint/FixedConstraintConfiguration.java`
- `dev/ryanhcode/sable/api/physics/mass/MassData.java`
- `dev/ryanhcode/sable/sublevel/SubLevel.java`
- `dev/ryanhcode/sable/sublevel/ServerSubLevel.java`
- `dev/ryanhcode/sable/sublevel/plot/LevelPlot.java`
- `dev/ryanhcode/sable/sublevel/plot/ServerLevelPlot.java`
- `dev/ryanhcode/sable/sublevel/plot/EmbeddedPlotLevelAccessor.java`
- `dev/ryanhcode/sable/sublevel/plot/heat/SubLevelHeatMapManager.java`
- `dev/ryanhcode/sable/sublevel/storage/serialization/SubLevelSerializer.java`
- `dev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem.java`
- `dev/ryanhcode/sable/ActiveSableCompanion.java`
- `dev/ryanhcode/sable/SableCommonEvents.java`
- `dev/ryanhcode/sable/mixin/plot/LevelChunkMixin.java`
- `dev/ryanhcode/sable/mixin/plot/ServerLevelMixin.java`
- `dev/ryanhcode/sable/mixin/clip_overwrite/BlockGetterMixin.java`

Sable `neoforge/src/main/java`:

- `dev/ryanhcode/sable/neoforge/event/ForgeSableSubLevelContainerReadyEvent.java`
- `dev/ryanhcode/sable/neoforge/event/ForgeSablePrePhysicsTickEvent.java`
- `dev/ryanhcode/sable/neoforge/event/ForgeSablePostPhysicsTickEvent.java`
- `dev/ryanhcode/sable/neoforge/gametest/SableTestHelper.java`

Sable Scale 1.2.0, entradas exactas del JAR:

- `dev/sablescale/scale/SubLevelScale.class`
- `dev/sablescale/scale/api/SubLevelScaleEvents.class`
- `dev/sablescale/scale/ScaledColliders.class`
- `dev/sablescale/scale/ScaledConstraints.class`
- `dev/sablescale/scale/mixin/RapierPhysicsPipelineMixin.class`
- `dev/sablescale/scale/mixin/RapierConstraintScaleMixin.class`
- `dev/sablescale/scale/mixin/SableNBTUtilsMixin.class`
- `dev/sablescale/scale/mixin/SableBufferUtilsMixin.class`
- `dev/sablescale/scale/mixin/SubLevelAssemblyHelperScaleMixin.class`
- `dev/sablescale/scale/mixin/BlockGetterClipScaleMixin.class`
- `dev/sablescale/scale/mixin/PlayerReachScaleMixin.class`
- `dev/sablescale/scale/mixin/SubLevelEntityCollisionMixin.class`
- `dev/sablescale/scale/mixin/SublevelRenderOffsetScaleMixin.class`
- `dev/sablescale/scale/flywheel/mixin/SableFlywheelEmbeddingScaleMixin.class`
