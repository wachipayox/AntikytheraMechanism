# Create + Mechanism Frames: static orientation policy

## Status

This is an intentional gameplay and coordinate-system rule of Antikythera Mechanism.

A `MechanismAssembly` may follow any rigid 3D rotation while it is captured in a Create contraption. Once a Mechanism Frame exists again as an ordinary placed block, however, its local up direction is always the parent level's `UP`. Static pitch and roll are not persistent Frame state. Only horizontal yaw is.

The implementation therefore separates two concepts:

- `FrameOrientation` is the discrete **static yaw** of a placed assembly. It stores only `NORTH`, `EAST`, `SOUTH` or `WEST` as `front`; `UP` and `DOWN` never swap.
- `AssemblyPose` is the continuous physical pose. While Create or another physical host is moving an assembly it may contain arbitrary pitch, yaw and roll through its quaternion.

Mini `BlockPos` values remain immutable. They are never physically rotated or relocated merely because Create rotates a Frame.

## Why static FrameOrientation is yaw-only

A placed Mechanism Frame has only `HORIZONTAL_FACING`. There is no valid static BlockState in which the Frame itself is upside-down or lying on its side. Retaining a hidden 24-way logical orientation after Create had already placed the visible Frame and managed SubLevel upright created two contradictory coordinate systems: the visible boundary used one orientation while redstone/support could interpret faces through another.

That hidden pitch/roll state is now deliberately forbidden. When a Frame is static:

- local `UP` is always `UP`;
- local `DOWN` is always `DOWN`;
- horizontal faces are transformed only by the stored yaw;
- the placed Frame, managed SubLevel and macro/mini boundary systems share the same static orientation.

This means a Create flip such as 180 degrees around X may exist while the contraption is moving, but after a single Frame is placed with `HORIZONTAL_FACING=SOUTH` its persistent static orientation is simply `SOUTH`, not a hidden `DOWN/SOUTH` basis.

## Immutable mini regions and connected Frames

Yaw is still necessary. It preserves which immutable mini region belongs to each physical Frame after a valid horizontal rotation of a multi-Frame assembly.

For example, if a sibling that originally represented logical offset `+X` is physically moved to `+Z` by a 90-degree Y rotation, the horizontal `FrameOrientation` records that mapping without moving any mini blocks.

Pitch/roll are different. A connected multi-Frame graph has a physical shape. Placing that graph on its side while its Frames themselves are upright would make the physical Frame arrangement disagree with the immutable mini-region layout. Such a static placement is therefore not allowed.

## Moving Create contraptions

While extracted into a contraption there is no placed origin Frame BlockEntity acting as a static orientation authority. The managed SubLevel follows the full `AssemblyPose`, including arbitrary intermediate and snapped pitch/roll.

`FrameOrientation` remains the yaw-only discrete mapping captured from the last valid static state. It is not used to approximate the contraption's continuous 3D pose.

This division is intentional:

- static discrete mapping: `FrameOrientation`;
- moving physical transform: `AssemblyPose` / Create quaternion;
- immutable content storage: mini plot `BlockPos` values.

## Voluntary disassembly

When a Mechanical Bearing is asked to disassemble normally, Antikythera checks the same 90-degree snapped transform that Create would use.

For a connected multi-Frame assembly, a static pitch/roll placement is rejected and Create's `AssemblyException` UI reports:

> Could not disassemble: connected Mechanism Frames must be upright

The contraption remains assembled. The player can rotate it back to a valid upright snapped pose and disassemble again.

A single Frame does not have an inter-Frame physical shape, so it may be returned from an arbitrary Create orientation. Create still materializes the only valid upright Frame BlockState; Antikythera then canonicalizes the assembly's static yaw and static pose to that placed `HORIZONTAL_FACING`.

## Forced disassembly when the controller is destroyed

Controller destruction cannot depend on the player first restoring a valid pose. `MechanicalBearingBlockEntity.remove()` therefore enters a forced-disassembly scope.

If the final Create transform is already upright, it is unchanged. If a connected multi-Frame assembly is on a horizontal bearing axis at a snapped pitch/roll, the complete contraption receives the nearest upright transform before Create sends the disassembly packet or places any blocks.

Create 6.0.10 represents this controlled disassembly with one rotation axis. In that one-axis family, a horizontal X/Z bearing has one upright snapped member: zero rotation relative to the captured structure. The transform keeps Create's placement offset, so the **whole contraption** snaps as one rigid structure; individual Frames are never rearranged independently.

This forced snap applies only to controller-loss teardown. A normal disassembly request still fails with the visible exception described above.

Create's `SmartBlockEntity` does not invoke `remove()` for an ordinary chunk unload: it marks the block entity as chunk-unloaded first and skips the removal hook. Therefore unloading the bearing's chunk is not accidentally classified as controller destruction. A real block removal, or the bearing itself being picked up by another contraption, is a forced teardown and uses the snap policy.

## Boundary semantics after placement

Support, redstone and other macro/mini face projections must interpret a static Frame through the same yaw that its visible BlockState and managed SubLevel use.

In particular, Create pitch/roll must never leave an invisible face inversion behind. If only the mini bottom face is sturdy or emits redstone before/after placement, only the physically visible bottom boundary may provide that support or output. The opposite top face must not inherit the old flipped result.

