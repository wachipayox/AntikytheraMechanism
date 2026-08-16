package dev.antikytheramechanism.frame;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MechanismFrameSelectionShapeTest {
    @Test
    void selectionGetsHalfPixelInwardWithoutChangingCollision() {
        MechanismFrameBlock block = new MechanismFrameBlock(BlockBehaviour.Properties.of());
        BlockState state = block.defaultBlockState();

        // 2.25 model pixels from the west edge: outside the real 2 px bar, inside the 2.5 px
        // client grab area. Aim straight through the north face halfway up the Frame.
        double x = 2.25 / 16.0;
        Vec3 from = new Vec3(x, 0.5, -1.0);
        Vec3 to = new Vec3(x, 0.5, 2.0);

        BlockHitResult selectionHit = MechanismFrameSelectionShape.shape(state).clip(from, to, BlockPos.ZERO);
        BlockHitResult collisionHit = state.getCollisionShape(
                EmptyBlockGetter.INSTANCE, BlockPos.ZERO).clip(from, to, BlockPos.ZERO);

        assertNotNull(selectionHit, "expanded selection cage should catch the near-edge ray");
        assertNull(collisionHit, "selection affordance must not widen the physical collision cage");
    }

    @Test
    void centralOpeningStillPassesRayThroughFrame() {
        MechanismFrameBlock block = new MechanismFrameBlock(BlockBehaviour.Properties.of());
        BlockState state = block.defaultBlockState();

        Vec3 from = new Vec3(0.5, 0.5, -1.0);
        Vec3 to = new Vec3(0.5, 0.5, 2.0);

        BlockHitResult selectionHit = MechanismFrameSelectionShape.shape(state).clip(from, to, BlockPos.ZERO);
        assertNull(selectionHit, "expanded selection cage must leave the central mini-content opening targetable");
    }
}
