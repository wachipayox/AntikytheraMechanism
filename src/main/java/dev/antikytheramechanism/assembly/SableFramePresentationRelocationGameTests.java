package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Real Sable ROOT/FOREIGN relocation coverage for assembly-scoped Frame presentation. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SableFramePresentationRelocationGameTests {
    private SableFramePresentationRelocationGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 260)
    public static void rootForeignRootRoundTripPreservesIndependentPresentationAndPayload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceRoot = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockPos bridgeRoot = sourceRoot.above();
        BlockPos targetRoot = sourceRoot.offset(0, 1, 1);
        placeFrame(level, sourceRoot);
        placeFrame(level, targetRoot);
        check(level.setBlock(bridgeRoot, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place Sable presentation bridge");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceBefore = manager.getAssemblyAt(sourceRoot).orElseThrow();
        MechanismAssembly targetBefore = manager.getAssemblyAt(targetRoot).orElseThrow();
        check(!sourceBefore.id().equals(targetBefore.id()),
                "diagonal presentation fixtures unexpectedly merged before Sable hosting");
        UUID sourceId = sourceBefore.id();
        UUID targetId = targetBefore.id();

        check(manager.setFrameShellMode(level, sourceRoot, FrameShellMode.GLASS),
                "could not set source GLASS mode before Sable hosting");
        check(manager.setFrameSkin(level, sourceRoot, FrameSkin.BRASS_CASING),
                "could not set source BRASS skin before Sable hosting");
        check(manager.setFrameShellMode(level, targetRoot, FrameShellMode.HIDDEN),
                "could not set target HIDDEN mode before Sable hosting");
        check(manager.setFrameSkin(level, targetRoot, FrameSkin.RAILWAY_CASING),
                "could not set target RAILWAY skin before Sable hosting");

        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, sourceBefore);
        ServerSubLevel targetChild = MechanismSubLevelService.ensureForContent(level, targetBefore);
        check(sourceChild != null && targetChild != null,
                "could not materialize presentation children before Sable hosting");
        UUID sourceChildId = sourceChild.getUniqueId();
        UUID targetChildId = targetChild.getUniqueId();
        BlockPos sourceMini = MiniCoordinateMapper.frameToMini(sourceBefore, sourceRoot, 0, 0, 0);
        BlockPos targetMini = MiniCoordinateMapper.frameToMini(targetBefore, targetRoot, 1, 1, 1);
        check(sourceChild.getPlot().getEmbeddedLevelAccessor().setBlock(
                        sourceMini, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed source mini payload before Sable hosting");
        check(targetChild.getPlot().getEmbeddedLevelAccessor().setBlock(
                        targetMini, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed target mini payload before Sable hosting");
        sourceChild.getPlot().updateBoundingBox();
        targetChild.getPlot().updateBoundingBox();

        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                sourceRoot,
                List.of(sourceRoot, bridgeRoot, targetRoot),
                new BoundingBox3i(
                        sourceRoot.getX(), sourceRoot.getY(), sourceRoot.getZ(),
                        targetRoot.getX(), targetRoot.getY(), targetRoot.getZ()));
        check(host != null && !host.isRemoved(), "Sable did not create a live foreign presentation host");

        BlockPos hostedSource = host.getPlot().getCenterBlock();
        BlockPos hostedBridge = hostedSource.above();
        BlockPos hostedTarget = hostedSource.offset(0, 1, 1);
        check(level.getBlockState(hostedBridge).is(Blocks.STONE),
                "Sable presentation bridge did not enter foreign host");
        assertPresentationAndPayload(
                level, manager, hostedSource, sourceId, sourceChildId, sourceMini,
                Blocks.DIAMOND_BLOCK, FrameShellMode.GLASS, FrameSkin.BRASS_CASING, "source FOREIGN");
        assertPresentationAndPayload(
                level, manager, hostedTarget, targetId, targetChildId, targetMini,
                Blocks.GOLD_BLOCK, FrameShellMode.HIDDEN, FrameSkin.RAILWAY_CASING, "target FOREIGN");
        check(!manager.getAssemblyAt(hostedSource).orElseThrow().id()
                        .equals(manager.getAssemblyAt(hostedTarget).orElseThrow().id()),
                "independent assemblies merged merely because they entered the same foreign host");
        MechanismAssemblyHost.Resolution sourceHost = MechanismAssemblyHost.resolve(level, hostedSource);
        MechanismAssemblyHost.Resolution targetHost = MechanismAssemblyHost.resolve(level, hostedTarget);
        check(sourceHost.kind() == MechanismAssemblyHost.Kind.FOREIGN
                        && targetHost.kind() == MechanismAssemblyHost.Kind.FOREIGN,
                "hosted presentation Frames did not both resolve as FOREIGN");
        check(host.getUniqueId().equals(sourceHost.foreignId())
                        && host.getUniqueId().equals(targetHost.foreignId()),
                "hosted presentation Frames resolved to the wrong foreign host");

        SubLevelAssemblyHelper.AssemblyTransform cleanupTransform =
                new SubLevelAssemblyHelper.AssemblyTransform(
                        hostedSource,
                        sourceRoot,
                        0,
                        Rotation.NONE,
                        level);
        SubLevelAssemblyHelper.moveBlocks(
                level,
                cleanupTransform,
                List.of(hostedSource, hostedBridge, hostedTarget));

        check(level.getBlockState(bridgeRoot).is(Blocks.STONE),
                "Sable presentation bridge did not return to ROOT");
        assertPresentationAndPayload(
                level, manager, sourceRoot, sourceId, sourceChildId, sourceMini,
                Blocks.DIAMOND_BLOCK, FrameShellMode.GLASS, FrameSkin.BRASS_CASING, "source ROOT return");
        assertPresentationAndPayload(
                level, manager, targetRoot, targetId, targetChildId, targetMini,
                Blocks.GOLD_BLOCK, FrameShellMode.HIDDEN, FrameSkin.RAILWAY_CASING, "target ROOT return");
        check(MechanismAssemblyHost.resolve(level, sourceRoot).kind() == MechanismAssemblyHost.Kind.ROOT
                        && MechanismAssemblyHost.resolve(level, targetRoot).kind() == MechanismAssemblyHost.Kind.ROOT,
                "presentation Frames did not resolve back to ROOT");
        check(host.isRemoved(), "emptied Sable presentation host remained live after ROOT return");
        helper.succeed();
    }

    private static void assertPresentationAndPayload(
            ServerLevel level,
            MechanismAssemblyManager manager,
            BlockPos framePos,
            UUID assemblyId,
            UUID childId,
            BlockPos miniPos,
            Block expectedMiniBlock,
            FrameShellMode expectedMode,
            FrameSkin expectedSkin,
            String label) {
        BlockState state = level.getBlockState(framePos);
        check(state.is(ModRegistries.MECHANISM_FRAME.get()), label + " lost physical Frame block");
        check(state.getValue(MechanismFrameBlock.SHELL_MODE) == expectedMode,
                label + " BlockState shell mode mismatch");
        check(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity,
                label + " lost Frame BlockEntity");
        MechanismFrameBlockEntity frame = (MechanismFrameBlockEntity) level.getBlockEntity(framePos);
        check(assemblyId.equals(frame.getAssemblyId()), label + " BlockEntity assembly UUID mismatch");
        check(frame.getPresentationSkin() == expectedSkin, label + " BlockEntity skin mismatch");

        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        check(assemblyId.equals(assembly.id()), label + " changed assembly UUID");
        check(assembly.shellMode() == expectedMode, label + " assembly shell mode mismatch");
        check(assembly.skin() == expectedSkin, label + " assembly skin mismatch");
        ServerSubLevel child = MechanismSubLevelService.findExisting(level, assembly);
        check(child != null && childId.equals(child.getUniqueId()), label + " recreated/replaced managed child");
        check(child.getPlot().getEmbeddedLevelAccessor().getBlockState(miniPos).is(expectedMiniBlock),
                label + " lost or remapped mini payload");
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(position, state, Block.UPDATE_ALL),
                "could not place Mechanism Frame at " + position);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
