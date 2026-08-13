package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.assembly.AssemblyPose;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Keeps managed SubLevels on their semantic assembly transforms after every physics substep. */
public final class AssemblyPoseDriver {
    private AssemblyPoseDriver() {
    }

    public static void onPostPhysicsTick(ForgeSablePostPhysicsTickEvent event) {
        ServerLevel level = event.getPhysicsSystem().getLevel();
        PhysicsPipeline pipeline = event.getPhysicsSystem().getPipeline();
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);

        for (MechanismAssembly assembly : manager.assemblies()) {
            // Sable has already updated foreign host poses before this post-physics callback. Keep the
            // assembly target in host-local coordinates and compose it only for the managed child.
            AssemblyPose worldTarget = MechanismAssemblyHost.worldPose(level, assembly);
            if (worldTarget == null) {
                // Missing/unsupported hosts fail closed. Do not reinterpret plot-yard coordinates as
                // world coordinates while the real host is unavailable.
                continue;
            }

            // Never allocate while Sable is iterating its physics bodies. Recovery and
            // allocation remain part of the regular server-level maintenance tick.
            ServerSubLevel subLevel = MechanismSubLevelService.get(level, assembly);
            if (subLevel != null && !subLevel.isRemoved()) {
                drive(pipeline, subLevel, worldTarget);
            }
        }
    }

    public static void drive(PhysicsPipeline pipeline, ServerSubLevel subLevel, AssemblyPose target) {
        Quaterniond orientation = target.orientation(new Quaterniond());
        BlockPos plotCenter = subLevel.getPlot().getCenterBlock();

        // The semantic anchor is the center of the origin frame's 2x2x2 mini volume.
        // Sable's rotation point can move when mass changes, so derive the rigid-body
        // position that maps this stable plot point onto the requested world anchor.
        Vector3d anchorOffset = new Vector3d(
                plotCenter.getX() + 1.0,
                plotCenter.getY() + 1.0,
                plotCenter.getZ() + 1.0)
                .sub(subLevel.logicalPose().rotationPoint())
                .mul(subLevel.logicalPose().scale());
        orientation.transform(anchorOffset);

        Vector3d bodyPosition = target.anchor(new Vector3d()).sub(anchorOffset);
        pipeline.teleport(subLevel, bodyPosition, orientation);
        pipeline.resetVelocity(subLevel);
        subLevel.updateBoundingBox();
    }
}
