package dev.antikytheramechanism.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.antikytheramechanism.assembly.FrameShellMode;
import dev.antikytheramechanism.assembly.FrameSkin;
import dev.antikytheramechanism.frame.MechanismFrameBlock;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Resolves Create connected-texture UV transforms for Frame skins without linking Antikythera's
 * common/client classes against Create at class-load time.
 *
 * <p>Create's {@code CTModel} already contains the authoritative CT behaviour for every casing. We
 * feed that model a deliberately synthetic world view in which only visible, same-skin, physically
 * connected Mechanism Frames exist. The transformed quads returned by the model are then compared
 * with their untransformed counterparts to recover the affine atlas-UV mapping Create applied. The
 * Frame renderer can consequently keep its existing 0..16 proportional crop while sampling from the
 * correct connected tile.</p>
 *
 * <p>If Create is absent, the model is not a CTModel, a model behaves unexpectedly, or UV recovery
 * cannot be proven affine, this helper returns identity transforms and the existing skin renderer is
 * used unchanged.</p>
 */
final class CreateConnectedTextureFrameSkinResolver {
    private static final String CT_MODEL_CLASS_NAME =
            "com.simibubi.create.foundation.block.connected.CTModel";
    private static final long MODEL_SEED = 42L;
    private static final int VERTEX_STRIDE = DefaultVertexFormat.BLOCK.getVertexSize() / Integer.BYTES;
    private static final int U_OFFSET = 4;
    private static final int V_OFFSET = 5;
    private static final double AXIS_EPSILON = 1.0e-8;
    private static final double VERIFY_EPSILON = 2.0e-5;
    private static final int MAX_CACHE_ENTRIES_PER_MODEL = 2048;

    private static final Map<BakedModel, FaceCache> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean ctModelClassResolved;
    private static volatile Class<?> ctModelClass;

    private CreateConnectedTextureFrameSkinResolver() {
    }

