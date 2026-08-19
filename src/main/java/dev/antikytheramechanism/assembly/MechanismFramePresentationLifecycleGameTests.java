package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Presentation persistence coverage for the relocation transactions shared by Create and Sable. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MechanismFramePresentationLifecycleGameTests {
    private MechanismFramePresentationLifecycleGameTests() {}

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 180)
    public static void sablePartialPartitionCopiesPresentationAndAssembliesRemainIndependent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockPos middle = first.east();
        BlockPos endpoint = middle.east();
        placeFrame(level, first);
        placeFrame(level, middle);
        placeFrame(level, endpoint);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly joined = manager.getAssemblyAt(first).orElseThrow();
        UUID originalId = joined.id();
        check(manager.setFrameShellMode(level, middle, FrameShellMode.GLASS), "could not prepare GLASS partial-Sable fixture");
        check(manager.setFrameSkin(level, middle, FrameSkin.RAILWAY_CASING), "could not prepare RAILWAY skin partial-Sable fixture");

        check(manager.partitionPartialAssembliesForSableMove(level, List.of(endpoint)),
                "partial Sable pre-partition failed");
        MechanismAssembly retained = manager.getAssemblyAt(first).orElseThrow();
        MechanismAssembly moved = manager.getAssemblyAt(endpoint).orElseThrow();
        check(retained.id().equals(originalId), "retained larger component lost survivor UUID during Sable partition");
        check(!retained.id().equals(moved.id()), "partial Sable move did not create an independent assembly");
        check(retained.frames().equals(Set.of(first, middle)), "retained Sable partition component is incorrect");
        check(moved.frames().equals(Set.of(endpoint)), "moved Sable partition component is incorrect");
        check(retained.shellMode() == FrameShellMode.GLASS && moved.shellMode() == FrameShellMode.GLASS,
                "Sable partition did not copy shell mode to both results");
        check(retained.skin() == FrameSkin.RAILWAY_CASING && moved.skin() == FrameSkin.RAILWAY_CASING,
                "Sable partition did not copy skin to both results");

        check(manager.setFrameShellMode(level, endpoint, FrameShellMode.NORMAL), "could not mutate partitioned assembly mode");
        check(manager.setFrameSkin(level, endpoint, FrameSkin.COPPER), "could not mutate partitioned assembly skin");
        check(retained.shellMode() == FrameShellMode.GLASS && retained.skin() == FrameSkin.RAILWAY_CASING,
                "independent assembly presentation leaked across assembly UUIDs");
        helper.succeed();
    }

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 220)
    public static void contraptionRelocationJournalPreservesPresentationUuidChildAndMiniPayload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockPos target = source.east(4);
        placeFrame(level, source);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(source).orElseThrow();
        UUID id = assembly.id();
        check(manager.setFrameShellMode(level, source, FrameShellMode.GLASS), "could not set GLASS before relocation");
        check(manager.setFrameSkin(level, source, FrameSkin.BRASS_CASING), "could not set BRASS before relocation");

        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not create child before relocation");
        UUID childId = child.getUniqueId();
        BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, source, 1, 1, 1);
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(mini, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed mini payload before relocation");
        child.getPlot().updateBoundingBox();

        BlockState carriedState = level.getBlockState(source);
        check(manager.prepareContraptionMoves(level, Map.of(id, Set.of(source)), BlockPos.ZERO, false),
                "could not journal relocation source");
        check(level.removeBlock(source, false), "could not remove journaled source Frame");
        check(manager.getAssembly(id).isPresent(), "journaled extraction deleted assembly authority");

        Map<UUID, Set<BlockPos>> targets = Map.of(id, Set.of(target));
        Map<UUID, BlockPos> origins = Map.of(id, target);
        Map<UUID, AssemblyPose> poses = Map.of(id, AssemblyPose.identityAt(target));
        check(manager.prepareContraptionPlacement(level, targets, origins, poses), "could not journal relocation destination");
        int depth = CreateAssemblyPlacementContext.depth();
        CreateAssemblyPlacementContext.begin(level, targets, origins, poses);
        try {
            check(level.setBlock(target, carriedState, Block.UPDATE_ALL), "could not place carried Frame state");
            check(manager.finalizeContraptionPlacement(level, Set.of(id)), "could not commit relocation placement");
        } finally {
            CreateAssemblyPlacementContext.restoreDepth(depth);
        }

        MechanismAssembly relocated = manager.getAssemblyAt(target).orElseThrow();
        check(relocated.id().equals(id), "relocation changed assembly UUID");
        check(relocated.frames().equals(Set.of(target)) && relocated.origin().equals(target),
                "relocation did not update physical Frame mapping");
        check(relocated.shellMode() == FrameShellMode.GLASS && relocated.skin() == FrameSkin.BRASS_CASING,
                "relocation lost presentation authority");
        check(level.getBlockState(target).getValue(MechanismFrameBlock.SHELL_MODE) == FrameShellMode.GLASS,
                "relocation did not restore shell mode to destination BlockState");
        check(level.getBlockEntity(target) instanceof MechanismFrameBlockEntity,
                "relocation lost destination Frame BlockEntity");
        MechanismFrameBlockEntity frame = (MechanismFrameBlockEntity) level.getBlockEntity(target);
        check(frame.getPresentationSkin() == FrameSkin.BRASS_CASING,
                "relocation did not restore skin render cache");
        check(id.equals(frame.getAssemblyId()), "relocation destination Frame has wrong assembly UUID");

        ServerSubLevel afterChild = MechanismSubLevelService.findExisting(level, relocated);
        check(afterChild != null && childId.equals(afterChild.getUniqueId()), "relocation recreated/replaced child SubLevel");
        BlockPos relocatedMini = MiniCoordinateMapper.frameToMini(relocated, target, 1, 1, 1);
        check(afterChild.getPlot().getEmbeddedLevelAccessor().getBlockState(relocatedMini).is(Blocks.DIAMOND_BLOCK),
                "relocation lost or remapped mini payload");
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
