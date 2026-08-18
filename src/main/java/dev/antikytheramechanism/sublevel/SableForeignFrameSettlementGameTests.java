package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Verifies that ordinary Sable hosting fully settles independent Frame ownership before callers continue. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SableForeignFrameSettlementGameTests {
    private SableForeignFrameSettlementGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void independentFramesSettleIntoSameForeignHostSynchronously(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceRoot = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockPos targetRoot = sourceRoot.offset(0, 1, 1);
        BlockPos bridgeRoot = sourceRoot.above();
        placeFrame(level, sourceRoot);
        placeFrame(level, targetRoot);
        check(level.setBlock(bridgeRoot, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place foreign-host bridge");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceBefore = manager.getAssemblyAt(sourceRoot).orElseThrow();
        MechanismAssembly targetBefore = manager.getAssemblyAt(targetRoot).orElseThrow();
        check(!sourceBefore.id().equals(targetBefore.id()), "fixture Frames merged before hosting");

        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                sourceRoot,
                java.util.List.of(sourceRoot, bridgeRoot, targetRoot),
                new BoundingBox3i(
                        sourceRoot.getX(), sourceRoot.getY(), sourceRoot.getZ(),
                        targetRoot.getX(), targetRoot.getY(), targetRoot.getZ()));
        check(host != null && !host.isRemoved(), "Sable did not return a live foreign host");

        BlockPos hostedSource = host.getPlot().getCenterBlock();
        BlockPos hostedTarget = hostedSource.offset(0, 1, 1);
        check(level.getBlockState(hostedSource).is(ModRegistries.MECHANISM_FRAME.get()),
                "source outer Frame state did not reach foreign host");
        check(level.getBlockState(hostedTarget).is(ModRegistries.MECHANISM_FRAME.get()),
                "target outer Frame state did not reach foreign host");
        check(level.getBlockEntity(hostedSource) instanceof MechanismFrameBlockEntity,
                "source Frame BlockEntity did not reach foreign host");
        check(level.getBlockEntity(hostedTarget) instanceof MechanismFrameBlockEntity,
                "target Frame BlockEntity did not reach foreign host");

        check(manager.pendingContraptionMove(sourceBefore.id()).isEmpty(),
                "source relocation journal remained pending after synchronous Sable hosting");
        check(manager.pendingContraptionMove(targetBefore.id()).isEmpty(),
                "target relocation journal remained pending after synchronous Sable hosting");
        check(!manager.isContentRecoveryLocked(sourceBefore.id()),
                "source assembly became recovery-locked during ordinary foreign hosting");
        check(!manager.isContentRecoveryLocked(targetBefore.id()),
                "target assembly became recovery-locked during ordinary foreign hosting");

        MechanismAssembly sourceAfter = manager.getAssemblyAt(hostedSource)
                .orElseThrow(() -> new AssertionError("source frameIndex did not adopt foreign-host coordinate"));
        MechanismAssembly targetAfter = manager.getAssemblyAt(hostedTarget)
                .orElseThrow(() -> new AssertionError("target frameIndex did not adopt foreign-host coordinate"));
        check(sourceAfter.id().equals(sourceBefore.id()), "source assembly UUID changed during hosting");
        check(targetAfter.id().equals(targetBefore.id()), "target assembly UUID changed during hosting");
        check(!sourceAfter.id().equals(targetAfter.id()), "independent Frames merged during hosting");

        MechanismAssemblyHost.Resolution sourceHost = MechanismAssemblyHost.resolve(level, hostedSource);
        MechanismAssemblyHost.Resolution targetHost = MechanismAssemblyHost.resolve(level, hostedTarget);
        check(sourceHost.kind() == MechanismAssemblyHost.Kind.FOREIGN,
                "source did not resolve as FOREIGN after hosting: " + sourceHost.kind());
        check(targetHost.kind() == MechanismAssemblyHost.Kind.FOREIGN,
                "target did not resolve as FOREIGN after hosting: " + targetHost.kind());
        check(host.getUniqueId().equals(sourceHost.foreignId()),
                "source foreign host UUID differs from Sable host");
        check(host.getUniqueId().equals(targetHost.foreignId()),
                "target foreign host UUID differs from Sable host");
        check(MechanismAssemblyHost.sameResolvedHost(level, hostedSource, hostedTarget),
                "hosted Frames do not resolve to the same foreign host");
        helper.succeed();
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(position, state, Block.UPDATE_ALL), "could not place Mechanism Frame at " + position);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
