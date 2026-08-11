package dev.antikytheramechanism.api;

import dev.antikytheramechanism.AntikytheraMechanism;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** Public datapack extension points for the miniaturization policy. */
public final class AntikytheraBlockTags {
    /** Blocks supported by a datapack, unless a higher-priority rule denies them. */
    public static final TagKey<Block> MINIATURIZABLE = TagKey.create(
            Registries.BLOCK,
            AntikytheraMechanism.id("miniaturizable"));

    /** Blocks denied by a datapack. This wins over {@link #MINIATURIZABLE}. */
    public static final TagKey<Block> NON_MINIATURIZABLE = TagKey.create(
            Registries.BLOCK,
            AntikytheraMechanism.id("non_miniaturizable"));

    private AntikytheraBlockTags() {
    }
}
