package dev.antikytheramechanism.compat.create.transmission.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxBlockEntity;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxCogMode;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxCorner;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxFaceMode;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Dynamic Create-style shafts and half-scale cogwheels over the static gearbox casing model. */
public final class TransmissionBoxRenderer extends SafeBlockEntityRenderer<TransmissionBoxBlockEntity> {
    public TransmissionBoxRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(
            TransmissionBoxBlockEntity box,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int light,
            int overlay) {
        float time = AnimationTickHolder.getRenderTime(box.getLevel());

        for (Direction face : Direction.values()) {
            TransmissionBoxFaceMode mode = box.faceMode(face);
            if (mode == TransmissionBoxFaceMode.MACRO) {
                renderShaft(box, face, 1.0F, box.sideSign(face), time, poseStack, buffers, light);
            } else if (mode == TransmissionBoxFaceMode.MICRO) {
                for (int first = 0; first < 2; first++) {
                    for (int second = 0; second < 2; second++) {
                        int[] cell = faceCell(face, first, second);
                        poseStack.pushPose();
                        poseStack.translate(cell[0] * 0.5, cell[1] * 0.5, cell[2] * 0.5);
                        poseStack.scale(0.5F, 0.5F, 0.5F);
                        renderShaft(box, face, 2.0F, box.sideSign(face), time, poseStack, buffers, light);
                        poseStack.popPose();
                    }
                }
            }
        }

        Direction.Axis axis = box.structuralAxis();
        for (TransmissionBoxCorner corner : TransmissionBoxCorner.values()) {
            TransmissionBoxCogMode mode = box.cornerMode(corner);
            if (mode == TransmissionBoxCogMode.EMPTY) {
                continue;
            }
            BlockState cogState = (mode == TransmissionBoxCogMode.SMALL
                    ? AllBlocks.COGWHEEL.getDefaultState()
                    : AllBlocks.LARGE_COGWHEEL.getDefaultState())
                    .setValue(BlockStateProperties.AXIS, axis);
            SuperByteBuffer cog = CachedBuffers.block(KineticBlockEntityRenderer.KINETIC_BLOCK, cogState);
            float angle = angleRadians(box.getSpeed(), 2.0F, time);

            poseStack.pushPose();
            poseStack.translate(
                    corner.cell(Direction.Axis.X) * 0.5,
                    corner.cell(Direction.Axis.Y) * 0.5,
                    corner.cell(Direction.Axis.Z) * 0.5);
            poseStack.scale(0.5F, 0.5F, 0.5F);
            KineticBlockEntityRenderer.kineticRotationTransform(cog, box, axis, angle, light)
                    .renderInto(poseStack, buffers.getBuffer(RenderType.solid()));
            poseStack.popPose();
        }
    }

    private static void renderShaft(
            TransmissionBoxBlockEntity box,
            Direction face,
            float ratio,
            int sign,
            float time,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int light) {
        SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, box.getBlockState(), face);
        float angle = angleRadians(box.getSpeed(), ratio * sign, time);
        KineticBlockEntityRenderer.kineticRotationTransform(
                        shaft,
                        box,
                        face.getAxis(),
                        angle,
                        light)
                .renderInto(poseStack, buffers.getBuffer(RenderType.solid()));
    }

    private static float angleRadians(float speed, float multiplier, float time) {
        float degrees = (time * speed * multiplier * 3.0F / 10.0F) % 360.0F;
        return degrees / 180.0F * (float) Math.PI;
    }

    private static int[] faceCell(Direction face, int first, int second) {
        int x = 0;
        int y = 0;
        int z = 0;
        switch (face.getAxis()) {
            case X -> {
                x = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
                y = first;
                z = second;
            }
            case Y -> {
                y = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
                x = first;
                z = second;
            }
            case Z -> {
                z = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
                x = first;
                y = second;
            }
        }
        return new int[] {x, y, z};
    }
}
