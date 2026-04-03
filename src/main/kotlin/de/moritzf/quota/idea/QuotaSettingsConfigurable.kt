package de.moritzf.quota.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.IconUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * Settings UI that manages authentication actions and shows latest quota payload data.
 */
class QuotaSettingsConfigurable : Configurable {
    private var panel: DialogPanel? = null
    private var accountIdField: JBTextField? = null
    private var emailField: JBTextField? = null
    private var responseArea: JBTextArea? = null
    private var loginHeaderLabel: JBLabel? = null
    private var statusLabel: JBLabel? = null
    private var displayModeComboBox: ComboBox<QuotaDisplayMode>? = null
    private var displayModePreview: DisplayModePreviewComponent? = null
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
        displayModePreview = DisplayModePreviewComponent()
        loginButton = createActionLink("Log In")
        cancelLoginButton = createActionLink("Cancel Login")
        logoutButton = createActionLink("Log Out")

        displayModeComboBox!!.addActionListener {
            updateDisplayModePreview()
        }

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

        val responseScroll = JBScrollPane(responseArea).apply {
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        panel = panel {
            row("Status bar display:") {
                cell(displayModeComboBox!!)
                cell(displayModePreview!!).gap(RightGap.SMALL)
            }

            separator()

            row {
                cell(loginHeaderLabel!!)
            }
            row {
                cell(loginButton!!).gap(RightGap.SMALL)
                cell(cancelLoginButton!!).gap(RightGap.SMALL)
                cell(logoutButton!!)
            }

            separator()

            row("Account ID:") {
                cell(accountIdField!!)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row("Email:") {
                cell(emailField!!)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }

            separator()

            row {
                label("Last quota response (JSON):")
            }
            row {
                scrollCell(responseScroll)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }.resizableRow()

            onApply {
                val selected = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onApply
                val state = QuotaSettingsState.getInstance()
                val current = state.displayMode()
                if (selected != current) {
                    state.setDisplayMode(selected)
                    ApplicationManager.getApplication().messageBus
                        .syncPublisher(QuotaSettingsListener.TOPIC)
                        .onSettingsChanged()
                }
            }

            onReset {
                displayModeComboBox?.selectedItem = QuotaSettingsState.getInstance().displayMode()
                updateDisplayModePreview()
                updateAuthUi()
                updateAccountFields()
                updateResponseArea()
            }

            onIsModified {
                val selected = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onIsModified false
                selected != QuotaSettingsState.getInstance().displayMode()
            }
        }.apply {
            preferredFocusedComponent = displayModeComboBox
        }

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
        return panel?.isModified() == true
    }

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
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
        displayModePreview = null
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

    private fun updateDisplayModePreview() {
        val mode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return
        displayModePreview?.updateMode(mode)
    }

    private fun createActionLink(text: String): ActionLink {
        return ActionLink(text).apply {
            autoHideOnDisable = false
        }
    }

    private class DisplayModePreviewComponent : BorderLayoutPanel() {
        private val previewIconLabel = JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }
        private val percentagePreview = QuotaPercentageIndicator().apply {
            update("42% • 2h 9m", 0.42, QuotaUsageColors.GREEN)
        }

        init {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(4)
            updateMode(QuotaDisplayMode.ICON_ONLY)
        }

        fun updateMode(mode: QuotaDisplayMode) {
            removeAll()
            when (mode) {
                QuotaDisplayMode.ICON_ONLY -> {
                    previewIconLabel.icon = QuotaIcons.STATUS
                    addToCenter(previewIconLabel)
                }

                QuotaDisplayMode.CAKE_DIAGRAM -> {
                    previewIconLabel.icon = scaledCakeIcon(previewIconLabel)
                    addToCenter(previewIconLabel)
                }

                QuotaDisplayMode.PERCENTAGE_BAR -> {
                    addToCenter(percentagePreview)
                }
            }
            revalidate()
            repaint()
        }

        private fun scaledCakeIcon(component: JComponent): Icon {
            val cakeIcon = QuotaIcons.CAKE_40
            val statusIcon = QuotaIcons.STATUS
            val targetWidth = statusIcon.iconWidth
            val targetHeight = statusIcon.iconHeight
            val iconWidth = cakeIcon.iconWidth
            val iconHeight = cakeIcon.iconHeight
            if (iconWidth <= 0 || iconHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
                return cakeIcon
            }
            if (iconWidth <= targetWidth && iconHeight <= targetHeight) {
                return cakeIcon
            }

            val widthScale = targetWidth / iconWidth.toFloat()
            val heightScale = targetHeight / iconHeight.toFloat()
            return IconUtil.scale(cakeIcon, component, minOf(widthScale, heightScale))
        }
    }
}
