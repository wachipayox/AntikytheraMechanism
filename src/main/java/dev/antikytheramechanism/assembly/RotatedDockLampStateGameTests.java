package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.compat.create.CreateContraptionBoundaryLifecycle;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.CreateAssemblyPlacementContext;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reproduces a stable lit 2x2x2 cube losing its rear layer only after a rotated Create stop. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RotatedDockLampStateGameTests {
    private RotatedDockLampStateGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void eastStopKeepsPreviouslyLitRearLayerLit(GameTestHelper helper) {
        exercise(helper, Direction.EAST);
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void westStopKeepsPreviouslyLitRearLayerLit(GameTestHelper helper) {
        exercise(helper, Direction.WEST);
    }

    private static void exercise(GameTestHelper helper, Direction targetFacing) {
        ServerLevel level = helper.getLevel();
        BlockPos frame = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockState frameState = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(frame, frameState, Block.UPDATE_ALL), "could not place source Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(frame).orElseThrow();
        UUID id = assembly.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed child");

        List<BlockPos> lamps = new ArrayList<>(8);
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            BlockPos local = MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z);
            BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
            BlockState lit = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true);
            check(MiniWorldEnvironment.withVirtualReads(() -> level.setBlock(global, lit, Block.UPDATE_ALL)),
                    "could not seed lit mini lamp at " + local);
            lamps.add(global);
        }

        Direction logicalSource = Direction.EAST;
        Direction sourcePhysical = assembly.orientation().toPhysical(logicalSource);
        BlockPos sourceLeverPos = frame.relative(sourcePhysical);
        BlockState sourceLever = poweredWallLever(sourcePhysical);
        check(level.setBlock(sourceLeverPos, sourceLever, Block.UPDATE_ALL), "could not place source lever");
        MiniWorldEnvironment.parentBlockChanged(level, sourceLeverPos);
        for (BlockPos lamp : lamps) {
            check(MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(lamp)),
                    "seeded lamp does not actually see power before Create at " + lamp);
            check(level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                    "seeded lamp was not lit before Create at " + lamp);
        }

        check(manager.prepareContraptionMoves(
                        level,
                        Map.of(id, Set.of(frame)),
                        Map.of(id, Map.of(sourceLeverPos, sourceLever)),
                        BlockPos.ZERO,
                        false),
                "capture preflight failed");
        CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(id));
        check(level.removeBlock(frame, false), "could not remove source Frame");
        check(level.removeBlock(sourceLeverPos, false), "could not remove source lever");

        FrameOrientation targetOrientation = new FrameOrientation(Direction.UP, targetFacing);
        Quaterniond q = targetOrientation.quaternion(new Quaterniond());
        AssemblyPose targetPose = new AssemblyPose(
                frame.getX() + .5, frame.getY() + .5, frame.getZ() + .5,
                q.x, q.y, q.z, q.w);
        Map<UUID, Set<BlockPos>> targets = Map.of(id, Set.of(frame));
        Map<UUID, BlockPos> origins = Map.of(id, frame);
        Map<UUID, AssemblyPose> poses = Map.of(id, targetPose);
        check(manager.prepareContraptionPlacement(level, targets, origins, poses), "placement preflight failed");

        Direction targetPhysical = targetOrientation.toPhysical(logicalSource);
        BlockPos targetLeverPos = frame.relative(targetPhysical);
        BlockState targetLever = poweredWallLever(targetPhysical);
        BlockState targetFrame = frameState
                .setValue(BlockStateProperties.HORIZONTAL_FACING, targetFacing)
                .setValue(MechanismFrameBlock.EMPTY, false);

        int depth = CreateAssemblyPlacementContext.depth();
        CreateAssemblyPlacementContext.begin(level, targets, origins, poses);
        try {
            check(level.setBlock(frame, targetFrame, Block.UPDATE_ALL), "could not restore rotated Frame");
            check(level.setBlock(targetLeverPos, targetLever, Block.UPDATE_ALL), "could not restore rotated lever");
            check(manager.finalizeContraptionPlacement(level, Set.of(id)), "rotated placement commit failed");
        } finally {
            CreateAssemblyPlacementContext.restoreDepth(depth);
        }

        helper.runAfterDelay(12, () -> {
            for (BlockPos lamp : lamps) {
                check(MiniWorldEnvironment.withVirtualReads(() -> level.hasNeighborSignal(lamp)),
                        "lamp lost actual power after " + targetFacing + " docking at " + lamp);
                check(level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                        "powered lamp went dark after " + targetFacing + " docking at " + lamp);
            }
            helper.succeed();
        });
    }

    private static BlockState poweredWallLever(Direction physicalFace) {
        return Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, physicalFace)
                .setValue(BlockStateProperties.POWERED, true);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
