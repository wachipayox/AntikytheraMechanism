package dev.antikytheramechanism.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiniPlacementRouterTest {
    @Test
    void floorReferenceSelectsLowerMiniCellFromHitPosition() {
        BlockPos frame = new BlockPos(10, 20, 30);
        MiniPlacementRouter.CellSelection selected = MiniPlacementRouter.selectBoundaryCell(
                frame,
                Direction.DOWN,
                new Vec3(10.75, 20.0, 30.25));

        assertEquals(1, selected.x());
        assertEquals(0, selected.y());
        assertEquals(0, selected.z());
    }

    @Test
    void frameFaceForcesSelectionOntoClickedBoundary() {
        BlockPos frame = new BlockPos(-4, 8, 12);
        MiniPlacementRouter.CellSelection selected = MiniPlacementRouter.selectBoundaryCell(
                frame,
                Direction.EAST,
                new Vec3(-3.0, 8.8, 12.7));

        assertEquals(1, selected.x());
        assertEquals(1, selected.y());
        assertEquals(1, selected.z());
    }
}
