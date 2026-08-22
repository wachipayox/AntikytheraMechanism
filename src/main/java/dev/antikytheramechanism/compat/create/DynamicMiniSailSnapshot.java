package dev.antikytheramechanism.compat.create;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllTags.AllBlockTags;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable derived view of WINDMILL_SAILS exposed by HIDDEN Mechanism Frames captured in one bearing.
 * Native contraption blocks remain untouched; only the dynamic mini contribution is represented here.
 */
public final class DynamicMiniSailSnapshot {
    public static final double MINI_SAIL_POWER = 0.25;
    public static final double MINI_HALF_EXTENT = 0.25;
    public static final DynamicMiniSailSnapshot EMPTY =
            new DynamicMiniSailSnapshot(List.of(), Set.of());

    private final List<MiniSail> sails;
    private final Set<UUID> assemblyIds;
    private final double miniSailPower;

    private DynamicMiniSailSnapshot(List<MiniSail> sails, Set<UUID> assemblyIds) {
        this.sails = List.copyOf(sails);
        this.assemblyIds = Set.copyOf(assemblyIds);
        this.miniSailPower = sails.size() * MINI_SAIL_POWER;
    }

    /** Client packet reconstruction: geometry/power are authoritative; assembly indexing is server-only. */
    public static DynamicMiniSailSnapshot fromClientCenters(List<MiniSail> sails) {
        return sails == null || sails.isEmpty()
                ? EMPTY
                : new DynamicMiniSailSnapshot(sails, Set.of());
    }

    /**
     * Compatibility wrapper for callers that need a one-shot view. Runtime cache replacement should
     * prefer {@link #captureResult(ServerLevel, BearingContraption)} so a transiently incomplete read
     * cannot be mistaken for an authoritative reduction in sail area.
     */
    public static DynamicMiniSailSnapshot capture(ServerLevel level, BearingContraption contraption) {
        return captureResult(level, contraption).snapshot();
    }

    /**
     * Captures the current mini-sail view and reports whether every captured HIDDEN managed Frame could
     * be resolved all the way to its authoritative mini world. NORMAL and GLASS assemblies remain in
     * {@link #assemblyIds()} for cheap shell-mode change detection but intentionally contribute zero.
     */
    public static CaptureResult captureResult(ServerLevel level, BearingContraption contraption) {
        if (level == null || contraption == null) {
            return new CaptureResult(EMPTY, false);
        }

        CreateFrameCapture.Captures captures =
                CreateFrameCapture.inspectAll(contraption, ModRegistries.MECHANISM_FRAME.get());
        if (captures.localFramesByAssembly().isEmpty()) {
            return new CaptureResult(EMPTY, true);
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        List<MiniSail> sails = new ArrayList<>();
        Set<UUID> assemblyIds = new LinkedHashSet<>();
        boolean complete = true;

        for (Map.Entry<UUID, Set<BlockPos>> captured : captures.localFramesByAssembly().entrySet()) {
            UUID assemblyId = captured.getKey();
            assemblyIds.add(assemblyId);

            MechanismAssembly assembly = manager.getAssembly(assemblyId).orElse(null);
            if (assembly == null) {
                complete = false;
                continue;
            }
            if (assembly.shellMode() != FrameShellMode.HIDDEN) {
                continue;
            }
            BlockPos translation = ContraptionPoseBinding.findTranslation(captured.getValue(), assembly.frames())
                    .orElse(null);
            if (translation == null) {
                complete = false;
                continue;
            }
            ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
            if (child == null || child.isRemoved()) {
                // An assembly that has never materialized mini content genuinely contributes zero.
                // A persisted sublevel id without a resolvable child is instead a transient/incomplete read.
                if (assembly.subLevelId() != null) {
                    complete = false;
                }
                continue;
            }

            for (BlockPos localFrame : captured.getValue()) {
                BlockPos physicalFrame = localFrame.offset(translation);
                for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
                    for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                        for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                            BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, physicalFrame, x, y, z);
                            BlockPos plotPos = MechanismSubLevelService.toPlotPosition(child, mini);
                            BlockState state = resolveSailState(level, plotPos, level.getBlockState(plotPos));
                            if (!AllBlockTags.WINDMILL_SAILS.matches(state)) {
                                continue;
                            }

                            BlockPos physicalCell = assembly.orientation().logicalCellToPhysical(x, y, z);
                            sails.add(new MiniSail(
                                    localFrame.getX() + cellOffset(physicalCell.getX()),
                                    localFrame.getY() + cellOffset(physicalCell.getY()),
                                    localFrame.getZ() + cellOffset(physicalCell.getZ())));
                        }
                    }
                }
            }
        }

        return new CaptureResult(new DynamicMiniSailSnapshot(sails, assemblyIds), complete);
    }

    private static BlockState resolveSailState(ServerLevel level, BlockPos position, BlockState state) {
        if (!AllBlocks.COPYCAT_PANEL.has(state)) {
            return state;
        }
        BlockEntity blockEntity = level.getBlockEntity(position);
        if (blockEntity instanceof CopycatBlockEntity copycat) {
            return copycat.getMaterial();
        }
        return state;
    }

    private static double cellOffset(int physicalCellCoordinate) {
        return physicalCellCoordinate == 0 ? -0.25 : 0.25;
    }

    public List<MiniSail> sails() {
        return sails;
    }

    public Set<UUID> assemblyIds() {
        return assemblyIds;
    }

    public double miniSailPower() {
        return miniSailPower;
    }

    public double effectiveSailPower(int nativeSails) {
        return nativeSails + miniSailPower;
    }

    /** Builds fractional Aero-style layers without quantising axial position or radius. */
    public List<MiniLayer> layers(Direction bearingDirection) {
        if (sails.isEmpty()) {
            return List.of();
        }
        double ax = bearingDirection.getStepX();
        double ay = bearingDirection.getStepY();
        double az = bearingDirection.getStepZ();
        Map<Double, LayerAccumulator> layers = new LinkedHashMap<>();
        for (MiniSail sail : sails) {
            double axial = sail.x * ax + sail.y * ay + sail.z * az;
            double rx = sail.x - ax * axial;
            double ry = sail.y - ay * axial;
            double rz = sail.z - az * axial;
            double radius = Math.sqrt(rx * rx + ry * ry + rz * rz);
            LayerAccumulator layer = layers.computeIfAbsent(axial, ignored -> new LayerAccumulator());
            layer.inner = Math.min(layer.inner, Math.max(radius - MINI_HALF_EXTENT, 0.0));
            layer.outer = Math.max(layer.outer, radius + MINI_HALF_EXTENT);
        }
        List<MiniLayer> result = new ArrayList<>(layers.size());
        layers.forEach((axial, layer) -> result.add(new MiniLayer(axial + 1.0, layer.inner, layer.outer)));
        result.sort(java.util.Comparator.comparingDouble(MiniLayer::offset));
        return Collections.unmodifiableList(result);
    }

    public record CaptureResult(DynamicMiniSailSnapshot snapshot, boolean complete) {
    }

    public record MiniSail(double x, double y, double z) {
        public Vector3d center(Vector3d destination) {
            return destination.set(x, y, z);
        }
    }

    public record MiniLayer(double offset, double innerRadius, double outerRadius) {
    }

    private static final class LayerAccumulator {
        private double inner = Double.POSITIVE_INFINITY;
        private double outer = 0.0;
    }
}
