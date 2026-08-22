package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.contraptions.Contraption;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only connectivity guard for foreign-hosted HIDDEN Frame payloads while Create owns the
 * physical Frames.
 *
 * <p>The normal {@code HiddenFrameGeometryPolicy} deliberately refuses presentation mutations while a
 * pending contraption journal is alive, and its world-space validation cannot inspect extracted Frame
 * blocks. This guard therefore answers only the part that remains authoritative during flight: whether
 * each captured, foreign-hosted HIDDEN assembly's occupied minis still belong to one 26-neighbour
 * touching component. Occupied minis from every captured assembly participate in the shared component,
 * preserving the same cross-assembly face/edge/corner continuity semantics as the hosted policy.</p>
 *
 * <p>It never rewrites Contraption.blocks or assembly presentation. An invalid result is consumed by
 * the bearing overlay manager to request Create's ordinary disassembly path; once the Frames are placed
 * again, the normal hidden-geometry policy can persist HIDDEN -> NORMAL safely. Root-world HIDDEN Frames
 * are intentionally ignored because the hosted geometry policy does not constrain them either.</p>
 */
final class CreateHiddenFrameConnectivityGuard {
    private CreateHiddenFrameConnectivityGuard() {
    }

    static Result evaluate(ServerLevel level, Contraption contraption) {
        if (level == null || contraption == null) {
            return Result.incomplete();
        }

        CreateFrameCapture.Captures captures =
                CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        if (captures.missingAssemblyId()) {
            return Result.incomplete();
        }
        if (captures.localFramesByAssembly().isEmpty()) {
            return Result.valid();
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        Map<UUID, Set<BlockPos>> occupiedByAssembly = new HashMap<>();
        Set<BlockPos> allOccupied = new HashSet<>();
        Set<UUID> guardedHiddenAssemblies = new LinkedHashSet<>();

        for (Map.Entry<UUID, Set<BlockPos>> captured : captures.localFramesByAssembly().entrySet()) {
            UUID assemblyId = captured.getKey();
            MechanismAssembly assembly = manager.getAssembly(assemblyId).orElse(null);
            if (assembly == null) {
                return Result.incomplete();
            }
            MechanismAssemblyHost.Resolution host = MechanismAssemblyHost.resolve(level, assembly.origin());
            if (assembly.shellMode() == FrameShellMode.HIDDEN
                    && host.kind() == MechanismAssemblyHost.Kind.FOREIGN
                    && host.subLevel() != null) {
                guardedHiddenAssemblies.add(assemblyId);
            }

            BlockPos translation = ContraptionPoseBinding.findTranslation(captured.getValue(), assembly.frames())
                    .orElse(null);
            if (translation == null) {
                return Result.incomplete();
            }

            ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
            if (child == null || child.isRemoved()) {
                if (assembly.subLevelId() != null) {
                    return Result.incomplete();
                }
                occupiedByAssembly.put(assemblyId, Set.of());
                continue;
            }

            Set<BlockPos> ownOccupied = new HashSet<>();
            for (BlockPos localFrame : captured.getValue()) {
                BlockPos physicalFrame = localFrame.offset(translation);
                for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
                    for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                        for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                            BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, physicalFrame, x, y, z);
                            BlockPos plotPos = MechanismSubLevelService.toPlotPosition(child, mini);
                            if (level.getBlockState(plotPos).isAir()) {
                                continue;
                            }

                            BlockPos physicalCell = assembly.orientation().logicalCellToPhysical(x, y, z);
                            BlockPos shared = sharedMiniPosition(localFrame, physicalCell);
                            ownOccupied.add(shared);
                            allOccupied.add(shared);
                        }
                    }
                }
            }
            occupiedByAssembly.put(assemblyId, Set.copyOf(ownOccupied));
        }

        if (guardedHiddenAssemblies.isEmpty()) {
            return Result.valid();
        }

        Set<UUID> invalid = new LinkedHashSet<>();
        for (UUID assemblyId : guardedHiddenAssemblies) {
            Set<BlockPos> ownOccupied = occupiedByAssembly.getOrDefault(assemblyId, Set.of());
            if (ownOccupied.isEmpty()) {
                invalid.add(assemblyId);
                continue;
            }
            Set<BlockPos> component = touchComponent(ownOccupied.iterator().next(), allOccupied);
            if (!component.containsAll(ownOccupied)) {
                invalid.add(assemblyId);
            }
        }

        return invalid.isEmpty() ? Result.valid() : Result.invalid(invalid);
    }

    private static BlockPos sharedMiniPosition(BlockPos localFrame, BlockPos physicalCell) {
        return new BlockPos(
                localFrame.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getX(),
                localFrame.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getY(),
                localFrame.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + physicalCell.getZ());
    }

    private static Set<BlockPos> touchComponent(BlockPos first, Set<BlockPos> occupied) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        visited.add(first);
        frontier.add(first);

        while (!frontier.isEmpty()) {
            BlockPos current = frontier.removeFirst();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbour = current.offset(dx, dy, dz);
                        if (occupied.contains(neighbour) && visited.add(neighbour)) {
                            frontier.addLast(neighbour);
                        }
                    }
                }
            }
        }
        return visited;
    }

    enum Verdict {
        VALID,
        INVALID,
        INCOMPLETE
    }

    record Result(Verdict verdict, Set<UUID> invalidHiddenAssemblies) {
        Result {
            invalidHiddenAssemblies = Set.copyOf(invalidHiddenAssemblies);
        }

        static Result valid() {
            return new Result(Verdict.VALID, Set.of());
        }

        static Result invalid(Set<UUID> assemblyIds) {
            return new Result(Verdict.INVALID, assemblyIds);
        }

        static Result incomplete() {
            return new Result(Verdict.INCOMPLETE, Set.of());
        }
    }
}
