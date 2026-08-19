package dev.antikytheramechanism.compat.create;

import com.simibubi.create.AllBlocks;
import dev.antikytheramechanism.assembly.FrameSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;

/** Exact finite Create 6.0.10 official casing set accepted by the Frame wrench interaction. */
public final class CreateFrameSkinItems {
    private CreateFrameSkinItems() {
    }

    public static Optional<FrameSkin> skinFrom(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        if (stack.is(Items.COPPER_INGOT) || stack.is(Items.COPPER_BLOCK)) {
            return Optional.of(FrameSkin.COPPER);
        }
        if (AllBlocks.ANDESITE_CASING.isIn(stack)) return Optional.of(FrameSkin.ANDESITE_CASING);
        if (AllBlocks.BRASS_CASING.isIn(stack)) return Optional.of(FrameSkin.BRASS_CASING);
        if (AllBlocks.COPPER_CASING.isIn(stack)) return Optional.of(FrameSkin.COPPER_CASING);
        if (AllBlocks.SHADOW_STEEL_CASING.isIn(stack)) return Optional.of(FrameSkin.SHADOW_STEEL_CASING);
        if (AllBlocks.REFINED_RADIANCE_CASING.isIn(stack)) return Optional.of(FrameSkin.REFINED_RADIANCE_CASING);
        if (AllBlocks.RAILWAY_CASING.isIn(stack)) return Optional.of(FrameSkin.RAILWAY_CASING);
        return Optional.empty();
    }
}
