package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/** Regression for Simulated springs reacting against the physical carrier of a hosted mini block. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HostedMiniSpringPhysicsGameTests {
    private static final ResourceLocation SPRING =
            ResourceLocation.fromNamespaceAndPath("simulated", "spring");
    private static final double EPSILON = 1.0E-7;

    private HostedMiniSpringPhysicsGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void springOnHostedMiniBlockAcceleratesForeignHost(GameTestHelper helper) {
        if (!ModList.get().isLoaded("simulated")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Block springBlock = BuiltInRegistries.BLOCK.get(SPRING);
        check(springBlock != Blocks.AIR, "Simulated is loaded but simulated:spring is missing");

        HostedSetup setup = createHostedSetup(helper);
        BlockPos hostedFrame = setup.host().getPlot().getCenterBlock();

        BlockPos miniSupportLocal = MiniCoordinateMapper.frameToMini(
                setup.movedAssembly(), hostedFrame, 0, 0, 0);
        BlockPos miniSpringLocal = MiniCoordinateMapper.frameToMini(
                setup.movedAssembly(), hostedFrame, 1, 0, 0);
        BlockPos miniSupportGlobal =
                MechanismSubLevelService.toPlotPosition(setup.child(), miniSupportLocal);
        BlockPos miniSpringGlobal =
                MechanismSubLevelService.toPlotPosition(setup.child(), miniSpringLocal);

        check(level.setBlock(miniSupportGlobal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place mini spring support");
        BlockState miniSpringState = springState(springBlock, Direction.EAST);
        check(level.setBlock(miniSpringGlobal, miniSpringState, Block.UPDATE_ALL),
                "could not place spring endpoint in managed child");
        BlockEntity miniSpring = level.getBlockEntity(miniSpringGlobal);
        check(miniSpring != null, "managed mini spring BlockEntity missing");
        check(miniSpring instanceof BlockEntitySubLevelActor,
                "Simulated spring is no longer a Sable BlockEntitySubLevelActor");
        check(isRegisteredActor(setup.child(), miniSpring),
                "managed mini spring was not registered in the child Sable actor list");

        BlockPos rootSpring = helper.absolutePos(new BlockPos(7, 3, 3));
        BlockPos rootSupport = rootSpring.east();
        check(level.setBlock(rootSupport, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not place root spring support");
        check(level.setBlock(rootSpring, springState(springBlock, Direction.WEST), Block.UPDATE_ALL),
                "could not place root spring endpoint");
        BlockEntity rootSpringEntity = level.getBlockEntity(rootSpring);
        check(rootSpringEntity != null, "root spring BlockEntity missing");

        configurePair(miniSpring, rootSpringEntity, setup.child());

        setup.child().updateMergedMassData(0.0f);
        SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.require(level);
        AssemblyPose target = MechanismAssemblyHost.worldPose(level, setup.movedAssembly());
        check(target != null, "could not resolve hosted child pose");
        AssemblyPoseDriver.drive(physicsSystem.getPipeline(), setup.child(), target);
        setup.host().updateMergedMassData(0.0f);

        RigidBodyHandle childHandle = RigidBodyHandle.of(setup.child());
        RigidBodyHandle hostHandle = RigidBodyHandle.of(setup.host());
        check(childHandle != null && childHandle.isValid(), "managed child rigid-body handle missing");
        check(hostHandle != null && hostHandle.isValid(), "foreign host rigid-body handle missing");

        check(HostedMiniForceProjection.foreignHost(level, setup.child()) == setup.host(),
                "hosted mini force projection did not resolve the Frame's actual foreign host");

        // Prove the test host itself is a live dynamic rigid body. Keep that known impulse in the
        // baseline; the spring must still produce an additional velocity change afterwards.
        Vector3d directBefore = hostHandle.getLinearVelocity(new Vector3d());
        hostHandle.applyLinearImpulse(new Vector3d(0.125, 0.0, 0.0));
        Vector3d directAfter = hostHandle.getLinearVelocity(new Vector3d());
        check(directAfter.distanceSquared(directBefore) > EPSILON,
                "foreign host rigid body did not react to a direct impulse in regression setup");

        Vector3d beforeLinear = hostHandle.getLinearVelocity(new Vector3d());
        Vector3d beforeAngular = hostHandle.getAngularVelocity(new Vector3d());

        // Use Sable's actual BlockEntity actor dispatcher instead of invoking the spring callback
        // directly; the latter can hide exactly the scheduling failure seen in a live hosted Frame.
        setup.child().prePhysicsTickBegin();
        setup.child().prePhysicsTick(physicsSystem, childHandle, 1.0 / 20.0);

        Vector3d afterLinear = hostHandle.getLinearVelocity(new Vector3d());
        Vector3d afterAngular = hostHandle.getAngularVelocity(new Vector3d());
        double response = afterLinear.distanceSquared(beforeLinear)
                + afterAngular.distanceSquared(beforeAngular);
        check(response > EPSILON,
                "spring attached to managed mini block did not accelerate its foreign physical host");
        helper.succeed();
    }

    private static boolean isRegisteredActor(ServerSubLevel child, BlockEntity blockEntity) {
        for (BlockEntitySubLevelActor actor : child.getPlot().getBlockEntityActors()) {
            if (actor == blockEntity) {
                return true;
            }
        }
        return false;
    }

    private static BlockState springState(Block spring, Direction facing) {
        BlockState state = spring.defaultBlockState();
        check(state.hasProperty(BlockStateProperties.FACING),
                "Simulated spring no longer exposes the six-way FACING property");
        return state.setValue(BlockStateProperties.FACING, facing);
    }

    private static void configurePair(
            BlockEntity miniSpring,
            BlockEntity rootSpring,
            ServerSubLevel child) {
        try {
            Class<?> type = miniSpring.getClass();
            Method setController = type.getMethod("setController", boolean.class);
            Method setDesiredLength = type.getMethod("setDesiredLength", double.class);
            Method setPartnerPos = type.getMethod("setPartnerPos", BlockPos.class, UUID.class);

            setController.invoke(miniSpring, true);
            setController.invoke(rootSpring, false);
            setDesiredLength.invoke(miniSpring, 1.0);
            setDesiredLength.invoke(rootSpring, 1.0);
            setPartnerPos.invoke(miniSpring, rootSpring.getBlockPos(), null);
            setPartnerPos.invoke(rootSpring, miniSpring.getBlockPos(), child.getUniqueId());
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new AssertionError("Could not configure Simulated spring regression pair", exception);
        } catch (InvocationTargetException exception) {
            throw rethrow("Configuring Simulated spring pair failed", exception);
        }
    }

    private static AssertionError rethrow(String message, InvocationTargetException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        return new AssertionError(message, cause);
    }

    private static HostedSetup createHostedSetup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rootFrame = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(rootFrame, state, Block.UPDATE_ALL), "could not place Mechanism Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssemblyAt(rootFrame).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize managed child");

        ServerSubLevel host = SubLevelAssemblyHelper.assembleBlocks(
                level,
                rootFrame,
                List.of(rootFrame),
                new BoundingBox3i(
                        rootFrame.getX(), rootFrame.getY(), rootFrame.getZ(),
                        rootFrame.getX(), rootFrame.getY(), rootFrame.getZ()));
        check(host != null && !host.isRemoved(), "Sable did not create foreign host");

        BlockPos hostedFrame = host.getPlot().getCenterBlock();
        MechanismAssembly moved = manager.getAssemblyAt(hostedFrame).orElseThrow();
        check(moved.id().equals(assembly.id()), "logical assembly did not follow Sable host move");
        return new HostedSetup(moved, child, host);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record HostedSetup(
            MechanismAssembly movedAssembly,
            ServerSubLevel child,
            ServerSubLevel host) {
    }
}
