package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.compat.simulated.MiniPhysicsAssemblyContext;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.sablescale.scale.SubLevelScale;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for free 0.5 Antikythera bodies and optional Simulated integration. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DetachedMiniPhysicsGameTests {
    private static final ResourceLocation PHYSICS_ASSEMBLER =
            ResourceLocation.fromNamespaceAndPath("simulated", "physics_assembler");

    private DetachedMiniPhysicsGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void detachedBodyKeepsPolicyAndIdentityAcrossSableAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerSubLevel source = allocateBody(level);
        DetachedMiniPhysicsSubLevelService.markDetached(source);
        check(DetachedMiniPhysicsSubLevelService.isDetached(source), "detached marker was not installed");
        check(DetachedMiniPhysicsSubLevelService.hasHalfScale(source), "detached body is not exactly half scale");
        check(MechanismSubLevelService.getOwnerAssemblyId(source) == null,
                "detached body was incorrectly given a Mechanism Frame owner");
        check(!MiniWorldEnvironment.isManagedSubLevel(source),
                "detached body collided with the Frame-managed sublevel identity");

        // Detached mini-physics is a subtype invariant, not merely the scale chosen at creation.
        // Any supported Sable Scale API change must bounce straight back to 0.5.
        SubLevelScale.apply(source, 1.0);
        check(DetachedMiniPhysicsSubLevelService.hasHalfScale(source),
                "detached body escaped its immutable 0.5 scale through Sable Scale");

        BlockPos first = source.getPlot().getCenterBlock();
        BlockPos second = first.east();
        check(level.setBlock(first, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed detached body");

        FrameMaskWriteGuard.beginTrackedItemUse();
        FrameMaskWriteGuard.WriteAttempt denied;
        boolean wroteDenied;
        try {
            wroteDenied = level.setBlock(second, Blocks.TNT.defaultBlockState(), Block.UPDATE_ALL);
        } finally {
            denied = FrameMaskWriteGuard.finishTrackedItemUse();
        }
        check(!wroteDenied && denied.rejectedWithoutPlacement(),
                "detached body accepted a denied tracked BlockItem write");
        check(level.getBlockState(second).isAir(), "denied block leaked into detached body");

        FrameMaskWriteGuard.beginTrackedItemUse();
        FrameMaskWriteGuard.WriteAttempt allowed;
        boolean wroteAllowed;
        try {
            wroteAllowed = level.setBlock(second, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        } finally {
            allowed = FrameMaskWriteGuard.finishTrackedItemUse();
        }
        check(wroteAllowed && allowed.acceptedNonAirWrite(),
                "detached body rejected/failed to account an allowed tracked write");

        check(!level.setBlock(second.above(),
                        ModRegistries.MECHANISM_FRAME.get().defaultBlockState(), Block.UPDATE_ALL),
                "recursive Mechanism Frame was accepted in detached body");

        // Sable's own heat-map splitter creates child bodies via this same helper. Moving a piece out
        // of a detached body must propagate both the detached marker and the invariant 0.5 scale.
        BoundingBox3i bounds = Objects.requireNonNull(BoundingBox3i.from(List.of(second))).expand(1, 1, 1);
        ServerSubLevel split = SubLevelAssemblyHelper.assembleBlocks(level, second, List.of(second), bounds);
        check(split != null && split != source, "Sable split helper did not create a distinct body");
        check(DetachedMiniPhysicsSubLevelService.isDetached(split),
                "detached identity was lost across Sable assemble/split");
        check(DetachedMiniPhysicsSubLevelService.hasHalfScale(split),
                "split detached body lost half scale");
        check(MechanismSubLevelService.getOwnerAssemblyId(split) == null,
                "split detached body acquired Frame ownership");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void miniPhysicsAssemblerEjectsOneWayDetachedBody(GameTestHelper helper) {
        if (!ModList.get().isLoaded("simulated")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Block physicsAssembler = BuiltInRegistries.BLOCK.get(PHYSICS_ASSEMBLER);
        check(physicsAssembler != Blocks.AIR,
                "Simulated is loaded but simulated:physics_assembler is missing");

        BlockPos framePos = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockState frameState = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH);
        check(level.setBlock(framePos, frameState, Block.UPDATE_ALL), "could not place Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        UUID assemblyId = assembly.id();
        ServerSubLevel source = MechanismSubLevelService.ensureForContent(level, assembly);
        check(source != null && !source.isRemoved(), "could not materialize Frame mini world");

        BlockPos supportLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 0, 0);
        BlockPos assemblerLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 1, 0);
        BlockPos supportGlobal = MechanismSubLevelService.toPlotPosition(source, supportLocal);
        BlockPos assemblerGlobal = MechanismSubLevelService.toPlotPosition(source, assemblerLocal);
        check(level.setBlock(supportGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place mini support payload");

        BlockState assemblerState = physicsAssembler.defaultBlockState();
        if (assemblerState.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            assemblerState = assemblerState.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
        }
        if (assemblerState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            assemblerState = assemblerState.setValue(
                    BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH);
        }
        check(level.setBlock(assemblerGlobal, assemblerState, Block.UPDATE_ALL),
                "could not place mini Physics Assembler");
        check(level.getBlockEntity(assemblerGlobal) != null, "mini Physics Assembler BlockEntity missing");
        check(MiniPhysicsAssemblyContext.validFrameSource(level, assemblerGlobal) == source,
                "mini Physics Assembler source did not validate as an owned half-scale Frame child");

        Set<UUID> before = subLevelIds(level);
        invokeAssembler(level.getBlockEntity(assemblerGlobal));

        ServerSubLevel detached = findNewDetached(level, before);
        check(detached != null, "mini Physics Assembler did not create a detached Antikythera body");
        check(DetachedMiniPhysicsSubLevelService.hasHalfScale(detached),
                "Physics Assembler result did not preserve scale 0.5");
        check(MechanismSubLevelService.getOwnerAssemblyId(detached) == null,
                "Physics Assembler result incorrectly owns a Frame assembly");
        check(!MiniWorldEnvironment.isManagedSubLevel(detached),
                "Physics Assembler result was mistaken for a Frame child");
        check(level.getBlockState(supportGlobal).isAir(),
                "assembled support payload remained duplicated in Frame child");
        check(level.getBlockState(assemblerGlobal).isAir(),
                "Physics Assembler remained duplicated in Frame child");
        check(level.getBlockState(framePos).is(ModRegistries.MECHANISM_FRAME.get()),
                "ejection modified the physical Mechanism Frame");
        check(manager.getAssemblyAt(framePos).map(MechanismAssembly::id).filter(assemblyId::equals).isPresent(),
                "ejection replaced or detached the Frame's logical assembly");

        BlockPos detachedAssembler = findBlock(detached, physicsAssembler);
        check(detachedAssembler != null, "detached body lost its Physics Assembler block");
        Object detachedAssemblerEntity = level.getBlockEntity(detachedAssembler);
        check(detachedAssemblerEntity != null, "detached Physics Assembler BlockEntity missing");
        Set<UUID> beforeForbiddenDisassembly = subLevelIds(level);
        BlockState detachedAssemblerState = level.getBlockState(detachedAssembler);
        invokeAssembler(detachedAssemblerEntity);
        check(level.getBlockState(detachedAssembler).equals(detachedAssemblerState),
                "detached Physics Assembler disassembled or mutated its free body");
        check(DetachedMiniPhysicsSubLevelService.isDetached(detached) && !detached.isRemoved(),
                "one-way detached body was removed by a second assembler activation");
        check(subLevelIds(level).equals(beforeForbiddenDisassembly),
                "forbidden detached disassembly changed the Sable body set");
        helper.succeed();
    }

    private static ServerSubLevel allocateBody(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        check(container != null, "Sable container unavailable");
        ServerSubLevel subLevel = (ServerSubLevel) container.allocateNewSubLevel(new Pose3d());
        subLevel.getPlot().newEmptyChunk(subLevel.getPlot().getCenterChunk());
        return subLevel;
    }

    private static Set<UUID> subLevelIds(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        check(container != null, "Sable container unavailable");
        Set<UUID> ids = new HashSet<>();
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (!subLevel.isRemoved()) {
                ids.add(subLevel.getUniqueId());
            }
        }
        return ids;
    }

    private static ServerSubLevel findNewDetached(ServerLevel level, Set<UUID> before) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        check(container != null, "Sable container unavailable");
        ServerSubLevel result = null;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (!(subLevel instanceof ServerSubLevel server)
                    || server.isRemoved()
                    || before.contains(server.getUniqueId())
                    || !DetachedMiniPhysicsSubLevelService.isDetached(server)) {
                continue;
            }
            check(result == null, "more than one detached body appeared from one Physics Assembler use");
            result = server;
        }
        return result;
    }

    private static BlockPos findBlock(ServerSubLevel subLevel, Block block) {
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null) {
            return null;
        }
        for (BlockPos pos : BlockPos.betweenClosed(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            if (subLevel.getLevel().getBlockState(pos).is(block)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static void invokeAssembler(Object blockEntity) {
        try {
            Method method = blockEntity.getClass().getMethod("assembleOrDisassemble");
            method.invoke(blockEntity);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new AssertionError("Could not invoke Simulated Physics Assembler", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("Physics Assembler invocation failed", cause);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
