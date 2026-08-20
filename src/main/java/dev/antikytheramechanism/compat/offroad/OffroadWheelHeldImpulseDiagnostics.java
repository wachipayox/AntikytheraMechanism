package dev.antikytheramechanism.compat.offroad;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.command.SableCommandHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Temporary diagnostic that separates Wheel Mount force recomputation cadence from impulse delivery.
 *
 * <p>A positive recomputes-per-tick value makes a Wheel Mount fully recompute raycasts, compression,
 * velocities and its force vector only that many times per Minecraft tick. Between those recomputes,
 * the last small substep impulse is replayed unchanged so Rapier still receives one wheel impulse per
 * Sable substep. This lets us distinguish "frequent force recalculation" from "many small impulses".
 * State is transient and never persisted.</p>
 */
public final class OffroadWheelHeldImpulseDiagnostics {
    private static final Map<ServerSubLevel, Integer> RECOMPUTES_PER_TICK = new WeakHashMap<>();

    private OffroadWheelHeldImpulseDiagnostics() {
    }

    public static synchronized int recomputesPerTick(ServerSubLevel subLevel) {
        return subLevel == null ? 0 : RECOMPUTES_PER_TICK.getOrDefault(subLevel, 0);
    }

    private static synchronized void setRecomputesPerTick(ServerSubLevel subLevel, int recomputes) {
        if (recomputes <= 0) {
            RECOMPUTES_PER_TICK.remove(subLevel);
        } else {
            RECOMPUTES_PER_TICK.put(subLevel, recomputes);
        }
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("antikythera")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("offroad_hold")
                                .then(Commands.literal("set")
                                        .then(Commands.literal("recomputes_per_tick")
                                                .then(Commands.argument("recomputes", IntegerArgumentType.integer(0, 256))
                                                        .executes(OffroadWheelHeldImpulseDiagnostics::setNearestRecomputes))))
                                .then(Commands.literal("status")
                                        .executes(OffroadWheelHeldImpulseDiagnostics::statusNearest))
                                .then(Commands.literal("reset")
                                        .executes(OffroadWheelHeldImpulseDiagnostics::resetNearest)))
        );
    }

    private static int setNearestRecomputes(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        int recomputes = IntegerArgumentType.getInteger(context, "recomputes");
        setRecomputesPerTick(target, recomputes);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad held-impulse debug " + describe(target)
                        + ": recomputes_per_tick=" + recomputes
                        + (recomputes == 0 ? " (normal)" : "")),
                false);
        return 1;
    }

    private static int statusNearest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        int recomputes = recomputesPerTick(target);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad held-impulse debug " + describe(target)
                        + ": recomputes_per_tick=" + recomputes),
                false);
        return 1;
    }

    private static int resetNearest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        setRecomputesPerTick(target, 0);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad held-impulse debug " + describe(target) + ": reset"),
                false);
        return 1;
    }

    private static ServerSubLevel nearestSubLevel(CommandSourceStack source) throws CommandSyntaxException {
        ServerSubLevelContainer container = SableCommandHelper.requireSubLevelContainer(source);
        Vec3 sourcePosition = Sable.HELPER.projectOutOfSubLevel(source.getLevel(), source.getPosition());

        ServerSubLevel closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (ServerSubLevel candidate : container.getAllSubLevels()) {
            double distance = candidate.logicalPose().position()
                    .distance(sourcePosition.x, sourcePosition.y, sourcePosition.z);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = candidate;
            }
        }

        if (closest == null) {
            throw SableCommandHelper.ERROR_NO_SUB_LEVELS_FOUND.create();
        }
        return closest;
    }

    private static String describe(ServerSubLevel subLevel) {
        String name = subLevel.getName();
        return name == null
                ? "sublevel " + subLevel.getUniqueId().toString().substring(0, 8)
                : "sublevel '" + name + "'";
    }
}
