# Antikythera Mechanism

Antikythera Mechanism is an experimental Minecraft 1.21.1 NeoForge mod for building real machinery at half linear scale inside connected **Mechanism Frames**.

Each frame maps to exactly eight logical positions in a real Sable SubLevel:

```text
1 frame = 2 × 2 × 2 mini-block cells
SubLevel scale = 0.5 × 0.5 × 0.5
```

The contents are the original Minecraft or mod blocks, not decorative substitutes or `mini_*` copies. Adjacent frames form one continuous assembly and one SubLevel, so supported blocks can tick and connect across frame boundaries normally.

## Dependencies

- Minecraft 1.21.1
- NeoForge 21.1.228
- Sable 2.0.3 (required on client and server)
- Sable Scale 1.2.0 (required on client and server)
- Create 6.0.10-280 (optional; compatibility is enabled only when Create is installed)

Sable owns the real SubLevels. Sable Scale supplies the scale-aware persistence, networking, collision, reach, ray casting, rendering and Flywheel corrections. This project uses those dependencies directly instead of copying their implementations.

## Current state

Implemented in the experimental core:

- thin connected frame models and collision with hidden internal separators;
- eight selectable placement cells using normal `BlockItem` stacks;
- persistent `MechanismAssembly` and one force-loaded Sable SubLevel per connected component;
- a strict `FrameMask` and low-level write guard scoped only to managed SubLevels;
- deterministic multi-neighbour merge and graph-authoritative split;
- BlockState, BlockEntity/NBT and scheduled block/fluid tick transfer;
- transactional per-frame evacuation with tool loot, inventories, explosion recovery and rollback;
- persistent semantic assembly poses driven through Sable physics without rotating internal BlockStates;
- configurable/datapack/Java-API miniaturization policy with an immutable recursion deny;
- persistent movement journals for vanilla pistons, including sticky retraction, without copying mini contents;
- optional Create contraption following with persisted placement/recovery state;
- optional Create Transmission Boxes for shafts and small/large cogs, per-port covers and native Create RPM, ratio, stress and conflict propagation.

Experimental limitations:

- final client-side/manual validation of every placement, raycast, collision and multiplayer path;
- rotating Create contraptions are intentionally restricted to one-frame assemblies; complete multi-frame assemblies support translation and unsafe partial captures fail closed;
- the current Transmission Box assets are functional placeholder models rather than final art;
- certification of falling blocks and other complex blocks currently denied by default.

See [`docs/testing.md`](docs/testing.md) for the reproducible matrix, completed server checks and explicit expected failures. API and dependency research is retained in `docs/research-*.md`.

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

## Development

Java 21 is required. On Windows:

```powershell
.\gradlew.bat --no-daemon compileJava test
.\gradlew.bat --no-daemon build
.\gradlew.bat --no-daemon runClient
.\gradlew.bat --no-daemon runServer
```

The normal development runtime excludes Create to catch accidental classloading. Add the optional Create runtime with:

```powershell
.\gradlew.bat -Pinclude_create=true --no-daemon runClient
.\gradlew.bat -Pinclude_create=true --no-daemon runServer
```

Artifacts are written to `build/libs` as `antikythera-mechanism-<version>.jar`.
