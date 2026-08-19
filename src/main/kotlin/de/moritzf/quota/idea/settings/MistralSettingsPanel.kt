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
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.mistral.MistralQuota
import java.awt.Color
import javax.swing.JComponent

internal class MistralSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    override val hideFromPopupCheckBox = com.intellij.ui.components.JBCheckBox("Hide from quota popup")
    private val apiKeyField = JBPasswordField().apply { columns = 40 }
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()

    init {
        addToTop(panel {
            row { cell(hideFromPopupCheckBox) }
            row { cell(statusLabel) }
            row("API key:") { cell(apiKeyField).resizableColumn().align(AlignX.FILL) }
            row {
                button("Save") { saveKeysNow() }
                button("Clear") { clearKeysNow() }
            }
            separator()
        })
        addToCenter(BorderLayoutPanel().apply {
            addToTop(JBLabel("Last quota response:"))
            addToCenter(createResponseViewerPanel(responseViewer))
        })
    }

    override fun updateFields() {
        val apiKey = MistralApiKeyStore.getInstance().load(onLoaded = ::refreshAfterKeyLoad)
        apiKeyField.text = if (apiKey.isNullOrBlank()) "" else PLACEHOLDER
        updateStatus()
    }

    override fun updateStatus() {
        val store = MistralApiKeyStore.getInstance()
        val apiKey = store.load(onLoaded = ::refreshAfterKeyLoad)
        val quota = QuotaUsageService.getInstance().getLastQuota(QuotaProviderType.MISTRAL) as? MistralQuota
        val error = QuotaUsageService.getInstance().getLastError(QuotaProviderType.MISTRAL)
        statusLabel.text = when {
            !store.isLoaded() -> formatStatusText("Loading API keys...", AuthStatusKind.PENDING)
            apiKey.isNullOrBlank() -> formatStatusText("Mistral API key missing", AuthStatusKind.DISCONNECTED)
            error != null -> formatStatusText("Error: $error", AuthStatusKind.DISCONNECTED)
            quota != null -> formatStatusText("Connected", AuthStatusKind.CONNECTED)
            else -> formatStatusText("API key stored securely", AuthStatusKind.CONNECTED)
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

    private fun saveKeysNow() {
        val current = MistralApiKeyStore.getInstance().load()
        val apiKey = String(apiKeyField.password).let { if (it == PLACEHOLDER) current else it }
        setPending("Saving API keys...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MistralApiKeyStore.getInstance().save(apiKey)
            ApplicationManager.getApplication().invokeLater({
                apiKeyField.text = if (apiKey.isNullOrBlank()) "" else PLACEHOLDER
                updateStatus()
                QuotaUsageService.getInstance().refreshAsync(QuotaProviderType.MISTRAL)
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun clearKeysNow() {
        setPending("Clearing API keys...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MistralApiKeyStore.getInstance().clear()
            ApplicationManager.getApplication().invokeLater({
                apiKeyField.text = ""
                updateStatus()
                QuotaUsageService.getInstance().clearUsageData(QuotaProviderType.MISTRAL)
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this))
        }
    }

    private fun refreshAfterKeyLoad() {
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
