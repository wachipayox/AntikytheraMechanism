package dev.antikytheramechanism.frame;

import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.Predicate;

/** Optional-mod bridge for Frame maintenance targeting. */
public final class FramePresentationToolHooks {
    private static volatile Predicate<ItemStack> maintenanceTool = stack -> false;

    private FramePresentationToolHooks() {
    }

    public static void registerMaintenanceTool(Predicate<ItemStack> predicate) {
        maintenanceTool = Objects.requireNonNull(predicate, "predicate");
    }

    public static boolean isMaintenanceTool(ItemStack stack) {
        return stack != null && !stack.isEmpty() && maintenanceTool.test(stack);
    }
}
