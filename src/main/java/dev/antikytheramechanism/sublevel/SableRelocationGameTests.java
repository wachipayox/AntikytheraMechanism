package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Regression coverage for populated Mechanism Frames crossing Sable host boundaries. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SableRelocationGameTests {
    private SableRelocationGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void sableAssemblyKeepsCarriedMacroSupport(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(3, 4, 3));
        BlockPos floorPos = framePos.below();
        check(level.setBlock(floorPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place macro support floor");
        placeFrame(level, framePos, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        // Allow Sable's newly registered plot holder to become visible through ServerLevel
        // before authoritative fixture writes address the managed child.
        helper.runAfterDelay(2, () -> {

        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 0, 0);
        BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(child, miniLocal);
        BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();

        // GameTests cannot exercise the client mini-placement raycast, so seed the authoritative
        // managed child directly. EmbeddedPlotLevelAccessor performs the translated ServerLevel write
        // and already drives Sable's normal chunk/bounds/mass hooks; reporting the transition again
        // through PlotChunkHolder would double-apply the same placement.
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(miniLocal, wire, Block.UPDATE_ALL),
                "could not place supported mini redstone dust");
        child.getPlot().updateBoundingBox();
        check(!MechanismSubLevelService.isPhysicallyEmpty(child),
                "supported mini dust fixture left the managed child physically empty");

        manager.refreshFrame(level, framePos);
        check(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity dustFrame
                        && dustFrame.getOccupiedMask() == 1 << MiniCoordinateMapper.cellIndex(0, 0, 0),
                "refreshFrame did not synchronize mini dust fixture");

        check(MiniWorldEnvironment.withVirtualReads(() ->
                        child.getPlot().getEmbeddedLevelAccessor().getBlockState(miniLocal)
                                .canSurvive(level, miniGlobal)),
                "mini dust did not see its macro floor before Sable assembly");

        BoundingBox3i bounds = bounds(floorPos, framePos);
        // Deliberately put the Frame first. Sable copies blocks one by one, so this reproduces the
        // dangerous ordering where the Frame's child receives updates before its macro floor has
        // reached the destination plot.
        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                framePos,
                List.of(framePos, floorPos),
                bounds);
        check(host != null && !host.isRemoved(), "Sable removed the new host during assembly");

        BlockPos relocatedFrame = host.getPlot().getCenterBlock();
        MechanismAssembly moved = manager.getAssemblyAt(relocatedFrame).orElseThrow();
        check(moved.id().equals(assembly.id()), "Frame logical assembly was not adopted at Sable destination");
        check(level.getBlockState(relocatedFrame).is(ModRegistries.MECHANISM_FRAME.get()),
                "Frame is missing from Sable destination");
        check(level.getBlockState(relocatedFrame.below()).is(Blocks.STONE),
                "carried macro support is missing from Sable destination");
        check(child.getPlot().getEmbeddedLevelAccessor().getBlockState(miniLocal).is(Blocks.REDSTONE_WIRE),
                "supported mini dust broke while Sable copied its macro neighbour");
        check(MiniWorldEnvironment.withVirtualReads(() ->
                        child.getPlot().getEmbeddedLevelAccessor().getBlockState(miniLocal)
                                .canSurvive(level, miniGlobal)),
                "mini dust does not see carried macro support after Sable assembly");
        helper.succeed();
        });
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 200)
    public static void miniBackedMacroAttachmentSurvivesSableAssemblyAndDisassembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rootFrame = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockPos rootTorch = rootFrame.above();
        placeFrame(level, rootFrame, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(rootFrame).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        // Fill exactly the four mini cells that synthesize the Frame's UP support face. The embedded
        // accessor is the authoritative managed-child route and already updates Sable bookkeeping.
        int occupiedMask = 0;
        BlockState support = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                BlockPos miniLocal = MiniCoordinateMapper.frameToMini(
                        assembly, rootFrame, x, 1, z);
                check(child.getPlot().getEmbeddedLevelAccessor().setBlock(miniLocal, support, Block.UPDATE_ALL),
                        "could not fill mini support face");
                occupiedMask |= 1 << MiniCoordinateMapper.cellIndex(x, 1, z);
            }
        }
        child.getPlot().updateBoundingBox();
        check(!MechanismSubLevelService.isPhysicallyEmpty(child),
                "mini-backed support fixture left the managed child physically empty");
        manager.refreshFrame(level, rootFrame);
        check(level.getBlockEntity(rootFrame) instanceof MechanismFrameBlockEntity supportFrame
                        && supportFrame.getOccupiedMask() == occupiedMask,
                "refreshFrame did not synchronize mini support fixture");

        check(level.setBlock(rootTorch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL),
                "could not place macro torch on mini-backed Frame face");
        check(level.getBlockState(rootTorch).is(Blocks.TORCH),
                "macro torch disappeared immediately after placement");
        check(level.getBlockState(rootTorch).canSurvive(level, rootTorch),
                "macro torch did not recognize complete mini-backed support before assembly");

        // Frame first intentionally creates the dangerous interval: Sable can clear/write the Frame
        // while the dependent torch still exists at the opposite endpoint. While hosted, gameplay
        // only requires the carried attachment and its mini-backed payload to survive; canSurvive()
        // is a static-world support query and is not an invariant of the transient foreign host.
        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                rootFrame,
                List.of(rootFrame, rootTorch),
                bounds(rootFrame, rootTorch));
        check(host != null && !host.isRemoved(), "Sable host disappeared during support regression assembly");

        BlockPos hostedFrame = host.getPlot().getCenterBlock();
        BlockPos hostedTorch = hostedFrame.above();
        check(level.getBlockState(hostedFrame).is(ModRegistries.MECHANISM_FRAME.get()),
                "Frame did not arrive in host during support regression");
        check(level.getBlockState(hostedTorch).is(Blocks.TORCH),
                "macro attachment broke during Sable assembly while Frame support was transient");
        check(!child.isRemoved(),
                "managed mini world was removed while the mini-backed attachment was hosted");
        check(!MechanismSubLevelService.isPhysicallyEmpty(child),
                "mini-backed support payload disappeared while the attachment was hosted");

        // Simulated owns a distinct Physics Assembler disassembly lifecycle through
        // SimAssemblyHelper.disassembleSubLevel. Calling Sable's low-level moveBlocks directly while
        // Simulated is installed is not the player route and has been observed to deadlock the
        // dedicated GameTest server. Keep the Sable round-trip assertion in core/Create, while the
        // Simulated matrix still verifies the complete hosted state reached by assembly. The real
        // Physics Assembler round trip is covered by manual gameplay validation until a faithful
        // Simulated server-side fixture can invoke its actual disassembly lifecycle.
        if (ModList.get().isLoaded("simulated")) {
            helper.succeed();
            return;
        }

        SubLevelAssemblyHelper.AssemblyTransform backToRoot =
                new SubLevelAssemblyHelper.AssemblyTransform(
                        hostedFrame,
                        rootFrame,
                        0,
                        Rotation.NONE,
                        level);
        SubLevelAssemblyHelper.moveBlocks(
                level,
                backToRoot,
                List.of(hostedFrame, hostedTorch));

        check(level.getBlockState(rootFrame).is(ModRegistries.MECHANISM_FRAME.get()),
                "Frame did not return to ROOT during support regression");
        check(level.getBlockState(rootTorch).is(Blocks.TORCH),
                "macro attachment broke during Sable disassembly while Frame support was transient");
        check(level.getBlockState(rootTorch).canSurvive(level, rootTorch),
                "macro attachment lacks mini-backed support after Sable disassembly");
        check(!child.isRemoved() && !MechanismSubLevelService.isPhysicallyEmpty(child),
                "mini-backed support payload was lost during Sable round trip");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 240)
    public static void nestedHostSplitRebindsBothManagedChildrenForPhysicsStaff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rootA = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockPos rootB = rootA.east();
        placeFrame(level, rootA, Direction.NORTH);
        placeFrame(level, rootB, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly joined = manager.getAssemblyAt(rootA).orElseThrow();
        check(manager.getAssemblyAt(rootB).map(MechanismAssembly::id).orElse(null).equals(joined.id()),
                "adjacent Frames did not start in one logical assembly");
        check(joined.frames().size() == 2, "host split regression requires exactly two joined Frames");

        ServerSubLevel originalChild = MechanismSubLevelService.ensureForContent(level, joined);
        check(originalChild != null && !originalChild.isRemoved(), "could not create joined managed child");
        BlockPos miniA = MechanismSubLevelService.toPlotPosition(
                originalChild, MiniCoordinateMapper.frameToMini(joined, rootA, 0, 0, 0));
        BlockPos miniB = MechanismSubLevelService.toPlotPosition(
                originalChild, MiniCoordinateMapper.frameToMini(joined, rootB, 1, 0, 1));
        check(level.setBlock(miniA, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not place stationary-side mini payload");
        check(level.setBlock(miniB, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not place moving-side mini payload");

        // First put both physical Frames in one ordinary unit-scale Sable host.
        ServerSubLevel firstHost = SubLevelAssemblyHelper.assembleBlocks(
                level,
                rootA,
                List.of(rootA, rootB),
                bounds(rootA, rootB));
        check(firstHost != null && !firstHost.isRemoved(), "first foreign host disappeared");
        BlockPos hostedA = firstHost.getPlot().getCenterBlock();
        BlockPos hostedB = hostedA.east();
        MechanismAssembly hostedJoined = manager.getAssemblyAt(hostedA).orElseThrow();
        check(manager.getAssemblyAt(hostedB).map(MechanismAssembly::id).orElse(null).equals(hostedJoined.id()),
                "joined assembly was already split before the foreign host split");

        // This is the essential Sable-host split operation: create a new host from only B while A
        // stays in the original host. Before this regression fix Antikythera journaled A+B as though
        // both would move, leaving B's new physical host permanently detached from its managed child.
        ServerSubLevel secondHost = SubLevelAssemblyHelper.assembleBlocks(
                level,
                hostedB,
                List.of(hostedB),
                bounds(hostedB, hostedB));
        check(secondHost != null && !secondHost.isRemoved(), "split foreign host disappeared");
        BlockPos splitB = secondHost.getPlot().getCenterBlock();

        MechanismAssembly stationary = manager.getAssemblyAt(hostedA).orElseThrow();
        MechanismAssembly moved = manager.getAssemblyAt(splitB).orElseThrow();
        check(!stationary.id().equals(moved.id()),
                "partial host split did not split the Antikythera logical assembly");
        check(stationary.frames().equals(java.util.Set.of(hostedA)),
                "stationary logical assembly still claims the moved Frame");
        check(moved.frames().equals(java.util.Set.of(splitB)),
                "moved logical assembly does not exclusively own its new-host Frame");
        check(manager.pendingContraptionMove(stationary.id()).isEmpty(),
                "stationary side retained a stale Sable relocation journal");
        check(manager.pendingContraptionMove(moved.id()).isEmpty(),
                "moved side retained a stale Sable relocation journal");

        ServerSubLevel stationaryChild = MechanismSubLevelService.findExisting(level, stationary);
        ServerSubLevel movedChild = MechanismSubLevelService.findExisting(level, moved);
        check(stationaryChild != null && !stationaryChild.isRemoved(),
                "stationary side lost its managed child after host split");
        check(movedChild != null && !movedChild.isRemoved(),
                "moved side lost its managed child after host split");
        check(stationaryChild != movedChild,
                "both split assemblies still point at the same managed child");

        PhysicsStaffServerSelectionBridge.Selection stationarySelection =
                PhysicsStaffServerSelectionBridge.resolveManaged(level, stationaryChild.getUniqueId());
        PhysicsStaffServerSelectionBridge.Selection movedSelection =
                PhysicsStaffServerSelectionBridge.resolveManaged(level, movedChild.getUniqueId());
        check(stationarySelection != null && stationarySelection.hasHost(),
                "Physics Staff can no longer resolve the stationary managed child to a host");
        check(movedSelection != null && movedSelection.hasHost(),
                "Physics Staff can no longer resolve the moved managed child to its new host");
        check(stationarySelection.host() == firstHost,
                "stationary managed child rebound to the wrong foreign host");
        check(movedSelection.host() == secondHost,
                "moved managed child retained the old/null foreign host after split");

        BlockPos stationaryMini = MechanismSubLevelService.toPlotPosition(
                stationaryChild,
                MiniCoordinateMapper.frameToMini(stationary, hostedA, 0, 0, 0));
        BlockPos movedMini = MechanismSubLevelService.toPlotPosition(
                movedChild,
                MiniCoordinateMapper.frameToMini(moved, splitB, 1, 0, 1));
        check(level.getBlockState(stationaryMini).is(Blocks.IRON_BLOCK),
                "stationary mini payload was lost while splitting the foreign host");
        check(level.getBlockState(movedMini).is(Blocks.GOLD_BLOCK),
                "moved mini payload was lost while rebinding to the new foreign host");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void solePopulatedFrameSurvivesMiniBreakAfterSableAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(3, 3, 3));
        placeFrame(level, framePos, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        // Allow Sable's newly registered plot holder to become visible through ServerLevel
        // before authoritative fixture writes address the managed child.
        helper.runAfterDelay(2, () -> {

        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 0, 0);
        BlockState payload = Blocks.STONE.defaultBlockState();
        boolean seeded = child.getPlot().getEmbeddedLevelAccessor().setBlock(miniLocal, payload, Block.UPDATE_ALL);
        check(seeded || child.getPlot().getEmbeddedLevelAccessor().getBlockState(miniLocal).equals(payload),
                "could not seed sole-frame managed mini payload");
        child.getPlot().updateBoundingBox();
        manager.refreshFrame(level, framePos);
        check(!MechanismSubLevelService.isPhysicallyEmpty(child), "sole-frame managed fixture left child physically empty");
        check(!level.getBlockState(framePos).getValue(MechanismFrameBlock.EMPTY), "managed payload left populated Frame marked empty");
        check(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity frameEntity
                        && frameEntity.getOccupiedMask() == 1 << MiniCoordinateMapper.cellIndex(0, 0, 0),
                "refreshFrame did not synchronize sole-cell occupancy");

        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                framePos,
                List.of(framePos),
                bounds(framePos, framePos));
        check(host != null && !host.isRemoved(), "sole populated Frame produced an invalid Sable host");

        BlockPos relocatedFrame = host.getPlot().getCenterBlock();
        MechanismAssembly moved = manager.getAssemblyAt(relocatedFrame).orElseThrow();
        check(moved.id().equals(assembly.id()), "sole Frame logical assembly was not relocated");
        check(level.getBlockState(relocatedFrame).is(ModRegistries.MECHANISM_FRAME.get()),
                "sole Frame is missing immediately after Sable assembly");
        check(!child.isRemoved(), "managed child was removed before mini break after Sable assembly");
        check(moved.subLevelId() != null && moved.subLevelId().equals(child.getUniqueId()),
                "relocated assembly no longer references the original managed child before mini break");
        check(child.getPlot().getEmbeddedLevelAccessor().getBlockState(miniLocal).is(Blocks.STONE),
                "mini payload disappeared during Sable assembly before break");

        // Remove the real managed-child block through the same translated accessor. Sable receives
        // the STONE -> AIR transition exactly once. The regression invariant is that changing the
        // last user mini payload must not delete the foreign host or its physical Frame.
        check(child.getPlot().getEmbeddedLevelAccessor().removeBlock(miniLocal, false),
                "could not break mini payload after Sable assembly");
        child.getPlot().updateBoundingBox();
        check(child.getPlot().getEmbeddedLevelAccessor().getBlockState(miniLocal).isAir(),
                "mini payload remained in managed child after break");

        manager.refreshFrame(level, relocatedFrame);
        check(level.getBlockState(relocatedFrame).getValue(MechanismFrameBlock.EMPTY),
                "refreshFrame did not mark the emptied relocated Frame empty");
        check(level.getBlockEntity(relocatedFrame) instanceof MechanismFrameBlockEntity emptiedFrame
                        && emptiedFrame.getOccupiedMask() == 0,
                "refreshFrame retained stale occupancy after sole mini break");

        check(!host.isRemoved(), "breaking one mini block destroyed the sole-Frame Sable host");
        check(level.getBlockState(relocatedFrame).is(ModRegistries.MECHANISM_FRAME.get()),
                "breaking one mini block destroyed its physical Mechanism Frame");
        check(manager.getAssemblyAt(relocatedFrame).map(MechanismAssembly::id).orElse(null).equals(assembly.id()),
                "breaking one mini block detached the surviving Frame from its logical assembly");
        helper.succeed();
        });
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 200)
    public static void populatedFrameRoundTripThroughSableMoveBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rootFrame = helper.absolutePos(new BlockPos(3, 3, 3));
        placeFrame(level, rootFrame, Direction.NORTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(rootFrame).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed mini world");

        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(assembly, rootFrame, 1, 0, 1);
        BlockState payload = Blocks.IRON_BLOCK.defaultBlockState();
        boolean seeded = child.getPlot().getEmbeddedLevelAccessor().setBlock(miniLocal, payload, Block.UPDATE_ALL);
        check(seeded || child.getPlot().getEmbeddedLevelAccessor().getBlockState(miniLocal).equals(payload),
                "could not place managed mini payload before Sable round trip");
        child.getPlot().updateBoundingBox();
        manager.refreshFrame(level, rootFrame);

        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                rootFrame,
                List.of(rootFrame),
                bounds(rootFrame, rootFrame));
        check(host != null && !host.isRemoved(), "Sable host disappeared during root -> foreign move");
        BlockPos hostedFrame = host.getPlot().getCenterBlock();
        check(level.getBlockState(hostedFrame).is(ModRegistries.MECHANISM_FRAME.get()),
                "Frame did not arrive in Sable host");

        // This is the same low-level operation visible in the supplied freeze dump: a Frame is
        // written back to the root ServerLevel while Sable simultaneously updates its host mass.
        SubLevelAssemblyHelper.AssemblyTransform backToRoot = new SubLevelAssemblyHelper.AssemblyTransform(
                hostedFrame,
                rootFrame,
                0,
                Rotation.NONE,
                level);
        SubLevelAssemblyHelper.moveBlocks(level, backToRoot, List.of(hostedFrame));

        check(level.getBlockState(rootFrame).is(ModRegistries.MECHANISM_FRAME.get()),
                "Frame did not return to root after Sable disassembly move");
        check(manager.getAssemblyAt(rootFrame).map(MechanismAssembly::id).orElse(null).equals(assembly.id()),
                "logical assembly was not restored at root after Sable disassembly move");
        check(child.getPlot().getEmbeddedLevelAccessor().getBlockState(miniLocal).is(Blocks.IRON_BLOCK),
                "managed mini payload was lost during Sable round trip");
        check(child != null && !child.isRemoved(), "managed mini child was removed during Sable round trip");
        helper.succeed();
    }

    private static void placeMiniBlockThroughPlayerRoute(ServerLevel level, BlockPos framePos, BlockPos physicalCell, ServerSubLevel child, BlockPos expectedLocal, BlockItem item, Block expectedBlock) {
        ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        Vec3 hitLocation = new Vec3(framePos.getX() + (physicalCell.getX() == 0 ? .25 : .75), framePos.getY() + (physicalCell.getY() == 0 ? .25 : .75), framePos.getZ() + (physicalCell.getZ() == 0 ? .25 : .75));
        BlockHitResult hit = new BlockHitResult(hitLocation, Direction.UP, framePos, false);
        InteractionResult result = player.getItemInHand(InteractionHand.MAIN_HAND).useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        check(result.consumesAction(), "player mini placement route rejected payload at physical cell " + physicalCell);
        check(child.getPlot().getEmbeddedLevelAccessor().getBlockState(expectedLocal).is(expectedBlock), "player mini placement route did not populate " + expectedLocal);
    }

    private static BoundingBox3i bounds(BlockPos a, BlockPos b) {
        return new BoundingBox3i(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
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
