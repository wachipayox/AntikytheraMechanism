package dev.antikytheramechanism.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniPlacementRouterTest {
    @Test
    void directFrameHitUsesCursorOctantInsteadOfHitFaceNormal() {
        BlockPos frame = new BlockPos(-4, 8, 12);
        // This is near the east side of the frame even if the ray happened to hit the WEST-facing
        // inner face of an east-side bar. The cursor position, not that face normal, owns selection.
        MiniPlacementRouter.CellSelection selected = MiniPlacementRouter.selectDirectCell(
                frame,
                new Vec3(-3.01, 8.2, 12.2));

        assertEquals(1, selected.x());
        assertEquals(0, selected.y());
        assertEquals(0, selected.z());
    }

    @Test
    void directFrameHitCanSelectEveryOctant() {
        BlockPos frame = BlockPos.ZERO;
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    MiniPlacementRouter.CellSelection selected = MiniPlacementRouter.selectDirectCell(
                            frame,
                            new Vec3(x == 0 ? 0.25 : 0.75, y == 0 ? 0.25 : 0.75, z == 0 ? 0.25 : 0.75));
                    assertEquals(x, selected.x());
                    assertEquals(y, selected.y());
                    assertEquals(z, selected.z());
                }
            }
        }
    }

    @Test
    void innerBarFacesRouteIntoMiniWorldRegardlessOfPlayerSide() {
        BlockPos frame = new BlockPos(10, 20, 30);

        assertTrue(MiniPlacementRouter.isInteriorFacingFrameHit(
                frame, Direction.EAST, new Vec3(10.125, 20.05, 30.05)));
        assertTrue(MiniPlacementRouter.isInteriorFacingFrameHit(
                frame, Direction.WEST, new Vec3(10.875, 20.95, 30.95)));
        assertTrue(MiniPlacementRouter.isInteriorFacingFrameHit(
                frame, Direction.UP, new Vec3(10.05, 20.125, 30.05)));
        assertTrue(MiniPlacementRouter.isInteriorFacingFrameHit(
                frame, Direction.NORTH, new Vec3(10.95, 20.95, 30.875)));
    }

    @Test
    void exteriorBarFacesStayVanilla() {
        BlockPos frame = new BlockPos(10, 20, 30);

        assertFalse(MiniPlacementRouter.isInteriorFacingFrameHit(
                frame, Direction.WEST, new Vec3(10.0, 20.05, 30.05)));
        assertFalse(MiniPlacementRouter.isInteriorFacingFrameHit(
                frame, Direction.EAST, new Vec3(11.0, 20.95, 30.95)));
        assertFalse(MiniPlacementRouter.isInteriorFacingFrameHit(
                frame, Direction.DOWN, new Vec3(10.05, 20.0, 30.05)));
        assertFalse(MiniPlacementRouter.isInteriorFacingFrameHit(
                frame, Direction.SOUTH, new Vec3(10.95, 20.95, 31.0)));
    }
}
