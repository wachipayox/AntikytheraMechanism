# Create + Mechanism Frames: static orientation policy

## Status

This is an intentional gameplay and coordinate-system rule of Antikythera Mechanism.

A `MechanismAssembly` may follow any rigid 3D rotation while it is captured in a Create contraption. Once a Mechanism Frame exists again as an ordinary placed block, however, its local up direction is always the containing level's `UP`. If the contraption lives inside a Sable sublevel, this means the sublevel's local `UP`, not the root world's `UP`. Static pitch and roll are not persistent Frame state. Only horizontal yaw is.

The implementation therefore separates two concepts:

- `FrameOrientation` is the discrete **static yaw** of a placed assembly. It stores only `NORTH`, `EAST`, `SOUTH` or `WEST` as `front`; `UP` and `DOWN` never swap.
- `AssemblyPose` is the continuous physical pose. While Create or another physical host is moving an assembly it may contain arbitrary pitch, yaw and roll through its quaternion.

Mini `BlockPos` values remain immutable. They are never physically rotated or relocated merely because Create rotates a Frame.

## Why static FrameOrientation is yaw-only

A placed Mechanism Frame has only `HORIZONTAL_FACING`. There is no valid static BlockState in which the Frame itself is upside-down or lying on its side. Retaining hidden pitch/roll after Create had already placed the visible Frame and managed SubLevel upright would create two contradictory coordinate systems.

That hidden pitch/roll state is deliberately forbidden for every Mechanism Frame, including a single-Frame assembly. When a Frame is static:

- local `UP` is always the containing level's `UP`;
- local `DOWN` is always the containing level's `DOWN`;
- horizontal faces are transformed only by the stored yaw;
- the placed Frame, managed SubLevel and macro/mini boundary systems share the same static orientation.

A Create flip such as 180 degrees around containing-level X may exist while the contraption is moving, but it cannot be materialized statically in that phase. A rotation around containing-level Y is valid yaw because it preserves local `UP`.

## Immutable mini regions and connected Frames

Yaw is still necessary. It preserves which immutable mini region belongs to each physical Frame after a valid horizontal rotation of a multi-Frame assembly.

For example, if a sibling that originally represented logical offset `+X` is physically moved to `+Z` by a 90-degree Y rotation, the horizontal `FrameOrientation` records that mapping without moving any mini blocks.

Pitch/roll are different. They change the Frame's local up direction relative to the level that contains it and therefore cannot be retained as static Frame state, regardless of assembly size.

## Moving Create contraptions

While extracted into a contraption there is no placed origin Frame BlockEntity acting as a static orientation authority. The managed SubLevel follows the full `AssemblyPose`, including arbitrary intermediate and snapped pitch/roll.

`FrameOrientation` remains the yaw-only discrete mapping captured from the last valid static state. It is not used to approximate the contraption's continuous 3D pose.

This division is intentional:

- static discrete mapping: `FrameOrientation`;
- moving physical transform: `AssemblyPose` / Create quaternion;
- immutable content storage: mini plot `BlockPos` values.

## Voluntary disassembly

When a Mechanical Bearing is asked to disassemble normally, Antikythera checks the same 90-degree snapped transform that Create would use.

If the contraption contains any Mechanism Frame, a static pitch/roll placement is rejected and Create's `AssemblyException` UI reports that Mechanism Frames must be upright. The contraption remains assembled until its rotation again preserves the containing level's `UP`.

For a one-axis X/Z bearing this means 0 degrees modulo 360 relative to the captured structure. A Y-axis bearing may disassemble at any snapped yaw because Y is the containing level's up axis.

Aeronautics Propeller Bearings and Gyroscopic Propeller Bearings already have a native slowdown controller. When an X/Z-axis propeller carries any Mechanism Frame, Antikythera only changes that controller's symmetry target to `NONE` (360 degrees). Aeronautics therefore performs its normal smooth slowdown but returns to a full-turn phase before it calls its ordinary disassembly path. This rule depends on Frame presence, not on whether the Frame contributes mini wind sails.

## Forced disassembly when the controller is destroyed

Controller destruction cannot depend on the player first restoring a valid pose. `MechanicalBearingBlockEntity.remove()` therefore enters a forced-disassembly scope.

