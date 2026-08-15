package dev.antikytheramechanism.config;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** NeoForge COMMON configuration for explicit user policy overrides. */
public final class AntikytheraCommonConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<List<? extends String>> DENIED_ENTRIES;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_ENTRIES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("miniaturization");

        DENIED_ENTRIES = builder
                .comment(
                        "Blocks denied in Antikythera mini worlds (Mechanism Frames and detached mini-physics bodies).",
                        "Each entry must be a block ID (minecraft:tnt) or block tag (#example:unsafe).",
                        "Config deny has priority over config allow and all Java/datapack registrations.",
                        "Immutable internal/portal hard denies cannot be overridden here.")
                .defineListAllowEmpty("deny", List.of(), () -> "minecraft:tnt", AntikytheraCommonConfig::isPolicyEntry);

        ALLOWED_ENTRIES = builder
                .comment(
                        "Blocks explicitly allowed in Antikythera mini worlds.",
                        "Each entry must be a block ID (namespace:block) or block tag (#namespace:safe).",
                        "A match reports USER_ALLOWED. Config deny and immutable hard denies still win.")
                .defineListAllowEmpty("allow", List.of(), () -> "minecraft:stone", AntikytheraCommonConfig::isPolicyEntry);

        builder.pop();
        SPEC = builder.build();
    }

    private AntikytheraCommonConfig() {
    }

    public static List<? extends String> deniedEntries() {
        return SPEC.isLoaded() ? DENIED_ENTRIES.get() : List.of();
    }

    public static List<? extends String> allowedEntries() {
        return SPEC.isLoaded() ? ALLOWED_ENTRIES.get() : List.of();
    }

    private static boolean isPolicyEntry(Object candidate) {
        if (!(candidate instanceof String value)) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        return !normalized.isEmpty() && ResourceLocation.tryParse(normalized) != null;
    }
}
