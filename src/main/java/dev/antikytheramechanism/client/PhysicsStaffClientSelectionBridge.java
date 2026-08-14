package dev.antikytheramechanism.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.UUID;

/** Client-only resolution of a managed child hit to the physical Frame host manipulated by Simulated. */
public final class PhysicsStaffClientSelectionBridge {
    private PhysicsStaffClientSelectionBridge() {
    }

    /**
     * Returns a foreign host selection for a managed child, or {@code null} when the child is rooted
     * in the normal world, unavailable, ambiguous, or otherwise unsafe to manipulate directly.
     */
    public static @Nullable Selection resolve(ClientSubLevel child, Position childHit) {
        ManagedClientFrameHost.Binding binding = ManagedClientFrameHost.resolve(child);
        if (binding == null) {
            return null;
        }

        Vector3d worldHit = child.logicalPose().transformPosition(
                new Vector3d(childHit.x(), childHit.y(), childHit.z()),
                new Vector3d());
        Vector3d hostHit = binding.host().logicalPose().transformPositionInverse(worldHit, new Vector3d());
        return new Selection(binding, hostHit);
    }

    public record Selection(ManagedClientFrameHost.Binding binding, Vector3d hostHit) {
        public ClientSubLevel child() {
            return binding.child();
        }

        public ClientSubLevel host() {
            return binding.host();
        }

        public UUID assemblyId() {
            return binding.assemblyId();
        }

        public BlockPos originFrame() {
            return binding.originFrame();
        }

        /** Resolves the exact Frame containing the mini block that was hit, falling back to origin. */
        public BlockPos frameForMiniBlock(BlockPos childPlotBlock) {
            return binding.frameForChildPlotBlock(childPlotBlock);
        }

        public Vec3 hostHitLocation() {
            return new Vec3(hostHit.x, hostHit.y, hostHit.z);
        }
    }
}