If the final Create transform is already upright, it is unchanged. If a Frame-carrying contraption on an X/Z bearing axis is at a snapped pitch/roll, the complete contraption receives the upright zero-angle transform before Create sends the disassembly packet or places any blocks.

The transform keeps Create's placement offset, so the **whole contraption** snaps as one rigid structure; individual Frames are never rearranged independently. This forced snap applies only to controller-loss teardown. A normal Create disassembly request still fails visibly when the current phase is not statically valid.

Create's `SmartBlockEntity` does not invoke `remove()` for an ordinary chunk unload: it marks the block entity as chunk-unloaded first and skips the removal hook. Therefore unloading the bearing's chunk is not accidentally classified as controller destruction. A real block removal, or the bearing itself being picked up by another contraption, is a forced teardown and uses the snap policy.

## Boundary semantics after placement

Support, redstone and other macro/mini face projections must interpret a static Frame through the same yaw that its visible BlockState and managed SubLevel use.

Create pitch/roll must never leave an invisible face inversion behind. The placement synchronization path canonicalizes both the assembly orientation and its static `AssemblyPose` before normal boundary reconnection.

## Persisted orientation and migration

New `FrameOrientation` NBT stores only horizontal `front`; it no longer writes an `up` field.

The loader still understands the former 24-way `up` + `front` representation and canonicalizes it to an upright yaw. Full quaternions in Create movement/recovery journals remain full `AssemblyPose` data and are not reduced prematurely while the body is moving.

## Occupied and indestructible destination blocks

Antikythera does **not** search for a free NORTH/EAST/SOUTH/WEST alternative and does not add a generic free-space policy for ordinary destination blocks. After the valid placement transform is selected, Create's normal `Contraption.addBlocksToWorld()` replacement semantics remain authoritative.

Create normally destroys replaceable destination blocks (respecting its contraption replacement/drop configuration) and places the carried block. For an indestructible target, or another placement case that Create itself refuses, Create can skip the carried block and optionally drop that carried block according to its own configuration.

A destination that is itself another managed Mechanism Frame needs additional Antikythera bookkeeping, but it is still **replaced at the location Create chose** rather than causing a search for another placement. The unrelated Frame ownership entry is temporarily hidden only while the moving assembly's target journal is validated. Create then performs its ordinary outer-block destruction/replacement. After placement, Antikythera uses the preserved old ownership to evacuate that destroyed Frame's mini region and split its old assembly before assigning the destination Frame to the moving assembly.

## Missing Mechanism Frames during placement

A skipped Mechanism Frame needs extra bookkeeping because Create only knows about the outer carried block; Antikythera also owns the Frame's mini region and assembly graph.

After `addBlocksToWorld()` returns, placement is inspected against the persisted Create relocation journal. Definite lost Frames evacuate their exact mini cells, surviving Frames retain the same transactional assembly bookkeeping, and ambiguous materialization retains recovery journals rather than guessing destructively.

For every non-empty `MechanismAssembly`, `origin` is the logical Frame that defines the assembly's coordinate basis and **must itself be one of `assembly.frames()`**. If topology maintenance removes or transfers the old origin Frame, the surviving component chooses a deterministic surviving Frame as its new origin while preserving payload transactionally.

## Invariants

The integration is required to preserve all of the following:

- Every non-empty `MechanismAssembly` has `origin ∈ frames`; removing the old origin triggers a payload-preserving logical rebase or fail-closed recovery.
- Create rotates and places the **entire contraption**, never just the Frames.
- Connected Frames are captured as one complete `MechanismAssembly` graph.
- Every static Mechanism Frame has local `UP` equal to the containing level's `UP`; for a Sable sublevel this is sublevel `UP`, not root-world `UP`.
- Arbitrary pitch/roll exists only in the moving physical `AssemblyPose`, never as hidden static Frame orientation.
- Horizontal yaw remains supported and preserves physical-Frame to logical-mini-region correspondence.
- Mini blocks are never moved merely to compensate for Create rotation.
- Aeronautics X/Z propellers carrying any Frame use a full-turn slowdown target before ordinary disassembly.
- Static support/redstone face semantics agree with the visible Frame/SubLevel orientation.
- Create remains authoritative for ordinary destination placement, block replacement and outer Frame drops.
- Ambiguous or partially materialised BlockEntities retain recovery journals instead of causing destructive inference.