    static Map<Direction, UvTransform> resolve(
            MechanismFrameBlockEntity frame,
            FrameSkin skin,
            BlockState materialState,
            BakedModel model,
            RenderType renderType,
            Map<Direction, TextureAtlasSprite> sourceSprites) {
        if (frame.getLevel() == null || sourceSprites.isEmpty() || !isCreateCtModel(model)) {
            return Map.of();
        }

        BlockAndTintGetter world = (BlockAndTintGetter) frame.getLevel();
        EnumMap<Direction, UvTransform> resolved = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, TextureAtlasSprite> entry : sourceSprites.entrySet()) {
            Direction face = entry.getKey();
            TextureAtlasSprite sourceSprite = entry.getValue();
            if (sourceSprite == null) {
                continue;
            }

            SyntheticFrameView view = new SyntheticFrameView(
                    world,
                    frame.getBlockPos(),
                    skin,
                    materialState,
                    face);
            FaceKey key = new FaceKey(
                    frame.getBlockPos().asLong(),
                    materialState,
                    skin,
                    face,
                    view.topologyMask());

            UvTransform transform = cached(model, key);
            if (transform == null) {
                transform = recoverTransform(
                        model,
                        materialState,
                        renderType,
                        face,
                        sourceSprite,
                        view,
                        frame.getBlockPos());
                cache(model, key, transform);
            }
            resolved.put(face, transform);
        }
        return resolved;
    }

    private static boolean isCreateCtModel(BakedModel model) {
        Class<?> type = ctModelClass();
        return type != null && type.isInstance(model);
    }

    private static Class<?> ctModelClass() {
        if (ctModelClassResolved) {
            return ctModelClass;
        }
        synchronized (CreateConnectedTextureFrameSkinResolver.class) {
            if (ctModelClassResolved) {
                return ctModelClass;
            }
            try {
                ctModelClass = Class.forName(
                        CT_MODEL_CLASS_NAME,
                        false,
                        CreateConnectedTextureFrameSkinResolver.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError ignored) {
                ctModelClass = null;
            }
            ctModelClassResolved = true;
            return ctModelClass;
        }
    }

    private static UvTransform recoverTransform(
            BakedModel model,
            BlockState materialState,
            RenderType renderType,
            Direction face,
            TextureAtlasSprite sourceSprite,
            SyntheticFrameView view,
            BlockPos framePos) {
        try {
            RandomSource random = RandomSource.create();
            random.setSeed(MODEL_SEED);
            List<BakedQuad> baseQuads = model.getQuads(
                    materialState,
                    face,
                    random,
                    ModelData.EMPTY,
                    renderType);

            ModelData connectedData = model.getModelData(
                    view,
                    framePos,
                    materialState,
                    ModelData.EMPTY);
            random.setSeed(MODEL_SEED);
            List<BakedQuad> connectedQuads = model.getQuads(
                    materialState,
                    face,
                    random,
                    connectedData,
                    renderType);

            if (baseQuads.size() != connectedQuads.size()) {
                return UvTransform.IDENTITY;
            }

            for (int i = 0; i < baseQuads.size(); i++) {
                BakedQuad base = baseQuads.get(i);
                if (base.getSprite() != sourceSprite) {
                    continue;
                }
                BakedQuad connected = connectedQuads.get(i);
                UvTransform transform = solveTransform(base, connected);
                if (transform != null) {
                    return transform;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Optional Create/model integration must remain fail-soft for arbitrary future skins.
        }
        return UvTransform.IDENTITY;
    }

    private static UvTransform solveTransform(BakedQuad base, BakedQuad connected) {
        int[] baseVertices = base.getVertices();
        int[] connectedVertices = connected.getVertices();
        int requiredLength = VERTEX_STRIDE * 4;
        if (VERTEX_STRIDE <= V_OFFSET
                || baseVertices.length < requiredLength
                || connectedVertices.length < requiredLength) {
            return null;
        }

        AxisTransform u = solveAxis(baseVertices, connectedVertices, U_OFFSET);
        AxisTransform v = solveAxis(baseVertices, connectedVertices, V_OFFSET);
        if (u == null || v == null) {
            return null;
        }
        return new UvTransform(u.scale, u.offset, v.scale, v.offset);
    }

    private static AxisTransform solveAxis(int[] baseVertices, int[] connectedVertices, int offset) {
        double[] source = new double[4];
        double[] target = new double[4];
        for (int vertex = 0; vertex < 4; vertex++) {
            source[vertex] = readUv(baseVertices, vertex, offset);
            target[vertex] = readUv(connectedVertices, vertex, offset);
        }

        int first = -1;
        int second = -1;
        double widest = 0.0;
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                double delta = Math.abs(source[j] - source[i]);
                if (delta > widest) {
                    widest = delta;
                    first = i;
                    second = j;
                }
            }
        }
        if (first < 0 || widest <= AXIS_EPSILON) {
            return null;
        }

        double scale = (target[second] - target[first]) / (source[second] - source[first]);
        double shift = target[first] - scale * source[first];
        if (!Double.isFinite(scale) || !Double.isFinite(shift)) {
            return null;
        }

        for (int vertex = 0; vertex < 4; vertex++) {
            double predicted = scale * source[vertex] + shift;
            if (!Double.isFinite(predicted)
                    || Math.abs(predicted - target[vertex]) > VERIFY_EPSILON) {
                return null;
            }
        }
        return new AxisTransform((float) scale, (float) shift);
    }

    private static float readUv(int[] vertices, int vertex, int offset) {
        return Float.intBitsToFloat(vertices[vertex * VERTEX_STRIDE + offset]);
    }

    private static UvTransform cached(BakedModel model, FaceKey key) {
        synchronized (CACHE) {
            FaceCache cache = CACHE.get(model);
            return cache == null ? null : cache.get(key);
        }
    }

    private static void cache(BakedModel model, FaceKey key, UvTransform transform) {
        synchronized (CACHE) {
            CACHE.computeIfAbsent(model, ignored -> new FaceCache()).put(key, transform);
        }
    }

    static record UvTransform(float uScale, float uOffset, float vScale, float vOffset) {
        static final UvTransform IDENTITY = new UvTransform(1.0f, 0.0f, 1.0f, 0.0f);

        float mapU(float atlasU) {
            return uScale * atlasU + uOffset;
        }

        float mapV(float atlasV) {
            return vScale * atlasV + vOffset;
        }
    }

    private record AxisTransform(float scale, float offset) {
    }

    private record FaceKey(
            long framePos,
            BlockState materialState,
            FrameSkin skin,
            Direction face,
            int topologyMask) {
    }

    private static final class FaceCache extends LinkedHashMap<FaceKey, UvTransform> {
        private FaceCache() {
            super(32, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<FaceKey, UvTransform> eldest) {
            return size() > MAX_CACHE_ENTRIES_PER_MODEL;
        }
    }

    /**
     * Face-specific world projection used only while asking Create for connected model data.
     * Normal-axis neighbours are deliberately air so the requested face cannot be culled before its
     * CT context is produced. The four cardinal and four diagonal cells in the face plane are the
     * only positions that may expose the casing state.
     */
    private static final class SyntheticFrameView implements BlockAndTintGetter {
        private final BlockAndTintGetter delegate;
        private final BlockPos center;
        private final FrameSkin skin;
        private final BlockState materialState;
        private final Direction face;

        private SyntheticFrameView(
                BlockAndTintGetter delegate,
                BlockPos center,
                FrameSkin skin,
                BlockState materialState,
                Direction face) {
            this.delegate = delegate;
            this.center = center.immutable();
            this.skin = skin;
            this.materialState = materialState;
            this.face = face;
        }

        int topologyMask() {
            int mask = 0;
            int bit = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        if (axisComponent(face.getAxis(), dx, dy, dz) != 0) {
                            continue;
                        }
                        BlockPos candidate = center.offset(dx, dy, dz);
                        if (getBlockState(candidate).getBlock() == materialState.getBlock()) {
                            mask |= 1 << bit;
                        }
                        bit++;
                    }
                }
            }
            return mask;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            int dx = pos.getX() - center.getX();
            int dy = pos.getY() - center.getY();
            int dz = pos.getZ() - center.getZ();
            if (dx == 0 && dy == 0 && dz == 0) {
                return materialState;
            }
            if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || Math.abs(dz) > 1) {
                return Blocks.AIR.defaultBlockState();
            }
            if (axisComponent(face.getAxis(), dx, dy, dz) != 0) {
                return Blocks.AIR.defaultBlockState();
            }

            int nonZero = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
            if (nonZero == 1) {
                return connectedVisible(center, pos) ? materialState : Blocks.AIR.defaultBlockState();
            }
            if (nonZero == 2) {
                return diagonalVisible(pos, dx, dy, dz)
                        ? materialState
                        : Blocks.AIR.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        }

        private boolean diagonalVisible(BlockPos diagonal, int dx, int dy, int dz) {
            BlockPos first = center.offset(dx == 0 ? 0 : dx, 0, 0);
            BlockPos second;
            if (dx != 0 && dy != 0) {
                second = center.offset(0, dy, 0);
            } else if (dx != 0) {
                second = center.offset(0, 0, dz);
            } else {
                first = center.offset(0, dy, 0);
                second = center.offset(0, 0, dz);
            }

            return connectedVisible(center, first)
                    && connectedVisible(center, second)
                    && connectedVisible(first, diagonal)
                    && connectedVisible(second, diagonal);
        }

        private boolean connectedVisible(BlockPos from, BlockPos to) {
            Direction direction = directionBetween(from, to);
            if (direction == null || !visibleSameSkinFrame(from) || !visibleSameSkinFrame(to)) {
                return false;
            }
            BlockState fromState = delegate.getBlockState(from);
            BlockState toState = delegate.getBlockState(to);
            return MechanismFrameBlock.isConnected(fromState, direction)
                    && MechanismFrameBlock.isConnected(toState, direction.getOpposite());
        }

        private boolean visibleSameSkinFrame(BlockPos pos) {
            BlockState state = delegate.getBlockState(pos);
            if (!(state.getBlock() instanceof MechanismFrameBlock)
                    || state.getValue(MechanismFrameBlock.SHELL_MODE) == FrameShellMode.HIDDEN) {
                return false;
            }
            BlockEntity blockEntity = delegate.getBlockEntity(pos);
            return blockEntity instanceof MechanismFrameBlockEntity frame
                    && frame.getPresentationSkin() == skin;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return delegate.getShade(direction, shade);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return delegate.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return delegate.getBlockTint(pos, resolver);
        }

        @Override
        public int getHeight() {
            return delegate.getHeight();
        }

        @Override
        public int getMinY() {
            return delegate.getMinY();
        }

        private static int axisComponent(Direction.Axis axis, int dx, int dy, int dz) {
            return switch (axis) {
                case X -> dx;
                case Y -> dy;
                case Z -> dz;
            };
        }

        private static Direction directionBetween(BlockPos from, BlockPos to) {
            int dx = to.getX() - from.getX();
            int dy = to.getY() - from.getY();
            int dz = to.getZ() - from.getZ();
            if (dx == 1 && dy == 0 && dz == 0) return Direction.EAST;
            if (dx == -1 && dy == 0 && dz == 0) return Direction.WEST;
            if (dx == 0 && dy == 1 && dz == 0) return Direction.UP;
            if (dx == 0 && dy == -1 && dz == 0) return Direction.DOWN;
            if (dx == 0 && dy == 0 && dz == 1) return Direction.SOUTH;
            if (dx == 0 && dy == 0 && dz == -1) return Direction.NORTH;
            return null;
        }
    }
}
