package de.moritzf.quota.idea.ui.popup

import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.ui.indicator.clampPercent
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.mistral.MistralUsageWindow
import de.moritzf.quota.shared.ProviderQuota
import kotlin.math.roundToInt

private const val MISTRAL_LABEL = "Mistral"

internal class MistralPopupSection : ProviderPopupSection() {
    private val separator = createSeparatedBlock()
    private val errorLabel = createWarningLabel("").apply { border = JBUI.Borders.emptyTop(1) }
    private val titleLabel = createSectionTitleLabel(MISTRAL_LABEL, QuotaIcons.MISTRAL).apply { border = JBUI.Borders.emptyTop(0) }
    private val tokenBlock = WindowBlockPanel(3)
    private val requestBlock = WindowBlockPanel(3)

    init {
        isOpaque = false
        add(separator)
        add(errorLabel)
        add(titleLabel)
        add(tokenBlock)
        add(requestBlock)
        hideAll()
    }

    override fun update(quota: ProviderQuota?, error: String?, visible: Boolean) {
        updateContent(quota as? MistralQuota, error, visible)
    }

    private fun updateContent(quota: MistralQuota?, error: String?, visible: Boolean) {
        isVisible = visible
        if (!visible) return

        when {
            error != null -> {
                errorLabel.isVisible = true
                errorLabel.text = "Mistral error: $error"
                hideContent()
            }
            quota == null -> {
                hideAll()
                titleLabel.isVisible = true
                titleLabel.text = MISTRAL_LABEL
                tokenBlock.showLoading("Tokens")
                requestBlock.showLoading("Requests")
            }
            else -> {
                val limitReached = (quota.tokenUsage?.usagePercent ?: 0.0) >= 100.0 ||
                    (quota.requestUsage?.usagePercent ?: 0.0) >= 100.0
                errorLabel.isVisible = limitReached
                if (limitReached) {
                    errorLabel.text = "Mistral limit reached"
                }
                titleLabel.isVisible = true
                titleLabel.text = quota.organization.ifBlank { MISTRAL_LABEL }
                quota.tokenUsage?.let { tokenBlock.updateMistral(it, "Tokens") } ?: tokenBlock.clear()
                quota.requestUsage?.let { requestBlock.updateMistral(it, "Requests") } ?: requestBlock.clear()
            }
        }
    }

    private fun hideAll() {
        errorLabel.isVisible = false
        hideContent()
    }

    private fun hideContent() {
        titleLabel.isVisible = false
        tokenBlock.isVisible = false
        requestBlock.isVisible = false
    }

    private fun WindowBlockPanel.updateMistral(window: MistralUsageWindow, label: String) {
        val percent = clampPercent(window.usagePercent.roundToInt())
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) info += " - $resetText"
        update(describeDurationLimitLabel(window.periodDuration, label), info, percent)
    }
}
