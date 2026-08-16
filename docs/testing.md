# Antikythera Mechanism testing and validation

Current documentation baseline: **16 August 2026**. The code baseline used for the status below is `af2b8332d31f6bcaf1a40dfa4bb93a80155742b0` (`Use vanilla block placement in rotated lamp fixture`). Documentation-only commits after that baseline do not change the runtime conclusions below.

This document is a testing guide, not a release certificate. A successful compile/build, JUnit pass, GameTest pass and manual/visual validation are different levels of evidence and must not be reported as equivalent.

## What the current implementation covers

The integrated branch currently contains runtime behavior and regressions for substantially more than the original Frame prototype:

- connected Mechanism Frames, one logical `MechanismAssembly` per connected graph and a strict `FrameMask`;
- real `2 × 2 × 2` mini content per Frame in Sable SubLevels at scale `0.5`;
- merge/split with BlockState, BlockEntity/NBT and scheduled block/fluid tick preservation;
- transactional Frame evacuation and movement/recovery journals;
- Sable relocation of Frames and assemblies, including Frames hosted inside foreign SubLevels;
- orthogonal Frame orientation and logical↔physical mapping without rotating the actual mini BlockStates;
- macro↔mini virtual boundary behavior, oriented redstone and preservation of normal mini→mini Level behavior;
- Create whole-assembly contraption capture, in-flight snapshots, docking, upright/yaw rotation, client pose synchronization and recovery;
- Simulated mini Physics Assembler handoff to detached/free Antikythera half-scale physics bodies;
- mixed-scale glue/assembly guards;
- hosted mini mass/inertia and selected physical force/impulse bridges;
- half-scale placement/collision fixes;
- fluid policy for buckets, waterlogging, dispensers and flowing-fluid writes;
- lifecycle cleanup plus server/client freeze watchdogs.

The `docs/research-*.md` files are historical research notes. They are useful for understanding why integrations were designed in a certain way, but they are not the source of truth for current support. When a research note conflicts with the integrated code or this document, use the integrated code.

## Validation layers

### 1. Compile and build

Java 21 is required.

```powershell
.\gradlew.bat --no-daemon compileJava --console=plain
.\gradlew.bat --no-daemon build --console=plain
```

A successful build proves compilation, resource processing and the configured JVM test phase. It does **not** prove that Minecraft runtime mixins, Sable movement, Create contraptions, Simulated physics or client rendering behave correctly.

### 2. JUnit

```powershell
.\gradlew.bat --no-daemon test --console=plain
```

`src/test/java` currently covers pure or mostly isolated logic such as:

- `FrameGraph`, `FrameMask` and `MiniCoordinateMapper`;
- `AssemblyPose`, `FrameOrientation` and assembly orientation/translation integration;
- piston/contraption/evacuation journals;
- Create contraption pose/rotation math and transmission geometry;
- mini placement/raycast helpers;
- managed SubLevel bounds/service helpers.

JUnit does not start a real NeoForge server and must not substitute for GameTests when the bug depends on Minecraft ticks, mixin injection, Sable/Simulated internals or Create runtime behavior.

### 3. NeoForge GameTests

The repository **does contain an active GameTest suite** under `src/main/java`, plus the structure fixture `src/main/resources/data/antikytheramechanism/structure/frame_rotation_empty.nbt`.

Run GameTests with explicit dependency profiles:

```powershell
# Core only: no Create, no Simulated
.\gradlew.bat --no-daemon runGameTestServer -Pinclude_create=false -Pinclude_simulated=false --console=plain

# Create only
.\gradlew.bat --no-daemon runGameTestServer -Pinclude_create=true -Pinclude_simulated=false --console=plain

# Create + Simulated
.\gradlew.bat --no-daemon runGameTestServer -Pinclude_create=true -Pinclude_simulated=true --console=plain
```

The GitHub `Runtime Validation` workflow runs exactly those three profiles. `fail-fast` is disabled, and the workflow additionally scans the log for mixin/classloading/runtime-start failures.

The GameTest classes currently exercise, among other paths:

- Frame boundary and removal regressions;
- Create boundary snapshots and Frame orientation;
- whole-assembly rotation/docking behavior;
- Create contraption structural and kinetic behavior;
- Sable relocation and transient support cases;
- Frame evacuation drops and fluids;
- placement feedback and detached-body collision;
- detached mini physics and mixed-scale assembly guards;
- hosted mini physics, hosted spring projection and client support sync;
- mini physics assembly boundary behavior;
- fluid policy.

A GameTest that manually calls a helper is evidence for that helper, not automatically for the actual third-party runtime call path. Regressions involving Create or Simulated should prefer the real block/entity/tick route whenever practical.

## Current automated status

### Build

At baseline `af2b8332d31f6bcaf1a40dfa4bb93a80155742b0`, the GitHub **Build** workflow completed successfully.

