package de.moritzf.quota.idea.ui.popup

import kotlin.test.Test
import kotlin.test.assertEquals

class PopupUiUtilsTest {
    @Test
    fun toDisplayLabelStripsSelfServeAndTitleCasesWords() {
        assertEquals("Business Prolite", "self_serve_business_prolite".toDisplayLabel())
        assertEquals("Business Usage Based", "self_serve_business_usage_based".toDisplayLabel())
        assertEquals("Business Prolite", "SELF_SERVE_BUSINESS_PROLITE".toDisplayLabel())
        assertEquals("Plus", "plus".toDisplayLabel())
        assertEquals("Prolite", "prolite".toDisplayLabel())
        assertEquals("Team", "team".toDisplayLabel())
        assertEquals("", "self_serve".toDisplayLabel())
        assertEquals("Business Prolite", "  self_serve_business_prolite  ".toDisplayLabel())
    }
}
