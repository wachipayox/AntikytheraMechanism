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

        // Use the same proven UP-face support arrangement as the Sable relocation support regression:
        // all four boundary mini cells are stone, so a vanilla macro torch can survive on the Frame.
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                BlockPos local = MiniCoordinateMapper.frameToMini(assembly, framePos, x, 1, z);
                BlockPos global = MechanismSubLevelService.toPlotPosition(child, local);
                check(level.setBlock(global, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL),
                        "could not fill top mini support face");
            }
        }

        // A ceiling-mounted assembler at y=0 has sticky facing UP, so it assembles the stone at
        // (0,1,0). Removing that selected support (and any connected mini payload) invalidates UP.
        BlockPos assemblerLocal = MiniCoordinateMapper.frameToMini(assembly, framePos, 0, 0, 0);
        BlockPos assemblerGlobal = MechanismSubLevelService.toPlotPosition(child, assemblerLocal);
        BlockState assemblerState = physicsAssembler.defaultBlockState();
        if (assemblerState.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            assemblerState = assemblerState.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.CEILING);
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

        BlockPos torchPos = framePos.above();
        check(level.setBlock(torchPos, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL),
                "could not place macro torch on mini-backed Frame face");
        check(level.getBlockState(torchPos).is(Blocks.TORCH)
                        && level.getBlockState(torchPos).canSurvive(level, torchPos),
                "macro torch did not recognize complete mini-backed UP support");

        int snapshotsBefore = level.capturedBlockSnapshots.size();
        invokeAssembler(assemblerEntity);

        check(level.getBlockState(torchPos).isAir(),
                "macro torch survived after Physics Assembler removed its mini-backed support");
        check(hasTorchDrop(level, torchPos),
                "macro torch broke without producing its normal server-side drop");

        // The original visual bug is represented exactly by a snapshot captured for torchPos:
        // NeoForge changes the server block but intentionally skips markAndNotifyBlock (and therefore
        // UPDATE_CLIENTS) while captureBlockSnapshots is true. The fixed path must break the torch
        // only after Sable leaves that phase.
        List<BlockSnapshot> newSnapshots = level.capturedBlockSnapshots.subList(
                Math.min(snapshotsBefore, level.capturedBlockSnapshots.size()),
                level.capturedBlockSnapshots.size());
        check(newSnapshots.stream().noneMatch(snapshot -> snapshot.getPos().equals(torchPos)),
                "macro attachment was still destroyed inside Sable's snapshot-capture window");
        check(!level.captureBlockSnapshots,
                "Sable left NeoForge block snapshot capture enabled after mini assembly");
        helper.succeed();
    }

    private static boolean hasTorchDrop(ServerLevel level, BlockPos position) {
        AABB bounds = new AABB(position).inflate(2.0);
        return !level.getEntitiesOfClass(
                ItemEntity.class,
                bounds,
                entity -> entity.getItem().is(Items.TORCH)).isEmpty();
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
