package dev.antikytheramechanism.registry;

import dev.antikytheramechanism.AntikytheraMechanism;
import dev.antikytheramechanism.api.AntikytheraBlockTags;
import dev.antikytheramechanism.api.MiniaturizationStatus;
import dev.antikytheramechanism.config.AntikytheraCommonConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MiniaturizableRegistry {
    private static final Set<ResourceLocation> HARD_DENY_IDS = Set.of(
            AntikytheraMechanism.id("mechanism_frame"),
            AntikytheraMechanism.id("assembly_anchor"),
            AntikytheraMechanism.id("internal_shaft_port"),
            AntikytheraMechanism.id("internal_small_cog_port"),
            AntikytheraMechanism.id("internal_large_cog_port"),
            vanilla("air"),
            vanilla("cave_air"),
            vanilla("void_air"),
            vanilla("nether_portal"),
            vanilla("end_portal"),
            vanilla("end_portal_frame"),
            vanilla("end_gateway"),
            vanilla("moving_piston"),
            vanilla("piston_head"),
            vanilla("fire"),
            vanilla("soul_fire"),
            vanilla("structure_block"),
            vanilla("structure_void"),
            vanilla("jigsaw"),
            vanilla("barrier"),
            vanilla("light"),
            vanilla("command_block"),
            vanilla("chain_command_block"),
            vanilla("repeating_command_block"),
            vanilla("test_block"),
            vanilla("test_instance_block"));

    private static final Set<Block> API_ALLOW_BLOCKS = ConcurrentHashMap.newKeySet();
    private static final Set<TagKey<Block>> API_ALLOW_TAGS = ConcurrentHashMap.newKeySet();
    private static final Set<Block> API_DENY_BLOCKS = ConcurrentHashMap.newKeySet();
    private static final Set<TagKey<Block>> API_DENY_TAGS = ConcurrentHashMap.newKeySet();

    private MiniaturizableRegistry() {
    }

    public static void registerAllowed(Block block) {
        API_ALLOW_BLOCKS.add(Objects.requireNonNull(block, "block"));
    }

    public static void registerAllowed(TagKey<Block> tag) {
        API_ALLOW_TAGS.add(Objects.requireNonNull(tag, "tag"));
    }

    public static void registerDenied(Block block) {
        API_DENY_BLOCKS.add(Objects.requireNonNull(block, "block"));
    }

    public static void registerDenied(TagKey<Block> tag) {
        API_DENY_TAGS.add(Objects.requireNonNull(tag, "tag"));
    }

    /**
     * Resolves the policy in this exact order: immutable hard deny, config deny, config allow,
     * Java API deny/allow, datapack deny/allow, then deny by default.
     */
    public static MiniaturizationStatus status(Block block) {
        Objects.requireNonNull(block, "block");
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);

        if (isHardDenied(id)) {
            return MiniaturizationStatus.DENIED;
        }
        if (matchesEntries(block, id, AntikytheraCommonConfig.deniedEntries())) {
            return MiniaturizationStatus.DENIED;
        }
        if (matchesEntries(block, id, AntikytheraCommonConfig.allowedEntries())) {
            return MiniaturizationStatus.USER_ALLOWED;
        }
        if (API_DENY_BLOCKS.contains(block) || matchesAnyTag(block, API_DENY_TAGS)) {
            return MiniaturizationStatus.DENIED;
        }
        if (API_ALLOW_BLOCKS.contains(block) || matchesAnyTag(block, API_ALLOW_TAGS)) {
            return MiniaturizationStatus.SUPPORTED;
        }
        if (block.defaultBlockState().is(AntikytheraBlockTags.NON_MINIATURIZABLE)) {
            return MiniaturizationStatus.DENIED;
        }
        if (block.defaultBlockState().is(AntikytheraBlockTags.MINIATURIZABLE)) {
            return MiniaturizationStatus.SUPPORTED;
        }
        return MiniaturizationStatus.DENIED;
    }

    public static boolean isAllowed(Block block) {
        return status(block) != MiniaturizationStatus.DENIED;
    }

    private static boolean isHardDenied(ResourceLocation id) {
        return id == null || HARD_DENY_IDS.contains(id);
    }

    private static boolean matchesEntries(Block block, ResourceLocation blockId, List<? extends String> entries) {
        for (String rawEntry : entries) {
            String entry = rawEntry.trim();
            if (entry.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(entry.substring(1));
                if (tagId != null && block.defaultBlockState().is(TagKey.create(Registries.BLOCK, tagId))) {
                    return true;
                }
            } else {
                ResourceLocation configuredId = ResourceLocation.tryParse(entry);
                if (configuredId != null && configuredId.equals(blockId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesAnyTag(Block block, Set<TagKey<Block>> tags) {
        for (TagKey<Block> tag : tags) {
            if (block.defaultBlockState().is(tag)) {
                return true;
            }
        }
        return false;
    }

    private static ResourceLocation vanilla(String path) {
        return ResourceLocation.fromNamespaceAndPath(ResourceLocation.DEFAULT_NAMESPACE, path);
    }
}
