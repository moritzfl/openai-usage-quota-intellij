package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.mistral.MistralSessionCookieStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.mistral.MistralQuota
import java.awt.Color
import javax.swing.JComponent

internal class MistralSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    override val hideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    private val cookieField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Cookie header from admin.mistral.ai (must include ory_session_*)"
    }
    private val apiKeyField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Mistral API key for MCP search, images, and OCR"
    }
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()

    init {
        addToTop(panel {
            row { cell(hideFromPopupCheckBox) }
            row { cell(statusLabel) }
            row {
                label("Quota: paste Cookie from admin.mistral.ai → DevTools → Network → a billing request. Needs ory_session_* and preferably csrftoken.")
            }
            row("Session cookie:") { cell(cookieField).resizableColumn().align(AlignX.FILL) }
            row("API key:") { cell(apiKeyField).resizableColumn().align(AlignX.FILL) }
            row {
                button("Save") { saveNow() }
                button("Clear") { clearNow() }
            }
            separator()
        })
        addToCenter(BorderLayoutPanel().apply {
            addToTop(JBLabel("Last quota response:"))
            addToCenter(createResponseViewerPanel(responseViewer))
        })
    }

    override fun updateFields() {
        val cookie = MistralSessionCookieStore.getInstance().load(onLoaded = ::refreshAfterLoad)
        val apiKey = MistralApiKeyStore.getInstance().load(onLoaded = ::refreshAfterLoad)
        cookieField.text = if (cookie.isNullOrBlank()) "" else PLACEHOLDER
        apiKeyField.text = if (apiKey.isNullOrBlank()) "" else PLACEHOLDER
        updateStatus()
    }

    override fun updateStatus() {
        val cookieStore = MistralSessionCookieStore.getInstance()
        val cookie = cookieStore.load(onLoaded = ::refreshAfterLoad)
        val quota = QuotaUsageService.getInstance().getLastQuota(QuotaProviderType.MISTRAL) as? MistralQuota
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.MISTRAL)
        statusLabel.text = when {
            !cookieStore.isLoaded() -> formatStatusText("Loading credentials...", AuthStatusKind.PENDING)
            cookie.isNullOrBlank() -> formatStatusText("Mistral session cookie missing", AuthStatusKind.DISCONNECTED)
            error != null -> formatStatusText("Error: $error", AuthStatusKind.DISCONNECTED)
            quota != null -> formatStatusText("Connected", AuthStatusKind.CONNECTED)
            else -> formatStatusText("Session cookie stored securely", AuthStatusKind.CONNECTED)
        }
        statusLabel.foreground = statusLabelDefaultForeground ?: statusLabel.foreground
        statusLabel.isVisible = true
    }

    override fun updateResponseArea() {
        val raw = QuotaUsageService.getInstance().getLastResponseJson(QuotaProviderType.MISTRAL)
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.MISTRAL)
        responseViewer.text = when {
            error != null && !raw.isNullOrBlank() -> "Error: $error\n\n$raw"
            error != null -> "Error: $error"
            raw.isNullOrBlank() -> "No Mistral response yet."
            else -> raw
        }
        responseViewer.setCaretPosition(0)
    }

    private fun saveNow() {
        val currentCookie = MistralSessionCookieStore.getInstance().load()
        val currentKey = MistralApiKeyStore.getInstance().load()
        val cookie = String(cookieField.password).let { if (it == PLACEHOLDER) currentCookie else it }
        val apiKey = String(apiKeyField.password).let { if (it == PLACEHOLDER) currentKey else it }
        setPending("Saving credentials...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MistralSessionCookieStore.getInstance().save(cookie)
            MistralApiKeyStore.getInstance().save(apiKey)
            ApplicationManager.getApplication().invokeLater({
                cookieField.text = if (cookie.isNullOrBlank()) "" else PLACEHOLDER
                apiKeyField.text = if (apiKey.isNullOrBlank()) "" else PLACEHOLDER
                updateStatus()
                QuotaUsageService.getInstance().refreshAsync(QuotaProviderType.MISTRAL)
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun clearNow() {
        setPending("Clearing credentials...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MistralSessionCookieStore.getInstance().clear()
            MistralApiKeyStore.getInstance().clear()
            ApplicationManager.getApplication().invokeLater({
                cookieField.text = ""
                apiKeyField.text = ""
                updateStatus()
                QuotaUsageService.getInstance().clearUsageData(QuotaProviderType.MISTRAL)
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun refreshAfterLoad() {
        updateFields()
        updateResponseArea()
    }

    private fun setPending(text: String) {
        statusLabel.text = formatStatusText(text, AuthStatusKind.PENDING)
        statusLabel.isVisible = true
    }

    private fun formatStatusText(text: String, kind: AuthStatusKind): String {
        val color = when (kind) {
            AuthStatusKind.CONNECTED -> "#4CAF50"
            AuthStatusKind.DISCONNECTED -> "#F44336"
            AuthStatusKind.PENDING -> "#FFC107"
        }
        return "<html><span style=\"color: $color\">●</span>&nbsp;${QuotaUiUtil.escapeHtml(text)}</html>"
    }

    private companion object { const val PLACEHOLDER = "********" }
}
