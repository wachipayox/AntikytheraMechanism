package dev.antikytheramechanism.compat.create.transmission.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.antikytheramechanism.compat.create.transmission.TransmissionBoxCogPlacementHelper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.engine_room.flywheel.lib.model.baked.EmptyVirtualBlockGetter;
import net.createmod.catnip.client.render.model.BakedModelBufferer;
import net.createmod.catnip.ghostblock.GhostBlockParams;
import net.createmod.catnip.ghostblock.GhostBlockRenderer;
import net.createmod.catnip.ghostblock.GhostBlocks;
import net.createmod.catnip.impl.client.render.ColoringVertexConsumer;
import net.createmod.catnip.placement.PlacementClient;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/** Client-side managed-plot lookup and half-scale Create ghost for configured corner cogs. */
public final class TransmissionBoxCogPlacementClient
        implements TransmissionBoxCogPlacementHelper.ClientBridge {
    private static final TransmissionBoxCogPlacementClient INSTANCE =
            new TransmissionBoxCogPlacementClient();
    private static final Object GHOST_SLOT = new Object();
    /**
     * Native Create cog helpers only need a coordinate workspace in order to rank candidate offsets.
     * Before an empty Frame owns a ClientSubLevel there is no real managed plot to run those reads in,
     * even though the server can create one on demand when the click arrives. Keep a client-only
     * workspace far outside build height so vanilla returns AIR/VOID_AIR for helper probes. The
     * resulting PlacementOffset is never rendered or written at these coordinates: the common bridge
     * immediately converts its delta back into the physical half-scale lattice and validates the real
     * destination Frame.
     */
    private static final int SYNTHETIC_WORKSPACE_Y = 1_000_000;

    private TransmissionBoxCogPlacementClient() {
    }

    public static void register() {
        TransmissionBoxCogPlacementHelper.registerClientBridge(INSTANCE);
    }

    @Override
    public @Nullable BlockPos resolveManagedPlotTarget(
            Level level,
            UUID assemblyId,
            BlockPos logicalMini) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return syntheticWorkspace(logicalMini);
        }
        BlockPos match = null;
        for (SubLevel candidate : container.getAllSubLevels()) {
            if (!(candidate instanceof ClientSubLevel child)
                    || child.isRemoved()
                    || !ManagedClientSubLevelIdentity.isManaged(child)
                    || !assemblyId.equals(ManagedClientSubLevelIdentity.assemblyId(child))) {
                continue;
            }
            BlockPos resolved = child.getPlot().getCenterBlock().offset(logicalMini);
            if (match != null && !match.equals(resolved)) {
                // Never render into an arbitrary child during a transient duplicate tracking state.
                return null;
            }
            match = resolved.immutable();
        }
        return match != null ? match : syntheticWorkspace(logicalMini);
    }

    private static BlockPos syntheticWorkspace(BlockPos logicalMini) {
        return new BlockPos(
                logicalMini.getX(),
                SYNTHETIC_WORKSPACE_Y + logicalMini.getY(),
                logicalMini.getZ());
    }

    @Override
    public void renderPreview(TransmissionBoxCogPlacementHelper.Preview preview) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return;
        }

        ClientSubLevel boxHost = Sable.HELPER.getContainingClient(preview.boxPosition());
        if (Sable.HELPER.getContainingClient(preview.destinationFrame()) != boxHost) {
            return;
        }

        BlockState physicalState = preview.physicalGhostState();
        double localX = preview.destinationFrame().getX() + preview.physicalCell().getX() * 0.5;
        double localY = preview.destinationFrame().getY() + preview.physicalCell().getY() * 0.5;
        double localZ = preview.destinationFrame().getZ() + preview.physicalCell().getZ() * 0.5;

        Vector3d worldOrigin;
        Quaternionf hostRotation;
        float scaleX;
        float scaleY;
        float scaleZ;
        if (boxHost == null) {
            worldOrigin = new Vector3d(localX, localY, localZ);
            hostRotation = new Quaternionf();
            scaleX = scaleY = scaleZ = 1.0F;
        } else {
            worldOrigin = boxHost.logicalPose().transformPosition(
                    new Vector3d(localX, localY, localZ), new Vector3d());
            Quaterniondc q = boxHost.logicalPose().orientation();
            hostRotation = new Quaternionf(
                    (float) q.x(), (float) q.y(), (float) q.z(), (float) q.w()).normalize();
            Vector3dc scale = boxHost.logicalPose().scale();
            scaleX = (float) scale.x();
            scaleY = (float) scale.y();
            scaleZ = (float) scale.z();
        }

        GhostBlocks.getInstance().showGhost(
                GHOST_SLOT,
                new HalfScaleGhostRenderer(
                        physicalState,
                        new Vec3(worldOrigin.x, worldOrigin.y, worldOrigin.z),
                        hostRotation,
                        scaleX,
                        scaleY,
                        scaleZ,
                        preview.destinationFrame()),
                GhostBlockParams.of(physicalState),
                1);
    }

    private static final class HalfScaleGhostRenderer extends GhostBlockRenderer {
        private final BlockState state;
        private final Vec3 worldOrigin;
        private final Quaternionf hostRotation;
        private final float scaleX;
        private final float scaleY;
        private final float scaleZ;
        private final BlockPos seedPosition;

        private HalfScaleGhostRenderer(
                BlockState state,
                Vec3 worldOrigin,
                Quaternionf hostRotation,
                float scaleX,
                float scaleY,
                float scaleZ,
                BlockPos seedPosition) {
            this.state = state;
            this.worldOrigin = worldOrigin;
            this.hostRotation = new Quaternionf(hostRotation);
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.scaleZ = scaleZ;
            this.seedPosition = seedPosition.immutable();
        }

        @Override
        public void render(
                PoseStack poseStack,
                SuperRenderTypeBuffer buffer,
                Vec3 camera,
                GhostBlockParams ignored) {
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            float alpha = (float) GhostBlocks.getBreathingAlpha()
                    * 0.75F
                    * PlacementClient.getCurrentAlpha();
            VertexConsumer vertices = new ColoringVertexConsumer(
                    buffer.getEarlyBuffer(RenderType.translucent()),
                    1.0F,
                    1.0F,
                    1.0F,
                    alpha);

            poseStack.pushPose();
            poseStack.translate(
                    worldOrigin.x - camera.x,
                    worldOrigin.y - camera.y,
                    worldOrigin.z - camera.z);
            poseStack.mulPose(hostRotation);
            poseStack.scale(scaleX * 0.5F, scaleY * 0.5F, scaleZ * 0.5F);

            // Same breathing/shrink convention as Create's placement guides, inside one mini cell.
            poseStack.translate(0.5, 0.5, 0.5);
            poseStack.scale(0.85F, 0.85F, 0.85F);
            poseStack.translate(-0.5, -0.5, -0.5);
            BakedModelBufferer.bufferModel(
                    model,
                    seedPosition,
                    EmptyVirtualBlockGetter.FULL_BRIGHT,
                    state,
                    poseStack,
                    (layer, shade) -> vertices);
            poseStack.popPose();
        }
    }
}
