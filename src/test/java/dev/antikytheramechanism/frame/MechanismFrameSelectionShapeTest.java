package dev.antikytheramechanism.frame;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MechanismFrameSelectionShapeTest {
    @Test
    void selectionGetsHalfPixelInwardBeyondPhysicalBar() {
        VoxelShape selection = MechanismFrameSelectionShape.shapeForConnectionMask(0);
        VoxelShape physicalNorthWestVerticalBar = Block.box(0, 0, 0, 2, 16, 2);

        // 2.25 model pixels from the west edge: outside the real 2 px bar, inside the 2.5 px
        // client grab area. Aim straight through the north face halfway up the Frame.
        double x = 2.25 / 16.0;
        Vec3 from = new Vec3(x, 0.5, -1.0);
        Vec3 to = new Vec3(x, 0.5, 2.0);

        BlockHitResult selectionHit = selection.clip(from, to, BlockPos.ZERO);
        BlockHitResult physicalBarHit = physicalNorthWestVerticalBar.clip(from, to, BlockPos.ZERO);

        assertNotNull(selectionHit, "expanded selection cage should catch the near-edge ray");
        assertNull(physicalBarHit, "the same ray must remain outside the real 2 px physical bar");
    }

    @Test
    void centralOpeningStillPassesRayThroughFrame() {
        VoxelShape selection = MechanismFrameSelectionShape.shapeForConnectionMask(0);
        Vec3 from = new Vec3(0.5, 0.5, -1.0);
        Vec3 to = new Vec3(0.5, 0.5, 2.0);

        BlockHitResult selectionHit = selection.clip(from, to, BlockPos.ZERO);
        assertNull(selectionHit, "expanded selection cage must leave the central mini-content opening targetable");
    }
}
