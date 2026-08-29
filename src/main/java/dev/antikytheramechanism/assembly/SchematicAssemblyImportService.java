package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.mixin.MechanismAssemblyManagerAccessor;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Narrow import boundary for schematic systems that deliberately suppress normal Block#onPlace.
 *
 * <p>The caller must provide a complete source assembly mapped to its placed Frame positions. This
 * method only registers ownership; managed mini content can then be attached before the ordinary
 * manager reconciliation pass is allowed to merge the imported assembly with surrounding Frames.
 */
public final class SchematicAssemblyImportService {
    private SchematicAssemblyImportService() {
    }

    public static @Nullable MechanismAssembly registerCompleteAssembly(
            ServerLevel level,
            UUID importedAssemblyId,
            BlockPos origin,
            Set<BlockPos> frames) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(importedAssemblyId, "importedAssemblyId");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(frames, "frames");
        if (frames.isEmpty() || !frames.contains(origin)) {
            return null;
        }

        Set<BlockPos> immutableFrames = new LinkedHashSet<>();
        for (BlockPos framePos : frames) {
            immutableFrames.add(framePos.immutable());
        }

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) manager;
        if (access.antikytheramechanism$getAssemblies().containsKey(importedAssemblyId)) {
            AntikytheraMechanism.LOGGER.error(
                    "Refusing schematic assembly import because generated id {} already exists",
                    importedAssemblyId);
            return null;
        }

        BlockState originState = level.getBlockState(origin);
        if (!originState.is(ModRegistries.MECHANISM_FRAME.get())
                || !originState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return null;
        }
        FrameOrientation orientation = new FrameOrientation(originState.getValue(BlockStateProperties.HORIZONTAL_FACING));
        FrameShellMode shellMode = originState.hasProperty(MechanismFrameBlock.SHELL_MODE)
                ? originState.getValue(MechanismFrameBlock.SHELL_MODE)
                : FrameShellMode.NORMAL;
        FrameSkin skin = level.getBlockEntity(origin) instanceof MechanismFrameBlockEntity originFrame
                ? originFrame.getPresentationSkin()
                : FrameSkin.COPPER;

        for (BlockPos framePos : immutableFrames) {
            BlockState state = level.getBlockState(framePos);
            if (!state.is(ModRegistries.MECHANISM_FRAME.get())
                    || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    || !orientation.equals(new FrameOrientation(state.getValue(BlockStateProperties.HORIZONTAL_FACING)))
                    || !MechanismAssemblyHost.samePhysicalHost(level, origin, framePos)
                    || access.antikytheramechanism$getFrameIndex().containsKey(framePos)) {
                AntikytheraMechanism.LOGGER.error(
                        "Refusing schematic assembly {} because Frame {} is missing, incompatible, differently hosted, or already owned",
                        importedAssemblyId,
                        framePos);
                return null;
            }
        }

        MechanismAssembly assembly = new MechanismAssembly(
                importedAssemblyId,
                origin.immutable(),
                immutableFrames,
                orientation);
        assembly.setShellMode(shellMode);
        assembly.setSkin(skin);

        access.antikytheramechanism$getAssemblies().put(importedAssemblyId, assembly);
        for (BlockPos framePos : immutableFrames) {
            access.antikytheramechanism$getFrameIndex().put(framePos, importedAssemblyId);
        }
        manager.setDirty();

        for (BlockPos framePos : immutableFrames) {
            BlockEntity blockEntity = level.getBlockEntity(framePos);
            if (blockEntity instanceof MechanismFrameBlockEntity frame) {
                frame.setAssemblyMapping(
                        importedAssemblyId,
                        orientation,
                        assembly.logicalFrameOffset(framePos));
                frame.setPresentationSkin(skin);
            }
        }
        return assembly;
    }
}
