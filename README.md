# Antikythera Mechanism

Antikythera Mechanism is an experimental Minecraft 1.21.1 NeoForge mod for building real machinery at half linear scale inside connected **Mechanism Frames**.

Each frame maps to exactly eight logical positions in a real Sable SubLevel:

```text
1 frame = 2 × 2 × 2 mini-block cells
SubLevel scale = 0.5 × 0.5 × 0.5
```

The contents are the original Minecraft or mod blocks, not decorative substitutes or `mini_*` copies. Adjacent Frames form one logical `MechanismAssembly` and one managed mini SubLevel, so supported blocks can tick and connect across Frame boundaries normally. Frame motion and rotation change the assembly/host pose and the logical mapping; the real mini BlockStates are not rotated in place.

## Dependencies

Required:

- Minecraft 1.21.1
- NeoForge 21.1.228
- Sable 2.0.3
- Sable Scale 1.2.0

Optional compatibility currently developed against:

- Create 6.0.10-280
- Simulated / Aeronautics / Create: Offroad 1.3.1

Sable owns the real SubLevels and physics bodies. Sable Scale supplies scale-aware persistence, networking, collision, reach, ray casting, rendering and Flywheel corrections. Antikythera reuses those systems rather than reimplementing them. Create and Simulated integrations are loaded conditionally through the mixin configuration plugin.

The experimental Create: Offroad Wheel Mount continuous-force workaround is **not embedded in Antikythera anymore**. Its native loader, Wheel Mount interception and patched Sable Rapier binaries are maintained by the standalone [`RCFE-WF`](https://github.com/wachipayox/RCFE-WF) mod so there is a single owner of that physics patch.

## Current architecture

The current integrated implementation includes:

- connected `MechanismFrame` blocks with eight mini cells per Frame;
- persistent `MechanismAssembly` identity, graph-authoritative merge/split and `FrameMask` enforcement;
- transfer of BlockStates, BlockEntities/NBT and scheduled block/fluid ticks during topology changes;
- transactional Frame evacuation and movement journals with validation before destructive mutation;
- semantic assembly orientation and reversible physical↔logical offset mapping without rotating mini BlockStates;
- assemblies hosted directly in the macro world or inside foreign Sable SubLevels / physical bodies;
- host-aware transforms between mini-local, assembly-logical, child SubLevel, world and host-local coordinates;
- virtual macro↔mini boundary projection for interaction/redstone while preserving normal mini→mini Level behavior;
- oriented redstone projection after yaw rotation;
- fluid policy covering buckets, waterlogging, dispensers and fluid/block equivalence in Antikythera half-scale worlds;
- hosted mini mass/inertia composition and host force projection for supported physics interactions;
- server/client freeze watchdogs and lifecycle cleanup for stale temporary state.

### Create compatibility

Create support is optional and currently covers whole-assembly contraption capture, temporary snapshots while moving, docking/disassembly, client pose synchronization and recovery state. Multi-Frame and irregular assemblies can rotate through supported **upright/yaw** orientations while keeping the mini content unrotated. Unsupported non-upright pitch/roll configurations fail closed rather than corrupting the logical mapping.

Create kinetic blocks inside static Frames use Create's native kinetic network rules. Mini kinetic graphs are rebuilt transactionally after Frame topology transfers, and ordinary diagonal small/large cog transmission can cross between separate static root-world Frames through the visible half-block lattice. There is intentionally **no macro↔micro kinetic bridge block registered at present**; that interface is reserved for a future redesign from first principles.

### Simulated compatibility

A Simulated Physics Assembler can be used as a real mini block inside a valid Frame child. Assembly delegates to Simulated/Sable's native block-assembly path, then marks the resulting body as a **detached Antikythera mini physics SubLevel**:

- it remains an Antikythera half-scale world;
- its scale is fixed at `0.5`;
- it has independent physics;
- it is intentionally no longer owned by a `MechanismAssembly` or Frame;
- the handoff is one-way: detached bodies do not try to recreate or rediscover a Frame during disassembly.

Mixed-scale glue/merge paths are guarded before mutation when Antikythera half-scale bodies are involved.

## Experimental limitations

This project is still experimental. In particular:

- GameTests cover many server/runtime paths, but they do not replace manual client, rendering or multiplayer validation;
- Create support deliberately rejects orientations that cannot be represented by the current upright orthogonal Frame model;
- third-party blocks are only safe when their behavior is compatible with Sable/Sable Scale and the miniaturization policy;
- falling blocks and other complex cases remain conservative/denied unless explicitly supported;
- physics integration is still an active area, especially interactions that originate on a mini block but must act on a foreign host body.

See [`docs/testing.md`](docs/testing.md) for the current automated validation profiles, known failing regressions and manual acceptance guidance. Files under `docs/research-*.md` are retained as historical research notes; when they disagree with the current code or `docs/testing.md`, the integrated code is authoritative.

## Miniaturization policy

The default datapack whitelist is intentionally conservative. Resolution order is:

```text
hard safety deny
→ config deny
→ config allow (USER_ALLOWED)
→ Java API deny/allow
→ datapack deny/allow
→ denied
```

The common config accepts block IDs or block tags in:

```toml
[miniaturization]
deny = ["othermod:unsafe_block", "#othermod:unsafe_tag"]
allow = ["othermod:tested_block", "#othermod:tested_tag"]
```

User-forced blocks are reported as `USER_ALLOWED`; compatibility is not guaranteed. A Mechanism Frame, the internal anchor and other hard safety blocks can never be enabled through config, tags or API.

Other mods can register tested blocks during construction:

```java
AntikytheraMechanismApi.allow(myBlock);
AntikytheraMechanismApi.allow(myBlockTag);
AntikytheraMechanismApi.deny(unsafeBlock);
```

## Development and validation profiles

Java 21 is required.

Unit tests and build:

```powershell
.\gradlew.bat --no-daemon test --console=plain
.\gradlew.bat --no-daemon build --console=plain
```

The runtime dependencies are controlled by Gradle properties. Use explicit profiles when validating compatibility:

```powershell
# Core: no Create, no Simulated
.\gradlew.bat --no-daemon runGameTestServer -Pinclude_create=false -Pinclude_simulated=false --console=plain

# Create only
.\gradlew.bat --no-daemon runGameTestServer -Pinclude_create=true -Pinclude_simulated=false --console=plain

# Create + Simulated
.\gradlew.bat --no-daemon runGameTestServer -Pinclude_create=true -Pinclude_simulated=true --console=plain
```

The same properties can be used with `runClient` or `runServer`. The default Gradle development configuration currently includes the Simulated runtime, which also pulls Create into the runtime; use the explicit core profile when checking accidental optional-class loading.

Artifacts are written to `build/libs` as `antikythera-mechanism-<version>.jar`.
