package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.compat.simulated.SimulatedFrameAttachmentPolicy;
import dev.antikytheramechanism.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regression coverage for implicit Frame attachment versus explicit glue boundaries. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FrameAssemblyAttachmentGameTests {
    private FrameAssemblyAttachmentGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 40)
    public static void implicitAttachmentRequiresSameLogicalAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        BlockPos first = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos second = first.east();
        BlockPos differentFacing = second.east();
        placeFrame(level, first, Direction.NORTH);
        placeFrame(level, second, Direction.NORTH);
        placeFrame(level, differentFacing, Direction.SOUTH);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly firstAssembly = manager.getAssemblyAt(first)
                .orElseThrow(() -> new AssertionError("missing first Frame assembly"));
        MechanismAssembly secondAssembly = manager.getAssemblyAt(second)
                .orElseThrow(() -> new AssertionError("missing second Frame assembly"));
        MechanismAssembly foreignAssembly = manager.getAssemblyAt(differentFacing)
                .orElseThrow(() -> new AssertionError("missing differently-oriented Frame assembly"));

        check(firstAssembly.id().equals(secondAssembly.id()),
                "same-facing cardinal Frames did not form one logical assembly");
        check(!secondAssembly.id().equals(foreignAssembly.id()),
                "differently-oriented touching Frame unexpectedly joined the logical assembly");
        check(FrameAssemblyAttachment.sameAssembly(level, first, second),
                "same logical assembly was not implicitly attached");
        check(!FrameAssemblyAttachment.sameAssembly(level, second, differentFacing),
                "touching independent assembly was implicitly attached by proximity");

        // Simulated queries a full BlockPos offset, so the same identity rule must also hold on an
        // edge diagonal. Build an L where the diagonal endpoints genuinely share one assembly.
        BlockPos lOrigin = helper.absolutePos(new BlockPos(7, 2, 2));
        BlockPos lBridge = lOrigin.east();
        BlockPos lDiagonal = lBridge.above();
        placeFrame(level, lOrigin, Direction.NORTH);
        placeFrame(level, lBridge, Direction.NORTH);
        placeFrame(level, lDiagonal, Direction.NORTH);
        BlockPos diagonalBackToOrigin = lOrigin.subtract(lDiagonal);
        Boolean sameAssemblyDiagonal = SimulatedFrameAttachmentPolicy.attachmentOverride(
                level.getBlockState(lDiagonal), level, lDiagonal, diagonalBackToOrigin);
        check(Boolean.TRUE.equals(sameAssemblyDiagonal),
                "Simulated policy lost a diagonal edge inside one logical Frame assembly");

        // Two same-facing Frames can touch diagonally while remaining separate graph components.
        // Generic NeoForge stickiness must not turn that mere proximity into implicit Simulated glue.
        BlockPos isolated = helper.absolutePos(new BlockPos(11, 2, 2));
        BlockPos isolatedDiagonal = isolated.offset(1, 1, 0);
        placeFrame(level, isolated, Direction.NORTH);
        placeFrame(level, isolatedDiagonal, Direction.NORTH);
        check(!FrameAssemblyAttachment.sameAssembly(level, isolated, isolatedDiagonal),
                "diagonally touching Frames unexpectedly became one logical assembly");
        Boolean independentDiagonal = SimulatedFrameAttachmentPolicy.attachmentOverride(
                level.getBlockState(isolatedDiagonal),
                level,
                isolatedDiagonal,
                isolated.subtract(isolatedDiagonal));
        check(Boolean.FALSE.equals(independentDiagonal),
                "Simulated policy implicitly attached independent diagonal Frame assemblies");
        check(!SimulatedFrameAttachmentPolicy.useGenericStickiness(
                        level.getBlockState(isolated), level.getBlockState(isolatedDiagonal)),
                "generic sticky fallback could still capture independent Frame assemblies");

        helper.succeed();
    }

    private static void placeFrame(ServerLevel level, BlockPos position, Direction facing) {
        BlockState state = ModRegistries.MECHANISM_FRAME.get()
                .defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        check(level.setBlock(position, state, Block.UPDATE_ALL),
                "could not place test Frame at " + position);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
