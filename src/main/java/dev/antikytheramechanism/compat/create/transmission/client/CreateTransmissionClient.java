package dev.antikytheramechanism.compat.create.transmission.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllItems;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.compat.create.transmission.CreateTransmissionRegistries;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxBlockEntity;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxCorner;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxHitTarget;
import dev.antikytheramechanism.frame.FramePresentationToolHooks;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Create-only client registration, wrench targeting and rejected-cog feedback. */
public final class CreateTransmissionClient {
    private static final double EPSILON = 0.0025;
    private static final long REJECTION_PULSE_MS = 550L;
    private static final Map<PulseKey, Long> REJECTED_TARGET_PULSES = new HashMap<>();
    private static final Map<PulseKey, Long> BLOCKING_COG_PULSES = new HashMap<>();

    private CreateTransmissionClient() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(CreateTransmissionClient::registerAdditionalModels);
        modBus.addListener(CreateTransmissionClient::registerRenderers);
        NeoForge.EVENT_BUS.addListener(CreateTransmissionClient::suppressHiddenFrameVanillaOutline);
        NeoForge.EVENT_BUS.addListener(CreateTransmissionClient::renderTargetRegion);
        NeoForge.EVENT_BUS.addListener(CreateTransmissionClient::trackRejectedCornerClick);
    }

    private static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(ModelResourceLocation.standalone(
                AntikytheraMechanism.id("block/transmission_box_face_closed")));
        event.register(ModelResourceLocation.standalone(
                AntikytheraMechanism.id("block/transmission_box_face_macro")));
        event.register(ModelResourceLocation.standalone(
                AntikytheraMechanism.id("block/transmission_box_face_micro")));
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                CreateTransmissionRegistries.TRANSMISSION_BOX_BLOCK_ENTITY.get(),
                TransmissionBoxRenderer::new);
    }

    /**
     * Hidden Frames deliberately expose a thin pick shape while a maintenance wrench is held so
     * Create's own white wrench feedback can target them. Minecraft would additionally draw its dark
     * vanilla block-selection outline around that synthetic pick shape; suppress only that redundant
     * outline and leave the targeting shape itself untouched.
     */
    private static void suppressHiddenFrameVanillaOutline(RenderHighlightEvent.Block event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        BlockHitResult hit = event.getTarget();
        BlockState state = minecraft.level.getBlockState(hit.getBlockPos());
        if (!(state.getBlock() instanceof MechanismFrameBlock)
                || state.getValue(MechanismFrameBlock.SHELL_MODE) != FrameShellMode.HIDDEN
                || !(FramePresentationToolHooks.isMaintenanceTool(player.getMainHandItem())
                || FramePresentationToolHooks.isMaintenanceTool(player.getOffhandItem()))) {
            return;
        }
        event.setCanceled(true);
    }

    private static void trackRejectedCornerClick(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!event.getLevel().isClientSide
                || player == null
                || player.isShiftKeyDown()
                || !AllItems.WRENCH.isIn(event.getItemStack())) {
            return;
        }
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof TransmissionBoxBlockEntity box)) {
            return;
        }

        BlockHitResult hit = event.getHitVec();
        TransmissionBoxHitTarget target = TransmissionBoxHitTarget.resolve(hit, box);
        if (target.kind() != TransmissionBoxHitTarget.Kind.CORNER || target.corner() == null) {
            return;
        }

        Set<TransmissionBoxCorner> blockers = box.blockersForNextCornerMode(target.corner());
        if (blockers.isEmpty()) {
            return;
        }
        long now = Util.getMillis();
        BlockPos pos = event.getPos().immutable();
        REJECTED_TARGET_PULSES.put(new PulseKey(pos, target.corner()), now);
        for (TransmissionBoxCorner blocker : blockers) {
            BLOCKING_COG_PULSES.put(new PulseKey(pos, blocker), now);
        }
    }

    private static void renderTargetRegion(RenderHighlightEvent.Block event) {
        Player player = Minecraft.getInstance().player;
        if (player == null
                || player.isShiftKeyDown()
                || !(AllItems.WRENCH.isIn(player.getMainHandItem())
                || AllItems.WRENCH.isIn(player.getOffhandItem()))) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        BlockHitResult hit = event.getTarget();
        if (!minecraft.level.getBlockState(hit.getBlockPos())
                .is(CreateTransmissionRegistries.TRANSMISSION_BOX.get())) {
            return;
        }
        if (!(minecraft.level.getBlockEntity(hit.getBlockPos())
                instanceof TransmissionBoxBlockEntity box)) {
            return;
        }

        TransmissionBoxHitTarget target = TransmissionBoxHitTarget.resolve(hit, box);
        // Axial faces rotate through Create's ordinary wrench interaction and intentionally have no
        // custom selection rectangle. Non-interactive gaps on configurable faces are also unmarked.
        if (target.kind() == TransmissionBoxHitTarget.Kind.NONE
                || target.kind() == TransmissionBoxHitTarget.Kind.ROTATE) {
            return;
        }

        AABB region = targetBounds(hit.getBlockPos(), target, box);
        float rejection = target.kind() == TransmissionBoxHitTarget.Kind.CORNER && target.corner() != null
                ? rejectedTargetPulse(hit.getBlockPos(), target.corner())
                : 0.0F;
        float greenBlue = 1.0F - 0.9F * rejection;

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer lines = event.getMultiBufferSource().getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(
                poseStack,
                lines,
                region,
                1.0F,
                greenBlue,
                greenBlue,
                1.0F);
        poseStack.popPose();
        event.setCanceled(true);
    }

    static float blockingCogPulse(BlockPos pos, TransmissionBoxCorner corner) {
        return pulseStrength(BLOCKING_COG_PULSES, new PulseKey(pos.immutable(), corner));
    }

    private static float rejectedTargetPulse(BlockPos pos, TransmissionBoxCorner corner) {
        return pulseStrength(REJECTED_TARGET_PULSES, new PulseKey(pos.immutable(), corner));
    }

    private static float pulseStrength(Map<PulseKey, Long> pulses, PulseKey key) {
        Long started = pulses.get(key);
        if (started == null) {
            return 0.0F;
        }
        long age = Util.getMillis() - started;
        if (age < 0L || age >= REJECTION_PULSE_MS) {
            pulses.remove(key);
            return 0.0F;
        }
        float progress = age / (float) REJECTION_PULSE_MS;
        float envelope = 1.0F - progress;
        float wave = 0.7F + 0.3F * (float) Math.cos(progress * Math.PI * 4.0);
        return envelope * wave;
    }

    private static AABB targetBounds(
            BlockPos pos,
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
        return new AABB(
                pos.getX() + min[0],
                pos.getY() + min[1],
                pos.getZ() + min[2],
                pos.getX() + max[0],
                pos.getY() + max[1],
                pos.getZ() + max[2]);
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
