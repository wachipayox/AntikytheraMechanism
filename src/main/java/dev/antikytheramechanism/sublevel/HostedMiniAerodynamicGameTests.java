package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.List;

/** Runtime tests for projecting managed mini BlockSubLevelLiftProviders onto their physical host. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HostedMiniAerodynamicGameTests {
    private static final double EPSILON = 1.0E-6;
    private static final double TIME_STEP = 1.0 / 20.0;

    private HostedMiniAerodynamicGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 190)
    public static void createMiniSailUsesQuarterAreaRealPositionAndOrientation(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        HostedSetup setup = createHostedSetup(helper);
        ServerLevel level = helper.getLevel();
        Block sailBlock = requireBlock("create", "white_sail");
        BlockState eastFacing = sailBlock.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST);
        BlockSubLevelLiftProvider provider = requireProvider(eastFacing);

        BlockPos frame = setup.host().getPlot().getCenterBlock();
        BlockPos firstMini = MechanismSubLevelService.toPlotPosition(
                setup.child(), MiniCoordinateMapper.frameToMini(setup.movedAssembly(), frame, 0, 0, 0));
        BlockPos secondMini = MechanismSubLevelService.toPlotPosition(
                setup.child(), MiniCoordinateMapper.frameToMini(setup.movedAssembly(), frame, 1, 0, 1));
        check(level.setBlock(firstMini, eastFacing, Block.UPDATE_ALL), "could not place first aerodynamic mini sail");
        check(level.setBlock(secondMini, eastFacing, Block.UPDATE_ALL), "could not place second aerodynamic mini sail");

        ServerLevelPlot plot = setup.child().getPlot();
        check(plot.getLiftProviders().size() == 2,
                "managed child did not dynamically register both mini lift providers");

        RigidBodyHandle hostHandle = RigidBodyHandle.of(setup.host());
        check(hostHandle != null && hostHandle.isValid(), "foreign host has no valid physics handle");
        hostHandle.addLinearAndAngularVelocity(new Vector3d(4.0, 1.0, 2.0), new Vector3d());

        BlockSubLevelLiftProvider.LiftProviderContext firstContext = context(firstMini, eastFacing, provider);
        BlockSubLevelLiftProvider.LiftProviderContext secondContext = context(secondMini, eastFacing, provider);
        HostedMiniAerodynamicBridge.Contribution first = HostedMiniAerodynamicBridge.calculateHosted(
                provider, firstContext, setup.child(), TIME_STEP);
        HostedMiniAerodynamicBridge.Contribution second = HostedMiniAerodynamicBridge.calculateHosted(
                provider, secondContext, setup.child(), TIME_STEP);
        check(first != null && second != null, "mini sail did not resolve its foreign physical host");
        check(first.physicalBody() == setup.host(), "mini sail contribution targets the managed child instead of host");

        NativeImpulse macroEquivalent = nativeEquivalent(provider, firstContext.state(), first, hostHandle);
        checkVectorClose(new Vector3d(first.linearImpulse()).mul(4.0), macroEquivalent.linear(),
                "four equal-position mini surface impulses do not equal one macro provider");
        checkVectorClose(new Vector3d(first.angularImpulse()).mul(4.0), macroEquivalent.angular(),
                "four equal-position mini torque impulses do not equal one macro provider");

        check(first.physicalCenter().distance(second.physicalCenter()) > 0.1,
                "distinct mini cells collapsed to the same physical center");
        check(first.angularImpulse().distance(second.angularImpulse()) > EPSILON,
                "mini provider position did not affect aerodynamic torque");

        BlockState northFacing = eastFacing.setValue(BlockStateProperties.FACING, Direction.NORTH);
        HostedMiniAerodynamicBridge.Contribution rotated = HostedMiniAerodynamicBridge.calculateHosted(
                requireProvider(northFacing),
                context(firstMini, northFacing, requireProvider(northFacing)),
                setup.child(), TIME_STEP);
        check(rotated != null, "rotated mini sail lost hosted aerodynamic contribution");
        check(first.physicalNormal().distance(rotated.physicalNormal()) > 0.5,
                "mini sail state orientation did not rotate its aerodynamic normal");
        check(first.linearImpulse().distance(rotated.linearImpulse()) > EPSILON,
                "mini sail orientation did not affect aerodynamic force");

        // Real application must affect the foreign host only. The managed child is pose-driven and
        // therefore must not accumulate its own aerodynamic velocity.
        RigidBodyHandle childHandle = RigidBodyHandle.of(setup.child());
        check(childHandle != null && childHandle.isValid(), "managed child has no physics handle");
        Vector3d childLinearBefore = childHandle.getLinearVelocity(new Vector3d());
        Vector3d childAngularBefore = childHandle.getAngularVelocity(new Vector3d());
        check(HostedMiniAerodynamicBridge.project(provider, firstContext, setup.child(), TIME_STEP),
                "managed mini provider was not intercepted by hosted bridge");
        checkVectorClose(childHandle.getLinearVelocity(new Vector3d()), childLinearBefore,
                "managed child acquired independent linear motion from mini aerodynamics");
        checkVectorClose(childHandle.getAngularVelocity(new Vector3d()), childAngularBefore,
                "managed child acquired independent angular motion from mini aerodynamics");

        check(level.removeBlock(secondMini, false), "could not remove second mini aerodynamic provider");
        check(plot.getLiftProviders().size() == 1,
                "mini provider cache did not update immediately after block removal");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 170)
    public static void rootManagedMiniSailDoesNotCreateIndependentRigidPhysics(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos frame = helper.absolutePos(new BlockPos(4, 4, 4));
        placeFrame(level, frame);
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(frame).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not create root managed mini child");

        BlockState state = requireBlock("create", "white_sail").defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST);
        BlockSubLevelLiftProvider provider = requireProvider(state);
        BlockPos mini = MechanismSubLevelService.toPlotPosition(
                child, MiniCoordinateMapper.frameToMini(assembly, frame, 1, 1, 1));
        check(level.setBlock(mini, state, Block.UPDATE_ALL), "could not place root mini sail");
        BlockSubLevelLiftProvider.LiftProviderContext context = context(mini, state, provider);

        check(HostedMiniAerodynamicBridge.calculateHosted(provider, context, child, TIME_STEP) == null,
                "root-world managed child unexpectedly resolved a foreign rigid host");
        RigidBodyHandle childHandle = RigidBodyHandle.of(child);
        check(childHandle != null && childHandle.isValid(), "root managed child has no physics handle");
        Vector3d beforeLinear = childHandle.getLinearVelocity(new Vector3d());
        Vector3d beforeAngular = childHandle.getAngularVelocity(new Vector3d());
        check(HostedMiniAerodynamicBridge.project(provider, context, child, TIME_STEP),
                "root managed provider was not suppressed");
        checkVectorClose(childHandle.getLinearVelocity(new Vector3d()), beforeLinear,
                "root managed child received forbidden independent aerodynamic linear motion");
        checkVectorClose(childHandle.getAngularVelocity(new Vector3d()), beforeAngular,
                "root managed child received forbidden independent aerodynamic angular motion");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 190)
    public static void symmetricMiniSailKeepsItsOwnSableAerodynamics(GameTestHelper helper) {
        if (!ModList.get().isLoaded("simulated")) {
            helper.succeed();
            return;
        }

        HostedSetup setup = createHostedSetup(helper);
        ServerLevel level = helper.getLevel();
        Block symmetric = requireBlock("simulated", "white_symmetric_sail");
        BlockState state = symmetric.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
        BlockSubLevelLiftProvider provider = requireProvider(state);
        checkClose(provider.sable$getLiftScalar(), 0.0,
                "Symmetric Sail lost its native zero-lift behavior");
        checkClose(provider.sable$getParallelDragScalar(), 1.75,
                "Symmetric Sail lost its native parallel-drag scalar");

        BlockPos frame = setup.host().getPlot().getCenterBlock();
        BlockPos mini = MechanismSubLevelService.toPlotPosition(
                setup.child(), MiniCoordinateMapper.frameToMini(setup.movedAssembly(), frame, 1, 0, 0));
        check(level.setBlock(mini, state, Block.UPDATE_ALL), "could not place symmetric mini sail");
        check(setup.child().getPlot().getLiftProviders().size() == 1,
                "Symmetric Sail was not registered as its native Sable provider");

        RigidBodyHandle hostHandle = RigidBodyHandle.of(setup.host());
        check(hostHandle != null && hostHandle.isValid(), "symmetric-sail host has no physics handle");
        hostHandle.addLinearAndAngularVelocity(new Vector3d(5.0, 0.5, 1.0), new Vector3d());
        HostedMiniAerodynamicBridge.Contribution contribution = HostedMiniAerodynamicBridge.calculateHosted(
                provider, context(mini, state, provider), setup.child(), TIME_STEP);
        check(contribution != null, "Symmetric Sail did not project to foreign host");
        check(contribution.linearImpulse().lengthSquared() > EPSILON * EPSILON,
                "Symmetric Sail native drag produced no projected mini impulse");

        check(level.removeBlock(mini, false), "could not remove symmetric mini sail");
        check(setup.child().getPlot().getLiftProviders().isEmpty(),
                "Symmetric Sail provider cache remained stale after runtime removal");
        helper.succeed();
    }

    private static NativeImpulse nativeEquivalent(
            BlockSubLevelLiftProvider provider,
            BlockState state,
            HostedMiniAerodynamicBridge.Contribution mini,
            RigidBodyHandle hostHandle) {
        BlockSubLevelLiftProvider.LiftProviderContext context =
                new BlockSubLevelLiftProvider.LiftProviderContext(
                        BlockPos.ZERO,
                        state,
                        new Vec3(mini.physicalNormal().x, mini.physicalNormal().y, mini.physicalNormal().z));
        Pose3d pose = new Pose3d();
        pose.position().set(mini.physicalCenter()).sub(0.5, 0.5, 0.5);
        pose.orientation().identity();
        pose.scale().set(1.0, 1.0, 1.0);
        Vector3d linear = new Vector3d();
        Vector3d angular = new Vector3d();
        provider.sable$contributeLiftAndDrag(
                context,
                mini.physicalBody(),
                pose,
                TIME_STEP,
                hostHandle.getLinearVelocity(new Vector3d()),
                hostHandle.getAngularVelocity(new Vector3d()),
                linear,
                angular,
                null);
        return new NativeImpulse(linear, angular);
    }

    private static BlockSubLevelLiftProvider.LiftProviderContext context(
            BlockPos position,
            BlockState state,
            BlockSubLevelLiftProvider provider) {
        Direction normal = provider.sable$getNormal(state);
        return new BlockSubLevelLiftProvider.LiftProviderContext(
                position,
                state,
                Vec3.atLowerCornerOf(normal.getNormal()));
    }

    private static HostedSetup createHostedSetup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rootFrame = helper.absolutePos(new BlockPos(4, 4, 4));
        placeFrame(level, rootFrame);
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(rootFrame).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed aerodynamic child");

        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                rootFrame,
                List.of(rootFrame),
                new BoundingBox3i(
                        rootFrame.getX(), rootFrame.getY(), rootFrame.getZ(),
                        rootFrame.getX(), rootFrame.getY(), rootFrame.getZ()));
        check(host != null && !host.isRemoved(), "could not create foreign aerodynamic host");
        BlockPos hostedFrame = host.getPlot().getCenterBlock();
        MechanismAssembly moved = manager.getAssemblyAt(hostedFrame).orElseThrow();
        check(moved.id().equals(assembly.id()), "aerodynamic assembly identity changed during Sable hosting");
        return new HostedSetup(moved, child, host);
    }

    private static void placeFrame(ServerLevel level, BlockPos pos) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(pos, state, Block.UPDATE_ALL), "could not place Mechanism Frame");
    }

    private static Block requireBlock(String namespace, String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == Blocks.AIR && !id.equals(BuiltInRegistries.BLOCK.getKey(Blocks.AIR))) {
            throw new AssertionError("missing required block " + id);
        }
        return block;
    }

    private static BlockSubLevelLiftProvider requireProvider(BlockState state) {
        if (!(state.getBlock() instanceof BlockSubLevelLiftProvider provider)) {
            throw new AssertionError(state.getBlock() + " is not a Sable BlockSubLevelLiftProvider");
        }
        return provider;
    }

    private static void checkVectorClose(Vector3d actual, Vector3d expected, String message) {
        if (actual.distance(expected) > EPSILON) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void checkClose(double actual, double expected, String message) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > EPSILON) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record NativeImpulse(Vector3d linear, Vector3d angular) {
    }

    private record HostedSetup(MechanismAssembly movedAssembly, ServerSubLevel child, ServerSubLevel host) {
    }
}
