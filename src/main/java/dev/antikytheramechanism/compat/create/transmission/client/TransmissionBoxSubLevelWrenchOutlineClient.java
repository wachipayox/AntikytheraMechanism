package dev.antikytheramechanism.compat.create.transmission.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllItems;
import dev.antikytheramechanism.compat.create.transmission.CreateTransmissionRegistries;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxBlockEntity;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxCorner;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxHitTarget;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.Map;

/**
 * Root-world fallback for Transmission Box wrench selectors hosted inside a Sable SubLevel.
 *
 * <p>Sable wraps NeoForge's normal block-highlight callback and supplies a transformed PoseStack and
 * SubLevelCamera. The ordinary Transmission Box highlight renderer therefore must not try to apply
 * the host transform a second time. This renderer deliberately runs in a normal root-world render
 * stage instead: it reads the same logical hit result, resolves the fixed wrench selector, and maps
 * that local selector through {@link ClientSubLevel#renderPose()} exactly once.</p>
 */
public final class TransmissionBoxSubLevelWrenchOutlineClient {
    private static final double EPSILON = 0.0025;
    private static final long REJECTION_PULSE_MS = 550L;
    private static final Map<PulseKey, Long> REJECTED_TARGET_PULSES = new HashMap<>();

    private TransmissionBoxSubLevelWrenchOutlineClient() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(TransmissionBoxSubLevelWrenchOutlineClient::render);
        NeoForge.EVENT_BUS.addListener(TransmissionBoxSubLevelWrenchOutlineClient::trackRejectedCornerClick);
    }

    /**
     * The normal highlight path owns its own rejection pulse state. SubLevel outlines bypass that
     * path, so mirror the same invalid-corner click detection here, but only for boxes that actually
     * live inside a client SubLevel.
     */
    private static void trackRejectedCornerClick(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!event.getLevel().isClientSide
                || player == null
                || player.isShiftKeyDown()
                || !AllItems.WRENCH.isIn(event.getItemStack())) {
            return;
        }

        BlockPos boxPos = event.getPos();
        ClientSubLevel host = Sable.HELPER.getContainingClient(boxPos);
        if (host == null || host.isRemoved()) {
            return;
        }
        if (!(event.getLevel().getBlockEntity(boxPos) instanceof TransmissionBoxBlockEntity box)) {
            return;
        }

        TransmissionBoxHitTarget target = TransmissionBoxHitTarget.resolveWrench(event.getHitVec(), box);
        if (target.kind() != TransmissionBoxHitTarget.Kind.CORNER
                || target.corner() == null
                || box.blockersForNextCornerMode(target.corner()).isEmpty()) {
            return;
        }

        REJECTED_TARGET_PULSES.put(
                new PulseKey(boxPos.immutable(), target.corner()),
                Util.getMillis());
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null
                || minecraft.level == null
                || player.isShiftKeyDown()
                || !(AllItems.WRENCH.isIn(player.getMainHandItem())
                || AllItems.WRENCH.isIn(player.getOffhandItem()))
                || !(minecraft.hitResult instanceof BlockHitResult hit)) {
            return;
        }

        BlockPos boxPos = hit.getBlockPos();
        ClientSubLevel host = Sable.HELPER.getContainingClient(boxPos);
        if (host == null || host.isRemoved()) {
            return;
        }
        if (!minecraft.level.getBlockState(boxPos).is(CreateTransmissionRegistries.TRANSMISSION_BOX.get())
                || !(minecraft.level.getBlockEntity(boxPos) instanceof TransmissionBoxBlockEntity box)) {
            return;
        }

        TransmissionBoxHitTarget target = TransmissionBoxHitTarget.resolveWrench(hit, box);
        if (target.kind() == TransmissionBoxHitTarget.Kind.NONE
                || target.kind() == TransmissionBoxHitTarget.Kind.ROTATE) {
            return;
        }

        AABB localRegion = targetBoundsLocal(target, box);
        float rejection = target.kind() == TransmissionBoxHitTarget.Kind.CORNER && target.corner() != null
                ? rejectedTargetPulse(boxPos, target.corner())
                : 0.0F;
        float greenBlue = 1.0F - 0.9F * rejection;

        Pose3dc pose = host.renderPose();
        Vector3d worldOrigin = pose.transformPosition(
                new Vector3d(boxPos.getX(), boxPos.getY(), boxPos.getZ()),
                new Vector3d());
        Quaterniondc orientation = pose.orientation();
        Quaternionf rotation = new Quaternionf(
                (float) orientation.x(),
                (float) orientation.y(),
                (float) orientation.z(),
                (float) orientation.w()).normalize();
        Vector3dc scale = pose.scale();
        Vec3 camera = event.getCamera().getPosition();

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(
                worldOrigin.x - camera.x,
                worldOrigin.y - camera.y,
                worldOrigin.z - camera.z);
        poseStack.mulPose(rotation);
        poseStack.scale((float) scale.x(), (float) scale.y(), (float) scale.z());

        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(
                poseStack,
                lines,
                localRegion,
                1.0F,
                greenBlue,
                greenBlue,
                1.0F);
        poseStack.popPose();
        bufferSource.endBatch(RenderType.lines());
    }

    private static float rejectedTargetPulse(BlockPos pos, TransmissionBoxCorner corner) {
        PulseKey key = new PulseKey(pos.immutable(), corner);
        Long started = REJECTED_TARGET_PULSES.get(key);
        if (started == null) {
            return 0.0F;
        }

        long age = Util.getMillis() - started;
        if (age < 0L || age >= REJECTION_PULSE_MS) {
            REJECTED_TARGET_PULSES.remove(key);
            return 0.0F;
        }

        float progress = age / (float) REJECTION_PULSE_MS;
        float envelope = 1.0F - progress;
        float wave = 0.7F + 0.3F * (float) Math.cos(progress * Math.PI * 4.0);
        return envelope * wave;
    }

    private static AABB targetBoundsLocal(
            TransmissionBoxHitTarget target,
            TransmissionBoxBlockEntity box) {
        double[] min = {0.0, 0.0, 0.0};
        double[] max = {1.0, 1.0, 1.0};

        switch (target.kind()) {
            case FACE -> {
                Direction face = target.face();
                double normal = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0;
                setRange(min, max, face.getAxis(), normal - EPSILON, normal + EPSILON);
                Direction.Axis first = firstTangent(face.getAxis());
                Direction.Axis second = secondTangent(face.getAxis());
                setRange(min, max, first, 0.27, 0.73);
                setRange(min, max, second, 0.27, 0.73);
            }
            case CORNER -> {
                TransmissionBoxCorner corner = target.corner();
                for (Direction.Axis axis : Direction.Axis.values()) {
                    setCornerRange(
                            min,
                            max,
                            axis,
                            corner.sign(axis),
                            TransmissionBoxHitTarget.cornerExtent(axis, box.structuralAxis()));
                }
            }
            case ROTATE, NONE -> {
            }
        }
        return new AABB(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    private static void setCornerRange(
            double[] min,
            double[] max,
            Direction.Axis axis,
            int sign,
            double extent) {
        if (sign < 0) {
            setRange(min, max, axis, 0.0, extent);
        } else {
            setRange(min, max, axis, 1.0 - extent, 1.0);
        }
    }

    private static void setRange(
            double[] min,
            double[] max,
            Direction.Axis axis,
            double lower,
            double upper) {
        int index = switch (axis) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
        min[index] = lower;
        max[index] = upper;
    }

    private static Direction.Axis firstTangent(Direction.Axis normal) {
        return switch (normal) {
            case X -> Direction.Axis.Y;
            case Y -> Direction.Axis.X;
            case Z -> Direction.Axis.X;
        };
    }

    private static Direction.Axis secondTangent(Direction.Axis normal) {
        return switch (normal) {
            case X -> Direction.Axis.Z;
            case Y -> Direction.Axis.Z;
            case Z -> Direction.Axis.Y;
        };
    }

    private record PulseKey(BlockPos pos, TransmissionBoxCorner corner) {
    }
}
