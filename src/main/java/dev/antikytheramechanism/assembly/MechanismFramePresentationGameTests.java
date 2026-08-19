package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.mixin.MechanismAssemblyManagerAccessor;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.ManagedFrameMassPolicy;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.UUID;

/** Core presentation/collision/topology coverage; contains no direct optional-Create references. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MechanismFramePresentationGameTests {
    private MechanismFramePresentationGameTests() {}

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 160)
    public static void modeAndSkinPropagateWithoutChangingAssemblyOrMiniPayload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockPos second = first.east();
        BlockPos third = second.east();
        placeFrame(level, first);
        placeFrame(level, second);
        placeFrame(level, third);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(first).orElseThrow();
        UUID assemblyId = assembly.id();
        check(assembly.frames().equals(Set.of(first, second, third)), "multi-hop fixture did not form one assembly");

        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not create managed child for presentation identity test");
        UUID childId = child.getUniqueId();
        BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, second, 1, 0, 1);
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(mini, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed mini payload");
        child.getPlot().updateBoundingBox();

        check(manager.setFrameSkin(level, first, FrameSkin.BRASS_CASING), "could not apply BRASS skin");
        assertPresentation(level, manager, assemblyId, Set.of(first, second, third), FrameShellMode.NORMAL, FrameSkin.BRASS_CASING);
        check(manager.setFrameShellMode(level, third, FrameShellMode.GLASS), "could not enter GLASS from remote Frame");
        assertPresentation(level, manager, assemblyId, Set.of(first, second, third), FrameShellMode.GLASS, FrameSkin.BRASS_CASING);
        check(manager.setFrameShellMode(level, second, FrameShellMode.HIDDEN), "could not enter HIDDEN");
        assertPresentation(level, manager, assemblyId, Set.of(first, second, third), FrameShellMode.HIDDEN, FrameSkin.BRASS_CASING);
        check(manager.setFrameShellMode(level, first, FrameShellMode.NORMAL), "could not return to NORMAL");
        assertPresentation(level, manager, assemblyId, Set.of(first, second, third), FrameShellMode.NORMAL, FrameSkin.BRASS_CASING);

        MechanismAssembly after = manager.getAssembly(assemblyId).orElseThrow();
        ServerSubLevel afterChild = MechanismSubLevelService.findExisting(level, after);
        check(afterChild != null && childId.equals(afterChild.getUniqueId()), "presentation recreated or replaced child SubLevel");
        check(afterChild.getPlot().getEmbeddedLevelAccessor().getBlockState(mini).is(Blocks.STONE),
                "presentation changed mini payload or mapping");
        helper.succeed();
    }

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 100)
    public static void hiddenAndGlassCollisionShapesMatchShellContract(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lone = helper.absolutePos(new BlockPos(3, 3, 3));
        placeFrame(level, lone);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);

        BlockState normal = level.getBlockState(lone);
        VoxelShape normalShape = normal.getCollisionShape(level, lone);
        double normalMass = ManagedFrameMassPolicy.effectiveFrameMass(level, lone);
        check(!normalShape.isEmpty(), "NORMAL lost its cage collider");
        check(Math.abs(normalMass - ManagedFrameMassPolicy.FRAME_SHELL_MASS) < 1.0e-12,
                "NORMAL effective Frame mass changed unexpectedly");
        check(!contains(normalShape, .99, .5, .5), "NORMAL unexpectedly has a solid east wall");

        check(manager.setFrameShellMode(level, lone, FrameShellMode.GLASS), "could not enter GLASS");
        VoxelShape glass = level.getBlockState(lone).getCollisionShape(level, lone);
        check(!glass.isEmpty(), "GLASS has no collision");
        check(contains(glass, .99, .5, .5), "GLASS did not add east exterior panel collision");

        BlockPos neighbor = lone.east();
        placeFrame(level, neighbor);
        BlockState connectedGlass = level.getBlockState(lone);
        check(MechanismFrameBlock.isConnected(connectedGlass, Direction.EAST), "joined Frames did not expose same-assembly connection");
        check(!contains(connectedGlass.getCollisionShape(level, lone), .99, .5, .5),
                "GLASS retained an internal panel between connected Frames");

        check(manager.setFrameShellMode(level, neighbor, FrameShellMode.HIDDEN), "could not hide joined assembly");
        BlockState hidden = level.getBlockState(lone);
        check(hidden.getCollisionShape(level, lone).isEmpty(), "HIDDEN contributes a physical shell collider");
        double hiddenMass = ManagedFrameMassPolicy.effectiveFrameMass(level, lone);
        check(Math.abs(hiddenMass - normalMass) < 1.0e-12,
                "HIDDEN changed effective Frame mass when removing only the shell collider");
        check(hidden.getShape(level, lone).isEmpty(), "HIDDEN has a normal selection shape without maintenance tool context");
        check(level.getBlockEntity(lone) instanceof MechanismFrameBlockEntity, "HIDDEN removed the Frame BlockEntity");
        check(level.getBlockState(lone).is(ModRegistries.MECHANISM_FRAME.get()), "HIDDEN replaced the macro Frame block");
        helper.succeed();
    }

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 120)
    public static void newFrameAdoptsStyledAssemblyImmediately(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(5, 3, 5));
        BlockPos second = first.east();
        placeFrame(level, first);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly before = manager.getAssemblyAt(first).orElseThrow();
        UUID id = before.id();
        check(manager.setFrameSkin(level, first, FrameSkin.COPPER_CASING), "could not set source skin");
        check(manager.setFrameShellMode(level, first, FrameShellMode.GLASS), "could not set source mode");

        placeFrame(level, second);
        MechanismAssembly after = manager.getAssemblyAt(second).orElseThrow();
        check(after.id().equals(id), "connected placement reset/replaced styled assembly instead of joining it");
        assertPresentation(level, manager, id, Set.of(first, second), FrameShellMode.GLASS, FrameSkin.COPPER_CASING);
        helper.succeed();
    }

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 220)
    public static void rotationSplitsOnlyClickedFrameAndMergeUsesSurvivorPresentation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(4, 3, 8));
        BlockPos middle = first.east();
        BlockPos endpoint = middle.east();
        placeFrame(level, first);
        placeFrame(level, middle);
        placeFrame(level, endpoint);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly original = manager.getAssemblyAt(first).orElseThrow();
        UUID originalId = original.id();
        check(manager.setFrameSkin(level, middle, FrameSkin.BRASS_CASING), "could not style rotation fixture");
        check(manager.setFrameShellMode(level, middle, FrameShellMode.GLASS), "could not set GLASS on rotation fixture");

        check(manager.rotateFrame(level, endpoint, Direction.EAST), "single-Frame rotation transaction failed");
        MechanismAssembly retained = manager.getAssemblyAt(first).orElseThrow();
        MechanismAssembly rotated = manager.getAssemblyAt(endpoint).orElseThrow();
        check(retained.id().equals(originalId), "two-Frame retained component lost original assembly UUID");
        check(!rotated.id().equals(retained.id()), "incompatible rotated endpoint did not split");
        check(retained.frames().equals(Set.of(first, middle)), "rotation modified neighboring Frame ownership");
        check(rotated.frames().equals(Set.of(endpoint)), "rotated endpoint did not become singleton assembly");
        check(level.getBlockState(first).getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.NORTH
                        && level.getBlockState(middle).getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.NORTH,
                "rotating endpoint changed a neighbor orientation");
        check(level.getBlockState(endpoint).getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST,
                "clicked Frame did not receive requested orientation");
        check(rotated.shellMode() == FrameShellMode.GLASS && rotated.skin() == FrameSkin.BRASS_CASING,
                "rotation-induced split did not inherit presentation");

        check(manager.setFrameSkin(level, endpoint, FrameSkin.COPPER), "could not diverge singleton skin before merge");
        check(manager.setFrameShellMode(level, endpoint, FrameShellMode.NORMAL), "could not diverge singleton mode before merge");
        check(manager.rotateFrame(level, endpoint, Direction.NORTH), "compatible rotation back failed");
        MechanismAssembly merged = manager.getAssemblyAt(endpoint).orElseThrow();
        check(merged.id().equals(originalId), "larger deterministic survivor did not win merge");
        check(merged.frames().equals(Set.of(first, middle, endpoint)), "compatible rotation did not merge normally");
        check(merged.shellMode() == FrameShellMode.GLASS && merged.skin() == FrameSkin.BRASS_CASING,
                "merge did not propagate survivor presentation");
        assertPresentation(level, manager, originalId, Set.of(first, middle, endpoint), FrameShellMode.GLASS, FrameSkin.BRASS_CASING);
        helper.succeed();
    }

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 120)
    public static void presentationChangesAreRejectedByRecoveryLock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(5, 3, 5));
        placeFrame(level, framePos);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        MechanismAssemblyManagerAccessor access = (MechanismAssemblyManagerAccessor) (Object) manager;
        access.antikytheramechanism$getContentRecoveryLocks().add(assembly.id());
        try {
            check(!manager.setFrameShellMode(level, framePos, FrameShellMode.GLASS), "recovery-locked assembly accepted mode mutation");
            check(!manager.setFrameSkin(level, framePos, FrameSkin.BRASS_CASING), "recovery-locked assembly accepted skin mutation");
            check(!manager.rotateFrame(level, framePos, Direction.EAST), "recovery-locked assembly accepted rotation mutation");
            check(assembly.shellMode() == FrameShellMode.NORMAL && assembly.skin() == FrameSkin.COPPER,
                    "rejected presentation mutation still changed authority");
        } finally {
            access.antikytheramechanism$getContentRecoveryLocks().remove(assembly.id());
        }
        helper.succeed();
    }

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 100)
    public static void assemblyPresentationNbtRoundTripIsStable(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(new BlockPos(5, 3, 5));
        UUID id = UUID.randomUUID();
        MechanismAssembly source = new MechanismAssembly(id, origin, Set.of(origin, origin.east()), FrameOrientation.IDENTITY);
        source.setPresentation(FrameShellMode.HIDDEN, FrameSkin.RAILWAY_CASING);
        MechanismAssembly decoded = MechanismAssembly.load(source.save());
        check(decoded.id().equals(id), "presentation persistence changed UUID");
        check(decoded.frames().equals(source.frames()) && decoded.origin().equals(source.origin()),
                "presentation persistence changed FrameMask/origin");
        check(decoded.shellMode() == FrameShellMode.HIDDEN, "shell mode did not survive NBT round-trip");
        check(decoded.skin() == FrameSkin.RAILWAY_CASING, "skin did not survive NBT round-trip");
        helper.succeed();
    }

    @GameTest(batch = "frame_presentation", template = "frame_rotation_empty", timeoutTicks = 140)
    public static void miniColliderAndPayloadRemainWhenParentIsHidden(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(5, 3, 5));
        placeFrame(level, framePos);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not create mini child");
        BlockPos mini = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 0, 0);
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(mini, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place mini collision block");
        child.getPlot().updateBoundingBox();
        BlockState miniBefore = child.getPlot().getEmbeddedLevelAccessor().getBlockState(mini);
        check(!miniBefore.getCollisionShape(child.getPlot().getEmbeddedLevelAccessor(), mini).isEmpty(),
                "mini fixture has no collision before hiding parent");

        check(manager.setFrameShellMode(level, framePos, FrameShellMode.HIDDEN), "could not hide parent Frame");
        BlockState miniAfter = child.getPlot().getEmbeddedLevelAccessor().getBlockState(mini);
        check(miniAfter.is(Blocks.STONE), "hiding parent altered mini block");
        check(!miniAfter.getCollisionShape(child.getPlot().getEmbeddedLevelAccessor(), mini).isEmpty(),
                "hiding parent removed mini collision");
        check(level.getBlockState(framePos).getCollisionShape(level, framePos).isEmpty(),
                "hidden parent still contributes shell collision");
        helper.succeed();
    }

    private static void assertPresentation(
            ServerLevel level,
            MechanismAssemblyManager manager,
            UUID assemblyId,
            Set<BlockPos> frames,
            FrameShellMode expectedMode,
            FrameSkin expectedSkin) {
        MechanismAssembly assembly = manager.getAssembly(assemblyId).orElseThrow();
        check(assembly.id().equals(assemblyId), "assembly UUID changed while applying presentation");
        check(assembly.frames().equals(frames), "FrameMask changed while applying presentation");
        check(assembly.shellMode() == expectedMode, "assembly shell mode mismatch");
        check(assembly.skin() == expectedSkin, "assembly skin mismatch");
        for (BlockPos framePos : frames) {
            BlockState state = level.getBlockState(framePos);
            check(state.is(ModRegistries.MECHANISM_FRAME.get()), "presentation replaced Frame block at " + framePos);
            check(state.getValue(MechanismFrameBlock.SHELL_MODE) == expectedMode,
                    "Frame BlockState did not mirror assembly mode at " + framePos);
            check(level.getBlockEntity(framePos) instanceof MechanismFrameBlockEntity,
                    "presentation removed Frame BlockEntity at " + framePos);
            MechanismFrameBlockEntity frame = (MechanismFrameBlockEntity) level.getBlockEntity(framePos);
            check(assemblyId.equals(frame.getAssemblyId()), "presentation changed Frame assembly UUID at " + framePos);
            check(frame.getPresentationSkin() == expectedSkin, "Frame render cache did not mirror assembly skin at " + framePos);
        }
    }

    private static boolean contains(VoxelShape shape, double x, double y, double z) {
        Vec3 point = new Vec3(x, y, z);
        return shape.toAabbs().stream().anyMatch(box -> box.contains(point));
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
