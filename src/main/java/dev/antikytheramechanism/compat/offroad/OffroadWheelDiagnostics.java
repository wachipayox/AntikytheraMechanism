package dev.antikytheramechanism.compat.offroad;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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

import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/**
 * Temporary runtime switches for isolating Create Offroad Wheel Mount force components.
 *
 * <p>State is intentionally transient and keyed by the physical Sable sub-level instance. Nothing is
 * persisted to NBT or config. Commands always target the physical sub-level nearest to the command
 * source, mirroring Sable's {@code @n} selector semantics.</p>
 */
public final class OffroadWheelDiagnostics {
    public enum Term {
        SPRING("spring"),
        DAMPING("damping"),
        LONGITUDINAL("longitudinal"),
        LATERAL("lateral"),
        TORQUE("torque"),
        ALL("all");

        private final String commandName;

        Term(String commandName) {
            this.commandName = commandName;
        }

        public String commandName() {
            return this.commandName;
        }
    }

    private static final Map<ServerSubLevel, EnumSet<Term>> DISABLED = new WeakHashMap<>();

    private OffroadWheelDiagnostics() {
    }

    public static synchronized boolean isDisabled(ServerSubLevel subLevel, Term term) {
        if (subLevel == null) {
            return false;
        }
        EnumSet<Term> terms = DISABLED.get(subLevel);
        return terms != null && terms.contains(term);
    }

    private static synchronized void setDisabled(ServerSubLevel subLevel, Term term, boolean disabled) {
        EnumSet<Term> terms = DISABLED.computeIfAbsent(subLevel, ignored -> EnumSet.noneOf(Term.class));
        if (disabled) {
            terms.add(term);
        } else {
            terms.remove(term);
            if (terms.isEmpty()) {
                DISABLED.remove(subLevel);
            }
        }
    }

    private static synchronized EnumSet<Term> disabledTerms(ServerSubLevel subLevel) {
        EnumSet<Term> terms = DISABLED.get(subLevel);
        return terms == null ? EnumSet.noneOf(Term.class) : EnumSet.copyOf(terms);
    }

    private static synchronized void reset(ServerSubLevel subLevel) {
        DISABLED.remove(subLevel);
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> disable = Commands.literal("disable");
        LiteralArgumentBuilder<CommandSourceStack> enable = Commands.literal("enable");

        for (Term term : Term.values()) {
            disable.then(Commands.literal(term.commandName())
                    .executes(context -> setNearest(context, term, true)));
            enable.then(Commands.literal(term.commandName())
                    .executes(context -> setNearest(context, term, false)));
        }

        event.getDispatcher().register(
                Commands.literal("antikythera")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("offroad")
                                .then(disable)
                                .then(enable)
                                .then(Commands.literal("status").executes(OffroadWheelDiagnostics::statusNearest))
                                .then(Commands.literal("reset").executes(OffroadWheelDiagnostics::resetNearest)))
        );
    }

    private static int setNearest(CommandContext<CommandSourceStack> context, Term term, boolean disabled)
            throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        setDisabled(target, term, disabled);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad debug " + describe(target) + ": "
                        + term.commandName() + " " + (disabled ? "DISABLED" : "ENABLED")),
                false
        );
        return 1;
    }

    private static int statusNearest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        EnumSet<Term> terms = disabledTerms(target);
        String disabled = terms.isEmpty()
                ? "none"
                : terms.stream().map(Term::commandName).collect(Collectors.joining(", "));
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad debug " + describe(target) + " disabled: " + disabled),
                false
        );
        return 1;
    }

    private static int resetNearest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        reset(target);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad debug " + describe(target) + ": all wheel terms restored"),
                false
        );
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
