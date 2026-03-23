package de.moritzf.quota.idea;

import java.util.Locale;

/**
 * Rendering mode for the status bar quota widget.
 */
public enum QuotaDisplayMode {
    ICON_ONLY("Icon only"),
    PERCENTAGE_BAR("Percentage bar"),
    CAKE_DIAGRAM("Cake diagram");

    private final String displayName;

    QuotaDisplayMode(String displayName) {
        this.displayName = displayName;
    }

    public static QuotaDisplayMode fromStorageValue(String value) {
        if (value == null || value.isBlank()) {
            return ICON_ONLY;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (QuotaDisplayMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return ICON_ONLY;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
