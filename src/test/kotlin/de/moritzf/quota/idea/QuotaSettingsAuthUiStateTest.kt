package de.moritzf.quota.idea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaSettingsAuthUiStateTest {
    @Test
    fun createPreservesExplicitStatusMessageDuringLogin() {
        val statusMessage = AuthStatusMessage("Opening browser...")

        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = false,
            inProgress = true,
            statusMessage = statusMessage,
        )

        assertEquals("Login (In progress)", uiState.headerText)
        assertEquals(statusMessage, uiState.visibleStatusMessage)
        assertFalse(uiState.loginEnabled)
        assertTrue(uiState.cancelEnabled)
        assertFalse(uiState.logoutEnabled)
    }

    @Test
    fun createProvidesGenericInProgressHintWhenNoStatusMessageExists() {
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = false,
            inProgress = true,
            statusMessage = null,
        )

        assertEquals("Login (In progress)", uiState.headerText)
        assertEquals(AuthStatusMessage("Complete the login in your browser."), uiState.visibleStatusMessage)
    }

    @Test
    fun createHidesStatusMessageWhenIdleWithoutTransientFeedback() {
        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = false,
            inProgress = false,
            statusMessage = null,
        )

        assertEquals("Login (Not logged in)", uiState.headerText)
        assertNull(uiState.visibleStatusMessage)
        assertTrue(uiState.loginEnabled)
        assertFalse(uiState.cancelEnabled)
        assertFalse(uiState.logoutEnabled)
    }

    @Test
    fun createKeepsSuccessFeedbackVisibleAfterLoginCompletes() {
        val statusMessage = AuthStatusMessage("Logged in")

        val uiState = QuotaSettingsAuthUiState.create(
            loggedIn = true,
            inProgress = false,
            statusMessage = statusMessage,
        )

        assertEquals("Login (Logged in)", uiState.headerText)
        assertEquals(statusMessage, uiState.visibleStatusMessage)
        assertFalse(uiState.loginEnabled)
        assertFalse(uiState.cancelEnabled)
        assertTrue(uiState.logoutEnabled)
    }
}
