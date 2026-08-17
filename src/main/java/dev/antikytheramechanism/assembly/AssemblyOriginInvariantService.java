package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.api.assembly.AssemblyLifecycleListener;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.mixin.MechanismAssemblyManagerAccessor;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Restores the invariant that every non-empty {@link MechanismAssembly} is anchored by a Frame it
 * actually owns.
 *
 * <p>A topology change can remove or transfer the old origin Frame before the retained component is
 * known. Merely assigning another origin would reinterpret every mini coordinate in the existing
 * managed child. Repair therefore stages the retained eight-cell regions through the ordinary
 * transactional transfer service, rebases the semantic pose, then transfers the exact payload back
 * into the original assembly UUID under its new logical basis. Block entities and scheduled ticks
 * follow the same path as an ordinary split/merge transfer.</p>
 */
public final class AssemblyOriginInvariantService {
    private static final Comparator<BlockPos> POSITION_ORDER = Comparator
            .comparingInt((BlockPos pos) -> pos.getY())
            .thenComparingInt(pos -> pos.getZ())
            .thenComparingInt(pos -> pos.getX());

    private AssemblyOriginInvariantService() {
    }

    /**
     * Repairs one live assembly after graph maintenance has finished choosing its retained Frames.
     * Returns false only when the operation had to fail closed into recovery state.
     */
    public static boolean repairIfNeeded(ServerLevel level, MechanismAssembly source) {
        if (source == null || source.frames().isEmpty() || source.frames().contains(source.origin())) {
            return true;
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        if (manager.getAssembly(source.id()).orElse(null) != source
                || manager.pendingPistonMove(source.id()).isPresent()
                || manager.pendingContraptionMove(source.id()).isPresent()
                || manager.isContentRecoveryLocked(source.id())) {
            return false;
        }

        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) manager;
        Map<UUID, MechanismAssembly> assemblies = access.antikytheramechanism$getAssemblies();
        Map<BlockPos, UUID> frameIndex = access.antikytheramechanism$getFrameIndex();
        Set<BlockPos> retainedFrames = Set.copyOf(source.frames());

        for (BlockPos frame : retainedFrames) {
            if (!source.id().equals(frameIndex.get(frame))
                    || !level.hasChunkAt(frame)
                    || !level.getBlockState(frame).is(ModRegistries.MECHANISM_FRAME.get())) {
                lockSourceOnly(manager, access, source,
                        "cannot rebase a stale origin because retained Frame ownership is incomplete");
                return false;
            }
        }

        BlockPos oldOrigin = source.origin();
        BlockPos newOrigin = retainedFrames.stream().min(POSITION_ORDER).orElseThrow();
        FrameOrientation orientation = source.orientation();
        AssemblyPose rebasedPose = AssemblyOrientationMath.rebaseLogical(
                source.poseTarget(), source.logicalFrameOffset(newOrigin));

        /*
         * Use a real temporary managed assembly rather than moving cells in-place. An origin shift can
         * make the new mini region overlap the old one (for example frames at +1/+2 rebased to +1),
         * so direct same-plot copying is order-dependent. The staging child gives the existing
         * transfer service disjoint source/target plots and preserves BlockEntity NBT plus scheduled
         * block/fluid ticks exactly.
         */
        MechanismAssembly staging = new MechanismAssembly(
                UUID.randomUUID(), newOrigin, retainedFrames, orientation);
        staging.setPoseTarget(rebasedPose);
        assemblies.put(staging.id(), staging);
        manager.setDirty();

        AssemblyContentTransferService.TransferResult outbound =
                AssemblyContentTransferService.transferFrames(
                        level,
                        source,
                        staging,
                        retainedFrames,
                        AssemblyLifecycleListener.TransferKind.TRANSFER);
        if (outbound == AssemblyContentTransferService.TransferResult.ROLLED_BACK) {
            MechanismSubLevelService.remove(level, staging);
            assemblies.remove(staging.id());
            lockSourceOnly(manager, access, source,
                    "staging the retained payload for origin rebase rolled back");
            return false;
        }
        if (outbound == AssemblyContentTransferService.TransferResult.RECOVERY_REQUIRED) {
            lockBoth(manager, access, source, staging,
                    "staging the retained payload for origin rebase requires recovery");
            return false;
        }

        // The payload is now safe in staging, so changing the original assembly's logical basis can
        // no longer reinterpret live blocks in its own child.
        source.relocate(newOrigin, retainedFrames, orientation);
        source.setPoseTarget(rebasedPose);

        ServerSubLevel stagedChild = MechanismSubLevelService.findExisting(level, staging);
        if (stagedChild != null && !stagedChild.isRemoved()) {
            AssemblyContentTransferService.TransferResult inbound =
                    AssemblyContentTransferService.transferFrames(
                            level,
                            staging,
                            source,
                            retainedFrames,
                            AssemblyLifecycleListener.TransferKind.TRANSFER);
            if (inbound != AssemblyContentTransferService.TransferResult.SUCCESS) {
                synchronizeFrameMappings(level, manager, source);
                lockBoth(manager, access, source, staging,
                        inbound == AssemblyContentTransferService.TransferResult.ROLLED_BACK
                                ? "restoring the rebased payload to the original assembly rolled back"
                                : "restoring the rebased payload to the original assembly requires recovery");
                return false;
            }
        }

        MechanismSubLevelService.remove(level, staging);
        assemblies.remove(staging.id());
        synchronizeFrameMappings(level, manager, source);
        MechanismSubLevelService.synchronizePlacedPhysicalPose(level, source);
        manager.setDirty();

        AntikytheraMechanism.LOGGER.debug(
                "Rebased assembly {} after topology change: stale origin {} -> {} over {} retained Frames",
                source.id(), oldOrigin, newOrigin, retainedFrames.size());
        return true;
    }

    private static void synchronizeFrameMappings(
            ServerLevel level,
            MechanismAssemblyManager manager,
            MechanismAssembly assembly) {
        for (BlockPos framePosition : assembly.frames()) {
            if (level.getBlockEntity(framePosition) instanceof MechanismFrameBlockEntity frame) {
                frame.setAssemblyMapping(
                        assembly.id(),
                        assembly.orientation(),
                        assembly.logicalFrameOffset(framePosition));
            }
            manager.refreshFrame(level, framePosition);
        }
    }

    private static void lockSourceOnly(
            MechanismAssemblyManager manager,
            MechanismAssemblyManagerAccessor access,
            MechanismAssembly source,
            String reason) {
        access.antikytheramechanism$getContentRecoveryLocks().add(source.id());
        manager.setDirty();
        AntikytheraMechanism.LOGGER.error(
                "Locked assembly {} after stale-origin repair failed: {}. Its existing assembly and physical payload were retained.",
                source.id(), reason);
    }

    private static void lockBoth(
            MechanismAssemblyManager manager,
            MechanismAssemblyManagerAccessor access,
            MechanismAssembly source,
            MechanismAssembly staging,
            String reason) {
        access.antikytheramechanism$getContentRecoveryLocks().add(source.id());
        access.antikytheramechanism$getContentRecoveryLocks().add(staging.id());
        manager.setDirty();
        AntikytheraMechanism.LOGGER.error(
                "Locked assemblies {} and {} after stale-origin repair failed: {}. Both managed payload references were retained for recovery.",
                source.id(), staging.id(), reason);
    }
}
