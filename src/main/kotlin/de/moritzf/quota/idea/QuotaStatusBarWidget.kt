package de.moritzf.quota.idea

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.IconUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.UsageWindow
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JProgressBar
import javax.swing.JSeparator
import javax.swing.SwingConstants
import kotlin.math.roundToInt

/**
 * Status bar widget that displays the current quota state and shows a detailed popup.
 */
class QuotaStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val connection: MessageBusConnection
    private val widgetComponent = UsageWidgetComponent()
    @Volatile private var quota: OpenAiCodexQuota?
    @Volatile private var error: String?
    private var statusBar: StatusBar? = null

    init {
        val usageService = QuotaUsageService.getInstance()
        quota = usageService.getLastQuota()
        error = usageService.getLastError()
        connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(QuotaUsageListener.TOPIC, QuotaUsageListener { updatedQuota, updatedError ->
            quota = updatedQuota
            error = updatedError
            updateWidget()
        })
        connection.subscribe(QuotaSettingsListener.TOPIC, QuotaSettingsListener { updateWidget() })
        widgetComponent.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                showPopup(widgetComponent)
            }
        })
    }

    override fun ID(): String = QuotaStatusBarWidgetFactory.ID

    override fun getComponent(): JComponent = widgetComponent

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        updateWidget()
    }

    override fun dispose() {
        connection.dispose()
    }

    private fun updateWidget() {
        widgetComponent.updateUsage()
        statusBar?.updateWidget(ID())
    }

    private fun showPopup(component: Component?) {
        if (component == null) {
            return
        }

        QuotaUsageService.getInstance().refreshNowAsync()
        val content = buildPopupContent()
        val popup: JBPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, content)
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
        Disposer.register(this, popup)
        popup.showUnderneathOf(component)
    }

    private fun buildTooltipText(): String {
        val authService = QuotaAuthService.getInstance()
        if (!authService.isLoggedIn()) {
            return "OpenAI usage quota: not logged in"
        }
        if (error != null) {
            return "OpenAI usage quota: $error"
        }
        val primary = quota?.primary ?: return "OpenAI usage quota: loading"
        return "OpenAI usage quota: ${clampPercent(primary.usedPercent.roundToInt())}% used"
    }

    private fun buildPopupContent(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        val content = JBPanel<JBPanel<*>>(GridBagLayout())
        content.border = JBUI.Borders.empty(8, 8, 8, 3)
        panel.add(content, BorderLayout.CENTER)

        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.emptyInsets()
            weightx = 1.0
        }

        val planLabel = quota?.planType
        val titleRow = JBPanel<JBPanel<*>>(BorderLayout()).apply { isOpaque = false }
        val title = JBLabel("OpenAI usage")
        title.font = title.font.deriveFont(title.font.style or Font.BOLD, title.font.size + 2f)
        titleRow.add(title, BorderLayout.WEST)
        titleRow.add(createOpenSettingsButton(), BorderLayout.EAST)
        content.add(titleRow, constraints)
        constraints.gridy++

        if (!planLabel.isNullOrBlank()) {
            val capitalizedPlanLabel = planLabel.split(Regex("\\s+")).joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { character ->
                    if (character.isLowerCase()) character.titlecase() else character.toString()
                }
            }
            val plan = JBLabel("Plan: $capitalizedPlanLabel")
            plan.foreground = JBColor.GRAY
            content.add(plan, constraints)
            constraints.gridy++
        }

        constraints.insets = JBUI.insets(8, 0)
        addSeparator(content, constraints)
        constraints.insets = JBUI.emptyInsets()

        val authService = QuotaAuthService.getInstance()
        if (!authService.isLoggedIn()) {
            content.add(JBLabel("Not logged in."), constraints)
            constraints.gridy++
            content.add(ActionLink("Open Settings") { openSettings() }, constraints)
            return panel
        }

        if (error != null) {
            content.add(JBLabel("Error: $error"), constraints)
            constraints.gridy++
            content.add(ActionLink("Open Settings") { openSettings() }, constraints)
            return panel
        }

        val currentQuota = quota
        if (currentQuota == null) {
            content.add(JBLabel("Loading usage data..."), constraints)
            return panel
        }

        val limitWarning = getLimitWarning(currentQuota)
        if (limitWarning != null) {
            val warningLabel = JBLabel(limitWarning)
            warningLabel.foreground = JBColor.RED
            warningLabel.font = warningLabel.font.deriveFont(warningLabel.font.style or Font.BOLD)
            content.add(warningLabel, constraints)
            constraints.gridy++
            constraints.insets = JBUI.insets(8, 0)
            addSeparator(content, constraints)
            constraints.insets = JBUI.emptyInsets()
        }

        addSectionTitle(content, constraints, "Codex")
        addWindow(content, constraints, currentQuota.primary, "Primary")
        addWindow(content, constraints, currentQuota.secondary, "Secondary")

        val hasReviewData = currentQuota.reviewPrimary != null || currentQuota.reviewSecondary != null ||
            currentQuota.reviewAllowed != null || currentQuota.reviewLimitReached != null
        if (hasReviewData) {
            constraints.insets = JBUI.insets(12, 0, 4, 0)
            addSeparator(content, constraints)
            constraints.insets = JBUI.insets(4, 0)
            addSectionTitle(content, constraints, "Code Review")
            addWindow(content, constraints, currentQuota.reviewPrimary, "Primary")
            addWindow(content, constraints, currentQuota.reviewSecondary, "Secondary")
        }

        constraints.insets = JBUI.insets(12, 0, 4, 0)
        addSeparator(content, constraints)
        constraints.insets = JBUI.insetsTop(4)

        val fetchedAt = QuotaUiUtil.formatInstant(currentQuota.fetchedAt)
        if (fetchedAt != null) {
            val updatedLabel = JBLabel("Last updated: $fetchedAt")
            updatedLabel.font = updatedLabel.font.deriveFont(updatedLabel.font.size - 1f)
            updatedLabel.foreground = JBColor.GRAY
            content.add(updatedLabel, constraints)
        }

        return panel
    }

    private fun createOpenSettingsButton(): ActionLink {
        return ActionLink("") { openSettings() }.apply {
            icon = AllIcons.General.Settings
            autoHideOnDisable = false
            toolTipText = "Open settings"
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty()
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    private fun addWindow(content: JBPanel<*>, constraints: GridBagConstraints, window: UsageWindow?, fallbackLabel: String) {
        if (window == null) {
            return
        }

        val percent = clampPercent(window.usedPercent.roundToInt())
        val title = describeWindowLabel(window, fallbackLabel)
        val resetText = QuotaUiUtil.formatReset(window.resetsAt)
        var info = "$percent% used"
        if (resetText != null) {
            info += " - $resetText"
        }

        constraints.insets = JBUI.insetsTop(4)
        content.add(JBLabel(title), constraints)
        constraints.gridy++

        constraints.insets = JBUI.emptyInsets()
        content.add(JBLabel(info), constraints)
        constraints.gridy++

        val bar = JProgressBar(0, 100).apply {
            value = percent
            isStringPainted = false
            preferredSize = Dimension(200, 6)
        }
        content.add(bar, constraints)
        constraints.gridy++

        constraints.insets = JBUI.insetsTop(4)
    }

    private fun openSettings() {
        if (project.isDisposed) {
            return
        }

        val modality = statusBar?.component?.let { ModalityState.stateForComponent(it) }
            ?: ModalityState.defaultModalityState()
        ApplicationManager.getApplication().invokeLater(
            { ShowSettingsUtil.getInstance().showSettingsDialog(project, QuotaSettingsConfigurable::class.java) },
            modality,
        )
    }

    private inner class UsageWidgetComponent : JBPanel<UsageWidgetComponent>() {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = " "
        }

        fun updateUsage() {
            revalidate()
            repaint()
        }

        override fun getToolTipText(event: MouseEvent?): String = buildTooltipText()

        override fun getPreferredSize(): Dimension = calculateSize()

        override fun getMinimumSize(): Dimension = calculateSize()

        override fun getMaximumSize(): Dimension = calculateSize()

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g2d = graphics.create() as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            when (displayMode()) {
                QuotaDisplayMode.ICON_ONLY -> paintIconOnly(g2d)
                QuotaDisplayMode.CAKE_DIAGRAM -> paintCakeDiagram(g2d)
                QuotaDisplayMode.PERCENTAGE_BAR -> paintPercentageBar(g2d)
            }

            g2d.dispose()
        }

        private fun paintIconOnly(g2d: Graphics2D) {
            val icon = QuotaIcons.STATUS
            val x = (width - icon.iconWidth) / 2
            val y = (height - icon.iconHeight) / 2
            icon.paintIcon(this, g2d, x, y)
        }

        private fun paintCakeDiagram(g2d: Graphics2D) {
            val cakeIcon = scaledCakeIcon()
            val x = (width - cakeIcon.iconWidth) / 2
            val y = (height - cakeIcon.iconHeight) / 2
            cakeIcon.paintIcon(this, g2d, x, y)
        }

        private fun paintPercentageBar(g2d: Graphics2D) {
            val text = barDisplayText()
            val fm = g2d.fontMetrics
            val rectWidth = maxOf(fm.stringWidth(text) + 10, STATUS_MIN_WIDTH - 8)
            val rectHeight = fm.height + 4
            val x = (width - rectWidth) / 2
            val y = (height - rectHeight) / 2
            val rect: Shape = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), rectWidth.toFloat(), rectHeight.toFloat(), 6f, 6f)

            g2d.color = COLOR_BG
            g2d.fill(rect)

            val percentage = displayPercent()
            if (percentage >= 0) {
                var fillWidth = (rectWidth * (percentage / 100.0)).roundToInt()
                if (percentage > 0 && fillWidth < 4) {
                    fillWidth = 4
                }
                if (fillWidth > 0) {
                    g2d.color = usageColor(percentage)
                    val previousClip = g2d.clip
                    g2d.clip(rect)
                    g2d.fillRect(x, y, fillWidth, rectHeight)
                    g2d.clip = previousClip
                }
            } else if (error != null) {
                g2d.color = COLOR_GRAY
                g2d.fill(rect)
            }

            g2d.color = COLOR_TEXT
            g2d.drawString(text, x + 5, y + fm.ascent + 2)
        }

        private fun barDisplayText(): String {
            val authService = QuotaAuthService.getInstance()
            if (!authService.isLoggedIn()) {
                return "OpenAI: not logged in"
            }
            if (error != null) {
                return "OpenAI: error"
            }

            val primary = quota?.primary ?: return "OpenAI: loading..."
            val percent = clampPercent(primary.usedPercent.roundToInt())
            val reset = QuotaUiUtil.formatResetCompact(primary.resetsAt)
            return if (reset != null) "$percent% • $reset" else "$percent%"
        }

        private fun cakeIcon(): Icon {
            val authService = QuotaAuthService.getInstance()
            if (!authService.isLoggedIn() || error != null) {
                return QuotaIcons.CAKE_UNKNOWN
            }

            val primary = quota?.primary ?: return QuotaIcons.CAKE_UNKNOWN
            if (quota?.limitReached == true) {
                return QuotaIcons.CAKE_100
            }

            val percent = clampPercent(primary.usedPercent.roundToInt())
            if (percent >= 100) {
                return QuotaIcons.CAKE_100
            }
            if (percent <= 0) {
                return QuotaIcons.CAKE_0
            }

            val bucket = minOf(95, ((percent + 4) / 5) * 5)
            return when (bucket) {
                5 -> QuotaIcons.CAKE_5
                10 -> QuotaIcons.CAKE_10
                15 -> QuotaIcons.CAKE_15
                20 -> QuotaIcons.CAKE_20
                25 -> QuotaIcons.CAKE_25
                30 -> QuotaIcons.CAKE_30
                35 -> QuotaIcons.CAKE_35
                40 -> QuotaIcons.CAKE_40
                45 -> QuotaIcons.CAKE_45
                50 -> QuotaIcons.CAKE_50
                55 -> QuotaIcons.CAKE_55
                60 -> QuotaIcons.CAKE_60
                65 -> QuotaIcons.CAKE_65
                70 -> QuotaIcons.CAKE_70
                75 -> QuotaIcons.CAKE_75
                80 -> QuotaIcons.CAKE_80
                85 -> QuotaIcons.CAKE_85
                90 -> QuotaIcons.CAKE_90
                95 -> QuotaIcons.CAKE_95
                else -> QuotaIcons.CAKE_UNKNOWN
            }
        }

        private fun scaledCakeIcon(): Icon {
            val cakeIcon = cakeIcon()
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
            return IconUtil.scale(cakeIcon, this, minOf(widthScale, heightScale))
        }

        private fun displayMode(): QuotaDisplayMode = QuotaSettingsState.getInstance().displayMode()

        private fun displayPercent(): Int {
            val authService = QuotaAuthService.getInstance()
            if (!authService.isLoggedIn() || error != null) {
                return -1
            }
            val primary = quota?.primary ?: return -1
            return clampPercent(primary.usedPercent.roundToInt())
        }

        private fun calculateSize(): Dimension {
            return when (displayMode()) {
                QuotaDisplayMode.ICON_ONLY -> {
                    val icon = QuotaIcons.STATUS
                    val width = icon.iconWidth + STATUS_ICON_PADDING
                    val height = maxOf(icon.iconHeight, getFontMetrics(font).height) + 4
                    Dimension(width, height)
                }

                QuotaDisplayMode.CAKE_DIAGRAM -> {
                    val cakeIcon = scaledCakeIcon()
                    val width = cakeIcon.iconWidth + STATUS_ICON_PADDING
                    val height = maxOf(cakeIcon.iconHeight, getFontMetrics(font).height) + 4
                    Dimension(width, height)
                }

                QuotaDisplayMode.PERCENTAGE_BAR -> {
                    val fm: FontMetrics = getFontMetrics(font)
                    val text = barDisplayText()
                    val width = maxOf(fm.stringWidth(text) + 16, STATUS_MIN_WIDTH)
                    val height = fm.height + 6
                    Dimension(width, height)
                }
            }
        }

        private fun usageColor(percent: Int): Color {
            return when {
                percent >= STATUS_CRITICAL_PERCENT -> COLOR_RED
                percent >= STATUS_WARNING_PERCENT -> COLOR_YELLOW
                else -> COLOR_GREEN
            }
        }
    }

    companion object {
        private const val STATUS_WARNING_PERCENT = 70
        private const val STATUS_CRITICAL_PERCENT = 90
        private const val STATUS_MIN_WIDTH = 110
        private const val STATUS_ICON_PADDING = 8

        private val COLOR_GREEN = JBColor(Color(144, 238, 144), Color(60, 140, 60))
        private val COLOR_YELLOW = JBColor(Color(255, 245, 157), Color(180, 160, 50))
        private val COLOR_RED = JBColor(Color(255, 182, 182), Color(180, 70, 70))
        private val COLOR_GRAY = JBColor(Gray._208, Gray._85)
        private val COLOR_BG = JBColor(Gray._240, Gray._63)
        private val COLOR_TEXT = JBColor(Gray._60, Gray._210)

        private fun addSectionTitle(content: JBPanel<*>, constraints: GridBagConstraints, titleText: String) {
            val label = JBLabel(titleText)
            label.font = label.font.deriveFont(label.font.style or Font.BOLD, label.font.size + 1f)
            label.foreground = JBColor.BLUE
            content.add(label, constraints)
            constraints.gridy++
        }

        private fun addSeparator(content: JBPanel<*>, constraints: GridBagConstraints) {
            val separator = JSeparator(SwingConstants.HORIZONTAL)
            separator.foreground = JBColor.LIGHT_GRAY
            val size = separator.minimumSize
            separator.minimumSize = Dimension(size.width, 2)
            separator.maximumSize = Dimension(Int.MAX_VALUE, 2)
            content.add(separator, constraints)
            constraints.gridy++
        }

        private fun describeWindowLabel(window: UsageWindow, fallbackLabel: String): String {
            val minutes = window.windowDuration?.inWholeMinutes ?: return "$fallbackLabel limit"
            return when {
                minutes in 295L..305L -> "5h limit"
                minutes in 10070L..10090L -> "Weekly limit"
                minutes % (60L * 24L * 7L) == 0L -> {
                    val weeks = minutes / (60L * 24L * 7L)
                    if (weeks == 1L) "Weekly limit" else "${weeks}w limit"
                }

                minutes % (60L * 24L) == 0L -> "${minutes / (60L * 24L)}d limit"
                minutes % 60L == 0L -> "${minutes / 60L}h limit"
                else -> "${minutes}m limit"
            }
        }

        private fun getLimitWarning(quota: OpenAiCodexQuota?): String? {
            if (quota == null) {
                return null
            }
            return when {
                quota.limitReached == true -> "Codex limit reached"
                quota.allowed == false -> "Codex usage not allowed"
                quota.reviewLimitReached == true -> "Code review limit reached"
                quota.reviewAllowed == false -> "Code review usage not allowed"
                else -> null
            }
        }

        private fun clampPercent(value: Int): Int = value.coerceIn(0, 100)
    }
}
