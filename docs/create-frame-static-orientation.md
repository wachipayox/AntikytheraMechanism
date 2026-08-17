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

## Occupied and indestructible destination blocks

Antikythera intentionally does not add a destination-space preflight. After the upright transform is selected, Create's normal `Contraption.addBlocksToWorld()` policy remains authoritative.

Create normally destroys replaceable destination blocks (respecting its contraption replacement/drop configuration) and places the carried block. For an indestructible target, or another placement case that Create itself refuses, Create can skip the carried block and optionally drop that carried block according to its own configuration.

This means Antikythera does not search NORTH/EAST/SOUTH/WEST for a different free placement and does not silently change Create's obstruction semantics.

## Missing Mechanism Frames during placement

A skipped Mechanism Frame needs extra bookkeeping because Create only knows about the outer carried block; Antikythera also owns the Frame's mini region and assembly graph.

After `addBlocksToWorld()` returns, placement is inspected against the persisted Create relocation journal:

1. If every expected Frame exists with its BlockEntity, the existing atomic relocation path is used unchanged.
2. A target that contains a Mechanism Frame state but not yet its BlockEntity is treated as unresolved, **not destroyed**. The journal is retained for recovery; mini content is not evacuated from ambiguous evidence.
3. A target that is no longer a Mechanism Frame is a definite skipped/failed materialisation. Before changing any assembly coordinates, Antikythera evacuates that source Frame's exact eight immutable mini cells through `FrameEvacuationService` with a generic destruction cause.
4. The outer Mechanism Frame item is **not** dropped by Antikythera here. Create has already applied its own carried-block drop policy, so Antikythera only drops the mini contents and avoids duplication.
5. After every missing Frame has evacuated successfully, stale source indices are removed and only materialised target Frames become members of the relocated assembly.
6. The normal `splitDisconnectedAssembly` transaction then runs. If the skipped Frame was a bridge/articulation point, each surviving connected component receives the same safe split/content-transfer treatment as a Frame broken normally in the world.
7. Boundary bridges and occupied masks are rebuilt for the surviving assembly ids.

If evacuation cannot be completed or rolled back exactly, the content-recovery lock and persistent journals are retained. The code fails closed rather than guessing whether mini cells are still owned by a Frame that Create did not materialise.

## Invariants

The integration is required to preserve all of the following:

- Create rotates and places the **entire contraption**, never just the Frames.
- Connected Frames are captured as one complete `MechanismAssembly` graph.
- Static multi-Frame assemblies are world-up.
- Horizontal yaw remains supported.
- Mini blocks are never moved merely to compensate for Create rotation.
- A Frame skipped by Create evacuates the same mini contents as an ordinary generic Frame destruction.
- A skipped bridge Frame causes the same connectivity split as an ordinary Frame removal.
- Create remains authoritative for ordinary destination replacement and carried outer-block drops.
- Ambiguous or partially materialised BlockEntities retain recovery journals instead of causing destructive inference.
