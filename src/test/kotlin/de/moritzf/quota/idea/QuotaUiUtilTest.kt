package de.moritzf.quota.idea

import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class QuotaUiUtilTest {
    @Test
    fun formatResetCompactReturnsNullForNullReset() {
        assertNull(QuotaUiUtil.formatResetCompact(null))
    }

    @Test
    fun formatResetCompactReturnsRelativeValueForFutureReset() {
        val formatted = QuotaUiUtil.formatResetCompact(Clock.System.now().plus(120.seconds))

        assertNotNull(formatted)
        assertFalse(formatted.startsWith("in "))
    }

    @Test
    fun formatResetCompactReturnsNullForPastReset() {
        assertNull(QuotaUiUtil.formatResetCompact(Clock.System.now().minus(60.seconds)))
    }
}
