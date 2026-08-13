package dev.antikytheramechanism.mixin.client;

import dev.antikytheramechanism.client.ManagedClientSubLevelIdentity;
import dev.antikytheramechanism.client.PhysicsStaffClientSelectionBridge;
import dev.antikytheramechanism.frame.MechanismFrameBlockEntity;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MiniWorldEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Client-side render corrections that apply only to Antikythera-managed ClientSubLevels. */
@Mixin(value = ClientSubLevel.class, priority = 2000)
abstract class ClientSubLevelManagedSkyLightMixin {
    @Unique
    private final Pose3d antikytheramechanism$hostComposedPose = new Pose3d();
    @Unique
    private final Quaterniond antikytheramechanism$hostOrientation = new Quaterniond();
    @Unique
    private final Vector3d antikytheramechanism$hostAnchor = new Vector3d();
    @Unique
    private final Vector3d antikytheramechanism$childAnchorOffset = new Vector3d();
    @Unique
    private @Nullable PhysicsStaffClientSelectionBridge.Selection antikytheramechanism$hostSelection;
    @Unique
    private long antikytheramechanism$lastHostResolutionTick = Long.MIN_VALUE;
    @Unique
    private long antikytheramechanism$lastCompositionTick = Long.MIN_VALUE;
    @Unique
    private float antikytheramechanism$lastCompositionPartialTick = Float.NaN;

    /**
     * A hosted managed child is not an independent visual rigid body. Sable's normal interpolation
     * lerps its already-composed world pose independently from the host, which makes the child follow
     * a straight chord while a rotating Frame follows the host's arc. Compose from the host render
     * pose instead so mini content remains rigidly welded to its physical Frame between ticks.
     */
    @Inject(
            method = "renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;",
            at = @At("HEAD"),
            cancellable = true)
    private void antikytheramechanism$composeHostedRenderPose(
            float partialTick,
            CallbackInfoReturnable<Pose3dc> callback) {
        ClientSubLevel child = (ClientSubLevel) (Object) this;
        if (!ManagedClientSubLevelIdentity.isManaged(child)) {
            return;
        }

        long tick = child.getLevel().getGameTime();
        if (antikytheramechanism$lastHostResolutionTick != tick) {
            antikytheramechanism$lastHostResolutionTick = tick;
            if (!antikytheramechanism$selectionStillValid(child)) {
                BlockPos plotCenter = child.getPlot().getCenterBlock();
                antikytheramechanism$hostSelection = PhysicsStaffClientSelectionBridge.resolve(
                        child,
                        new Vec3(
                                plotCenter.getX() + 1.0,
                                plotCenter.getY() + 1.0,
                                plotCenter.getZ() + 1.0));
            }
        }

        PhysicsStaffClientSelectionBridge.Selection selection = antikytheramechanism$hostSelection;
        if (selection == null) {
            // Root-managed children deliberately retain Sable's ordinary render pose. They do not
            // have a moving physical parent whose interpolation must be shared.
            return;
        }

        if (antikytheramechanism$lastCompositionTick == tick
                && Float.floatToIntBits(antikytheramechanism$lastCompositionPartialTick)
                == Float.floatToIntBits(partialTick)) {
            callback.setReturnValue(antikytheramechanism$hostComposedPose);
            return;
        }
        antikytheramechanism$lastCompositionTick = tick;
        antikytheramechanism$lastCompositionPartialTick = partialTick;

        Pose3dc hostPose = selection.host().renderPose(partialTick);
        Pose3d output = antikytheramechanism$hostComposedPose;

        // Rotation point can legitimately move when mini mass changes and scale belongs to the child,
        // so interpolate those child-internal values normally. Translation/orientation come from the
        // parent hierarchy instead of the child's independent world interpolation.
        output.rotationPoint()
                .set(child.lastPose().rotationPoint())
                .lerp(child.logicalPose().rotationPoint(), partialTick);
        output.scale()
                .set(child.lastPose().scale())
                .lerp(child.logicalPose().scale(), partialTick);

        Quaterniond orientation = antikytheramechanism$hostOrientation
                .set(hostPose.orientation())
                .normalize();
        output.orientation().set(orientation);

        BlockPos originFrame = selection.originFrame();
        Vector3d worldAnchor = antikytheramechanism$hostAnchor
                .set(originFrame.getX() + 0.5, originFrame.getY() + 0.5, originFrame.getZ() + 0.5)
                .sub(hostPose.rotationPoint())
                .mul(hostPose.scale());
        orientation.transform(worldAnchor);
        worldAnchor.add(hostPose.position());

        // AssemblyPoseDriver defines plotCenter+(1,1,1) as the stable center of the origin Frame's
        // 2x2x2 mini volume. Reconstruct Sable's body position around that same semantic anchor.
        BlockPos plotCenter = child.getPlot().getCenterBlock();
        Vector3d offset = antikytheramechanism$childAnchorOffset
                .set(plotCenter.getX() + 1.0, plotCenter.getY() + 1.0, plotCenter.getZ() + 1.0)
                .sub(output.rotationPoint())
                .mul(output.scale());
        orientation.transform(offset);
        output.position().set(worldAnchor).sub(offset);

        callback.setReturnValue(output);
    }