The placement synchronization path canonicalizes both the assembly orientation and its static `AssemblyPose` before normal boundary reconnection. No manual Frame/SubLevel update should be required to repair face semantics.

## Persisted orientation and migration

New `FrameOrientation` NBT stores only horizontal `front`; it no longer writes an `up` field.

The loader still understands the former 24-way `up` + `front` representation and canonicalizes it to an upright yaw. A placed single-Frame origin additionally reconciles against its actual `HORIZONTAL_FACING` during normal synchronization. Full quaternions in Create movement/recovery journals remain full `AssemblyPose` data and are not reduced prematurely while the body is moving.

## Occupied and indestructible destination blocks

Antikythera does **not** search for a free NORTH/EAST/SOUTH/WEST alternative and does not add a generic free-space policy for ordinary destination blocks. After the valid placement transform is selected, Create's normal `Contraption.addBlocksToWorld()` replacement semantics remain authoritative.

Create normally destroys replaceable destination blocks (respecting its contraption replacement/drop configuration) and places the carried block. For an indestructible target, or another placement case that Create itself refuses, Create can skip the carried block and optionally drop that carried block according to its own configuration.

A destination that is itself another managed Mechanism Frame needs additional Antikythera bookkeeping, but it is still **replaced at the location Create chose** rather than causing a search for another placement. The unrelated Frame ownership entry is temporarily hidden only while the moving assembly's target journal is validated. Create then performs its ordinary outer-block destruction/replacement. After placement, Antikythera uses the preserved old ownership to evacuate that destroyed Frame's mini region and split its old assembly before assigning the destination Frame to the moving assembly.

This special bookkeeping exists because Create knows only about the outer Frame block, while Antikythera also owns the Frame's mini content and graph identity. It does not change Create's choice of placement position or its outer-block drop policy.

## Missing Mechanism Frames during placement

A skipped Mechanism Frame needs extra bookkeeping because Create only knows about the outer carried block; Antikythera also owns the Frame's mini region and assembly graph.

After `addBlocksToWorld()` returns, placement is inspected against the persisted Create relocation journal:

1. If every expected Frame exists with its BlockEntity and no unrelated Frame ownership was displaced, the existing atomic relocation path is used unchanged.
2. A target that contains a Mechanism Frame state but not yet its BlockEntity is treated as unresolved, **not destroyed**. The journal is retained for recovery; mini content is not evacuated from ambiguous evidence.
3. A target that is no longer a Mechanism Frame is a definite skipped/failed materialisation. Before changing any moving assembly coordinates, Antikythera evacuates that source Frame's exact eight immutable mini cells through `FrameEvacuationService` with a generic destruction cause.
4. The outer carried Mechanism Frame item is **not** dropped by Antikythera here. Create has already applied its own carried-block drop policy, so Antikythera only drops the mini contents and avoids duplication.
5. Mini-content drops for a skipped carried Frame are projected through the final snapped placement pose. This affects only visual spawn projection; mini `BlockPos` values never move.
6. If Create replaced an unrelated destination Mechanism Frame, that old Frame's mini region is evacuated with the same generic destruction semantics. Create remains responsible for the old outer Frame's normal world-block drop.
7. After every definite Frame loss has evacuated successfully, stale indices are removed and only materialised target Frames become members of the relocated assembly.
8. The normal `splitDisconnectedAssembly` transaction then runs. If a lost Frame was a bridge/articulation point, each surviving connected component receives the same safe split/content-transfer treatment as a Frame broken normally in the world.
9. Boundary bridges and occupied masks are rebuilt for the surviving assembly ids.

If evacuation cannot be completed or rolled back exactly, the affected assembly receives a content-recovery lock and persistent journals are retained. The code fails closed rather than guessing whether mini cells are still owned by a Frame that Create did not materialise or already replaced.

The semantic `origin` of a `MechanismAssembly` is deliberately a stable logical anchor, not a promise that a physical Frame always occupies that coordinate. If Create skips the Frame that happened to be at the origin, the surviving assembly is **not rebased**, because rebasing would change immutable logical mini offsets. This is the same model used by ordinary Frame removal.

## Invariants

The integration is required to preserve all of the following:

- Create rotates and places the **entire contraption**, never just the Frames.
- Connected Frames are captured as one complete `MechanismAssembly` graph.
- A static Mechanism Frame is always locally `UP`; `FrameOrientation` stores horizontal yaw only.
- Arbitrary pitch/roll exists only in the moving physical `AssemblyPose`, never as hidden static Frame orientation.
- Horizontal yaw remains supported and preserves physical-Frame to logical-mini-region correspondence.
- Mini blocks are never moved merely to compensate for Create rotation.
- Static support/redstone face semantics agree with the visible Frame/SubLevel orientation.
- A Frame skipped by Create evacuates the same mini contents as an ordinary generic Frame destruction.
- A skipped bridge Frame causes the same connectivity split as an ordinary Frame removal.
- A replaced destination Frame evacuates/splits its old assembly before the new moving ownership is committed.
- Create remains authoritative for the selected destination, ordinary block replacement, and outer Frame drops.
- Ambiguous or partially materialised BlockEntities retain recovery journals instead of causing destructive inference.
