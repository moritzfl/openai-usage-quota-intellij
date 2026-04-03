package de.moritzf.quota.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.FormBuilder
import java.awt.FlowLayout
import javax.swing.JComponent

/**
 * Settings UI that manages authentication actions and shows latest quota payload data.
 */
class QuotaSettingsConfigurable : Configurable {
    private var panel: JComponent? = null
    private var accountIdField: JBTextField? = null
    private var emailField: JBTextField? = null
    private var responseArea: JBTextArea? = null
    private var loginHeaderLabel: JBLabel? = null
    private var statusLabel: JBLabel? = null
    private var displayModeComboBox: ComboBox<QuotaDisplayMode>? = null
    private var loginButton: ActionLink? = null
    private var cancelLoginButton: ActionLink? = null
    private var logoutButton: ActionLink? = null
    private var connection: MessageBusConnection? = null

    override fun getDisplayName(): String = "OpenAI Usage Quota"

    override fun createComponent(): JComponent? {
        accountIdField = JBTextField().apply { isEditable = false }
        emailField = JBTextField().apply { isEditable = false }
        responseArea = JBTextArea().apply {
            isEditable = false
            lineWrap = false
        }
        loginHeaderLabel = JBLabel()
        statusLabel = JBLabel()
        displayModeComboBox = ComboBox(QuotaDisplayMode.entries.toTypedArray())
        loginButton = createActionLink("Log In")
        cancelLoginButton = createActionLink("Cancel Login")
        logoutButton = createActionLink("Log Out")

        loginButton!!.addActionListener {
            val authService = QuotaAuthService.getInstance()
            if (authService.isLoggedIn()) {
                updateAuthUi()
                return@addActionListener
            }

            loginButton!!.isEnabled = false
            statusLabel!!.text = "Opening browser..."
            authService.startLoginFlow { result ->
                val currentPanel = panel ?: return@startLoginFlow
                ApplicationManager.getApplication().invokeLater({
                    if (panel == null || statusLabel == null || loginButton == null || logoutButton == null) {
                        return@invokeLater
                    }

                    if (result.success) {
                        statusLabel!!.text = "Logged in"
                        QuotaUsageService.getInstance().refreshNowAsync()
                    } else {
                        val message = result.message ?: "Login failed"
                        statusLabel!!.text = "Login failed"
                        Messages.showErrorDialog(currentPanel, message, "OpenAI Login")
                    }
                    loginButton!!.isEnabled = true
                    updateAuthUi()
                    updateAccountFields()
                }, ModalityState.stateForComponent(currentPanel))
            }
            updateAuthUi()
        }

        cancelLoginButton!!.addActionListener {
            val aborted = QuotaAuthService.getInstance().abortLogin("Login canceled")
            statusLabel!!.text = if (aborted) "Login canceled" else "No login in progress"
            updateAuthUi()
        }

        logoutButton!!.addActionListener {
            QuotaAuthService.getInstance().clearCredentials()
            updateAuthUi()
            updateAccountFields()
            QuotaUsageService.getInstance().refreshNowAsync()
        }

        val authPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(loginButton)
            add(cancelLoginButton)
            add(logoutButton)
        }

        val responseScroll = JBScrollPane(responseArea).apply {
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Status bar display:", displayModeComboBox!!)
            .addSeparator()
            .addComponent(loginHeaderLabel!!)
            .addComponent(authPanel)
            .addSeparator()
            .addLabeledComponent("Account ID:", accountIdField!!)
            .addLabeledComponent("Email:", emailField!!)
            .addSeparator()
            .addLabeledComponent("Last quota response (JSON):", responseScroll)
            .panel

        connection = ApplicationManager.getApplication().messageBus.connect()
        connection!!.subscribe(QuotaUsageListener.TOPIC, QuotaUsageListener { _, _ ->
            val currentPanel = panel ?: return@QuotaUsageListener
            ApplicationManager.getApplication().invokeLater({
                if (panel == null || responseArea == null || accountIdField == null || emailField == null) {
                    return@invokeLater
                }
                updateAccountFields()
                updateResponseArea()
            }, ModalityState.stateForComponent(currentPanel))
        })

        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val selected = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return false
        val saved = QuotaSettingsState.getInstance().displayMode()
        return selected != saved
    }

    override fun apply() {
        val selected = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return
        val state = QuotaSettingsState.getInstance()
        val current = state.displayMode()
        if (selected != current) {
            state.setDisplayMode(selected)
            ApplicationManager.getApplication().messageBus
                .syncPublisher(QuotaSettingsListener.TOPIC)
                .onSettingsChanged()
        }
    }

    override fun reset() {
        displayModeComboBox?.selectedItem = QuotaSettingsState.getInstance().displayMode()
        updateAuthUi()
        updateAccountFields()
        updateResponseArea()
    }

    override fun disposeUIResources() {
        connection?.disconnect()
        connection = null
        panel = null
        accountIdField = null
        emailField = null
        responseArea = null
        loginHeaderLabel = null
        statusLabel = null
        displayModeComboBox = null
        loginButton = null
        cancelLoginButton = null
        logoutButton = null
    }

    private fun updateAuthUi() {
        val authService = QuotaAuthService.getInstance()
        val loggedIn = authService.isLoggedIn()
        val inProgress = authService.isLoginInProgress()
        val statusText = if (loggedIn) "Logged in" else "Not logged in"
        loginHeaderLabel?.text = "Login ($statusText)"
        statusLabel?.text = statusText
        loginButton?.isEnabled = !inProgress && !loggedIn
        cancelLoginButton?.isEnabled = inProgress
        logoutButton?.isEnabled = loggedIn
    }

    private fun updateResponseArea() {
        val area = responseArea ?: return
        val json = QuotaUsageService.getInstance().getLastResponseJson()
        area.text = if (json.isNullOrBlank()) "No quota response yet." else json
        area.caretPosition = 0
    }

    private fun updateAccountFields() {
        val accountField = accountIdField ?: return
        val emailField = emailField ?: return
        val authService = QuotaAuthService.getInstance()
        val accountId = authService.getAccountId()
        val email = if (authService.isLoggedIn()) QuotaUsageService.getInstance().getLastQuota()?.email else null
        accountField.text = accountId.orEmpty()
        emailField.text = email.orEmpty()
    }

    private fun createActionLink(text: String): ActionLink {
        return ActionLink(text).apply {
            autoHideOnDisable = false
        }
    }
}
