package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.FrameFaceSupport;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for structural Frame boundaries shared between macro and mini worlds. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FrameBoundaryRegressionGameTests {
    private FrameBoundaryRegressionGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 120)
    public static void rotatedFrameProjectsLogicalMiniFaceToPhysicalMacroSupport(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos target = helper.absolutePos(new BlockPos(8, 3, 3));
        placeFrame(level, source, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = requireAssembly(manager, source);
        UUID id = assembly.id();
        FrameOrientation targetOrientation = new FrameOrientation(Direction.UP, Direction.EAST);

        check(manager.prepareContraptionMoves(level, Map.of(id, Set.of(source)), BlockPos.ZERO, false),
                "capture preflight failed");
        level.removeBlock(source, false);
        check(manager.prepareContraptionPlacement(
                        level,
                        Map.of(id, Set.of(target)),
                        Map.of(id, target),
                        Map.of(id, poseAt(target, targetOrientation))),
                "placement preflight failed");
        placeFrame(level, target, targetOrientation.front());
        check(manager.finalizeContraptionPlacement(level, List.of(id)), "placement commit failed");

        assembly = requireAssembly(manager, target);
        check(assembly.orientation().equals(targetOrientation), "rotated assembly orientation was not committed");

        Direction physicalFace = Direction.NORTH;
        Direction logicalFace = assembly.orientation().toLogical(physicalFace);
        check(logicalFace != physicalFace,
                "test rotation did not separate physical and logical support faces");

        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos miniLocal = logicalBoundaryCell(assembly, target, logicalFace, a, b);
                BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(child, miniLocal);
                check(level.setBlock(miniGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                        "could not populate rotated logical support face at " + miniLocal);
            }
        }

        Boolean projected = FrameFaceSupport.query(level, target, physicalFace, SupportType.FULL);
        check(Boolean.TRUE.equals(projected),
                "rotated physical Frame face did not project the corresponding logical mini support");
        check(level.getBlockState(target).isFaceSturdy(level, target, physicalFace, SupportType.FULL),
                "vanilla macro support query did not receive rotated mini support");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 40)
    public static void mechanismFrameRejectsFluidReplacement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(3, 3, 3));
        placeFrame(level, framePos, Direction.NORTH);

        BlockState frame = level.getBlockState(framePos);
        check(!frame.canBeReplaced(Fluids.WATER),
                "water can replace a Mechanism Frame");
        check(!frame.canBeReplaced(Fluids.LAVA),
                "lava can replace a Mechanism Frame");
        check(frame.is(ModRegistries.MECHANISM_FRAME.get()),
                "fluid replacement preflight mutated the Mechanism Frame");
        helper.succeed();
    }

    private static BlockPos logicalBoundaryCell(
            MechanismAssembly assembly,
            BlockPos frame,
            Direction logicalFace,
            int a,
            int b) {
        int x;
        int y;
        int z;
        switch (logicalFace.getAxis()) {
            case X -> {
                x = logicalFace == Direction.WEST ? 0 : 1;
                y = a;
                z = b;
            }
            case Y -> {
                x = a;
                y = logicalFace == Direction.DOWN ? 0 : 1;
                z = b;
            }
            case Z -> {
                x = a;
                y = b;
                z = logicalFace == Direction.NORTH ? 0 : 1;
            }
            default -> throw new IllegalStateException("Unexpected axis " + logicalFace.getAxis());
        }
        return MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
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
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        check(level.setBlock(pos, state, Block.UPDATE_ALL), "could not place Frame at " + pos);
    }

    private static MechanismAssembly requireAssembly(MechanismAssemblyManager manager, BlockPos pos) {
        return manager.getAssemblyAt(pos)
                .orElseThrow(() -> new AssertionError("missing assembly at " + pos));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
