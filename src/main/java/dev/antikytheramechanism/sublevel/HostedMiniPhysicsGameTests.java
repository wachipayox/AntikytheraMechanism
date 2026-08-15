package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.mixin.FloatingBlockControllerAccessor;
import dev.antikytheramechanism.mixin.FloatingBlockDataAccessor;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockCluster;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockMaterial;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.util.SableMathUtils;
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

/** Regression coverage for projecting a managed child's native Sable physics into a foreign host. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HostedMiniPhysicsGameTests {
    private static final double EPSILON = 1.0E-6;

    private HostedMiniPhysicsGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void hostUsesScaledChildMassAndCenterOfMass(GameTestHelper helper) {
        HostedSetup setup = createHostedSetup(helper);
        ServerLevel level = helper.getLevel();

        BlockPos hostedFrame = setup.host().getPlot().getCenterBlock();
        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(
                setup.movedAssembly(), hostedFrame, 1, 0, 0);
        BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(setup.child(), miniLocal);
        check(level.setBlock(miniGlobal, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not add asymmetric mini payload");

        // Make the child's merged MassData current before asking the host to consume it.
        setup.child().updateMergedMassData(0.0f);
        AssemblyPose target = MechanismAssemblyHost.worldPose(level, setup.movedAssembly());
        check(target != null, "could not resolve hosted child world pose");
        AssemblyPoseDriver.drive(
                SubLevelPhysicsSystem.require(level).getPipeline(),
                setup.child(),
                target);

        ManagedSubLevelMassPolicy.PayloadMassData payload =
                ManagedSubLevelMassPolicy.payloadMassData(setup.child());
        check(payload != null, "managed child did not expose real payload MassData");

        double linearScale = setup.child().logicalPose().scale().x();
        double payloadScale = linearScale * linearScale * linearScale;
        double inertiaScale = payloadScale * linearScale * linearScale;
        double scaledPayloadMass = payload.mass() * payloadScale;
        double selfMass = setup.host().getSelfMassTracker().getMass();
        Vector3d selfCenter = new Vector3d(setup.host().getSelfMassTracker().getCenterOfMass());
        Matrix3d selfInertia = new Matrix3d(setup.host().getSelfMassTracker().getInertiaTensor());

        Vector3d payloadWorldCenter = setup.child().logicalPose().transformPosition(
                payload.centerOfMass(), new Vector3d());
        Vector3d payloadHostCenter = setup.host().logicalPose().transformPositionInverse(
                payloadWorldCenter, new Vector3d());
        double expectedMass = selfMass + scaledPayloadMass;
        Vector3d expectedCenter = new Vector3d(selfCenter)
                .mul(selfMass)
                .fma(scaledPayloadMass, payloadHostCenter)
                .div(expectedMass);

        Quaterniond childToHost = new Quaterniond(setup.host().logicalPose().orientation())
                .conjugate()
                .mul(setup.child().logicalPose().orientation())
                .normalize();
        Matrix3d payloadHostInertia = new Matrix3d()
                .rotateLocal(childToHost.conjugate(new Quaterniond()))
                .mulLocal(new Matrix3d(payload.inertiaTensor()).scale(inertiaScale))
                .rotateLocal(childToHost);
        Matrix3d expectedInertia = new Matrix3d(selfInertia);
        SableMathUtils.fmaInertiaTensor(
                new Vector3d(selfCenter).sub(expectedCenter),
                selfMass,
                expectedInertia);
        expectedInertia.add(payloadHostInertia);
        SableMathUtils.fmaInertiaTensor(
                new Vector3d(payloadHostCenter).sub(expectedCenter),
                scaledPayloadMass,
                expectedInertia);

        setup.host().updateMergedMassData(0.0f);

        checkClose(setup.host().getMassTracker().getMass(), expectedMass,
                "foreign host did not receive child mass scaled by 0.5^3");
        checkVectorClose(
                new Vector3d(setup.host().getMassTracker().getCenterOfMass()),
                expectedCenter,
                "foreign host center of mass does not include the transformed mini distribution");
        checkMatrixClose(
                new Matrix3d(setup.host().getMassTracker().getInertiaTensor()),
                expectedInertia,
                "foreign host inertia does not include the scaled/rotated mini distribution");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void punchProjectionTargetsForeignHostAtVisualMiniHit(GameTestHelper helper) {
        HostedSetup setup = createHostedSetup(helper);
        ServerLevel level = helper.getLevel();

        BlockPos hostedFrame = setup.host().getPlot().getCenterBlock();
        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(
                setup.movedAssembly(), hostedFrame, 1, 1, 0);
        BlockPos miniGlobal = MechanismSubLevelService.toPlotPosition(setup.child(), miniLocal);
        check(level.setBlock(miniGlobal, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL),
                "could not add mini punch payload");

        setup.child().updateMergedMassData(0.0f);
        AssemblyPose target = MechanismAssemblyHost.worldPose(level, setup.movedAssembly());
        check(target != null, "could not resolve hosted child world pose for punch");
        AssemblyPoseDriver.drive(
                SubLevelPhysicsSystem.require(level).getPipeline(),
                setup.child(),
                target);
        setup.host().updateMergedMassData(0.0f);

        Vector3d childPlotHit = new Vector3d(
                miniGlobal.getX() + 0.75,
                miniGlobal.getY() + 0.4,
                miniGlobal.getZ() + 0.2);
        Vector3d worldHit = setup.child().logicalPose().transformPosition(
                childPlotHit, new Vector3d());
        // This is the exact representation stored in Sable's punch packet: world-space offset from
        // the target SubLevel pose position.
        Vector3d packetLocalHit = new Vector3d(worldHit)
                .sub(setup.child().logicalPose().position());
        Vector3d worldDirection = new Vector3d(0.8, 0.2, -0.5).normalize();

        HostedMiniPunchBridge.Projection projection = HostedMiniPunchBridge.project(
                level,
                setup.child(),
                packetLocalHit,
                worldDirection);
        check(projection != null, "managed mini punch did not resolve a foreign physical host");
        check(projection.host() == setup.host(), "mini punch still targets the pose-driven child");
        checkVectorClose(
                projection.worldHitPosition(),
                worldHit,
                "mini punch did not reconstruct the visual hit point");

        Vector3d expectedHostHit = setup.host().logicalPose().transformPositionInverse(
                worldHit, new Vector3d());
        Vector3d expectedHostDirection = setup.host().logicalPose().transformNormalInverse(
                worldDirection, new Vector3d());
        checkVectorClose(
                projection.hostLocalHitPosition(),
                expectedHostHit,
                "mini punch hit point was not projected into host coordinates");
        checkVectorClose(
                projection.hostLocalDirection(),
                expectedHostDirection,
                "mini punch direction was not projected into host coordinates");

        double hostStrength = HostedMiniPunchBridge.computeStrengthScalar(
                setup.host(),
                projection.hostLocalHitPosition(),
                projection.hostLocalDirection());
        Vector3d childLocalHit = setup.child().logicalPose().transformPositionInverse(
                worldHit, new Vector3d());
        Vector3d childLocalDirection = setup.child().logicalPose().transformNormalInverse(
                worldDirection, new Vector3d());
        double childStrength = HostedMiniPunchBridge.computeStrengthScalar(
                setup.child(), childLocalHit, childLocalDirection);
        check(Double.isFinite(hostStrength) && hostStrength > 0.0,
                "foreign host produced an invalid Sable punch strength");
        check(Math.abs(hostStrength - childStrength) > EPSILON,
                "punch strength still appears to be calculated from managed child MassData");
        helper.succeed();
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void floatingBridgeTransformsChildAggregateWithoutRescanningBlocks(GameTestHelper helper) {
        HostedSetup setup = createHostedSetup(helper);
        ServerLevel level = helper.getLevel();

        setup.child().updateMergedMassData(0.0f);
        AssemblyPose target = MechanismAssemblyHost.worldPose(level, setup.movedAssembly());
        check(target != null, "could not resolve hosted child world pose");
        AssemblyPoseDriver.drive(
                SubLevelPhysicsSystem.require(level).getPipeline(),
                setup.child(),
                target);
        setup.host().updateMergedMassData(0.0f);

        FloatingBlockControllerAccessor controller =
                (FloatingBlockControllerAccessor) setup.child().getFloatingBlockController();
        // Initialize Sable's previous-COM bookkeeping before injecting a synthetic already-aggregated
        // material. The bridge itself will call this again, which must then be a no-op for the origin.
        controller.antikytheramechanism$processBlockChanges();
        FloatingClusterContainer childContainer = controller.antikytheramechanism$getSublevelContainer();

        FloatingBlockMaterial material = new FloatingBlockMaterial(
                false, false, false,
                1.0,
                0.0, 0.0, 0.0, 0.0, 0.0);
        FloatingBlockCluster source = new FloatingBlockCluster(material);
        source.getBlockData().addFloatingBlock(new Vector3d(0.5, -0.25, 0.75), 2.0);
        childContainer.clusters.add(source);

        FloatingBlockDataAccessor sourceData = (FloatingBlockDataAccessor) source.getBlockData();
        double sourceScale = sourceData.antikytheramechanism$getTotalScale();
        Vector3d sourceMean = new Vector3d(sourceData.antikytheramechanism$getWeightedPosition())
                .div(sourceScale);
        Matrix3d sourceMoment = new Matrix3d(sourceData.antikytheramechanism$getOuterProduct());

        List<FloatingClusterContainer> projectedContainers =
                HostedMiniFloatingMaterialBridge.contributionFor(setup.host());
        check(projectedContainers.size() == 1, "host did not receive one projected floating container");
        check(projectedContainers.getFirst().clusters.size() == 1,
                "host did not receive the child's existing floating aggregate");

        FloatingBlockCluster projected = projectedContainers.getFirst().clusters.getFirst();
        FloatingBlockDataAccessor projectedData =
                (FloatingBlockDataAccessor) projected.getBlockData();

        double s = setup.child().logicalPose().scale().x();
        double expectedScale = sourceScale * s * s * s;
        checkClose(projectedData.antikytheramechanism$getTotalScale(), expectedScale,
                "floating material amount was not volume-scaled");

        Vector3d childCenterWorld = setup.child().logicalPose().transformPosition(
                setup.child().getMassTracker().getCenterOfMass(), new Vector3d());
        Vector3d childCenterInHost = setup.host().logicalPose().transformPositionInverse(
                childCenterWorld, new Vector3d());
        Vector3d translation = childCenterInHost.sub(
                setup.host().getMassTracker().getCenterOfMass(), new Vector3d());
        Quaterniond childToHost = new Quaterniond(setup.host().logicalPose().orientation())
                .conjugate()
                .mul(setup.child().logicalPose().orientation())
                .normalize();
        Vector3d expectedMean = childToHost.transform(new Vector3d(sourceMean).mul(s)).add(translation);
        Vector3d actualMean = new Vector3d(projectedData.antikytheramechanism$getWeightedPosition())
                .div(projectedData.antikytheramechanism$getTotalScale());
        checkVectorClose(actualMean, expectedMean,
                "floating aggregate center was not transformed child -> host");

        double momentScale = s * s * s * s * s;
        Matrix3d expectedMoment = new Matrix3d()
                .rotateLocal(childToHost.conjugate(new Quaterniond()))
                .mulLocal(new Matrix3d(sourceMoment).scale(momentScale))
                .rotateLocal(childToHost);
        checkMatrixClose(
                projectedData.antikytheramechanism$getOuterProduct(),
                expectedMoment,
                "floating aggregate second moment was not scaled/rotated correctly");

        HostedMiniFloatingMaterialBridge.clear(setup.host());
        helper.succeed();
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

    private static void checkClose(double actual, double expected, String message) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > EPSILON) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void checkVectorClose(Vector3d actual, Vector3d expected, String message) {
        if (actual.distance(expected) > EPSILON) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void checkMatrixClose(Matrix3d actual, Matrix3d expected, String message) {
        double max = Math.max(
                Math.max(Math.abs(actual.m00() - expected.m00()), Math.abs(actual.m01() - expected.m01())),
                Math.max(
                        Math.max(Math.abs(actual.m02() - expected.m02()), Math.abs(actual.m10() - expected.m10())),
                        Math.max(
                                Math.max(Math.abs(actual.m11() - expected.m11()), Math.abs(actual.m12() - expected.m12())),
                                Math.max(
                                        Math.max(Math.abs(actual.m20() - expected.m20()), Math.abs(actual.m21() - expected.m21())),
                                        Math.abs(actual.m22() - expected.m22())))));
        if (max > EPSILON) {
            throw new AssertionError(message + ": max error=" + max);
        }
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
