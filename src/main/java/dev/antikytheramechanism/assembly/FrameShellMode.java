package dev.antikytheramechanism.assembly;

import net.minecraft.util.StringRepresentable;

public enum FrameShellMode implements StringRepresentable {
    NORMAL("normal"),
    GLASS("glass"),
    HIDDEN("hidden");

    private final String serializedName;

    FrameShellMode(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    public FrameShellMode nextFromWrench() {
        return switch (this) {
            case NORMAL -> GLASS;
            case GLASS -> HIDDEN;
            case HIDDEN -> NORMAL;
        };
    }

    public static FrameShellMode fromSerializedName(String value) {
        if (value != null) {
            for (FrameShellMode mode : values()) {
                if (mode.serializedName.equals(value)) {
                    return mode;
                }
            }
        }
        return NORMAL;
    }
}
