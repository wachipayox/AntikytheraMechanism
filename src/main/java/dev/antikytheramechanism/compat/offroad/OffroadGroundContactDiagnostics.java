package dev.antikytheramechanism.compat.offroad;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.command.SableCommandHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.joml.Vector3d;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Temporary diagnostic bridge between Rapier's real hard contacts and Offroad's raycast suspension.
 *
 * <p>Rapier already reports sub-level/world contact force data after every game tick. We retain only
 * floor/ceiling-like contacts (world-space normal with a significant Y component), and expose a test
 * mode that removes Wheel Mount suspension impulse only while the chassis itself is hard-grounded.
 * This is intentionally transient and is not persisted.</p>
 */
public final class OffroadGroundContactDiagnostics {
    private static final double MIN_GROUND_NORMAL_Y = 0.5;

    private static final Map<ServerSubLevel, Double> GROUND_FORCE = new WeakHashMap<>();
    private static final Map<ServerSubLevel, Integer> GROUND_CONTACTS = new WeakHashMap<>();
    private static final Map<ServerSubLevel, Boolean> CUT_SUSPENSION_WHEN_GROUNDED = new WeakHashMap<>();

    private OffroadGroundContactDiagnostics() {
    }

    /**
     * Capture the collision batch Sable is about to process. A body id of -1 is the global world.
     * The native event handler reports the same pair force for each manifold point, so max force is
     * deliberately used instead of summing and accidentally multiplying a contact by its point count.
     */
    public static synchronized void captureGroundContacts(
            ServerLevel level,
            Int2ObjectMap<ServerSubLevel> activeSubLevels,
            double[] collisions) {
        GROUND_FORCE.keySet().removeIf(subLevel -> subLevel == null
                || subLevel.isRemoved()
                || subLevel.getLevel() == level);
        GROUND_CONTACTS.keySet().removeIf(subLevel -> subLevel == null
                || subLevel.isRemoved()
                || subLevel.getLevel() == level);

        if (collisions == null) {
            return;
        }

        Vector3d normal = new Vector3d();
        for (int i = 0; i + 14 < collisions.length; i += 15) {
            int idA = (int) collisions[i];
            int idB = (int) collisions[i + 1];

            // We are specifically diagnosing a physical sub-level touching the static global world.
            boolean aIsSubLevel = idA >= 0;
            boolean bIsSubLevel = idB >= 0;
            if (aIsSubLevel == bIsSubLevel) {
                continue;
            }

            int subLevelId = aIsSubLevel ? idA : idB;
            ServerSubLevel subLevel = activeSubLevels.get(subLevelId);
            if (subLevel == null || subLevel.isRemoved()) {
                continue;
            }

            int normalIndex = aIsSubLevel ? i + 3 : i + 6;
            normal.set(
                    collisions[normalIndex],
                    collisions[normalIndex + 1],
                    collisions[normalIndex + 2]);
            subLevel.logicalPose().transformNormal(normal);

            if (!Double.isFinite(normal.y) || Math.abs(normal.y) < MIN_GROUND_NORMAL_Y) {
                continue;
            }

            double force = collisions[i + 2];
            if (!(force > 0.0) || !Double.isFinite(force)) {
                continue;
            }

            GROUND_FORCE.merge(subLevel, force, Math::max);
            GROUND_CONTACTS.merge(subLevel, 1, Integer::sum);
        }
    }

    public static synchronized double groundForce(ServerSubLevel subLevel) {
        return subLevel == null ? 0.0 : GROUND_FORCE.getOrDefault(subLevel, 0.0);
    }

    public static synchronized int groundContacts(ServerSubLevel subLevel) {
        return subLevel == null ? 0 : GROUND_CONTACTS.getOrDefault(subLevel, 0);
    }

    public static synchronized boolean isHardGrounded(ServerSubLevel subLevel) {
        return groundForce(subLevel) > 0.0;
    }

    public static synchronized boolean shouldCutSuspension(ServerSubLevel subLevel) {
        return subLevel != null
                && CUT_SUSPENSION_WHEN_GROUNDED.getOrDefault(subLevel, false)
                && isHardGrounded(subLevel);
    }

    private static synchronized void setCutSuspensionWhenGrounded(ServerSubLevel subLevel, boolean enabled) {
        if (enabled) {
            CUT_SUSPENSION_WHEN_GROUNDED.put(subLevel, true);
        } else {
            CUT_SUSPENSION_WHEN_GROUNDED.remove(subLevel);
        }
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("antikythera")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("offroad_ground")
                                .then(Commands.literal("status")
                                        .executes(OffroadGroundContactDiagnostics::statusNearest))
                                .then(Commands.literal("cut_suspension")
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(OffroadGroundContactDiagnostics::setNearestCutSuspension)))
                                .then(Commands.literal("reset")
                                        .executes(OffroadGroundContactDiagnostics::resetNearest)))
        );
    }

    private static int setNearestCutSuspension(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        setCutSuspensionWhenGrounded(target, enabled);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad ground debug " + describe(target)
                        + ": cut_suspension=" + enabled),
                false);
        return 1;
    }

    private static int statusNearest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        double force = groundForce(target);
        int contacts = groundContacts(target);
        boolean grounded = isHardGrounded(target);
        boolean cut = CUT_SUSPENSION_WHEN_GROUNDED.getOrDefault(target, false);
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad ground debug " + describe(target)
                        + ": grounded=" + grounded
                        + "; max_contact_force=" + force
                        + "; reported_points=" + contacts
                        + "; cut_suspension=" + cut),
                false);
        return 1;
    }

    private static int resetNearest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerSubLevel target = nearestSubLevel(context.getSource());
        synchronized (OffroadGroundContactDiagnostics.class) {
            CUT_SUSPENSION_WHEN_GROUNDED.remove(target);
            GROUND_FORCE.remove(target);
            GROUND_CONTACTS.remove(target);
        }
        context.getSource().sendSuccess(
                () -> Component.literal("Offroad ground debug " + describe(target) + ": reset"),
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
