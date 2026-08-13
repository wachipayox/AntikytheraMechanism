package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Makes one physical Mechanism Frame represent both its lightweight shell and the naturally
 * configured Sable masses of its eight mini cells when the Frame belongs to a foreign SubLevel.
 *
 * <p>The intentionally simple rule is:</p>
 * <pre>
 * frame mass = 0.1 + (sum of the eight mini block Sable masses) / 8
 * </pre>
 *
 * <p>Natural mini mass always comes from {@link PhysicsBlockPropertyHelper#getMass}, so modded
 * blocks and Sable physics-property datapacks keep their normal mass semantics. No duplicate mass
 * table is maintained by Antikythera.</p>
 */
public final class ManagedFrameMassPolicy {
    public static final double FRAME_SHELL_MASS = 0.1;
    public static final double MINI_MASS_DIVISOR = 8.0;
    private static final double MASS_EPSILON = 1.0E-12;

    private ManagedFrameMassPolicy() {
    }

    /** Called by the Sable mass-property hook whenever Sable weighs a Mechanism Frame. */
    public static double effectiveFrameMass(BlockGetter blockGetter, BlockPos framePosition) {
        ServerLevel level = owningServerLevel(blockGetter, framePosition);
        if (level == null) {
            return FRAME_SHELL_MASS;
        }

        MechanismAssembly assembly = findAssembly(level, blockGetter, framePosition);
        if (assembly == null) {
            return FRAME_SHELL_MASS;
        }

        ServerSubLevel child = MechanismSubLevelService.get(level, assembly);
        if (child == null || child.isRemoved()) {
            return FRAME_SHELL_MASS;
        }

        return FRAME_SHELL_MASS + payloadMass(level, child, assembly, framePosition);
    }

    /**
     * Adds only the incremental mini payload change to the foreign host's existing Frame mass.
     * Sable's normal per-substep MergedMassTracker update then uploads the new mass/COM/inertia.
     */
    public static void onManagedMiniWrite(
            ServerLevel level,
            BlockPos childGlobalPosition,
            BlockState oldState,
            BlockState newState) {
        dev.ryanhcode.sable.sublevel.SubLevel containing =
                dev.ryanhcode.sable.Sable.HELPER.getContaining(level, childGlobalPosition);
        if (!(containing instanceof ServerSubLevel child)) {
            return;
        }

        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(child);
        if (ownerId == null) {
            return;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(ownerId).orElse(null);
        if (assembly == null) {
            return;
        }

        BlockPos miniPosition = childGlobalPosition.subtract(child.getPlot().getCenterBlock());
        if (!MiniCoordinateMapper.isOwnedMiniPosition(assembly, miniPosition)) {
            return;
        }

        double oldMass = naturalMiniMass(level, childGlobalPosition, oldState);
        double newMass = naturalMiniMass(level, childGlobalPosition, newState);
        double delta = (newMass - oldMass) / MINI_MASS_DIVISOR;
        if (Math.abs(delta) <= MASS_EPSILON) {
            return;
        }

        BlockPos framePosition = MiniCoordinateMapper.miniToFrame(assembly, miniPosition);
        applyPayloadDeltaToForeignHost(level, assembly, framePosition, delta);
    }

    /**
     * Handles persisted children loaded after their foreign host. The host was necessarily weighed
     * before this child was available, so its Frames currently contain only the 0.1 shell mass; add
     * the already-saved payload once. Newly allocated children do not have ownership metadata yet
     * when Sable emits onSubLevelAdded and therefore do not enter this path; their later block writes
     * use the incremental path above.
     */
    public static void onManagedChildLoaded(ServerLevel level, ServerSubLevel child) {
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(child);
        if (ownerId == null) {
            return;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(ownerId).orElse(null);
        if (assembly == null) {
            return;
        }

        MechanismAssemblyHost.Resolution host = MechanismAssemblyHost.resolve(level, assembly.origin());
        if (host.kind() != MechanismAssemblyHost.Kind.FOREIGN
                || host.subLevel() == null
                || host.subLevel().isRemoved()
                || host.subLevel().getSelfMassTracker() == null) {
            return;
        }

        for (BlockPos framePosition : assembly.frames()) {
            double payload = payloadMass(level, child, assembly, framePosition);
            if (payload > MASS_EPSILON) {
                addMassAtFrame(level, host.subLevel(), framePosition, payload);
            }
        }
    }

    private static void applyPayloadDeltaToForeignHost(
            ServerLevel level,
            MechanismAssembly assembly,
            BlockPos framePosition,
            double delta) {
        MechanismAssemblyHost.Resolution host = MechanismAssemblyHost.resolve(level, framePosition);
        if (host.kind() != MechanismAssemblyHost.Kind.FOREIGN
                || host.subLevel() == null
                || host.subLevel().isRemoved()
                || !MechanismAssemblyHost.samePhysicalHost(level, assembly, framePosition)
                || host.subLevel().getSelfMassTracker() == null) {
            return;
        }
        addMassAtFrame(level, host.subLevel(), framePosition, delta);
    }

    private static void addMassAtFrame(
            ServerLevel level,
            ServerSubLevel host,
            BlockPos framePosition,
            double delta) {
        BlockState frameState = level.getBlockState(framePosition);
        if (!frameState.is(ModRegistries.MECHANISM_FRAME.get())) {
            return;
        }

        Vec3 inertia = PhysicsBlockPropertyHelper.getInertia(level, framePosition, frameState);
        host.getSelfMassTracker().addBlockMass(level, frameState, framePosition, delta, inertia);
    }

    private static double payloadMass(
            ServerLevel level,
            ServerSubLevel child,
            MechanismAssembly assembly,
            BlockPos framePosition) {
        double total = 0.0;
        for (int x = 0; x < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; x++) {
            for (int y = 0; y < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; y++) {
                for (int z = 0; z < MiniCoordinateMapper.CELLS_PER_FRAME_AXIS; z++) {
                    BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, framePosition, x, y, z);
                    BlockPos global = MechanismSubLevelService.toPlotPosition(child, mini);
                    BlockState state = level.getBlockState(global);
                    total += naturalMiniMass(level, global, state);
                }
            }
        }
        return total / MINI_MASS_DIVISOR;
    }

    private static double naturalMiniMass(ServerLevel level, BlockPos position, BlockState state) {
        return state.isAir() ? 0.0 : PhysicsBlockPropertyHelper.getMass(level, position, state);
    }

    private static MechanismAssembly findAssembly(
            ServerLevel level,
            BlockGetter blockGetter,
            BlockPos framePosition) {
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly indexed = manager.getAssemblyAt(framePosition).orElse(null);
        if (indexed != null) {
            return indexed;
        }

        // During Sable block relocation the copied destination Frame can exist a few calls before the
        // atomic frame index commit. Its copied BlockEntity still carries the authoritative UUID.
        BlockEntity blockEntity = blockGetter.getBlockEntity(framePosition);
        if (blockEntity instanceof MechanismFrameBlockEntity frame && frame.getAssemblyId() != null) {
            return manager.getAssembly(frame.getAssemblyId()).orElse(null);
        }
        return null;
    }

    private static ServerLevel owningServerLevel(BlockGetter blockGetter, BlockPos framePosition) {
        if (blockGetter instanceof ServerLevel serverLevel) {
            return serverLevel;
        }
        BlockEntity blockEntity = blockGetter.getBlockEntity(framePosition);
        return blockEntity != null && blockEntity.getLevel() instanceof ServerLevel serverLevel
                ? serverLevel
                : null;
    }
}
