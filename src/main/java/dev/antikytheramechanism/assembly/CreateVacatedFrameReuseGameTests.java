package dev.antikytheramechanism.assembly;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.registry.ModRegistries;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regression coverage for a physical parent coordinate reused while its old Frame is in Create. */
@GameTestHolder(AntikytheraMechanism.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateVacatedFrameReuseGameTests {
    private CreateVacatedFrameReuseGameTests() {
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 180)
    public static void vacatedCreateSourceCanHostIndependentFrameImmediately(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos destination = helper.absolutePos(new BlockPos(8, 2, 3));
        placeFrame(level, source);

        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly moving = manager.getAssemblyAt(source)
                .orElseThrow(() -> new AssertionError("missing original Frame assembly"));
        UUID movingId = moving.id();
        ServerSubLevel movingChild = MechanismSubLevelService.ensureForContent(level, moving);
        check(movingChild != null, "could not create original Frame mini world");
        BlockPos movingMini = MiniCoordinateMapper.frameToMini(moving, source, 0, 0, 0);
        putMini(movingChild, movingMini, Blocks.DIAMOND_BLOCK.defaultBlockState(),
                "original mini payload");

        check(manager.prepareContraptionMoves(
                        level,
                        Map.of(movingId, Set.of(source)),
                        BlockPos.ZERO,
                        false),
                "could not journal original Frame as Create capture");
        check(level.removeBlock(source, false),
                "could not remove original Frame during simulated Create extraction");
        check(level.getBlockState(source).isAir(),
                "Create source was not physically vacated");
        check(manager.pendingContraptionMove(movingId).isPresent(),
                "moving assembly lost its Create journal after extraction");

        // This is the user regression: a completely new Frame is placed in the macro coordinate that
        // the captured Frame used to occupy, while the old contraption remains assembled elsewhere.
        placeFrame(level, source);
        MechanismAssembly replacement = manager.getAssemblyAt(source)
                .orElseThrow(() -> new AssertionError(
                        "replacement Frame was not registered at the vacated Create source"));
        UUID replacementId = replacement.id();
        check(!replacementId.equals(movingId),
                "replacement Frame incorrectly inherited the in-flight assembly UUID");
        PendingContraptionMove pending = manager.pendingContraptionMove(movingId)
                .orElseThrow(() -> new AssertionError("old Create journal disappeared after source reuse"));
        check(ContraptionSourceRelease.isReleased(pending, source),
                "old Create journal did not persistently release the reused source position");
        check(!manager.isFrameLifecycleLocked(source),
                "replacement Frame remained lifecycle-locked by the unrelated in-flight journal");

        ServerSubLevel replacementChild = MechanismSubLevelService.ensureForContent(level, replacement);
        check(replacementChild != null, "replacement Frame could not create its own mini world");
        BlockPos replacementMini = MiniCoordinateMapper.frameToMini(replacement, source, 1, 1, 1);
        putMini(replacementChild, replacementMini, Blocks.STONE.defaultBlockState(),
                "replacement mini payload");
        check(replacementChild.getPlot().getEmbeddedLevelAccessor()
                        .getBlockState(replacementMini).is(Blocks.STONE),
                "replacement Frame rejected or lost its mini payload while old Create move was active");

        // Saving while both logical records refer historically to the same parent coordinate must not
        // turn the valid released-source state into duplicate-frame corruption on restart.
        MechanismAssemblyManager decoded = roundTripManager(manager, level.registryAccess());
        check(decoded.getAssemblyAt(source)
                        .map(MechanismAssembly::id)
                        .filter(replacementId::equals)
                        .isPresent(),
                "SavedData reload restored the historical moving owner instead of the replacement");
        PendingContraptionMove decodedMove = decoded.pendingContraptionMove(movingId)
                .orElseThrow(() -> new AssertionError("SavedData reload lost old Create journal"));
        check(ContraptionSourceRelease.isReleased(decodedMove, source),
                "SavedData reload lost released-source identity");
        check(!decoded.isContentRecoveryLocked(movingId)
                        && !decoded.isContentRecoveryLocked(replacementId),
                "SavedData reload falsely recovery-locked a legitimate reused source");

        // Wait through the manager's maintenance interval. The new physical Frame must not be mistaken
        // for proof that Create collection never started, which would incorrectly discard the journal.
        helper.runAfterDelay(25, () -> {
            check(manager.pendingContraptionMove(movingId).isPresent(),
                    "maintenance mistook replacement Frame for an unstarted Create capture");
            check(manager.getAssemblyAt(source)
                            .map(MechanismAssembly::id)
                            .filter(replacementId::equals)
                            .isPresent(),
                    "maintenance stole replacement Frame ownership");

            check(manager.prepareContraptionPlacement(
                            level,
                            Map.of(movingId, Set.of(destination)),
                            Map.of(movingId, destination),
                            Map.of(movingId, AssemblyPose.identityAt(destination))),
                    "could not prepare old contraption at a different destination");
            placeFrame(level, destination);

            CreatePlacementCommitService.CommitResult result =
                    CreatePlacementCommitService.finalizePreparedPlacement(level, List.of(movingId));
            check(result.committed(),
                    "old contraption could not commit after its source was reused");
            check(manager.pendingContraptionMove(movingId).isEmpty(),
                    "old Create journal survived successful placement");
            check(manager.getAssemblyAt(source)
                            .map(MechanismAssembly::id)
                            .filter(replacementId::equals)
                            .isPresent(),
                    "old contraption finalization stole the replacement source Frame");
            check(manager.getAssemblyAt(destination)
                            .map(MechanismAssembly::id)
                            .filter(movingId::equals)
                            .isPresent(),
                    "moving assembly did not own its real destination");
            check(!manager.isContentRecoveryLocked(movingId)
                            && !manager.isContentRecoveryLocked(replacementId),
                    "source reuse or final placement created an unexpected recovery lock");

            BlockPos relocatedMovingMini =
                    MiniCoordinateMapper.frameToMini(moving, destination, 0, 0, 0);
            check(movingChild.getPlot().getEmbeddedLevelAccessor()
                            .getBlockState(relocatedMovingMini).is(Blocks.DIAMOND_BLOCK),
                    "original moving mini payload changed during source reuse/final placement");
            check(replacementChild.getPlot().getEmbeddedLevelAccessor()
                            .getBlockState(replacementMini).is(Blocks.STONE),
                    "replacement mini payload changed when the old contraption finalized");
            helper.succeed();
        });
    }

    @GameTest(template = "frame_rotation_empty", timeoutTicks = 80)
    public static void releasedSourceMarkerSurvivesJournalPlacementCopyAndNbt(GameTestHelper helper) {
        BlockPos source = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos destination = helper.absolutePos(new BlockPos(6, 2, 2));
        PendingContraptionMove move = new PendingContraptionMove(
                UUID.randomUUID(),
                Set.of(source),
                source,
                Set.of(BlockPos.ZERO),
                AssemblyPose.identityAt(source),
                42L);

        check(ContraptionSourceRelease.release(move, source),
                "could not mark Create source as released");
        PendingContraptionMove loaded = PendingContraptionMove.load(move.save());
        check(ContraptionSourceRelease.isReleased(loaded, source),
                "released source did not survive journal NBT round-trip");

        PendingContraptionMove placed = loaded.withPlacement(
                Set.of(destination), destination, AssemblyPose.identityAt(destination));
        check(ContraptionSourceRelease.isReleased(placed, source),
                "withPlacement dropped released-source recovery metadata");
        PendingContraptionMove placedReloaded = PendingContraptionMove.load(placed.save());
        check(ContraptionSourceRelease.isReleased(placedReloaded, source),
                "placed journal NBT round-trip dropped released-source metadata");
        helper.succeed();
    }

    private static MechanismAssemblyManager roundTripManager(
            MechanismAssemblyManager manager,
            HolderLookup.Provider registries) {
        CompoundTag saved = manager.save(new CompoundTag(), registries);
        try {
            Method load = MechanismAssemblyManager.class.getDeclaredMethod(
                    "load", CompoundTag.class, HolderLookup.Provider.class);
            load.setAccessible(true);
            return (MechanismAssemblyManager) load.invoke(null, saved, registries);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new AssertionError("could not invoke MechanismAssemblyManager SavedData loader", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new AssertionError("MechanismAssemblyManager SavedData loader failed", cause);
        }
    }

    private static void placeFrame(ServerLevel level, BlockPos position) {
        check(level.setBlock(
                        position,
                        ModRegistries.MECHANISM_FRAME.get().defaultBlockState(),
                        Block.UPDATE_ALL),
                "could not place Mechanism Frame at " + position);
    }

    private static void putMini(
            ServerSubLevel child,
            BlockPos miniPosition,
            BlockState state,
            String label) {
        check(child.getPlot().getEmbeddedLevelAccessor().setBlock(
                        miniPosition, state, Block.UPDATE_ALL),
                "could not place " + label + " at " + miniPosition);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
