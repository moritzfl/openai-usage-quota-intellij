package de.moritzf.quota.idea.settings

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaUsageListener
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorLocation
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource
import de.moritzf.quota.idea.ui.indicator.QuotaPercentageIndicator
import de.moritzf.quota.idea.ui.indicator.QuotaUsageColors
import de.moritzf.quota.idea.ui.indicator.scaleIconToQuotaStatusSize
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

/**
 * Settings UI that manages authentication actions and shows latest quota payload data.
 */
class QuotaSettingsConfigurable : Configurable {
    private var rootComponent: JComponent? = null
    private var panel: DialogPanel? = null
    private var connection: MessageBusConnection? = null
    private var updatingDisplayModeChoices: Boolean = false

    private lateinit var openAiPanel: OpenAiSettingsPanel
    private lateinit var openCodePanel: OpenCodeSettingsPanel

    private var locationComboBox: ComboBox<QuotaIndicatorLocation>? = null
    private var displayModeComboBox: ComboBox<QuotaDisplayMode>? = null
    private var indicatorSourceComboBox: ComboBox<QuotaIndicatorSource>? = null
    private var displayModePreview: DisplayModePreviewComponent? = null

    override fun getDisplayName(): String = "LLM Subscription Usage"

    override fun createComponent(): JComponent? {
        val statusLabelDefaultForeground = UIManager.getColor("Label.foreground")

        openAiPanel = OpenAiSettingsPanel()
        openCodePanel = OpenCodeSettingsPanel(
            modalityComponentProvider = { panel ?: rootComponent },
            statusLabelDefaultForeground = statusLabelDefaultForeground,
        )

        locationComboBox = createIndicatorComboBox(QuotaIndicatorLocation.entries.toTypedArray())
        displayModeComboBox = createIndicatorComboBox(QuotaDisplayMode.entries.toTypedArray())
        indicatorSourceComboBox = createIndicatorComboBox(QuotaIndicatorSource.entries.toTypedArray())
        displayModePreview = DisplayModePreviewComponent()

        locationComboBox!!.addActionListener {
            updateDisplayModeChoices()
            updateDisplayModePreview()
        }

        displayModeComboBox!!.addActionListener {
            if (updatingDisplayModeChoices) {
                return@addActionListener
            }
            updateDisplayModePreview()
        }

        panel = buildIndicatorConfigPanel()

        val serviceTabs = JBTabbedPane().apply {
            addTab("OpenAI Codex", openAiPanel)
            addTab("OpenCode Go", openCodePanel)
        }

        rootComponent = BorderLayoutPanel().apply {
            isOpaque = false
            addToTop(panel!!)
            addToCenter(serviceTabs)
        }

        connection = ApplicationManager.getApplication().messageBus.connect()
        connection!!.subscribe(QuotaUsageListener.TOPIC, object : QuotaUsageListener {
            override fun onQuotaUpdated(quota: OpenAiCodexQuota?, error: String?) {
                val currentPanel = rootComponent ?: panel ?: return@onQuotaUpdated
                ApplicationManager.getApplication().invokeLater({
                    openAiPanel.updateAccountFields()
                    openAiPanel.updateResponseArea()
                    openCodePanel.updateOpenCodeStatus()
                }, ModalityState.stateForComponent(currentPanel))
            }

            override fun onOpenCodeQuotaUpdated(quota: OpenCodeQuota?, error: String?) {
                val currentPanel = rootComponent ?: panel ?: return@onOpenCodeQuotaUpdated
                ApplicationManager.getApplication().invokeLater({
                    openCodePanel.updateOpenCodeResponseArea()
                    openCodePanel.updateOpenCodeStatus()
                }, ModalityState.stateForComponent(currentPanel))
            }
        })

        reset()
        return rootComponent
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
        rootComponent = null
        panel = null
        locationComboBox = null
        displayModeComboBox = null
        indicatorSourceComboBox = null
        displayModePreview = null
        updatingDisplayModeChoices = false
    }

    private fun updateDisplayModeChoices(preferredMode: QuotaDisplayMode? = null) {
        val combo = displayModeComboBox ?: return
        val location = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return
        val selectedMode = preferredMode ?: combo.selectedItem as? QuotaDisplayMode ?: QuotaSettingsState.getInstance().displayMode()
        val sanitizedMode = QuotaDisplayMode.sanitizeFor(location, selectedMode)
        updatingDisplayModeChoices = true
        try {
            combo.removeAllItems()
            QuotaDisplayMode.supportedFor(location).forEach(combo::addItem)
            combo.selectedItem = sanitizedMode
        } finally {
            updatingDisplayModeChoices = false
        }
    }

