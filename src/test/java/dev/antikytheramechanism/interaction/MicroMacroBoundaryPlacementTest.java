package dev.antikytheramechanism.interaction;

import dev.antikytheramechanism.assembly.FrameOrientation;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicroMacroBoundaryPlacementTest {
    @Test
    void logicalNorthClickRoutesToPhysicalEastAfterYaw() {
        BlockPos frame = new BlockPos(10, 20, 30);
        MechanismAssembly assembly = new MechanismAssembly(
                UUID.randomUUID(), frame, Set.of(frame),
                new FrameOrientation(Direction.UP, Direction.EAST));

        Direction logicalFace = Direction.NORTH;
        Direction physicalFace = assembly.orientation().toPhysical(logicalFace);
        assertEquals(Direction.EAST, physicalFace);

        BlockPos miniGlobal = new BlockPos(100, 120, 140);
        Vec3 hit = MicroMacroBoundaryPlacement.macroHitLocation(
                assembly,
                frame,
                miniGlobal,
                BlockPos.ZERO,
                physicalFace,
                new Vec3(100.25, 120.75, 140.10));

        assertTrue(hit.x > frame.getX() + 0.999);
        assertTrue(hit.z > frame.getZ() + 0.0 && hit.z < frame.getZ() + 0.5,
                "logical X must become the correctly oriented in-face physical coordinate");
    }

    @Test
    void everyLogicalFaceMapsHitOntoItsPhysicalBoundary() {
        BlockPos frame = new BlockPos(-4, 8, 12);
        MechanismAssembly assembly = new MechanismAssembly(
                UUID.randomUUID(), frame, Set.of(frame),
                new FrameOrientation(Direction.UP, Direction.SOUTH));
        BlockPos miniGlobal = new BlockPos(200, 220, 240);

        for (Direction logicalFace : Direction.values()) {
            BlockPos cell = switch (logicalFace) {
                case WEST -> new BlockPos(0, 1, 1);
                case EAST -> new BlockPos(1, 1, 1);
                case DOWN -> new BlockPos(1, 0, 1);
                case UP -> new BlockPos(1, 1, 1);
                case NORTH -> new BlockPos(1, 1, 0);
                case SOUTH -> new BlockPos(1, 1, 1);
            };
            Direction physicalFace = assembly.orientation().toPhysical(logicalFace);
            Vec3 hit = MicroMacroBoundaryPlacement.macroHitLocation(
                    assembly,
                    frame,
                    miniGlobal,
                    cell,
                    physicalFace,
                    new Vec3(200.6, 220.4, 240.7));

            switch (physicalFace) {
                case WEST -> assertTrue(hit.x < frame.getX() + 0.001);
                case EAST -> assertTrue(hit.x > frame.getX() + 0.999);
                case DOWN -> assertTrue(hit.y < frame.getY() + 0.001);
                case UP -> assertTrue(hit.y > frame.getY() + 0.999);
                case NORTH -> assertTrue(hit.z < frame.getZ() + 0.001);
                case SOUTH -> assertTrue(hit.z > frame.getZ() + 0.999);
            }
        }
    }
}
