package dev.antikytheramechanism.frame;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Regression coverage for command/programmatic Frame removal outside player destruction hooks. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FrameDirectRemovalGameTests {
    private FrameDirectRemovalGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 100)
    public static void directAirReplacementDoesNotPoisonFrameCoordinate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos framePos = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockState frameState = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        check(level.setBlock(framePos, frameState, Block.UPDATE_ALL), "could not place original Frame");

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly original = manager.getAssemblyAt(framePos)
                .orElseThrow(() -> new AssertionError("original Frame was not indexed"));
        UUID originalId = original.id();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, original);
        check(child != null && !child.isRemoved(), "could not materialize mini content before direct removal");
        BlockPos miniLocal = MiniCoordinateMapper.frameToMini(original, framePos, 0, 0, 0);
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                        miniLocal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                "could not seed mini content before direct removal");
        child.getPlot().updateBoundingBox();
        manager.refreshFrame(level, framePos);

        // This is the same world mutation used by /setblock ... air, deliberately bypassing the
        // playerWillDestroy pre-evacuation path. It must return without recursively mutating Sable.
        check(level.setBlock(framePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL),
                "direct AIR replacement failed");
        check(level.getBlockState(framePos).isAir(), "Frame survived direct AIR replacement");

        helper.runAfterDelay(3, () -> {
            check(manager.getAssemblyAt(framePos).isEmpty(),
                    "deferred direct removal left stale Frame ownership at the coordinate");
            check(manager.getAssembly(originalId).isEmpty(),
                    "single-Frame assembly survived deferred direct removal");

            // Reusing the exact coordinate is the important poisoned-position regression. The new
            // Frame must receive a fresh owner and remain normally removable afterwards.
            check(level.setBlock(framePos, frameState, Block.UPDATE_ALL),
                    "could not place replacement Frame at directly-cleared coordinate");
            helper.runAfterDelay(2, () -> {
                MechanismAssembly replacement = manager.getAssemblyAt(framePos)
                        .orElseThrow(() -> new AssertionError("replacement Frame was not indexed"));
                check(!replacement.id().equals(originalId),
                        "replacement Frame inherited stale assembly identity from removed Frame");
                BlockEntity blockEntity = level.getBlockEntity(framePos);
                check(blockEntity instanceof MechanismFrameBlockEntity,
                        "replacement Frame has no block entity");
                MechanismFrameBlockEntity frameEntity = (MechanismFrameBlockEntity) blockEntity;
                check(replacement.id().equals(frameEntity.getAssemblyId()),
                        "replacement Frame block entity does not match manager ownership");

                check(level.setBlock(framePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL),
                        "replacement Frame could not be removed a second time");
                helper.runAfterDelay(3, () -> {
                    check(level.getBlockState(framePos).isAir(),
                            "replacement Frame survived second direct removal");
                    check(manager.getAssemblyAt(framePos).isEmpty(),
                            "second direct removal left poisoned ownership behind");
                    helper.succeed();
                });
            });
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
