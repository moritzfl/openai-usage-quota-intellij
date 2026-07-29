package de.moritzf.quota.idea.settings

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

/**
 * Common surface the settings dialog uses to drive a provider's panel.
 */
internal abstract class ProviderSettingsPanel : BorderLayoutPanel() {
    abstract val hideFromPopupCheckBox: JBCheckBox
    abstract fun updateFields()
    abstract fun updateStatus()
    abstract fun updateResponseArea()

    /** Read-only view of the provider's raw quota response. */
    protected fun createResponseViewer(): JBTextArea {
        return JBTextArea().apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            margin = JBUI.insets(6)
        }
    }

    /**
     * Scroll pane for [createResponseViewer]. The preferred height stays small on purpose: panels
     * add this to their centre, so it grows with the dialog, while a large preferred size would
     * push every settings tab to the same tall minimum.
     */
    protected fun createResponseViewerPanel(viewer: JBTextArea): JComponent {
        return JBScrollPane(viewer).apply {
            preferredSize = Dimension(1, JBUI.scale(140))
            minimumSize = Dimension(1, JBUI.scale(80))
            border = JBUI.Borders.emptyTop(4)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }
}
