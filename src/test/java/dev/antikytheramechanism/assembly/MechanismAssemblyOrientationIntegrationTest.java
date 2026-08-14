package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MechanismAssemblyOrientationIntegrationTest {
    private static final Set<BlockPos> LOGICAL_L = Set.of(
            BlockPos.ZERO,
            new BlockPos(1, 0, 0),
            new BlockPos(0, 0, 1),
            new BlockPos(0, 1, 1));

    @Test
    void irregularMultiFrameMappingIsOrthogonalAndReversibleForEveryYaw() {
        BlockPos origin = new BlockPos(12, 30, -7);
        for (Direction front : Direction.Plane.HORIZONTAL) {
            FrameOrientation orientation = new FrameOrientation(Direction.UP, front);
            Set<BlockPos> physical = LOGICAL_L.stream()
                    .map(orientation::toPhysical)
                    .map(origin::offset)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            MechanismAssembly assembly = new MechanismAssembly(
                    UUID.randomUUID(), origin, physical, orientation);

            for (BlockPos logical : LOGICAL_L) {
                BlockPos frame = assembly.physicalFrameAt(logical);
                assertEquals(logical, assembly.logicalFrameOffset(frame));
                for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
                    BlockPos physicalCell = orientation.logicalCellToPhysical(x, y, z);
                    assertEquals(
                            MiniCoordinateMapper.frameToMini(assembly, frame, x, y, z),
                            MiniCoordinateMapper.physicalFrameCellToMini(
                                    assembly, frame,
                                    physicalCell.getX(), physicalCell.getY(), physicalCell.getZ()));
                }
            }

            MechanismAssembly loaded = MechanismAssembly.load(assembly.save());
            assertEquals(orientation, loaded.orientation());
            assertEquals(physical, loaded.frames());
        }
    }

    @Test
    void physicallyAdjacentAssembliesWithDifferentLogicalOrientationsAreNeverCompatible() {
        MechanismAssembly north = new MechanismAssembly(
                UUID.randomUUID(), BlockPos.ZERO, Set.of(BlockPos.ZERO),
                new FrameOrientation(Direction.UP, Direction.NORTH));
        MechanismAssembly east = new MechanismAssembly(
                UUID.randomUUID(), BlockPos.ZERO.east(), Set.of(BlockPos.ZERO.east()),
                new FrameOrientation(Direction.UP, Direction.EAST));
        assertFalse(AssemblyOrientationMath.compatiblePhysical(north, east, 1.0E-6));
    }

    @Test
    void splitPoseRebasePreservesOriginalOrientationWithoutConstructionContext() {
        FrameOrientation orientation = new FrameOrientation(Direction.UP, Direction.WEST);
        BlockPos origin = new BlockPos(4, 5, 6);
        BlockPos splitOrigin = origin.north(2);
        MechanismAssembly source = new MechanismAssembly(
                UUID.randomUUID(), origin, Set.of(origin, splitOrigin), orientation);
        BlockPos logicalOffset = source.logicalFrameOffset(splitOrigin);
        AssemblyPose splitPose = AssemblyOrientationMath.rebaseLogical(source.poseTarget(), logicalOffset);
        MechanismAssembly split = new MechanismAssembly(
                UUID.randomUUID(), splitOrigin, Set.of(splitOrigin), source.orientation());
        split.setPoseTarget(splitPose);

        assertEquals(source.orientation(), split.orientation());
        assertTrue(AssemblyOrientationMath.compatiblePhysical(source, split, 1.0E-6));
    }
}
