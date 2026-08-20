package dev.antikytheramechanism.compat.offroad;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.command.SableCommandHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
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
    private static final Map<ServerSubLevel, Double> SPRING_SCALE = new WeakHashMap<>();
    private static final Map<ServerSubLevel, Boolean> SPRING_SATURATED = new WeakHashMap<>();
    private static final Map<ServerSubLevel, Boolean> MASS_AXIS_WORLD_UP = new WeakHashMap<>();
    private static final Map<ServerSubLevel, Boolean> UNIFORM_SPRING_MASS = new WeakHashMap<>();
    private static final Map<ServerSubLevel, Boolean> SUSPENSION_NO_TORQUE = new WeakHashMap<>();
    private static final Map<ServerSubLevel, Boolean> WHEEL_NO_WAKE = new WeakHashMap<>();
    private static final Map<ServerSubLevel, Double> COUNTER_GRAVITY = new WeakHashMap<>();

    /**
     * 0 means normal Offroad behaviour: update once per Sable physics substep. A positive value asks
     * the diagnostic mixin to evaluate each Wheel Mount only this many times per Minecraft tick while
     * compensating the applied impulse so total wheel support over the tick remains approximately equal.
     */
    private static final Map<ServerSubLevel, Integer> WHEEL_UPDATES_PER_TICK = new WeakHashMap<>();

    private OffroadWheelDiagnostics() {
    }

    /**
     * Registers the generic pre-physics diagnostic hook once. Counter-gravity is deliberately applied
     * outside Offroad's wheel implementation so it can distinguish "any active support force" from
     * "the Wheel Mount spring algorithm".
     */
    public static void registerPhysicsHook() {
        SableEventPlatform.INSTANCE.onPhysicsTick(OffroadWheelDiagnostics::onPhysicsTick);
    }

    private static void onPhysicsTick(SubLevelPhysicsSystem physicsSystem, double timeStep) {
        final ArrayList<Map.Entry<ServerSubLevel, Double>> entries;
        synchronized (OffroadWheelDiagnostics.class) {
            entries = new ArrayList<>(COUNTER_GRAVITY.entrySet());
        }

        if (entries.isEmpty()) {
            return;
        }

        for (Map.Entry<ServerSubLevel, Double> entry : entries) {
            ServerSubLevel subLevel = entry.getKey();
            double fraction = entry.getValue();
            if (subLevel == null || subLevel.isRemoved() || subLevel.getLevel() != physicsSystem.getLevel() || fraction == 0.0) {
                continue;
            }

            double mass = subLevel.getMassTracker().getMass();
            if (!(mass > 0.0) || !Double.isFinite(mass)) {
                continue;
            }

            Vector3d worldImpulse = DimensionPhysicsData.getGravity(physicsSystem.getLevel())
                    .mul(-mass * timeStep * fraction);
            Vector3d localImpulse = subLevel.logicalPose().transformNormalInverse(worldImpulse);
            physicsSystem.getPhysicsHandle(subLevel).applyLinearImpulse(localImpulse);
        }
    }

    public static synchronized boolean isDisabled(ServerSubLevel subLevel, Term term) {
        if (subLevel == null) {
            return false;
        }
        EnumSet<Term> terms = DISABLED.get(subLevel);
        return terms != null && terms.contains(term);
    }

    public static synchronized double springScale(ServerSubLevel subLevel) {
        return subLevel == null ? 1.0 : SPRING_SCALE.getOrDefault(subLevel, 1.0);
    }

    public static synchronized boolean springSaturated(ServerSubLevel subLevel) {
        return subLevel != null && SPRING_SATURATED.getOrDefault(subLevel, false);
    }

    public static synchronized boolean massAxisWorldUp(ServerSubLevel subLevel) {
        return subLevel != null && MASS_AXIS_WORLD_UP.getOrDefault(subLevel, false);
    }

    public static synchronized boolean uniformSpringMass(ServerSubLevel subLevel) {
        return subLevel != null && UNIFORM_SPRING_MASS.getOrDefault(subLevel, false);
    }

    public static synchronized boolean suspensionNoTorque(ServerSubLevel subLevel) {
        return subLevel != null && SUSPENSION_NO_TORQUE.getOrDefault(subLevel, false);
    }

    public static synchronized boolean wheelNoWake(ServerSubLevel subLevel) {
        return subLevel != null && WHEEL_NO_WAKE.getOrDefault(subLevel, false);
    }

    public static synchronized double counterGravity(ServerSubLevel subLevel) {
        return subLevel == null ? 0.0 : COUNTER_GRAVITY.getOrDefault(subLevel, 0.0);
    }

    public static synchronized int wheelUpdatesPerTick(ServerSubLevel subLevel) {
        return subLevel == null ? 0 : WHEEL_UPDATES_PER_TICK.getOrDefault(subLevel, 0);
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

    private static synchronized void setSpringScale(ServerSubLevel subLevel, double scale) {
        if (scale == 1.0) {
            SPRING_SCALE.remove(subLevel);
        } else {
            SPRING_SCALE.put(subLevel, scale);
        }
    }

    private static synchronized void setSpringSaturated(ServerSubLevel subLevel, boolean enabled) {
        setBoolean(SPRING_SATURATED, subLevel, enabled);
    }

    private static synchronized void setMassAxisWorldUp(ServerSubLevel subLevel, boolean enabled) {
        setBoolean(MASS_AXIS_WORLD_UP, subLevel, enabled);
    }

    private static synchronized void setUniformSpringMass(ServerSubLevel subLevel, boolean enabled) {
        setBoolean(UNIFORM_SPRING_MASS, subLevel, enabled);
    }

    private static synchronized void setSuspensionNoTorque(ServerSubLevel subLevel, boolean enabled) {
        setBoolean(SUSPENSION_NO_TORQUE, subLevel, enabled);
    }

    private static synchronized void setWheelNoWake(ServerSubLevel subLevel, boolean enabled) {
        setBoolean(WHEEL_NO_WAKE, subLevel, enabled);
    }

    private static synchronized void setCounterGravity(ServerSubLevel subLevel, double fraction) {
        if (fraction == 0.0) {
            COUNTER_GRAVITY.remove(subLevel);
        } else {
            COUNTER_GRAVITY.put(subLevel, fraction);
        }
    }

    private static synchronized void setWheelUpdatesPerTick(ServerSubLevel subLevel, int updates) {
        if (updates <= 0) {
            WHEEL_UPDATES_PER_TICK.remove(subLevel);
        } else {
            WHEEL_UPDATES_PER_TICK.put(subLevel, updates);
        }
    }

    private static void setBoolean(Map<ServerSubLevel, Boolean> map, ServerSubLevel subLevel, boolean enabled) {
        if (enabled) {
            map.put(subLevel, true);
        } else {
            map.remove(subLevel);
        }
    }

    private static synchronized EnumSet<Term> disabledTerms(ServerSubLevel subLevel) {
        EnumSet<Term> terms = DISABLED.get(subLevel);
        return terms == null ? EnumSet.noneOf(Term.class) : EnumSet.copyOf(terms);
    }

    private static synchronized void reset(ServerSubLevel subLevel) {
        DISABLED.remove(subLevel);
        SPRING_SCALE.remove(subLevel);
        SPRING_SATURATED.remove(subLevel);
        MASS_AXIS_WORLD_UP.remove(subLevel);
        UNIFORM_SPRING_MASS.remove(subLevel);
        SUSPENSION_NO_TORQUE.remove(subLevel);
        WHEEL_NO_WAKE.remove(subLevel);
        COUNTER_GRAVITY.remove(subLevel);
        WHEEL_UPDATES_PER_TICK.remove(subLevel);
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

        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set")
                .then(Commands.literal("spring_scale")
                        .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.0, 1.0))
                                .executes(OffroadWheelDiagnostics::setNearestSpringScale)))
                .then(Commands.literal("spring_saturated")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(OffroadWheelDiagnostics::setNearestSpringSaturated)))
                .then(Commands.literal("mass_axis_world_up")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(OffroadWheelDiagnostics::setNearestMassAxisWorldUp)))
                .then(Commands.literal("uniform_spring_mass")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(OffroadWheelDiagnostics::setNearestUniformSpringMass)))
                .then(Commands.literal("suspension_no_torque")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(OffroadWheelDiagnostics::setNearestSuspensionNoTorque)))
                .then(Commands.literal("wheel_no_wake")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(OffroadWheelDiagnostics::setNearestWheelNoWake)))
                .then(Commands.literal("countergravity")
                        .then(Commands.argument("fraction", DoubleArgumentType.doubleArg(0.0, 2.0))
                                .executes(OffroadWheelDiagnostics::setNearestCounterGravity)))
                .then(Commands.literal("wheel_updates_per_tick")
                        .then(Commands.argument("updates", IntegerArgumentType.integer(0, 256))
                                .executes(OffroadWheelDiagnostics::setNearestWheelUpdatesPerTick)));

        event.getDispatcher().register(
                Commands.literal("antikythera")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("offroad")
                                .then(disable)
                                .then(enable)
                                .then(set)
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

    private static int setNearestSpringScale(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        double scale = DoubleArgumentType.getDouble(context, "scale");
        setSpringScale(target, scale);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad debug " + describe(target) + ": spring_scale=" + scale),
                false
        );
        return 1;
    }

    private static int setNearestCounterGravity(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        double fraction = DoubleArgumentType.getDouble(context, "fraction");
        setCounterGravity(target, fraction);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad debug " + describe(target) + ": countergravity=" + fraction),
                false
        );
        return 1;
    }

    private static int setNearestWheelUpdatesPerTick(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        int updates = IntegerArgumentType.getInteger(context, "updates");
        setWheelUpdatesPerTick(target, updates);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad debug " + describe(target)
                        + ": wheel_updates_per_tick=" + updates + (updates == 0 ? " (normal)" : "")),
                false
        );
        return 1;
    }

    private static int setNearestSpringSaturated(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return setNearestBoolean(context, "spring_saturated", OffroadWheelDiagnostics::setSpringSaturated);
    }

    private static int setNearestMassAxisWorldUp(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return setNearestBoolean(context, "mass_axis_world_up", OffroadWheelDiagnostics::setMassAxisWorldUp);
    }

    private static int setNearestUniformSpringMass(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return setNearestBoolean(context, "uniform_spring_mass", OffroadWheelDiagnostics::setUniformSpringMass);
    }

    private static int setNearestSuspensionNoTorque(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return setNearestBoolean(context, "suspension_no_torque", OffroadWheelDiagnostics::setSuspensionNoTorque);
    }

    private static int setNearestWheelNoWake(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return setNearestBoolean(context, "wheel_no_wake", OffroadWheelDiagnostics::setWheelNoWake);
    }

    private static int setNearestBoolean(
            CommandContext<CommandSourceStack> context,
            String name,
            BooleanSetter setter) throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        setter.set(target, enabled);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad debug " + describe(target) + ": " + name + "=" + enabled),
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
        double springScale = springScale(target);
        boolean springSaturated = springSaturated(target);
        boolean massAxisWorldUp = massAxisWorldUp(target);
        boolean uniformSpringMass = uniformSpringMass(target);
        boolean suspensionNoTorque = suspensionNoTorque(target);
        boolean wheelNoWake = wheelNoWake(target);
        double counterGravity = counterGravity(target);
        int wheelUpdates = wheelUpdatesPerTick(target);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad debug " + describe(target)
                        + " disabled: " + disabled
                        + "; spring_scale=" + springScale
                        + "; spring_saturated=" + springSaturated
                        + "; mass_axis_world_up=" + massAxisWorldUp
                        + "; uniform_spring_mass=" + uniformSpringMass
                        + "; suspension_no_torque=" + suspensionNoTorque
                        + "; wheel_no_wake=" + wheelNoWake
                        + "; countergravity=" + counterGravity
                        + "; wheel_updates_per_tick=" + wheelUpdates),
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

    @FunctionalInterface
    private interface BooleanSetter {
        void set(ServerSubLevel subLevel, boolean enabled);
    }
}