### Runtime Validation

At the same baseline, the runtime matrix was **not green**. The Create and Simulated profiles each registered **49 tests** and each ended with three required failures. The core job did not provide a completed green result in that run, so the baseline must not be described as a full runtime pass.

Create-only failures:

- `minibackedmacroattachmentsurvivessableassemblyanddisassembly` — macro attachment broke during Sable disassembly while Frame support was transient;
- `westfacingframekeepsallfrontminilampslitinflight` — fixture could not place a mini lamp because the intended target was reported non-replaceable;
- `solepopulatedframesurvivesminibreakaftersableassembly` — fixture could not place the mini mass payload.

Create + Simulated failures:

- `westfacingframekeepsallfrontminilampslitinflight` — same non-replaceable mini lamp fixture failure;
- `northframestoppedfacingeastkeepsfilledlampcubepowered` — fixture could not place a mini lamp because the target was reported non-replaceable;
- `sableassemblykeepscarriedmacrosupport` — supported mini redstone dust broke while Sable copied its macro neighbour.

These failures must be investigated individually. A failure during test setup can indicate a real placement regression, an invalid fixture assumption, or both. Do not simply weaken the assertion or replace the real path with a helper call to make the suite green.

## Important known coverage gap: hosted Simulated spring forces

The current physics architecture already contains a general hosted-child projection service (`HostedMiniForceProjection`) and a Simulated-specific spring mixin (`SpringBlockEntityHostedMiniMixin`). The existing `HostedMiniSpringPhysicsGameTests` validate important coordinate/force projection behavior, but the key projection test applies force through the physics controller after resolving the projection rather than proving the complete real `SpringBlockEntity` tick/injection path.

Therefore a passing projection helper test is **not sufficient evidence** that a spring attached to a real mini block in a hosted Frame transfers force to the foreign host body in gameplay. Any future fix in this area should add a regression that constructs or exercises the actual Simulated spring path, waits the required ticks and observes the host body's resulting velocity/pose or other authoritative physics state.

## Manual/client validation

GameTests are server-side automation. The following still require targeted client/manual validation when a change touches them:

- raycast selection and reach on scaled mini blocks;
- collision feel and placement against moving/scaled bodies;
- Create in-flight rendering and snap on docking/disassembly;
- stale client SubLevel poses after Create movement;
- particles and block-break effects in scaled worlds;
- multiplayer synchronization and late tracking;
- visual Frame orientation and virtual boundary behavior.

Do not label these as visually validated unless a real client session was performed.

## Regression expectations by subsystem

When changing assembly topology or movement, verify UUID preservation, Frame ownership, exact mini content, BlockEntities/inventories, scheduled ticks, orientation, host relationship and cleanup after at least one later tick. For merges/splits and disassembly, also check that retired SubLevels do not remain as stale owners.

When changing Create integration, test both translation and supported yaw rotation with more than one Frame, including an irregular shape. While captured/in-flight, macro↔mini bridges must not behave as if the assembly were still docked. On disassembly, every physical Frame must resolve to the same logical mini region it owned before rotation.

When changing Sable/Simulated movement, validate before mutation. Do not catch an exception after Sable has already moved blocks and call that safe failure. Mixed scales (`0.5` Antikythera versus `1.0` ordinary bodies/macro grids) must be rejected before glue/assembly/disassembly mutates either participant.

When changing detached mini physics, preserve the one-way lifecycle: a detached Antikythera body remains scale `0.5` and under Antikythera policy, but it is not a Frame child and must not attempt to find/recreate a Frame on disassembly.

When changing fluids, test source blocks and flowing states separately. The policy treats a fluid and its representative liquid block consistently, and write guards must prevent flowing-fluid mechanics from becoming a duplication path.

When changing host physics, distinguish coordinate projection, mass/inertia composition and actual third-party force application. A test of only one layer does not certify the others.

## Suggested acceptance sequence for a focused fix

1. Run the most focused JUnit or GameTest reproducing the bug.
2. Run `test`.
3. Run the relevant GameTest dependency profile.
4. Run all three GameTest profiles if the change touches shared core/mixins/classloading.
5. Run `build`.
6. If the change affects rendering, input, collision feel or client synchronization, perform the corresponding manual client validation and record that separately.

For timing-sensitive movement tests, avoid asserting immediately after assembly/disassembly. Wait for the real lifecycle/tick boundary needed by Sable/Create/Simulated, then assert the later authoritative state.

## CI files

- `.github/workflows/build.yml` validates the normal Gradle build.
- `.github/workflows/runtime-validation.yml` validates `core`, `create` and `simulated` GameTest profiles on pushes to `agent/integration-current` and uploads failing GameTest logs.

When fixing a failing test on a temporary integration branch, run the same three commands locally or in that branch before merging back into `agent/integration-current`; the target branch workflow should then confirm the merged result.
