package dev.antikytheramechanism.compat.simulated;

import dev.antikytheramechanism.assembly.MechanismAssembly;
import dev.antikytheramechanism.assembly.MechanismAssemblyManager;
import dev.antikytheramechanism.registry.MiniaturizableRegistry;
import dev.antikytheramechanism.sublevel.DetachedMiniPhysicsSubLevelService;
import dev.antikytheramechanism.sublevel.MechanismSubLevelService;
import dev.antikytheramechanism.sublevel.MiniCoordinateMapper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Synchronous guard around Simulated's structure search when a Physics Assembler lives in a Frame.
 *
 * <p>Simulated is still authoritative for slime, glue, chassis and other attachment rules. This
 * context adds one non-negotiable boundary: every gathered block must be a real owned mini cell in
 * the exact same 0.5-scale Frame child that started the operation. Macro blocks and other Sable
 * bodies therefore cannot become attached merely because their world-space geometry touches.</p>
 */
public final class MiniPhysicsAssemblyContext {
    private static final ThreadLocal<Deque<Context>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private MiniPhysicsAssemblyContext() {
    }

    /**
     * Resolves a healthy, stationary Frame child containing the assembler position. A Frame that is
     * being evacuated, piston-moved or carried by Create is deliberately not a valid ejection source.
     * The assembler itself must also still be allowed by the live miniaturization policy, so a
     * modpack deny entry disables already-placed assemblers rather than only preventing new ones.
     */
    public static @Nullable ServerSubLevel validFrameSource(ServerLevel level, BlockPos assemblerPosition) {
        SubLevel containing = Sable.HELPER.getContaining(level, assemblerPosition);
        if (!(containing instanceof ServerSubLevel source)
                || source.isRemoved()
                || !DetachedMiniPhysicsSubLevelService.hasHalfScale(source)) {
            return null;
        }
        BlockState assemblerState = level.getBlockState(assemblerPosition);
        if (assemblerState.isAir() || !MiniaturizableRegistry.isAllowed(assemblerState.getBlock())) {
            return null;
        }
        UUID owner = MechanismSubLevelService.getOwnerAssemblyId(source);
        if (owner == null) {
            return null;
        }
        MechanismAssemblyManager manager = MechanismAssemblyManager.get(level);
        MechanismAssembly assembly = manager.getAssembly(owner).orElse(null);
        if (assembly == null
                || !source.getUniqueId().equals(assembly.subLevelId())
                || manager.isContentRecoveryLocked(owner)
                || manager.pendingPistonMove(owner).isPresent()
                || manager.pendingContraptionMove(owner).isPresent()
                || manager.pendingFrameEvacuation(owner).isPresent()) {
            return null;
        }
        BlockPos local = assemblerPosition.subtract(source.getPlot().getCenterBlock());
        return MiniCoordinateMapper.isOwnedMiniPosition(assembly, local) ? source : null;
    }

    public static boolean begin(ServerLevel level, ServerSubLevel source) {
        UUID owner = MechanismSubLevelService.getOwnerAssemblyId(source);
        if (owner == null || !DetachedMiniPhysicsSubLevelService.hasHalfScale(source)) {
            return false;
        }
        MechanismAssembly assembly = MechanismAssemblyManager.get(level).getAssembly(owner).orElse(null);
        if (assembly == null || source.isRemoved() || !source.getUniqueId().equals(assembly.subLevelId())) {
            return false;
        }
        STACK.get().push(new Context(level, source.getUniqueId(), owner));
        return true;
    }

    public static void end() {
        Deque<Context> stack = STACK.get();
        if (stack.isEmpty()) {
            throw new IllegalStateException("No mini Physics Assembler context is active");
        }
        stack.pop();
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    public static boolean isActive() {
        Deque<Context> stack = STACK.get();
        return !stack.isEmpty();
    }

    /** Returns true when Simulated may consider this candidate under its own normal movement rules. */
    public static boolean allowsCandidate(Level level, BlockPos position, BlockState state) {
        Context context = current();
        if (context == null) {
            return true;
        }
        if (level != context.level() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        SubLevel containing = Sable.HELPER.getContaining(level, position);
        if (!(containing instanceof ServerSubLevel source)
                || source.isRemoved()
                || !context.sourceSubLevelId().equals(source.getUniqueId())
                || !DetachedMiniPhysicsSubLevelService.hasHalfScale(source)
                || !context.assemblyId().equals(MechanismSubLevelService.getOwnerAssemblyId(source))) {
            return false;
        }

        MechanismAssembly assembly = MechanismAssemblyManager.get(serverLevel)
                .getAssembly(context.assemblyId())
                .orElse(null);
        if (assembly == null || !source.getUniqueId().equals(assembly.subLevelId())) {
            return false;
        }

        BlockPos local = position.subtract(source.getPlot().getCenterBlock());
        return MiniCoordinateMapper.isOwnedMiniPosition(assembly, local)
                && !state.isAir()
                && MiniaturizableRegistry.isAllowed(state.getBlock());
    }

    public static @Nullable Context current() {
        Deque<Context> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    public record Context(ServerLevel level, UUID sourceSubLevelId, UUID assemblyId) {
    }
}
