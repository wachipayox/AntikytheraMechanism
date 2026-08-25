package dev.antikytheramechanism.compat.create.transmission.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxBlockEntity;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxCogMode;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxCorner;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxFaceMode;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Dynamic face skins, Create-style shafts and half-scale cogwheels over the static gearbox casing. */
public final class TransmissionBoxRenderer extends SafeBlockEntityRenderer<TransmissionBoxBlockEntity> {
    private static final PartialModel CLOSED_FACE = PartialModel.of(
            AntikytheraMechanism.id("block/transmission_box_face_closed"));
    private static final PartialModel MACRO_FACE = PartialModel.of(
            AntikytheraMechanism.id("block/transmission_box_face_macro"));
    private static final PartialModel MICRO_FACE = PartialModel.of(
            AntikytheraMechanism.id("block/transmission_box_face_micro"));

    private TransmissionBoxRenderer() {
    }

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
            if (face.getAxis() == box.structuralAxis()) {
                // The two structural faces deliberately keep Create's original gearbox texture.
                continue;
            }
            TransmissionBoxFaceMode mode = box.faceMode(face);
            renderFaceSkin(box, face, mode, poseStack, buffers, light);
            if (mode == TransmissionBoxFaceMode.MACRO) {
                BlockState shaftState = KineticBlockEntityRenderer.shaft(face.getAxis());
                float offset = KineticBlockEntityVisual.rotationOffset(
                        shaftState, face.getAxis(), box.getBlockPos());
                renderShaft(box, face, 1.0F, box.sideSign(face), time, offset,
                        poseStack, buffers, light);
            } else if (mode == TransmissionBoxFaceMode.MICRO) {
                for (int first = 0; first < 2; first++) {
                    for (int second = 0; second < 2; second++) {
                        int[] cell = faceCell(face, first, second);
                        TransmissionBoxCorner portCorner = TransmissionBoxCorner.fromSigns(
                                cell[0] == 0 ? -1 : 1,
                                cell[1] == 0 ? -1 : 1,
                                cell[2] == 0 ? -1 : 1);
                        if (box.cornerMode(portCorner) != TransmissionBoxCogMode.EMPTY) {
                            continue;
                        }

                        // A half-scale shaft occupies one cell in a physical lattice whose macro block
                        // width is exactly two cells. Feed that lattice coordinate through Create's own
                        // checkerboard phase function rather than inventing a renderer-local offset.
                        BlockPos microPos = microCell(box.getBlockPos(), cell[0], cell[1], cell[2]);
                        BlockState shaftState = KineticBlockEntityRenderer.shaft(face.getAxis());
                        float offset = KineticBlockEntityVisual.rotationOffset(
                                shaftState, face.getAxis(), microPos);

                        poseStack.pushPose();
                        poseStack.translate(cell[0] * 0.5, cell[1] * 0.5, cell[2] * 0.5);
                        poseStack.scale(0.5F, 0.5F, 0.5F);
                        renderShaft(box, face, 2.0F, box.sideSign(face), time, offset,
                                poseStack, buffers, light);
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
            SuperByteBuffer cog = CachedBuffers.partialFacingVertical(
                    mode == TransmissionBoxCogMode.SMALL
                            ? AllPartialModels.SHAFTLESS_COGWHEEL
                            : AllPartialModels.SHAFTLESS_LARGE_COGWHEEL,
                    cogState,
                    Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE));
            BlockPos microPos = microCell(
                    box.getBlockPos(),
                    corner.cell(Direction.Axis.X),
                    corner.cell(Direction.Axis.Y),
                    corner.cell(Direction.Axis.Z));
            float offset = KineticBlockEntityVisual.rotationOffset(cogState, axis, microPos);
            float angle = angleRadians(box.getSpeed(), 2.0F, time, offset);

            poseStack.pushPose();
            poseStack.translate(
                    corner.cell(Direction.Axis.X) * 0.5,
                    corner.cell(Direction.Axis.Y) * 0.5,
                    corner.cell(Direction.Axis.Z) * 0.5);
            poseStack.scale(0.5F, 0.5F, 0.5F);
            SuperByteBuffer transformed = KineticBlockEntityRenderer.kineticRotationTransform(
                    cog, box, axis, angle, light);
            float rejectionPulse = CreateTransmissionClient.blockingCogPulse(box.getBlockPos(), corner);
            if (rejectionPulse > 0.0F) {
                int greenBlue = Math.max(0, Math.min(255, Math.round(255.0F * (1.0F - 0.9F * rejectionPulse))));
                transformed.color(0xFF0000 | greenBlue << 8 | greenBlue);
            }
            transformed.renderInto(poseStack, buffers.getBuffer(RenderType.solid()));
            poseStack.popPose();
        }
    }

    private static void renderFaceSkin(
            TransmissionBoxBlockEntity box,
            Direction face,
            TransmissionBoxFaceMode mode,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int light) {
        PartialModel partial = switch (mode) {
            case CLOSED -> CLOSED_FACE;
            case MACRO -> MACRO_FACE;
            case MICRO -> MICRO_FACE;
        };
        CachedBuffers.partialFacing(partial, box.getBlockState(), face)
                .light(light)
                .renderInto(poseStack, buffers.getBuffer(RenderType.solid()));
    }

    private static void renderShaft(
            TransmissionBoxBlockEntity box,
            Direction face,
            float ratio,
            int sign,
            float time,
            float offsetDegrees,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int light) {
        SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, box.getBlockState(), face);
        float angle = angleRadians(box.getSpeed(), ratio * sign, time, offsetDegrees);
        KineticBlockEntityRenderer.kineticRotationTransform(
                        shaft,
                        box,
                        face.getAxis(),
                        angle,
                        light)
                .renderInto(poseStack, buffers.getBuffer(RenderType.solid()));
    }

    /** Create applies positional phase after any sign inversion of the time-based rotation. */
    private static float angleRadians(float speed, float multiplier, float time, float offsetDegrees) {
        float degrees = (time * speed * multiplier * 3.0F / 10.0F) % 360.0F;
        degrees += offsetDegrees;
        return degrees / 180.0F * (float) Math.PI;
    }

    private static BlockPos microCell(BlockPos box, int x, int y, int z) {
        return new BlockPos(
                box.getX() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + x,
                box.getY() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + y,
                box.getZ() * MiniCoordinateMapper.CELLS_PER_FRAME_AXIS + z);
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
