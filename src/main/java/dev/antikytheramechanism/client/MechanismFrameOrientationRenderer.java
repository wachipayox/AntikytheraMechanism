package dev.antikytheramechanism.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.FrameSkin;
import dev.antikytheramechanism.frame.FramePresentationToolHooks;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

import java.util.EnumSet;

public final class MechanismFrameOrientationRenderer implements BlockEntityRenderer<MechanismFrameBlockEntity> {
    private static final double[][] POSITIONS = {
            {.125, .875, .125},
            {.8125, .875, .125},
            {.8125, .875, .8125},
            {.125, .875, .8125}
    };
    private static final double BAR = 2.0 / 16.0;
    private static final double SKIN_OVERDRAW = 0.0015;
    private static final double WIREFRAME_OVERDRAW = 0.003;

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
        BlockState frameState = frame.getBlockState();
        if (frameState.getValue(MechanismFrameBlock.SHELL_MODE) == FrameShellMode.HIDDEN) {
            renderHiddenMaintenanceOverlay(frame, poseStack, buffers);
            return;
        }

        if (frame.getPresentationSkin() != FrameSkin.COPPER) {
            renderSkinBars(frameState, frame.getPresentationSkin(), poseStack, buffers, packedLight, packedOverlay);
        }

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

        EnumSet<Direction> connectedFaces = connectedFaces(frameState);
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

    private static void renderHiddenMaintenanceOverlay(
            MechanismFrameBlockEntity frame,
            PoseStack poseStack,
            MultiBufferSource buffers) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || !FramePresentationToolHooks.isMaintenanceTool(minecraft.player.getMainHandItem())) {
            return;
        }

        boolean selected = minecraft.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(frame.getBlockPos());
        float alpha = selected ? 1.0f : 0.42f;
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        AABB box = new AABB(
                -WIREFRAME_OVERDRAW,
                -WIREFRAME_OVERDRAW,
                -WIREFRAME_OVERDRAW,
                1.0 + WIREFRAME_OVERDRAW,
                1.0 + WIREFRAME_OVERDRAW,
                1.0 + WIREFRAME_OVERDRAW);
        LevelRenderer.renderLineBox(poseStack, lines, box, 1.0f, 1.0f, 1.0f, alpha);

        if (selected) {
            AABB emphasis = box.inflate(WIREFRAME_OVERDRAW * 1.75);
            LevelRenderer.renderLineBox(poseStack, lines, emphasis, 1.0f, 1.0f, 1.0f, 0.72f);
        }
    }

    private static void renderSkinBars(
            BlockState frameState,
            FrameSkin skin,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay) {
        Block material = BuiltInRegistries.BLOCK.get(skin.blockId());
        if (material == Blocks.AIR) {
            material = Blocks.COPPER_BLOCK;
        }
        BlockState materialState = material.defaultBlockState();

        for (Direction ySide : new Direction[]{Direction.DOWN, Direction.UP}) {
            for (Direction zSide : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                if (!MechanismFrameBlock.isConnected(frameState, ySide)
                        && !MechanismFrameBlock.isConnected(frameState, zSide)) {
                    double y0 = ySide == Direction.DOWN ? 0 : 1 - BAR;
                    double z0 = zSide == Direction.NORTH ? 0 : 1 - BAR;
                    renderCuboid(materialState, poseStack, buffers, packedLight, packedOverlay,
                            0, y0, z0, 1, BAR, BAR);
                }
            }
        }
        for (Direction xSide : new Direction[]{Direction.WEST, Direction.EAST}) {
            for (Direction zSide : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                if (!MechanismFrameBlock.isConnected(frameState, xSide)
                        && !MechanismFrameBlock.isConnected(frameState, zSide)) {
                    double x0 = xSide == Direction.WEST ? 0 : 1 - BAR;
                    double z0 = zSide == Direction.NORTH ? 0 : 1 - BAR;
                    renderCuboid(materialState, poseStack, buffers, packedLight, packedOverlay,
                            x0, 0, z0, BAR, 1, BAR);
                }
            }
        }
        for (Direction xSide : new Direction[]{Direction.WEST, Direction.EAST}) {
            for (Direction ySide : new Direction[]{Direction.DOWN, Direction.UP}) {
                if (!MechanismFrameBlock.isConnected(frameState, xSide)
                        && !MechanismFrameBlock.isConnected(frameState, ySide)) {
                    double x0 = xSide == Direction.WEST ? 0 : 1 - BAR;
                    double y0 = ySide == Direction.DOWN ? 0 : 1 - BAR;
                    renderCuboid(materialState, poseStack, buffers, packedLight, packedOverlay,
                            x0, y0, 0, BAR, BAR, 1);
                }
            }
        }
    }

    private static void renderCuboid(
            BlockState material,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay,
            double x,
            double y,
            double z,
            double sizeX,
            double sizeY,
            double sizeZ) {
        poseStack.pushPose();
        poseStack.translate(x - SKIN_OVERDRAW, y - SKIN_OVERDRAW, z - SKIN_OVERDRAW);
        poseStack.scale(
                (float) (sizeX + SKIN_OVERDRAW * 2),
                (float) (sizeY + SKIN_OVERDRAW * 2),
                (float) (sizeZ + SKIN_OVERDRAW * 2));
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                material, poseStack, buffers, packedLight, packedOverlay);
        poseStack.popPose();
    }

    private static EnumSet<Direction> connectedFaces(BlockState state) {
        EnumSet<Direction> result = EnumSet.noneOf(Direction.class);
        for (Direction direction : Direction.values()) {
            if (MechanismFrameBlock.isConnected(state, direction)) {
                result.add(direction);
            }
        }
        return result;
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
