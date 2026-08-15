package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.index.SableAttributes;
import dev.ryanhcode.sable.network.packets.tcp.ServerboundPunchSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;
import java.util.UUID;

/**
 * Re-targets Sable's normal left-click push from a pose-driven managed mini child to the foreign
 * Sable body that physically carries its Mechanism Frame.
 *
 * <p>The managed child is not a free body: Antikythera drives its pose from the Frame/host after
 * every physics step. Applying Sable's punch impulse to that child therefore gets overwritten. The
 * foreign host is the actual physical body and already contains the child's scaled MassData through
 * {@link HostedMiniMassBridge}; punch strength and torque must consequently be evaluated against the
 * host's merged mass/inertia at the projected visual hit point.</p>
 */
public final class HostedMiniPunchBridge {
    private HostedMiniPunchBridge() {
    }

    /**
     * Handles a Sable punch packet only when its target is an Antikythera child carried by a foreign
     * unit-scale Sable host. Returning {@code false} delegates the packet unchanged to Sable.
     */
    public static boolean handleIfHostedMini(
            ServerboundPunchSubLevelPacket packet,
            PacketContext context) {
        if (!(context.level() instanceof ServerLevel level)) {
            return false;
        }

        SubLevel rawTarget = Sable.HELPER.getContaining(level, packet.punchedBlock());
        if (!(rawTarget instanceof ServerSubLevel child)
                || MechanismSubLevelService.getOwnerAssemblyId(child) == null) {
            return false;
        }

        ServerSubLevel host = foreignHost(level, child);
        if (host == null) {
            // ROOT-hosted managed children are attached to the static world. Let Sable retain its
            // ordinary packet lifecycle there; there is no foreign movable body to receive a push.
            return false;
        }

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            // A managed child cannot legitimately exist without this container. Consume the packet
            // fail-closed rather than falling back to an impulse on the pose-driven child.
            return true;
        }

        Player player = context.player();
        if (!player.onGround()
                && !player.isInWater()
                && !player.getAbilities().flying
                && !player.onClimbable()) {
            return true;
        }

        ServerSubLevel encodedStanding = (ServerSubLevel) Sable.HELPER.getTrackingSubLevel(player);
        ServerSubLevel physicalStanding = physicalBody(level, encodedStanding);

        // Preserve Sable's "do not punch the body you are standing on" rule, but compare physical
        // bodies rather than the managed child UUID against its carrier UUID.
        if (physicalStanding == host) {
            return true;
        }

        Vector3d globalDirection = new Vector3d(packet.direction()).normalize();
        if (encodedStanding != null) {
            // The client encodes look direction in the tracked SubLevel's coordinates. Decode it
            // with that exact SubLevel first, even when its physical reaction body is a host.
            encodedStanding.logicalPose().transformNormal(globalDirection);
        }

        int customCooldown = SableAttributes.getPushCooldownTicks(player);
        if (!container.physicsSystem().tryPunch(player.getGameProfile().getId(), customCooldown)) {
            return true;
        }
        player.getCooldowns().addCooldown(player.getMainHandItem().getItem(), customCooldown);

        double downwardStrengthMultiplier =
                SableConfig.SUB_LEVEL_PUNCH_DOWNWARD_STRENGTH_MULTIPLIER.getAsDouble();
        if (globalDirection.y < 0.0) {
            globalDirection.mul(1.0, downwardStrengthMultiplier, 1.0);
        }

        Projection projection = project(level, child, host, packet.localPosition(), globalDirection);
        if (projection == null) {
            return true;
        }

        double attributeStrength = Objects.requireNonNull(
                player.getAttribute(SableAttributes.PUNCH_STRENGTH)).getValue();
        double targetStrength = computeStrengthScalar(
                host,
                projection.hostLocalHitPosition(),
                projection.hostLocalDirection());
        double strengthScalar = targetStrength;

        PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        if (physicalStanding != null) {
            Vector3d standingLocalPosition = physicalStanding.logicalPose()
                    .transformPositionInverse(new Vector3d(
                            player.getX(), player.getY(), player.getZ()));
            Vector3d standingLocalDirection = physicalStanding.logicalPose()
                    .transformNormalInverse(new Vector3d(globalDirection));
            double standingStrength = computeStrengthScalar(
                    physicalStanding,
                    standingLocalPosition,
                    standingLocalDirection);
            strengthScalar = Math.min(targetStrength, standingStrength);

            standingLocalDirection.negate();
            pipeline.applyImpulse(
                    physicalStanding,
                    standingLocalPosition,
                    standingLocalDirection.mul(attributeStrength * strengthScalar));
        }

        pipeline.applyImpulse(
                host,
                projection.hostLocalHitPosition(),
                new Vector3d(projection.hostLocalDirection())
                        .mul(attributeStrength * strengthScalar));

