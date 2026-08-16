package dev.antikytheramechanism.sublevel;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Regression for macro attachments losing mini-backed support during Physics Assembler ejection. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MiniPhysicsSupportClientSyncGameTests {
    private static final ResourceLocation PHYSICS_ASSEMBLER =
            ResourceLocation.fromNamespaceAndPath("simulated", "physics_assembler");

    private MiniPhysicsSupportClientSyncGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 160)
    public static void assemblerSupportLossBreaksMacroAttachmentOutsideSnapshotCapture(GameTestHelper helper) {
        if (!ModList.get().isLoaded("simulated")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Block physicsAssembler = BuiltInRegistries.BLOCK.get(PHYSICS_ASSEMBLER);
        check(physicsAssembler != Blocks.AIR,
                "Simulated is loaded but simulated:physics_assembler is missing");

        BlockPos framePos = helper.absolutePos(new BlockPos(4, 4, 4));
        BlockState frameState = ModRegistries.MECHANISM_FRAME.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH);
        check(level.setBlock(framePos, frameState, Block.UPDATE_ALL), "could not place Frame");

        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssemblyAt(framePos).orElseThrow();
        ServerSubLevel child = MechanismSubLevelService.ensureForContent(level, assembly);
        check(child != null && !child.isRemoved(), "could not materialize Frame mini world");

        // Fill the complete DOWN face so the Frame legitimately supports a hanging macro lantern.
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                BlockPos local = MiniCoordinateMapper.frameToMini(assembly, framePos, x, 0, z);
                BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
                check(level.setBlock(global, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                        "could not fill bottom mini support face");
            }
        }

        BlockPos assemblerLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 1, 0);
        BlockPos assemblerGlobal = MechanismSubLevelService.toPlotPosition(child, assemblerLocal);
        BlockState assemblerState = physicsAssembler.defaultBlockState();
        if (assemblerState.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            assemblerState = assemblerState.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
        }
        if (assemblerState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            assemblerState = assemblerState.setValue(
                    BlockStateProperties.HORIZONTAL_FACING,
                    net.minecraft.core.Direction.NORTH);
        }
        check(level.setBlock(assemblerGlobal, assemblerState, Block.UPDATE_ALL),
                "could not place mini Physics Assembler");
        Object assemblerEntity = level.getBlockEntity(assemblerGlobal);
        check(assemblerEntity != null, "mini Physics Assembler BlockEntity missing");

        BlockPos lanternPos = framePos.below();
        BlockState lantern = Blocks.LANTERN.defaultBlockState()
                .setValue(BlockStateProperties.HANGING, true);
        check(level.setBlock(lanternPos, lantern, Block.UPDATE_ALL),
                "could not place hanging macro lantern");
        check(level.getBlockState(lanternPos).is(Blocks.LANTERN)
                        && level.getBlockState(lanternPos).canSurvive(level, lanternPos),
                "macro lantern did not recognize complete mini-backed DOWN support");

        int snapshotsBefore = level.capturedBlockSnapshots.size();
        invokeAssembler(assemblerEntity);

        check(level.getBlockState(lanternPos).isAir(),
                "macro lantern survived after Physics Assembler removed its mini-backed support");
        check(hasLanternDrop(level, lanternPos),
                "macro lantern broke without producing its normal server-side drop");

        // The original visual bug is represented exactly by a snapshot captured for lanternPos:
        // NeoForge changes the server block but intentionally skips markAndNotifyBlock (and therefore
        // UPDATE_CLIENTS) while captureBlockSnapshots is true. The fixed path must break the lantern
        // only after Sable leaves that phase.
        List<BlockSnapshot> newSnapshots = level.capturedBlockSnapshots.subList(
                Math.min(snapshotsBefore, level.capturedBlockSnapshots.size()),
                level.capturedBlockSnapshots.size());
        check(newSnapshots.stream().noneMatch(snapshot -> snapshot.getPos().equals(lanternPos)),
                "macro attachment was still destroyed inside Sable's snapshot-capture window");
        check(!level.captureBlockSnapshots,
                "Sable left NeoForge block snapshot capture enabled after mini assembly");
        helper.succeed();
    }

    private static boolean hasLanternDrop(ServerLevel level, BlockPos position) {
        AABB bounds = new AABB(position).inflate(2.0);
        return !level.getEntitiesOfClass(
                ItemEntity.class,
                bounds,
                entity -> entity.getItem().is(Items.LANTERN)).isEmpty();
    }

    private static void invokeAssembler(Object blockEntity) {
        try {
            Method method = blockEntity.getClass().getMethod("assembleOrDisassemble");
            method.invoke(blockEntity);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new AssertionError("Could not invoke Simulated Physics Assembler", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("Physics Assembler invocation failed", cause);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
