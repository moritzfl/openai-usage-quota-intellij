package de.moritzf.quota.idea.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopupVisibilityToggleTest {
    @Test
    fun startsShownInQuotaPopup() {
        val toggle = PopupVisibilityToggle()

        assertFalse(toggle.isHidden)
        assertEquals("Shown in quota popup (click to hide)", toggle.text)
    }

    @Test
    fun clickHidesAndShowsAgain() {
        val toggle = PopupVisibilityToggle()

        toggle.isHidden = true
        assertTrue(toggle.isHidden)
        assertEquals("Hidden from quota popup (click to show)", toggle.text)

        toggle.isHidden = false
        assertFalse(toggle.isHidden)
        assertEquals("Shown in quota popup (click to hide)", toggle.text)
    }
}