        sendPunchEffects(level, packet, projection.worldHitPosition(), globalDirection);
        return true;
    }

    /** Package-private regression seam: projects one already-decoded world direction onto the host. */
    static @Nullable Projection project(
            ServerLevel level,
            ServerSubLevel child,
            Vector3dc packetLocalPosition,
            Vector3dc globalDirection) {
        ServerSubLevel host = foreignHost(level, child);
        return host == null
                ? null
                : project(level, child, host, packetLocalPosition, globalDirection);
    }

    private static @Nullable Projection project(
            ServerLevel level,
            ServerSubLevel child,
            ServerSubLevel host,
            Vector3dc packetLocalPosition,
            Vector3dc globalDirection) {
        if (child.isRemoved() || host.isRemoved()) {
            return null;
        }

        // Sable's client packet stores the hit as a world-space vector relative to the target
        // SubLevel's pose position. Reconstruct exactly that visual point using the managed child,
        // then express it in the carrier's local coordinates for MassData/applyImpulse.
        Vector3d worldHit = new Vector3d(packetLocalPosition)
                .add(child.logicalPose().position());
        Vector3d hostLocalHit = host.logicalPose()
                .transformPositionInverse(worldHit, new Vector3d());
        Vector3d hostLocalDirection = host.logicalPose()
                .transformNormalInverse(globalDirection, new Vector3d());

        return new Projection(host, worldHit, hostLocalHit, hostLocalDirection);
    }

    static double computeStrengthScalar(
            ServerSubLevel body,
            Vector3dc localPosition,
            Vector3dc localDirection) {
        MassData massData = body.getMassTracker();
        double generalizedInverseMass =
                massData.getInverseNormalMass(localPosition, localDirection);
        double mass = 1.0 / generalizedInverseMass;
        double strengthMultiplier =
                SableConfig.SUB_LEVEL_PUNCH_STRENGTH_MULTIPLIER.getAsDouble();
        return ServerboundPunchSubLevelPacket.punchCurve(mass) * strengthMultiplier;
    }

    private static @Nullable ServerSubLevel foreignHost(
            ServerLevel level,
            ServerSubLevel child) {
        UUID ownerId = MechanismSubLevelService.getOwnerAssemblyId(child);
        if (ownerId == null) {
            return null;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level)
                .getAssembly(ownerId)
                .orElse(null);
        if (assembly == null) {
            return null;
        }
        MechanismAssemblyHost.Resolution resolution =
                MechanismAssemblyHost.resolve(level, assembly.origin());
        if (resolution.kind() != MechanismAssemblyHost.Kind.FOREIGN
                || resolution.subLevel() == null
                || resolution.subLevel() == child
                || resolution.subLevel().isRemoved()) {
            return null;
        }
        return resolution.subLevel();
    }

    private static @Nullable ServerSubLevel physicalBody(
            ServerLevel level,
            @Nullable ServerSubLevel tracked) {
        if (tracked == null) {
            return null;
        }
        if (MechanismSubLevelService.getOwnerAssemblyId(tracked) == null) {
            return tracked;
        }
        // Standing on a managed child means standing on whatever carries its Frame. If the Frame is
        // in ROOT there is no movable reaction body, which is equivalent to standing on the world.
        return foreignHost(level, tracked);
    }

    private static void sendPunchEffects(
            ServerLevel level,
            ServerboundPunchSubLevelPacket packet,
            Vector3dc worldHit,
            Vector3dc globalDirection) {
        BlockState blockState = level.getBlockState(packet.punchedBlock());
        if (blockState.getFluidState().isEmpty()) {
            Vector3d particlePos = new Vector3d(worldHit).fma(-0.1, globalDirection);
            level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, blockState),
                    particlePos.x(), particlePos.y(), particlePos.z(),
                    (int) (Math.random() * 3.0),
                    0.0, 0.0, 0.0, 0.0);
            return;
        }

        Vector3d particlePos = new Vector3d(worldHit).fma(0.1, globalDirection);
        if (blockState.getFluidState().is(FluidTags.WATER)) {
            level.sendParticles(
                    ParticleTypes.SPLASH,
                    particlePos.x(), particlePos.y(), particlePos.z(),
                    10, 0.2, 0.2, 0.2, 0.0);
            particlePos.fma(0.2, globalDirection);
            level.sendParticles(
                    ParticleTypes.BUBBLE,
                    particlePos.x(), particlePos.y(), particlePos.z(),
                    5, 0.2, 0.1, 0.2, 0.0);
            level.playSound(
                    null,
                    particlePos.x(), particlePos.y(), particlePos.z(),
                    SoundEvents.PLAYER_SWIM,
                    SoundSource.BLOCKS,
                    0.2F,
                    1.0F);
        } else {
            level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, blockState),
                    particlePos.x(), particlePos.y(), particlePos.z(),
                    (int) (Math.random() * 3.0),
                    0.2, 0.2, 0.2, 0.0);
        }
    }

    record Projection(
            ServerSubLevel host,
            Vector3d worldHitPosition,
            Vector3d hostLocalHitPosition,
            Vector3d hostLocalDirection) {
    }
}
