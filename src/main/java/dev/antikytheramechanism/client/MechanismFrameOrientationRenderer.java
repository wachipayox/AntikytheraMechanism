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
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

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
    private static final long SKIN_MODEL_SEED = 42L;

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
            renderSkinBars(
                    frame,
                    frameState,
                    frame.getPresentationSkin(),
                    poseStack,
                    buffers,
                    packedLight,
                    packedOverlay);
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
            MechanismFrameBlockEntity frame,
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
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(materialState);
        RenderType renderType = ItemBlockRenderTypes.getChunkRenderType(materialState);
        Map<Direction, TextureAtlasSprite> faceSprites = resolveFaceSprites(materialState, model, renderType);
        if (faceSprites.isEmpty()) {
            return;
        }

        Map<Direction, CreateConnectedTextureFrameSkinResolver.UvTransform> ctTransforms =
                CreateConnectedTextureFrameSkinResolver.resolve(
                        frame,
                        skin,
                        materialState,
                        model,
                        renderType,
                        faceSprites);
        EnumMap<Direction, FaceTexture> faceTextures = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, TextureAtlasSprite> entry : faceSprites.entrySet()) {
            faceTextures.put(
                    entry.getKey(),
                    new FaceTexture(
                            entry.getValue(),
                            ctTransforms.getOrDefault(
                                    entry.getKey(),
                                    CreateConnectedTextureFrameSkinResolver.UvTransform.IDENTITY)));
        }

        VertexConsumer consumer = buffers.getBuffer(renderType);

        for (Direction ySide : new Direction[]{Direction.DOWN, Direction.UP}) {
            for (Direction zSide : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                if (!MechanismFrameBlock.isConnected(frameState, ySide)
                        && !MechanismFrameBlock.isConnected(frameState, zSide)) {
                    double y0 = ySide == Direction.DOWN ? 0 : 1 - BAR;
                    double z0 = zSide == Direction.NORTH ? 0 : 1 - BAR;
                    renderTexturedCuboid(faceTextures, consumer, poseStack, packedLight, packedOverlay,
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
                    renderTexturedCuboid(faceTextures, consumer, poseStack, packedLight, packedOverlay,
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
                    renderTexturedCuboid(faceTextures, consumer, poseStack, packedLight, packedOverlay,
                            x0, y0, 0, BAR, BAR, 1);
                }
            }
        }
    }

    /**
     * Resolves one representative atlas sprite per block face. All currently selectable skins are
     * cube-style casing models, but the unculled-quad fallback keeps the renderer fail-soft for a
     * future model that does not expose directional quads.
     */
    private static Map<Direction, TextureAtlasSprite> resolveFaceSprites(
            BlockState materialState,
            BakedModel model,
            RenderType renderType) {
        RandomSource random = RandomSource.create();
        EnumMap<Direction, TextureAtlasSprite> result = new EnumMap<>(Direction.class);
        TextureAtlasSprite fallback = null;

        for (Direction direction : Direction.values()) {
            random.setSeed(SKIN_MODEL_SEED);
            List<BakedQuad> quads = model.getQuads(
                    materialState,
                    direction,
                    random,
                    ModelData.EMPTY,
                    renderType);
            if (!quads.isEmpty()) {
                TextureAtlasSprite sprite = quads.getFirst().getSprite();
                result.put(direction, sprite);
                if (fallback == null) {
                    fallback = sprite;
                }
            }
        }

        if (fallback == null) {
            random.setSeed(SKIN_MODEL_SEED);
            List<BakedQuad> unculled = model.getQuads(
                    materialState,
                    null,
                    random,
                    ModelData.EMPTY,
                    renderType);
            if (!unculled.isEmpty()) {
                fallback = unculled.getFirst().getSprite();
            }
        }
        if (fallback != null) {
            for (Direction direction : Direction.values()) {
                result.putIfAbsent(direction, fallback);
            }
        }
        return result;
    }

    /**
     * Draws a Frame bar at its real size while keeping the source block's UV scale. Geometry gets a
     * tiny overdraw to cover the copper base model, but UVs are derived from the nominal 0..16 block
     * coordinates, so a two-pixel Frame section samples two pixels of the casing instead of squeezing
     * the complete 16x16 face into it. Connected Create skins additionally map those source UVs into
     * the tile selected by the skin's own CTModel.
     */
    private static void renderTexturedCuboid(
            Map<Direction, FaceTexture> textures,
            VertexConsumer consumer,
            PoseStack poseStack,
            int packedLight,
            int packedOverlay,
            double x,
            double y,
            double z,
            double sizeX,
            double sizeY,
            double sizeZ) {
        float x0 = (float) (x - SKIN_OVERDRAW);
        float y0 = (float) (y - SKIN_OVERDRAW);
        float z0 = (float) (z - SKIN_OVERDRAW);
        float x1 = (float) (x + sizeX + SKIN_OVERDRAW);
        float y1 = (float) (y + sizeY + SKIN_OVERDRAW);
        float z1 = (float) (z + sizeZ + SKIN_OVERDRAW);

        float px0 = (float) (x * 16.0);
        float py0 = (float) (y * 16.0);
        float pz0 = (float) (z * 16.0);
        float px1 = (float) ((x + sizeX) * 16.0);
        float py1 = (float) ((y + sizeY) * 16.0);
        float pz1 = (float) ((z + sizeZ) * 16.0);

        PoseStack.Pose pose = poseStack.last();

        // Vanilla BlockElement's implicit UV projection, restricted to this bar's actual pixels.
        renderFace(consumer, pose, textures.get(Direction.DOWN), Direction.DOWN, packedLight, packedOverlay,
                x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1,
                px0, 16 - pz1, px1, 16 - pz0);
        renderFace(consumer, pose, textures.get(Direction.UP), Direction.UP, packedLight, packedOverlay,
                x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0,
                px0, pz0, px1, pz1);
        renderVerticalFace(consumer, pose, textures.get(Direction.NORTH), Direction.NORTH, packedLight, packedOverlay,
                x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0,
                16 - px1, 16 - py1, 16 - px0, 16 - py0);
        renderVerticalFace(consumer, pose, textures.get(Direction.SOUTH), Direction.SOUTH, packedLight, packedOverlay,
                x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1,
                px0, 16 - py1, px1, 16 - py0);
        renderVerticalFace(consumer, pose, textures.get(Direction.WEST), Direction.WEST, packedLight, packedOverlay,
                x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0,
                pz0, 16 - py1, pz1, 16 - py0);
        renderVerticalFace(consumer, pose, textures.get(Direction.EAST), Direction.EAST, packedLight, packedOverlay,
                x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1,
                16 - pz1, 16 - py1, 16 - pz0, 16 - py0);
    }

    private static void renderVerticalFace(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            FaceTexture texture,
            Direction direction,
            int packedLight,
            int packedOverlay,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float u0, float v0, float u1, float v1) {
        // Our vertical winding starts at the lower high-U corner.
        renderFaceVertices(consumer, pose, texture, direction, packedLight, packedOverlay,
                x0, y0, z0, u1, v1,
                x1, y1, z1, u1, v0,
                x2, y2, z2, u0, v0,
                x3, y3, z3, u0, v1);
    }

    private static void renderFace(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            FaceTexture texture,
            Direction direction,
            int packedLight,
            int packedOverlay,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float u0, float v0, float u1, float v1) {
        renderFaceVertices(consumer, pose, texture, direction, packedLight, packedOverlay,
                x0, y0, z0, u0, v0,
                x1, y1, z1, u0, v1,
                x2, y2, z2, u1, v1,
                x3, y3, z3, u1, v0);
    }

    private static void renderFaceVertices(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            FaceTexture texture,
            Direction direction,
            int packedLight,
            int packedOverlay,
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3) {
        if (texture == null) {
            return;
        }
        int shade = Math.round(faceShade(direction) * 255.0f);
        vertex(consumer, pose, texture, direction, packedLight, packedOverlay, shade, x0, y0, z0, u0, v0);
        vertex(consumer, pose, texture, direction, packedLight, packedOverlay, shade, x1, y1, z1, u1, v1);
        vertex(consumer, pose, texture, direction, packedLight, packedOverlay, shade, x2, y2, z2, u2, v2);
        vertex(consumer, pose, texture, direction, packedLight, packedOverlay, shade, x3, y3, z3, u3, v3);
    }

    private static void vertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            FaceTexture texture,
            Direction direction,
            int packedLight,
            int packedOverlay,
            int shade,
            float x, float y, float z,
            float u, float v) {
        consumer.addVertex(pose, x, y, z)
                .setColor(shade, shade, shade, 255)
                .setUv(texture.u(u), texture.v(v))
                .setOverlay(packedOverlay)
                .setLight(packedLight)
                .setNormal(pose, direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static float faceShade(Direction direction) {
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
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

    private record FaceTexture(
            TextureAtlasSprite sprite,
            CreateConnectedTextureFrameSkinResolver.UvTransform transform) {
        private float u(float modelU) {
            return transform.mapU(sprite.getU(modelU / 16.0f));
        }

        private float v(float modelV) {
            return transform.mapV(sprite.getV(modelV / 16.0f));
        }
    }
}
