package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.compat.create.CreateContraptionBoundaryLifecycle;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.CreateAssemblyPlacementContext;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regression for preserving a previously lit cube through a rotated Create stop. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RotatedDockLampStateGameTests {
    private RotatedDockLampStateGameTests() {}

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void eastStopKeepsPreviouslyLitRearLayerLit(GameTestHelper helper) { exercise(helper, Direction.EAST); }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void westStopKeepsPreviouslyLitRearLayerLit(GameTestHelper helper) { exercise(helper, Direction.WEST); }

    private static void exercise(GameTestHelper helper, Direction targetFacing) {
        ServerLevel level = helper.getLevel();
        BlockPos frame = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockState sourceFrame = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        helper.assertTrue(level.setBlock(frame, sourceFrame, Block.UPDATE_ALL), "could not place source Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(frame).orElseThrow();
        UUID id = assembly.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        helper.assertTrue(child != null && !child.isRemoved(), "could not materialize managed child");

        List<BlockPos> locals = new ArrayList<>(8);
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            BlockPos physical = new BlockPos(x, y, z);
            BlockPos local = MiniCoordinateMapper.physicalFrameCellToMini(assembly, frame, x, y, z);
            seedManagedMiniLamp(child, local);
            locals.add(local);
        }
        child.getPlot().updateBoundingBox();
        manager.refreshFrame(level, frame);
        helper.assertFalse(MechanismSubLevelService.isPhysicallyEmpty(child),
                "player mini placement route left seeded child empty");
        helper.assertTrue(level.getBlockEntity(frame) instanceof MechanismFrameBlockEntity,
                "Frame block entity missing");
        MechanismFrameBlockEntity frameBlockEntity = (MechanismFrameBlockEntity) level.getBlockEntity(frame);
        helper.assertTrue(frameBlockEntity.getOccupiedMask() == 0xFF,
                "player mini placement route did not synchronize full Frame occupancy");
        helper.assertFalse(level.getBlockState(frame).getValue(MechanismFrameBlock.EMPTY),
                "player mini placement route left populated Frame marked empty");
        forceLampsLit(child, locals);

        Direction logicalSource = Direction.EAST;
        Direction sourcePhysical = assembly.orientation().toPhysical(logicalSource);
        BlockPos sourceLeverPos = frame.relative(sourcePhysical);
        BlockState sourceLever = poweredWallLever(sourcePhysical);
        level.setBlock(sourceLeverPos, sourceLever, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        helper.assertTrue(level.getBlockState(sourceLeverPos).is(Blocks.LEVER), "source lever fixture missing");
        assertAllLit(helper, child, locals, "before Create");

        helper.assertTrue(manager.prepareContraptionMoves(level, Map.of(id, Set.of(frame)),
                        Map.of(id, Map.of(sourceLeverPos, sourceLever)), BlockPos.ZERO, false), "capture preflight failed");
        CreateContraptionBoundaryLifecycle.disconnect(level, Set.of(id));
        helper.assertTrue(level.removeBlock(frame, false), "could not remove source Frame");
        level.removeBlock(sourceLeverPos, false);
        assertAllLit(helper, child, locals, "while captured");

        FrameOrientation targetOrientation = new FrameOrientation(Direction.UP, targetFacing);
        Quaterniond q = targetOrientation.quaternion(new Quaterniond());
        AssemblyPose targetPose = new AssemblyPose(frame.getX() + .5, frame.getY() + .5, frame.getZ() + .5,
                q.x, q.y, q.z, q.w);
        Map<UUID, Set<BlockPos>> targets = Map.of(id, Set.of(frame));
        Map<UUID, BlockPos> origins = Map.of(id, frame);
        Map<UUID, AssemblyPose> poses = Map.of(id, targetPose);
        helper.assertTrue(manager.prepareContraptionPlacement(level, targets, origins, poses), "placement preflight failed");

        Direction targetPhysical = targetOrientation.toPhysical(logicalSource);
        BlockPos targetLeverPos = frame.relative(targetPhysical);
        BlockState targetLever = poweredWallLever(targetPhysical);
        BlockState targetFrame = sourceFrame.setValue(BlockStateProperties.HORIZONTAL_FACING, targetFacing)
                .setValue(MechanismFrameBlock.EMPTY, false);

        int depth = CreateAssemblyPlacementContext.depth();
        CreateAssemblyPlacementContext.begin(level, targets, origins, poses);
        try {
            level.setBlock(frame, targetFrame, Block.UPDATE_ALL);
            level.setBlock(targetLeverPos, targetLever, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            helper.assertTrue(manager.finalizeContraptionPlacement(level, Set.of(id)), "rotated placement commit failed");
        } finally {
            CreateAssemblyPlacementContext.restoreDepth(depth);
        }

        helper.runAfterDelay(12, () -> {
            assertAllLit(helper, child, locals, "after " + targetFacing + " docking");
            helper.succeed();
        });
    }

    private static void seedManagedMiniLamp(ServerSubLevel child, BlockPos local) {
        BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
        BlockState before = child.getPlot().getEmbeddedLevelAccessor().getBlockState(global);
        if (!before.canBeReplaced()) {
            throw new AssertionError("managed mini fixture target already contains " + before + " at " + local);
        }
        BlockState lamp = Blocks.REDSTONE_LAMP.defaultBlockState();
        boolean changed = child.getPlot().getEmbeddedLevelAccessor().setBlock(global, lamp, Block.UPDATE_ALL);
        if (!changed && !child.getPlot().getEmbeddedLevelAccessor().getBlockState(global).equals(lamp)) {
            throw new AssertionError("could not seed managed mini lamp at " + local);
        }
    }

    private static void forceLampsLit(ServerSubLevel child, List<BlockPos> locals) {
        for (BlockPos local : locals) {
            BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
            BlockState current = child.getPlot().getEmbeddedLevelAccessor().getBlockState(global);
            if (!current.is(Blocks.REDSTONE_LAMP)) {
                throw new AssertionError("cannot light missing mini lamp at " + local);
            }
            BlockState lit = current.setValue(BlockStateProperties.LIT, true);
            if (!current.equals(lit)
                    && !child.getPlot().getEmbeddedLevelAccessor().setBlock(global, lit, Block.UPDATE_ALL)
                    && !child.getPlot().getEmbeddedLevelAccessor().getBlockState(global).equals(lit)) {
                throw new AssertionError("could not establish lit mini lamp at " + local);
            }
        }
    }

    private static BlockState poweredWallLever(Direction face) {
        return Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, face).setValue(BlockStateProperties.POWERED, true);
    }

    private static void assertAllLit(GameTestHelper helper, ServerSubLevel child, List<BlockPos> locals, String phase) {
        helper.assertFalse(child.isRemoved(), "managed child removed " + phase);
        for (BlockPos local : locals) {
            BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
            BlockState state = child.getPlot().getEmbeddedLevelAccessor().getBlockState(global);
            helper.assertTrue(state.is(Blocks.REDSTONE_LAMP), "mini lamp disappeared " + phase + " at " + local);
            helper.assertTrue(state.getValue(BlockStateProperties.LIT), "mini lamp went dark " + phase + " at " + local);
        }
    }
}
