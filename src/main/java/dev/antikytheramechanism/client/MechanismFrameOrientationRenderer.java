package dev.antikytheramechanism.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import java.util.EnumSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

public final class MechanismFrameOrientationRenderer implements BlockEntityRenderer<MechanismFrameBlockEntity> {
    private static final double[][] POSITIONS = {
            {.125, .875, .125},
            {.8125, .875, .125},
            {.8125, .875, .8125},
            {.125, .875, .8125}
    };


    public MechanismFrameOrientationRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(
            MechanismFrameBlockEntity frame,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay) {
        // A placed Frame can only represent HORIZONTAL_FACING. The BE also stores the assembly's
        // full logical orientation so mini regions survive arbitrary Create rotations; rendering
        // that logical transform here made a static block appear pitched/rolled after disassembly.
        FrameOrientation orientation = frame.getPhysicalFrameOrientation();
        Quaterniond quaternion = orientation.quaternion(new Quaterniond());
        poseStack.pushPose();
        poseStack.translate(.5, .5, .5);
        poseStack.mulPose(new Quaternionf(
                (float) quaternion.x,
                (float) quaternion.y,
                (float) quaternion.z,
                (float) quaternion.w));
        poseStack.translate(-.5, -.5, -.5);

        EnumSet<Direction> connectedFaces = EnumSet.noneOf(Direction.class);
        for (Direction direction : Direction.values()) {
            if (MechanismFrameBlock.isConnected(frame.getBlockState(), direction)) {
                connectedFaces.add(direction);
            }
        }
        for (int marker = 0; marker < POSITIONS.length; marker++) {
            if (!FrameOrientationMarkerCulling.shouldRender(connectedFaces, orientation, marker)) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(
                    POSITIONS[marker][0],
                    POSITIONS[marker][1],
                    POSITIONS[marker][2]);
            poseStack.scale(.0625f, .0625f, .0625f);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    colorState(marker), poseStack, buffers, packedLight, packedOverlay);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private static BlockState colorState(int marker) {
        return switch (marker) {
            case 0 -> Blocks.RED_CONCRETE.defaultBlockState();
            case 1 -> Blocks.YELLOW_CONCRETE.defaultBlockState();
            case 2 -> Blocks.LIME_CONCRETE.defaultBlockState();
            case 3 -> Blocks.BLUE_CONCRETE.defaultBlockState();
            default -> throw new IllegalArgumentException("Unknown orientation marker " + marker);
        };
    }
}
