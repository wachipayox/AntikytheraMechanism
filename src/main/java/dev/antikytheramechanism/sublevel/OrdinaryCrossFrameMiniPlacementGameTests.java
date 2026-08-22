package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regression coverage for ordinary BlockItem uses that cross a managed Frame boundary. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrdinaryCrossFrameMiniPlacementGameTests {
    private OrdinaryCrossFrameMiniPlacementGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void allowedBlockRoutesIntoDifferentlyYawedNeighborWithoutOrphanOrLoss(
            GameTestHelper helper) {
        CrossFrameFixture fixture = rotatedFixture(helper);
        ServerLevel level = helper.getLevel();

        BlockItem cobblestone = (BlockItem) Blocks.COBBLESTONE.asItem();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(cobblestone, 1));

        InteractionResult result = player.getItemInHand(InteractionHand.MAIN_HAND).useOn(
                new UseOnContext(player, InteractionHand.MAIN_HAND, fixture.sourceHit()));

        check(result.consumesAction(),
                "ordinary cross-Frame BlockItem placement did not consume the valid interaction");
        check(level.getBlockState(fixture.destinationGlobal()).is(Blocks.COBBLESTONE),
                "ordinary cross-Frame placement did not commit into the destination Frame plot");
        check(level.getBlockState(fixture.proposedSourcePlotTarget()).isAir(),
                "ordinary cross-Frame placement left an orphan in the source assembly plot");
        check(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                "valid cross-Frame placement did not consume exactly one survival item");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void crossFrameWallTorchUsesSourceMiniAsVirtualSupport(GameTestHelper helper) {
        CrossFrameFixture fixture = rotatedFixture(helper);
        ServerLevel level = helper.getLevel();

        check(Items.REDSTONE_TORCH instanceof BlockItem,
                "redstone torch item stopped being a BlockItem");
        BlockItem torchItem = (BlockItem) Items.REDSTONE_TORCH;
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(torchItem, 1));

        InteractionResult result = player.getItemInHand(InteractionHand.MAIN_HAND).useOn(
                new UseOnContext(player, InteractionHand.MAIN_HAND, fixture.sourceHit()));

        check(result.consumesAction(),
                "cross-Frame wall torch could not use the source mini block as support");
        check(level.getBlockState(fixture.destinationGlobal()).is(Blocks.REDSTONE_WALL_TORCH),
                "cross-Frame support-sensitive placement did not produce a wall torch in destination plot");
        check(level.getBlockState(fixture.proposedSourcePlotTarget()).isAir(),
                "support-sensitive cross-Frame placement wrote into the source plot escape cell");
        check(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                "successful support-sensitive placement consumed the wrong survival count");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void occupiedCrossFrameDestinationDoesNotConsumeSurvivalItem(GameTestHelper helper) {
        CrossFrameFixture fixture = rotatedFixture(helper);
        ServerLevel level = helper.getLevel();
        check(level.setBlock(
                        fixture.destinationGlobal(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL),
                "could not occupy destination cell for atomic-failure regression");

        BlockItem cobblestone = (BlockItem) Blocks.COBBLESTONE.asItem();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(cobblestone, 1));

        InteractionResult result = player.getItemInHand(InteractionHand.MAIN_HAND).useOn(
                new UseOnContext(player, InteractionHand.MAIN_HAND, fixture.sourceHit()));

        check(!result.consumesAction(),
                "occupied cross-Frame destination incorrectly reported a successful placement");
        check(level.getBlockState(fixture.destinationGlobal()).is(Blocks.OBSIDIAN),
                "failed cross-Frame placement modified the occupied destination");
        check(level.getBlockState(fixture.proposedSourcePlotTarget()).isAir(),
                "failed cross-Frame placement left an orphan in the source plot");
        check(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 1,
                "failed cross-Frame placement consumed the survival item");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void forbiddenBlockAcrossSameAssemblyNeighborDoesNotConsume(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceFrame = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos destinationFrame = sourceFrame.east();
        placeFrame(level, sourceFrame, Direction.NORTH);
        placeFrame(level, destinationFrame, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceAssembly = manager.getAssemblyAt(sourceFrame)
                .orElseThrow(() -> new AssertionError("missing same-assembly source Frame"));
        MechanismAssembly destinationAssembly = manager.getAssemblyAt(destinationFrame)
                .orElseThrow(() -> new AssertionError("missing same-assembly destination Frame"));
        check(sourceAssembly.id().equals(destinationAssembly.id()),
                "equal-yaw face-adjacent Frames did not merge for same-assembly rejection regression");

        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, sourceAssembly);
        check(child != null, "could not materialize same-assembly managed child");
        BlockPos sourceGlobal = MechanismSubLevelService.toPlotPosition(
                child,
                MiniCoordinateMapper.frameToMini(sourceAssembly, sourceFrame, 1, 0, 1));
        check(level.setBlock(sourceGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place same-assembly support mini block");
        BlockPos destinationGlobal = sourceGlobal.east();
        check(ManagedMiniPlacementTargets.isOwnedTarget(level, sourceGlobal, destinationGlobal),
                "server FrameMask did not recognize the neighboring same-assembly target");

        check(ModRegistries.MECHANISM_FRAME.get().asItem() instanceof BlockItem,
                "Mechanism Frame item stopped being a BlockItem");
        BlockItem forbiddenFrame = (BlockItem) ModRegistries.MECHANISM_FRAME.get().asItem();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(forbiddenFrame, 1));
        BlockHitResult hit = eastFaceHit(sourceGlobal);

        InteractionResult result = player.getItemInHand(InteractionHand.MAIN_HAND).useOn(
                new UseOnContext(player, InteractionHand.MAIN_HAND, hit));

        check(!result.consumesAction(),
                "forbidden mini block across same-assembly neighbor was not rejected");
        check(level.getBlockState(destinationGlobal).isAir(),
                "forbidden mini block was written into the neighboring Frame");
        check(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 1,
                "forbidden same-assembly placement consumed its survival item");
        helper.succeed();
    }

    private static CrossFrameFixture rotatedFixture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceFrame = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos destinationFrame = sourceFrame.east();
        placeFrame(level, sourceFrame, Direction.NORTH);
        placeFrame(level, destinationFrame, Direction.EAST);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly sourceAssembly = manager.getAssemblyAt(sourceFrame)
                .orElseThrow(() -> new AssertionError("missing rotated source assembly"));
        MechanismAssembly destinationAssembly = manager.getAssemblyAt(destinationFrame)
                .orElseThrow(() -> new AssertionError("missing rotated destination assembly"));
        check(!sourceAssembly.id().equals(destinationAssembly.id()),
                "differently-yawed adjacent Frames unexpectedly merged");

        ServerSubLevel sourceChild = MechanismSubLevelService.ensureForContent(level, sourceAssembly);
        check(sourceChild != null, "could not materialize rotated source child");
        BlockPos sourceGlobal = MechanismSubLevelService.toPlotPosition(
                sourceChild,
                MiniCoordinateMapper.frameToMini(sourceAssembly, sourceFrame, 1, 0, 1));
        check(level.setBlock(sourceGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place rotated source support mini block");

        BlockPos proposedSourcePlotTarget = sourceGlobal.east();
        ManagedMiniPlacementTargets.NeighborFrameTarget routed =
                ManagedMiniPlacementTargets.resolveNeighborFrameTarget(
                                level, sourceGlobal, proposedSourcePlotTarget)
                        .orElseThrow(() -> new AssertionError(
                                "rotated ordinary placement target did not resolve to neighbor Frame"));
        check(routed.destinationAssemblyId().equals(destinationAssembly.id()),
                "ordinary cross-Frame fixture resolved the wrong destination assembly");
        check(level.getBlockState(proposedSourcePlotTarget).isAir(),
                "source-plot escape coordinate was not initially air");
        check(level.getBlockState(routed.destinationGlobalPosition()).canBeReplaced(),
                "destination mini cell was not initially replaceable");

        return new CrossFrameFixture(
                sourceGlobal,
                proposedSourcePlotTarget,
                routed.destinationGlobalPosition(),
                eastFaceHit(sourceGlobal));
    }

    private static BlockHitResult eastFaceHit(BlockPos sourceGlobal) {
        return new BlockHitResult(
                new Vec3(
                        sourceGlobal.getX() + 1.0 - 1.0E-6,
                        sourceGlobal.getY() + 0.31,
                        sourceGlobal.getZ() + 0.79),
                Direction.EAST,
                sourceGlobal,
                false);
    }

    private static void placeFrame(ServerLevel level, BlockPos position, Direction facing) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        check(level.setBlock(position, state, Block.UPDATE_ALL),
                "could not place Frame at " + position + " facing " + facing);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record CrossFrameFixture(
            BlockPos sourceGlobal,
            BlockPos proposedSourcePlotTarget,
            BlockPos destinationGlobal,
            BlockHitResult sourceHit) {
    }
}
