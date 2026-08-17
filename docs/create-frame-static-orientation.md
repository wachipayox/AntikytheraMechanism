# Create + connected Mechanism Frames: static orientation policy

## Status

This is an intentional gameplay/mental-model rule of Antikythera Mechanism, not a temporary limitation of Sable or Create.

A connected `MechanismAssembly` may follow any rigid Create rotation while it is captured in a contraption. Once it becomes ordinary world blocks again, a multi-Frame assembly must be **upright**: its physical up direction is world `UP`. Horizontal yaw rotations are allowed; static pitch and roll are not.

## Why static pitch/roll is deliberately forbidden

The mini blocks live at immutable positions in one managed Sable SubLevel. Connected Mechanism Frames are a physical window onto regions of that same logical mini world.

Allowing an upright placed Frame graph while its SubLevel remains sideways or upside-down is technically representable, but it is deliberately rejected because it makes the spatial model unnecessarily confusing. Looking into a Frame and finding gravity/up inverted, or finding a connected mini region projecting sideways out of an upright row of Frames, is difficult to reason about. A horizontal yaw change preserves the intuitive meaning of up and is therefore acceptable.

This gives the mod a simple visual rule:

- moving Create contraption: arbitrary rigid orientation is valid;
- static connected Mechanism Frames: always world-up;
- horizontal rotation around Y: valid;
- static pitch/roll: invalid.

Mini `BlockPos` values are never rotated or relocated to satisfy this rule.

## Voluntary disassembly

When a Mechanical Bearing is asked to disassemble normally, Antikythera checks the same 90-degree snapped pose that Create would use.

If a connected multi-Frame assembly would be placed with pitch or roll, disassembly is cancelled and Create's `AssemblyException` UI reports:

> Could not disassemble: connected Mechanism Frames must be upright

The contraption remains assembled. The player can rotate it back to an upright snapped pose and request disassembly again.

A single Frame does not need this restriction because it has no inter-Frame physical shape that can diverge from the immutable logical region; its placed physical view remains upright as before.

## Forced disassembly when the controller is destroyed

Controller destruction cannot depend on the player first restoring a valid pose. `MechanicalBearingBlockEntity.remove()` therefore enters a forced-disassembly scope.

If the final Create transform is already upright, it is unchanged. If a connected multi-Frame assembly is on a horizontal bearing axis at a snapped pitch/roll, the complete contraption receives the nearest upright transform before Create sends the disassembly packet or places any blocks.

Create 6.0.10 represents this controlled disassembly with one rotation axis. In that one-axis family, a horizontal X/Z bearing has one upright snapped member: zero rotation relative to the captured structure. The transform keeps Create's placement offset, so the **whole contraption** snaps as one rigid structure; individual Frames are never rearranged independently.

This forced snap applies only to controller-loss teardown. A normal disassembly request still fails with the visible exception described above.

Create's `SmartBlockEntity` does not invoke `remove()` for an ordinary chunk unload: it marks the block entity as chunk-unloaded first and skips the removal hook. Therefore unloading the bearing's chunk is not accidentally classified as controller destruction. A real block removal, or the bearing itself being picked up by another contraption, is a forced teardown and uses the snap policy.

## Occupied and indestructible destination blocks

Antikythera does **not** search for a free NORTH/EAST/SOUTH/WEST alternative and does not add a generic free-space policy for ordinary destination blocks. After the upright transform is selected, Create's normal `Contraption.addBlocksToWorld()` replacement semantics remain authoritative.

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
5. Mini-content drops for a skipped carried Frame are projected through the **final snapped placement pose**. This matters during forced controller-loss teardown because Sable can still be displaying the previous in-flight pose in the same synchronous tick. Only the visual spawn position is projected; mini `BlockPos` values are never moved.
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
- Static multi-Frame assemblies are world-up.
- Horizontal yaw remains supported.
- Mini blocks are never moved merely to compensate for Create rotation.
- A Frame skipped by Create evacuates the same mini contents as an ordinary generic Frame destruction.
- A skipped bridge Frame causes the same connectivity split as an ordinary Frame removal.
- A replaced destination Frame evacuates/splits its old assembly before the new moving ownership is committed.
- Create remains authoritative for the selected destination, ordinary block replacement, and outer Frame drops.
- Ambiguous or partially materialised BlockEntities retain recovery journals instead of causing destructive inference.
