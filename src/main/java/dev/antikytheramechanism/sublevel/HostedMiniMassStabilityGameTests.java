package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
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
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;

/** Regression coverage for world-motion-invariant mini mass projected into a foreign host. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HostedMiniMassStabilityGameTests {
    private HostedMiniMassStabilityGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void hostedMiniMassDistributionIgnoresWorldHostMotion(GameTestHelper helper) {
        HostedSetup setup = createHostedSetup(helper);
        ServerLevel level = helper.getLevel();

        BlockPos hostedFrame = setup.host().getPlot().getCenterBlock();
        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(
                setup.assembly(), hostedFrame, 1, 0, 1);
        BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(setup.child(), miniLocal);
        check(level.setBlock(miniGlobal, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not add asymmetric hosted mini payload");
        setup.child().updateMergedMassData(0.0f);

        double baseMass = setup.host().getSelfMassTracker().getMass();
        Vector3d baseCenter = new Vector3d(setup.host().getSelfMassTracker().getCenterOfMass());
        Matrix3d baseInertia = new Matrix3d(setup.host().getSelfMassTracker().getInertiaTensor());
        HostedMiniMassBridge.MergedMass before = HostedMiniMassBridge.mergeInto(
                setup.host(), baseMass, baseCenter, baseInertia);

        // Apply the exact same arbitrary rigid world transform to host and child. Their relationship
        // has not changed, so the mass distribution in host-local coordinates must be bit-stable.
        // The previous child -> world -> host projection lost precision here; MergedMassTracker then
        // interpreted those tiny differences as a real COM change and teleported the host each substep.
        Quaterniond delta = new Quaterniond()
                .rotateXYZ(0.371, -0.619, 1.137)
                .normalize();
        Vector3d translation = new Vector3d(12345.6789, -9876.54321, 4321.12345);
        applyWorldTransform(setup.host(), delta, translation);
        applyWorldTransform(setup.child(), delta, translation);

        HostedMiniMassBridge.MergedMass after = HostedMiniMassBridge.mergeInto(
                setup.host(), baseMass, baseCenter, baseInertia);

        check(Double.doubleToLongBits(before.mass()) == Double.doubleToLongBits(after.mass()),
                "rigid world motion changed hosted mini mass");
        checkVectorExact(before.centerOfMass(), after.centerOfMass(),
                "rigid world motion changed host-local center of mass");
        checkMatrixExact(before.inertiaTensor(), after.inertiaTensor(),
                "rigid world motion changed host-local inertia tensor");
        helper.succeed();
    }

    private static void applyWorldTransform(
            ServerSubLevel body,
            Quaterniond delta,
            Vector3d translation) {
        Vector3d position = new Quaterniond(delta)
                .transform(new Vector3d(body.logicalPose().position()))
                .add(translation);
        Quaterniond orientation = new Quaterniond(delta)
                .mul(body.logicalPose().orientation())
                .normalize();
        body.logicalPose().position().set(position);
        body.logicalPose().orientation().set(orientation);
    }

    private static HostedSetup createHostedSetup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rootFrame = helper.absolutePos(new BlockPos(3, 3, 3));
        BlockState state = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(MechanismFrameBlock.EMPTY, true);
        check(level.setBlock(rootFrame, state, Block.UPDATE_ALL),
                "could not place Mechanism Frame");

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

    private static void checkVectorExact(Vector3d actual, Vector3d expected, String message) {
        if (Double.doubleToLongBits(actual.x) != Double.doubleToLongBits(expected.x)
                || Double.doubleToLongBits(actual.y) != Double.doubleToLongBits(expected.y)
                || Double.doubleToLongBits(actual.z) != Double.doubleToLongBits(expected.z)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void checkMatrixExact(Matrix3d actual, Matrix3d expected, String message) {
        if (Double.doubleToLongBits(actual.m00()) != Double.doubleToLongBits(expected.m00())
                || Double.doubleToLongBits(actual.m01()) != Double.doubleToLongBits(expected.m01())
                || Double.doubleToLongBits(actual.m02()) != Double.doubleToLongBits(expected.m02())
                || Double.doubleToLongBits(actual.m10()) != Double.doubleToLongBits(expected.m10())
                || Double.doubleToLongBits(actual.m11()) != Double.doubleToLongBits(expected.m11())
                || Double.doubleToLongBits(actual.m12()) != Double.doubleToLongBits(expected.m12())
                || Double.doubleToLongBits(actual.m20()) != Double.doubleToLongBits(expected.m20())
                || Double.doubleToLongBits(actual.m21()) != Double.doubleToLongBits(expected.m21())
                || Double.doubleToLongBits(actual.m22()) != Double.doubleToLongBits(expected.m22())) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record HostedSetup(
            MechanismAssembly assembly,
            ServerSubLevel child,
            ServerSubLevel host) {
    }
}
