package de.moritzf.quota.idea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuotaDisplayModeTest {
    @Test
    void fromStorageValueFallsBackToIconOnly() {
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue(null));
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue(""));
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue("unknown"));
    }

    @Test
    void fromStorageValueParsesAllModesCaseInsensitively() {
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue("ICON_ONLY"));
        assertEquals(QuotaDisplayMode.PERCENTAGE_BAR, QuotaDisplayMode.fromStorageValue("percentage_bar"));
        assertEquals(QuotaDisplayMode.CAKE_DIAGRAM, QuotaDisplayMode.fromStorageValue("cake_diagram"));
    }
}
