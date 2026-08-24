package dev.antikytheramechanism.compat.create.transmission.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllItems;
import dev.antikytheramechanism.compat.create.transmission.CreateTransmissionRegistries;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxBlockEntity;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxCorner;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxHitTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Create-only client registration and wrench target overlay. */
public final class CreateTransmissionClient {
    private static final double EPSILON = 0.0025;

    private CreateTransmissionClient() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(CreateTransmissionClient::registerRenderers);
        NeoForge.EVENT_BUS.addListener(CreateTransmissionClient::renderTargetRegion);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                CreateTransmissionRegistries.TRANSMISSION_BOX_BLOCK_ENTITY.get(),
                TransmissionBoxRenderer::new);
    }

    private static void renderTargetRegion(RenderHighlightEvent.Block event) {
        Player player = Minecraft.getInstance().player;
        if (player == null
                || player.isShiftKeyDown()
                || !(AllItems.WRENCH.isIn(player.getMainHandItem())
                || AllItems.WRENCH.isIn(player.getOffhandItem()))) {
            return;
        }

        BlockHitResult hit = event.getTarget();
        if (!Minecraft.getInstance().level.getBlockState(hit.getBlockPos())
                .is(CreateTransmissionRegistries.TRANSMISSION_BOX.get())) {
            return;
        }
        if (!(Minecraft.getInstance().level.getBlockEntity(hit.getBlockPos())
                instanceof TransmissionBoxBlockEntity box)) {
            return;
        }

        TransmissionBoxHitTarget target = TransmissionBoxHitTarget.resolve(hit, box);
        if (target.kind() == TransmissionBoxHitTarget.Kind.NONE) {
            return;
        }

        AABB region = targetBounds(hit.getBlockPos(), target);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer lines = event.getMultiBufferSource().getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(poseStack, lines, region, 1.0F, 1.0F, 1.0F, 1.0F);
        poseStack.popPose();
        event.setCanceled(true);
    }

    private static AABB targetBounds(BlockPos pos, TransmissionBoxHitTarget target) {
        Direction face = target.face();
        double[] min = {0.0, 0.0, 0.0};
        double[] max = {1.0, 1.0, 1.0};

        double normal = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0;
        setRange(min, max, face.getAxis(), normal - EPSILON, normal + EPSILON);

        Direction.Axis first = firstTangent(face.getAxis());
        Direction.Axis second = secondTangent(face.getAxis());
        switch (target.kind()) {
            case FACE -> {
                setRange(min, max, first, 0.27, 0.73);
                setRange(min, max, second, 0.27, 0.73);
            }
            case CORNER -> {
                TransmissionBoxCorner corner = target.corner();
                setCornerRange(min, max, first, corner.sign(first));
                setCornerRange(min, max, second, corner.sign(second));
            }
            case ROTATE -> {
                setRange(min, max, first, 0.08, 0.92);
                setRange(min, max, second, 0.08, 0.92);
            }
            case NONE -> {
            }
        }
        return new AABB(
                pos.getX() + min[0],
                pos.getY() + min[1],
                pos.getZ() + min[2],
                pos.getX() + max[0],
                pos.getY() + max[1],
                pos.getZ() + max[2]);
    }

    private static void setCornerRange(double[] min, double[] max, Direction.Axis axis, int sign) {
        if (sign < 0) {
            setRange(min, max, axis, 0.0, 0.22);
        } else {
            setRange(min, max, axis, 0.78, 1.0);
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
}
