package de.moritzf.quota.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.mistral.MistralSessionCookieStore
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.mistral.MistralQuotaClient
import java.awt.Color
import javax.swing.JComponent

internal class MistralSettingsPanel(
    private val modalityComponentProvider: () -> JComponent?,
    private val statusLabelDefaultForeground: Color? = null,
) : ProviderSettingsPanel() {
    private val sessionNameField = JBTextField().apply {
        columns = 40
        emptyText.text = "ory_session_…"
        toolTipText = "Cookie name from admin.mistral.ai, starts with ory_session_"
    }
    private val sessionValueField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Value of the ory_session_* cookie"
    }
    private val csrfField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Value of the csrftoken cookie from console.mistral.ai"
    }
    private val apiKeyField = JBPasswordField().apply {
        columns = 40
        toolTipText = "Mistral API key for MCP search, images, and OCR"
    }
    private val statusLabel = JBLabel().apply { isVisible = false }
    private val responseViewer = createResponseViewer()

    init {
        addToTop(panel {
            row { cell(popupVisibilityToggle) }
            row { cell(statusLabel) }
            row {
                text("Quota cookies from admin.mistral.ai / console.mistral.ai → DevTools → Application → Cookies. Copy each name/value.")
            }
            row("ory_session name:") { cell(sessionNameField).resizableColumn().align(AlignX.FILL) }
            row("ory_session value:") { cell(sessionValueField).resizableColumn().align(AlignX.FILL) }
            row("csrftoken:") { cell(csrfField).resizableColumn().align(AlignX.FILL) }
            row("API key:") { cell(apiKeyField).resizableColumn().align(AlignX.FILL) }
            row {
                button("Save") { saveNow() }
                button("Clear") { clearNow() }
            }
            separator()
        })
        addToCenter(createResponseSection(responseViewer, "Last quota response (session + API key)"))
    }

    override fun updateFields() {
        val stored = MistralQuotaClient.storedSessionFields(
            MistralSessionCookieStore.getInstance().load(onLoaded = ::refreshAfterLoad),
        )
        val apiKey = MistralApiKeyStore.getInstance().load(onLoaded = ::refreshAfterLoad)
        sessionNameField.text = stored?.sessionName.orEmpty()
        sessionValueField.text = if (stored?.sessionValue.isNullOrBlank()) "" else PLACEHOLDER
        csrfField.text = if (stored?.csrfToken.isNullOrBlank()) "" else PLACEHOLDER
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
        val current = MistralQuotaClient.storedSessionFields(MistralSessionCookieStore.getInstance().load())
        val currentKey = MistralApiKeyStore.getInstance().load()
        val sessionName = sessionNameField.text
        val sessionValue = String(sessionValueField.password).let { value ->
            if (value == PLACEHOLDER) current?.sessionValue.orEmpty() else value
        }
        val csrf = String(csrfField.password).let { value ->
            if (value == PLACEHOLDER) current?.csrfToken.orEmpty() else value
        }
        val apiKey = String(apiKeyField.password).let { if (it == PLACEHOLDER) currentKey else it }
        setPending("Saving credentials...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val encoded = runCatching {
                MistralQuotaClient.encodeStoredSession(sessionName, sessionValue, csrf)
            }.getOrElse { error ->
                ApplicationManager.getApplication().invokeLater({
                    statusLabel.text = formatStatusText(error.message ?: "Invalid Mistral cookies", AuthStatusKind.DISCONNECTED)
                    statusLabel.isVisible = true
                }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@MistralSettingsPanel))
                return@executeOnPooledThread
            }
            MistralSessionCookieStore.getInstance().save(encoded)
            MistralApiKeyStore.getInstance().save(apiKey)
            ApplicationManager.getApplication().invokeLater({
                updateFields()
                QuotaUsageService.getInstance().refreshAsync(QuotaProviderType.MISTRAL)
            }, ModalityState.stateForComponent(modalityComponentProvider() ?: this@MistralSettingsPanel))
        }
    }

    private fun clearNow() {
        setPending("Clearing credentials...")
        ApplicationManager.getApplication().executeOnPooledThread {
            MistralSessionCookieStore.getInstance().clear()
            MistralApiKeyStore.getInstance().clear()
            ApplicationManager.getApplication().invokeLater({
                sessionNameField.text = ""
                sessionValueField.text = ""
                csrfField.text = ""
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