    @Unique
    private boolean antikytheramechanism$selectionStillValid(ClientSubLevel child) {
        PhysicsStaffClientSelectionBridge.Selection selection = antikytheramechanism$hostSelection;
        if (selection == null
                || selection.child() != child
                || selection.host().isRemoved()
                || selection.host().getLevel() != child.getLevel()) {
            return false;
        }

        BlockPos frame = selection.originFrame();
        return Sable.HELPER.getContainingClient(frame) == selection.host()
                && child.getLevel().getBlockState(frame).is(ModRegistries.MECHANISM_FRAME.get())
                && child.getLevel().getBlockEntity(frame) instanceof MechanismFrameBlockEntity frameEntity
                && selection.assemblyId().equals(frameEntity.getAssemblyId());
    }

    @Inject(method = "computeSubLevelSkyLight", at = @At("HEAD"), cancellable = true)
    private void antikytheramechanism$sampleOutsideFrame(
            Pose3dc pose,
            CallbackInfoReturnable<Integer> callback) {
        ClientSubLevel self = (ClientSubLevel) (Object) this;
        if (!MiniWorldEnvironment.isManagedSubLevel(self)) {
            return;
        }

        ClientLevel level = self.getLevel();
        BoundingBox3dc bounds = self.boundingBox();

        if (bounds.volume() <= 1.0E-6) {
            int x = Mth.floor(pose.position().x());
            int y = Mth.floor(pose.position().y());
            int z = Mth.floor(pose.position().z());
            callback.setReturnValue(maxSky(
                    level,
                    new BlockPos(x, y + 1, z),
                    new BlockPos(x + 1, y, z),
                    new BlockPos(x - 1, y, z),
                    new BlockPos(x, y, z + 1),
                    new BlockPos(x, y, z - 1)));
            return;
        }

        int minX = Mth.floor(bounds.minX());
        int minY = Mth.floor(bounds.minY());
        int minZ = Mth.floor(bounds.minZ());
        int maxX = Mth.floor(Math.nextDown(bounds.maxX()));
        int maxY = Mth.floor(Math.nextDown(bounds.maxY()));
        int maxZ = Mth.floor(Math.nextDown(bounds.maxZ()));
        int centerX = Mth.floor((bounds.minX() + bounds.maxX()) * 0.5);
        int centerY = Mth.floor((bounds.minY() + bounds.maxY()) * 0.5);
        int centerZ = Mth.floor((bounds.minZ() + bounds.maxZ()) * 0.5);

        callback.setReturnValue(maxSky(
                level,
                new BlockPos(centerX, maxY + 1, centerZ),
                new BlockPos(minX - 1, centerY, centerZ),
                new BlockPos(maxX + 1, centerY, centerZ),
                new BlockPos(centerX, centerY, minZ - 1),
                new BlockPos(centerX, centerY, maxZ + 1),
                new BlockPos(minX - 1, maxY + 1, minZ - 1),
                new BlockPos(maxX + 1, maxY + 1, maxZ + 1)));
    }

    private static int maxSky(ClientLevel level, BlockPos... positions) {
        int result = 0;
        for (BlockPos position : positions) {
            result = Math.max(result, level.getBrightness(LightLayer.SKY, position));
            if (result >= 15) {
                return 15;
            }
        }
        return result;
    }
}
