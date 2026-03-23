package de.moritzf.quota.idea;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class QuotaUiUtilTest {
    @Test
    void formatResetCompactReturnsNullForNullReset() {
        assertNull(QuotaUiUtil.formatResetCompact(null));
    }

    @Test
    void formatResetCompactReturnsRelativeValueForFutureReset() {
        String formatted = QuotaUiUtil.formatResetCompact(Instant.now().plusSeconds(120));

        assertNotNull(formatted);
        assertFalse(formatted.startsWith("in "));
    }

    @Test
    void formatResetCompactReturnsNullForPastReset() {
        assertNull(QuotaUiUtil.formatResetCompact(Instant.now().minusSeconds(60)));
    }
}
