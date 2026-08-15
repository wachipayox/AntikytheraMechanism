package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismAssemblyHost;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
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
import org.joml.Quaterniond;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for frozen structural parent reads while Create owns an assembly. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateContraptionStructuralSnapshotGameTests {
    private static final double HOST_ALIGNMENT_EPSILON = 1.0E-5;

    private CreateContraptionStructuralSnapshotGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void carriedFloorRemainsVisibleWhileCreatePoseIsUndocked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockPos floorPos = framePos.below();
        check(level.setBlock(floorPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place carried support floor");
        placeFrame(level, framePos, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 0, 0);
        BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(child, miniLocal);
        BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.setBlock(miniGlobal, wire, Block.UPDATE_ALL)),
                "could not place mini redstone dust");
        check(MiniWorldEnvironment.withVirtualReads(() -> wire.canSurvive(level, miniGlobal)),
                "mini dust did not see physical floor before capture");

        UUID id = assembly.id();
        Set<BlockPos> frames = Set.copyOf(assembly.frames());
        check(manager.prepareContraptionMoves(
                        level,
                        Map.of(id, frames),
                        Map.of(id, Map.of(floorPos, Blocks.STONE.defaultBlockState())),
                        BlockPos.ZERO,
                        false),
                "could not journal carried structural boundary");

        // Mirror Create extraction after the journal is durable.
        level.removeBlock(framePos, false);
        level.removeBlock(floorPos, false);
        check(manager.pendingContraptionMove(id).isPresent(), "movement journal disappeared during extraction");

        // MovementBehaviour continuously writes a non-docked pose while a bearing/contraption is
        // between snapped orientations. The frozen structural shell must remain addressable in this
        // interval even though live macro boundary projection is intentionally disabled.
        AssemblyPose startPose = assembly.poseTarget();
        Quaterniond inFlightRotation = new Quaterniond()
                .rotateY(Math.toRadians(37.0))
                .mul(startPose.orientation(new Quaterniond()))
                .normalize();
        AssemblyPose inFlightPose = new AssemblyPose(
                startPose.anchorX(),
                startPose.anchorY(),
                startPose.anchorZ(),
                inFlightRotation.x,
                inFlightRotation.y,
                inFlightRotation.z,
                inFlightRotation.w);
        check(manager.updatePoseTarget(id, inFlightPose), "could not apply in-flight Create pose");

        MechanismAssembly moving = manager.getAssembly(id).orElseThrow();
        check(!MechanismAssemblyHost.boundaryIsAligned(level, moving, HOST_ALIGNMENT_EPSILON),
                "test did not enter a non-docked Create pose");

        BlockState projectedFloor = MiniWorldEnvironment.withVirtualReads(
                () -> level.getBlockState(miniGlobal.below()));
        check(projectedFloor.is(Blocks.STONE),
                "captured floor disappeared from structural reads while Create pose was in flight");

        BlockState current = level.getBlockState(miniGlobal);
        check(current.is(Blocks.REDSTONE_WIRE), "mini redstone dust disappeared before support validation");
        check(MiniWorldEnvironment.withVirtualReads(() -> current.canSurvive(level, miniGlobal)),
                "mini redstone dust lost captured support during in-flight Create motion");

        BlockState shapeResult = MiniWorldEnvironment.withVirtualReads(() -> current.updateShape(
                Direction.DOWN,
                projectedFloor,
                level,
                miniGlobal,
                miniGlobal.below()));
        check(shapeResult.is(Blocks.REDSTONE_WIRE),
                "mini redstone updateShape treated captured support as air during Create motion");
        check(level.getBlockState(miniGlobal).is(Blocks.REDSTONE_WIRE),
                "mini redstone dust was destroyed during in-flight structural validation");
        helper.succeed();
    }

    private static void placeFrame(ServerLevel level, BlockPos pos, Direction facing) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(pos, state, Block.UPDATE_ALL), "could not place Frame at " + pos);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
