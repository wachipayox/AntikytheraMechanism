package dev.antikytheramechanism.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Structural verification for moved/restored cells; intentionally ignores mutable runtime BE NBT. */
public final class BlockSnapshotVerifier {
    private BlockSnapshotVerifier() {
    }

    public static boolean matches(
            ServerLevel level,
            BlockPos position,
            BlockState expectedState,
            CompoundTag expectedBlockEntityData) {
        if (!expectedState.equals(level.getBlockState(position))) {
            return false;
        }

        BlockEntity actual = level.getBlockEntity(position);
        if (expectedBlockEntityData == null) {
            return actual == null;
        }
        if (actual == null) {
            return false;
        }

        ResourceLocation expectedType = expectedBlockEntityType(expectedBlockEntityData);
        ResourceLocation actualType = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(actual.getType());
        return expectedType == null || expectedType.equals(actualType);
    }

    private static ResourceLocation expectedBlockEntityType(CompoundTag tag) {
        if (!tag.contains("id")) {
            return null;
        }
        return ResourceLocation.tryParse(tag.getString("id"));
    }
}
