package dev.antikytheramechanism.sublevel;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * One-shot server-side capture for Catnip placement sounds that need their origin decided after a
 * managed placement transaction finishes.
 *
 * <p>The first block inserted into a Frame may create its managed SubLevel during the same helper
 * interaction. Catnip emits the placement sound from the plot before clients can necessarily address
 * that new child. Adding a second physical sound fixes the silence but can double-play once the plot
 * sound is also projected correctly. Capture the original sound instead and replay exactly one copy
 * after the caller knows whether a new child was created.</p>
 */
public final class ManagedPlacementSoundCapture {
    private static final ThreadLocal<CaptureState> ACTIVE = new ThreadLocal<>();

    private ManagedPlacementSoundCapture() {
    }

    public static void begin() {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Managed placement sound capture is already active");
        }
        ACTIVE.set(new CaptureState());
    }

    public static @Nullable CapturedSound end() {
        CaptureState state = ACTIVE.get();
        ACTIVE.remove();
        return state == null ? null : state.sound;
    }

    /** Returns true when the sound was captured and the original call must be skipped. */
    public static boolean capture(
            Level level,
            @Nullable Player excludedPlayer,
            double x,
            double y,
            double z,
            SoundEvent sound,
            SoundSource source,
            float volume,
            float pitch) {
        CaptureState state = ACTIVE.get();
        if (state == null) {
            return false;
        }
        // PlacementOffset emits exactly one placement sound. Keep the first call defensively so a
        // future Catnip change cannot turn one managed placement into multiple replayed sounds.
        if (state.sound == null) {
            state.sound = new CapturedSound(
                    level,
                    excludedPlayer,
                    x,
                    y,
                    z,
                    sound,
                    source,
                    volume,
                    pitch);
        }
        return true;
    }

    public record CapturedSound(
            Level level,
            @Nullable Player excludedPlayer,
            double x,
            double y,
            double z,
            SoundEvent sound,
            SoundSource source,
            float volume,
            float pitch) {

        public void playOriginal() {
            playAt(x, y, z);
        }

        public void playAt(double newX, double newY, double newZ) {
            level.playSound(
                    excludedPlayer,
                    newX,
                    newY,
                    newZ,
                    sound,
                    source,
                    volume,
                    pitch);
        }
    }

    private static final class CaptureState {
        private @Nullable CapturedSound sound;
    }
}