    private fun updateDisplayModePreview() {
        val mode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return
        displayModePreview?.updateMode(mode)
    }

    private fun <T> createIndicatorComboBox(items: Array<T>): ComboBox<T> {
        return ComboBox(items).apply {
            preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
            minimumSize = preferredSize
        }
    }

    private fun buildIndicatorConfigPanel(): DialogPanel {
        return panel {
            row("Indicator location:") {
                cell(locationComboBox!!)
            }

            row("Indicator display:") {
                cell(displayModeComboBox!!)
                cell(displayModePreview!!).gap(RightGap.SMALL)
            }

            row("Indicator quota source:") {
                cell(indicatorSourceComboBox!!)
            }

            onApply {
                val selectedLocation = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onApply
                val selectedDisplayMode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onApply
                val selectedSource = indicatorSourceComboBox?.selectedItem as? QuotaIndicatorSource ?: return@onApply
                val sanitizedDisplayMode = QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode)
                val state = QuotaSettingsState.getInstance()
                val locationChanged = selectedLocation != state.location()
                val displayModeChanged = sanitizedDisplayMode != state.displayMode()
                val sourceChanged = selectedSource != state.source()
                val openAiPopupVisibilityChanged = openAiPanel.openAiHideFromPopupCheckBox.isSelected != state.hideOpenAiFromQuotaPopup
                val openCodePopupVisibilityChanged = openCodePanel.openCodeHideFromPopupCheckBox.isSelected != state.hideOpenCodeFromQuotaPopup
                if (locationChanged) {
                    state.setLocation(selectedLocation)
                }
                if (displayModeChanged) {
                    state.setDisplayMode(sanitizedDisplayMode)
                }
                if (sourceChanged) {
                    state.setSource(selectedSource)
                }
                state.hideOpenAiFromQuotaPopup = openAiPanel.openAiHideFromPopupCheckBox.isSelected
                state.hideOpenCodeFromQuotaPopup = openCodePanel.openCodeHideFromPopupCheckBox.isSelected
                if (locationChanged || displayModeChanged || sourceChanged || openAiPopupVisibilityChanged || openCodePopupVisibilityChanged) {
                    ApplicationManager.getApplication().messageBus
                        .syncPublisher(QuotaSettingsListener.TOPIC)
                        .onSettingsChanged()
                    ActivityTracker.getInstance().inc()
                }
            }

            onReset {
                locationComboBox?.selectedItem = QuotaSettingsState.getInstance().location()
                updateDisplayModeChoices(QuotaSettingsState.getInstance().displayMode())
                updateDisplayModePreview()
                indicatorSourceComboBox?.selectedItem = QuotaSettingsState.getInstance().source()
                openAiPanel.openAiHideFromPopupCheckBox.isSelected = QuotaSettingsState.getInstance().hideOpenAiFromQuotaPopup
                openCodePanel.openCodeHideFromPopupCheckBox.isSelected = QuotaSettingsState.getInstance().hideOpenCodeFromQuotaPopup
                openAiPanel.updateAuthUi()
                openAiPanel.updateAccountFields()
                openAiPanel.updateResponseArea()
                openCodePanel.updateOpenCodeResponseArea()
                openCodePanel.updateOpenCodeFields()
            }

            onIsModified {
                val selectedLocation = locationComboBox?.selectedItem as? QuotaIndicatorLocation ?: return@onIsModified false
                val selectedDisplayMode = displayModeComboBox?.selectedItem as? QuotaDisplayMode ?: return@onIsModified false
                val selectedSource = indicatorSourceComboBox?.selectedItem as? QuotaIndicatorSource ?: return@onIsModified false
                val state = QuotaSettingsState.getInstance()
                selectedLocation != state.location() ||
                    QuotaDisplayMode.sanitizeFor(selectedLocation, selectedDisplayMode) != state.displayMode() ||
                    selectedSource != state.source() ||
                    openAiPanel.openAiHideFromPopupCheckBox.isSelected != state.hideOpenAiFromQuotaPopup ||
                    openCodePanel.openCodeHideFromPopupCheckBox.isSelected != state.hideOpenCodeFromQuotaPopup
            }
        }.apply {
            preferredFocusedComponent = locationComboBox
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
            return scaleIconToQuotaStatusSize(QuotaIcons.CAKE_40, component)
        }
    }
}
