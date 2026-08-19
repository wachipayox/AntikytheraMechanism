package dev.antikytheramechanism.assembly;

import net.minecraft.resources.ResourceLocation;

public enum FrameSkin {
    COPPER("minecraft:copper_block"),
    ANDESITE_CASING("create:andesite_casing"),
    BRASS_CASING("create:brass_casing"),
    COPPER_CASING("create:copper_casing"),
    SHADOW_STEEL_CASING("create:shadow_steel_casing"),
    REFINED_RADIANCE_CASING("create:refined_radiance_casing"),
    RAILWAY_CASING("create:railway_casing");

    private final ResourceLocation blockId;

    FrameSkin(String blockId) {
        this.blockId = ResourceLocation.parse(blockId);
    }

    public ResourceLocation blockId() {
        return blockId;
    }

    public String serializedName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static FrameSkin fromSerializedName(String value) {
        if (value != null) {
            for (FrameSkin skin : values()) {
                if (skin.serializedName().equals(value)) {
                    return skin;
                }
            }
        }
        return COPPER;
    }
}
