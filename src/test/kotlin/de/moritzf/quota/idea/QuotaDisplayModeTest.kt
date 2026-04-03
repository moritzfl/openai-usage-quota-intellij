package de.moritzf.quota.idea

import kotlin.test.Test
import kotlin.test.assertEquals

class QuotaDisplayModeTest {
    @Test
    fun fromStorageValueFallsBackToIconOnly() {
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue(null))
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue(""))
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue("unknown"))
    }

    @Test
    fun fromStorageValueParsesAllModesCaseInsensitively() {
        assertEquals(QuotaDisplayMode.ICON_ONLY, QuotaDisplayMode.fromStorageValue("ICON_ONLY"))
        assertEquals(QuotaDisplayMode.PERCENTAGE_BAR, QuotaDisplayMode.fromStorageValue("percentage_bar"))
        assertEquals(QuotaDisplayMode.CAKE_DIAGRAM, QuotaDisplayMode.fromStorageValue("cake_diagram"))
    }
}
