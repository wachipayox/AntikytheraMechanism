package dev.antikytheramechanism.compat.create;

import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

/** Static-placement policy for Mechanism Frames carried by Create. */
public final class CreateFrameDisassemblyPolicy {
    public static final String INVALID_STATIC_ROTATION_KEY =
            "gui.assembly.exception.antikytheramechanism.mechanism_frames_must_be_upright";

    private CreateFrameDisassemblyPolicy() {
    }

    /** Any carried Frame is subject to the static upright invariant, regardless of assembly size. */
    public static boolean containsMechanismFrame(Contraption contraption) {
        if (contraption == null) {
            return false;
        }
        return contraption.getBlocks().values().stream()
                .anyMatch(info -> info.state().is(ModRegistries.MECHANISM_FRAME.get()));
    }

    /**
     * Uses the same 90-degree quantisation that Create applies in StructureTransform.
     *
     * <p>Axes are deliberately interpreted in the coordinate system of the level that owns the
     * contraption. Therefore a Y-axis rotation inside a Sable sublevel preserves that sublevel's UP;
     * no parent/root-world axis conversion belongs here.</p>
     */
    public static boolean canVoluntarilyDisassemble(ControlledContraptionEntity entity) {
        if (entity == null || !containsMechanismFrame(entity.getContraption())) {
            return true;
        }
        Direction.Axis axis = entity.getRotationAxis();
        if (axis == null || axis == Direction.Axis.Y) {
            return true;
        }
        return Math.floorMod(Math.round(entity.getAngle(1.0F) / 90.0F), 4) == 0;
    }

    public static boolean isStaticTransformUpright(StructureTransform transform) {
        if (transform == null || transform.rotationAxis == null || transform.rotationAxis == Direction.Axis.Y) {
            return true;
        }
        return Math.floorMod(Math.round(transform.angle / 90.0F), 4) == 0;
    }

    public static boolean canPlaceStatically(Contraption contraption, StructureTransform transform) {
        return !containsMechanismFrame(contraption) || isStaticTransformUpright(transform);
    }

    /**
     * Create 6.0.10 represents a controlled contraption disassembly as a rotation around one axis.
     * For an X/Z bearing axis in the containing level, the upright snapped member is zero rotation
     * relative to the captured structure. Keep Create's snapped offset and let its ordinary block
     * replacement/drop policy handle the resulting destination.
     */
    public static StructureTransform nearestForcedUprightTransform(
            Contraption contraption,
            StructureTransform transform) {
        if (canPlaceStatically(contraption, transform)) {
            return transform;
        }
        return new StructureTransform(transform.offset, 0.0F, 0.0F, 0.0F);
    }

    public static AssemblyException invalidStaticRotationException() {
        return new AssemblyException(Component.translatable(INVALID_STATIC_ROTATION_KEY));
    }

    public static boolean isInvalidStaticRotationException(AssemblyException exception) {
        return exception != null
                && exception.component.equals(Component.translatable(INVALID_STATIC_ROTATION_KEY));
    }
}
