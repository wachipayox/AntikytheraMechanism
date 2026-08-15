package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
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
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pinpoints weak-vs-strong macro redstone projection on a Frame's logical side face. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RotatedSideStrongPowerGameTests {
    private static final Direction LOGICAL_SOURCE_FACE = Direction.EAST;

    private RotatedSideStrongPowerGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void identityEastSideLeverStronglyPowersMiniConductor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos frame = helper.absolutePos(new BlockPos(4, 3, 4));
        placeFrame(level, frame, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = requireAssembly(manager, frame);
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize child");
        assertSideStrongPower(level, assembly, child, frame, LOGICAL_SOURCE_FACE);
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void eastDockKeepsLogicalEastSideLeverStrongPower(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos frame = helper.absolutePos(new BlockPos(4, 3, 4));
        placeFrame(level, frame, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = requireAssembly(manager, frame);
        UUID id = assembly.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize child before rotation");

        check(manager.prepareContraptionMoves(level, Map.of(id, Set.of(frame)), BlockPos.ZERO, false),
                "capture preflight failed");
        check(level.removeBlock(frame, false), "could not remove source Frame");

        FrameOrientation targetOrientation = new FrameOrientation(Direction.UP, Direction.EAST);
        check(manager.prepareContraptionPlacement(
                        level,
                        Map.of(id, Set.of(frame)),
                        Map.of(id, frame),
                        Map.of(id, poseAt(frame, targetOrientation))),
                "placement preflight failed");
        placeFrame(level, frame, targetOrientation.front());
        check(manager.finalizeContraptionPlacement(level, List.of(id)), "placement commit failed");

        assembly = requireAssembly(manager, frame);
        check(assembly.orientation().equals(targetOrientation), "target orientation was not committed");
        child = MechanismSubLevelService.findExisting(level, assembly);
        check(child != null && !child.isRemoved(), "managed child disappeared across rotation");
        assertSideStrongPower(level, assembly, child, frame, LOGICAL_SOURCE_FACE);
        helper.succeed();
    }

    private static void assertSideStrongPower(
            ServerLevel level,
            MechanismAssembly assembly,
            ServerSubLevel child,
            BlockPos frame,
            Direction logicalFace) {
        Direction physicalFace = assembly.orientation().toPhysical(logicalFace);
        BlockPos boundaryLocal = MiniCoordinateMapper.frameToMini(assembly, frame, 1, 0, 0);
        BlockPos innerLocal = MiniCoordinateMapper.frameToMini(assembly, frame, 0, 0, 0);
        BlockPos boundaryGlobal = MechanismSubLevelService.toPlotPosition(child, boundaryLocal);
        BlockPos innerGlobal = MechanismSubLevelService.toPlotPosition(child, innerLocal);

        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.setBlock(boundaryGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL)),
                "could not place boundary conductor");
        check(MiniWorldEnvironment.withVirtualReads(() ->
                        level.setBlock(innerGlobal, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL)),
                "could not place inner receiver");

        BlockPos leverPos = frame.relative(physicalFace);
        BlockState lever = poweredWallLever(physicalFace);
        check(level.setBlock(leverPos, lever, Block.UPDATE_ALL), "could not place side lever");
        MiniWorldEnvironment.parentBlockChanged(level, leverPos);

        int weak = MiniWorldEnvironment.withVirtualReads(() -> level.getBestNeighborSignal(boundaryGlobal));
        int direct = MiniWorldEnvironment.withVirtualReads(() -> level.getDirectSignalTo(boundaryGlobal));
        boolean innerPowered = MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(innerGlobal));
        check(weak > 0,
                "logical " + logicalFace + " / physical " + physicalFace + " side lever lost weak boundary power");
        check(direct > 0,
                "logical " + logicalFace + " / physical " + physicalFace
                        + " side lever has weak power but no direct/strong boundary power");
        check(innerPowered,
                "logical " + logicalFace + " / physical " + physicalFace
                        + " side lever cannot power the second mini layer through a conductor");
    }

    private static BlockState poweredWallLever(Direction physicalFace) {
        return Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, physicalFace)
                .setValue(BlockStateProperties.POWERED, true);
    }

    private static AssemblyPose poseAt(BlockPos origin, FrameOrientation orientation) {
        Quaterniond q = orientation.quaternion(new Quaterniond());
        return new AssemblyPose(
                origin.getX() + .5,
                origin.getY() + .5,
                origin.getZ() + .5,
                q.x, q.y, q.z, q.w);
    }

    private static void placeFrame(ServerLevel level, BlockPos pos, Direction facing) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(pos, state, Block.UPDATE_ALL), "could not place Frame at " + pos);
    }

    private static MechanismAssembly requireAssembly(MechanismAssemblyManager manager, BlockPos pos) {
        return manager.getAssemblyAt(pos)
                .orElseThrow(() -> new AssertionError("missing assembly at " + pos));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
