package de.moritzf.quota.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.IconUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import de.moritzf.quota.OpenAiCodexQuota;
import de.moritzf.quota.UsageWindow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Status bar widget that displays the current quota state and shows a detailed popup.
 */
public final class QuotaStatusBarWidget implements CustomStatusBarWidget {
    private static final int STATUS_WARNING_PERCENT = 70;
    private static final int STATUS_CRITICAL_PERCENT = 90;
    private static final int STATUS_MIN_WIDTH = 110;
    private static final int STATUS_ICON_PADDING = 8;

    private static final Color COLOR_GREEN = new JBColor(new Color(144, 238, 144), new Color(60, 140, 60));
    private static final Color COLOR_YELLOW = new JBColor(new Color(255, 245, 157), new Color(180, 160, 50));
    private static final Color COLOR_RED = new JBColor(new Color(255, 182, 182), new Color(180, 70, 70));
    private static final Color COLOR_GRAY = new JBColor(Gray._208, Gray._85);
    private static final Color COLOR_BG = new JBColor(Gray._240, Gray._63);
    private static final Color COLOR_TEXT = new JBColor(Gray._60, Gray._210);

    private final Project project;
    private final MessageBusConnection connection;
    private final UsageWidgetComponent widgetComponent = new UsageWidgetComponent();
    private volatile OpenAiCodexQuota quota;
    private volatile String error;
    private StatusBar statusBar;

    public QuotaStatusBarWidget(Project project) {
        this.project = project;
        QuotaUsageService usageService = QuotaUsageService.getInstance();
        this.quota = usageService.getLastQuota();
        this.error = usageService.getLastError();
        this.connection = ApplicationManager.getApplication().getMessageBus().connect(this);
        this.connection.subscribe(QuotaUsageListener.TOPIC, (QuotaUsageListener) (updatedQuota, updatedError) -> {
            QuotaStatusBarWidget.this.quota = updatedQuota;
            QuotaStatusBarWidget.this.error = updatedError;
            updateWidget();
        });
        this.connection.subscribe(QuotaSettingsListener.TOPIC, (QuotaSettingsListener) this::updateWidget);
        this.widgetComponent.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                showPopup(widgetComponent);
            }
        });
    }

    @Override
    public @NotNull String ID() {
        return QuotaStatusBarWidgetFactory.ID;
    }

    @Override
    public @NotNull JComponent getComponent() {
        return widgetComponent;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        updateWidget();
    }

    @Override
    public void dispose() {
        connection.dispose();
    }

    private void updateWidget() {
        widgetComponent.updateUsage();
        if (statusBar != null) {
            statusBar.updateWidget(ID());
        }
    }

    private void showPopup(@Nullable Component component) {
        if (component == null) {
            return;
        }
        QuotaUsageService.getInstance().refreshNowAsync();
        JComponent content = buildPopupContent();
        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, content)
                .setRequestFocus(true)
                .setFocusable(true)
                .setResizable(false)
                .setMovable(false)
                .createPopup();
        Disposer.register(this, popup);
        popup.showUnderneathOf(component);
    }

    private @NotNull String buildTooltipText() {
        QuotaAuthService authService = QuotaAuthService.getInstance();
        if (!authService.isLoggedIn()) {
            return "OpenAI usage quota: not logged in";
        }
        if (error != null) {
            return "OpenAI usage quota: " + error;
        }
        if (quota == null || quota.getPrimary() == null) {
            return "OpenAI usage quota: loading";
        }
        UsageWindow primary = quota.getPrimary();
        return String.format(Locale.ROOT, "OpenAI usage quota: %.0f%% used", primary.getUsedPercent());
    }

    private JComponent buildPopupContent() {
        JPanel panel = new JBPanel<>(new BorderLayout());
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(JBUI.Borders.empty(8, 8, 8, 3));
        panel.add(content, BorderLayout.CENTER);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = JBUI.emptyInsets();
        constraints.weightx = 1.0;

        String planLabel = quota != null ? quota.getPlanType() : null;
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        JBLabel title = new JBLabel("OpenAI usage");
        title.setFont(title.getFont().deriveFont(title.getFont().getStyle() | Font.BOLD, title.getFont().getSize() + 2));
        titleRow.add(title, BorderLayout.WEST);
        titleRow.add(createOpenSettingsButton(), BorderLayout.EAST);
        content.add(titleRow, constraints);
        constraints.gridy++;

        if (planLabel != null && !planLabel.isBlank()) {
            String capitalizedPlanLabel = Arrays.stream(planLabel.split("\\s+"))
                    .map(word -> word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase())
                    .collect(Collectors.joining(" "));
            JBLabel plan = new JBLabel("Plan: " + capitalizedPlanLabel);
            plan.setForeground(JBColor.GRAY);
            content.add(plan, constraints);
            constraints.gridy++;
        }

        constraints.insets = JBUI.insets(8, 0);
        addSeparator(content, constraints);
        constraints.insets = JBUI.emptyInsets();

        QuotaAuthService authService = QuotaAuthService.getInstance();
        if (!authService.isLoggedIn()) {
            content.add(new JBLabel("Not logged in."), constraints);
            constraints.gridy++;
            ActionLink settingsLink = new ActionLink("Open Settings", (java.awt.event.ActionListener) e -> openSettings());
            content.add(settingsLink, constraints);
            return panel;
        }

        if (error != null) {
            content.add(new JBLabel("Error: " + error), constraints);
            constraints.gridy++;
            ActionLink settingsLink = new ActionLink("Open Settings", (java.awt.event.ActionListener) e -> openSettings());
            content.add(settingsLink, constraints);
            return panel;
        }

        if (quota == null) {
            content.add(new JBLabel("Loading usage data..."), constraints);
            return panel;
        }

        String limitWarning = getLimitWarning(quota);
        if (limitWarning != null) {
            JBLabel warningLabel = new JBLabel(limitWarning);
            warningLabel.setForeground(JBColor.RED);
            warningLabel.setFont(warningLabel.getFont().deriveFont(warningLabel.getFont().getStyle() | Font.BOLD));
            content.add(warningLabel, constraints);
            constraints.gridy++;
            constraints.insets = JBUI.insets(8, 0);
            addSeparator(content, constraints);
            constraints.insets = JBUI.emptyInsets();
        }

        addSectionTitle(content, constraints, "Codex");
        addWindow(content, constraints, quota.getPrimary(), "Primary");
        addWindow(content, constraints, quota.getSecondary(), "Secondary");

        boolean hasReviewData = quota.getReviewPrimary() != null || quota.getReviewSecondary() != null
                || quota.getReviewAllowed() != null || quota.getReviewLimitReached() != null;
        if (hasReviewData) {
            constraints.insets = JBUI.insets(12, 0, 4, 0);
            addSeparator(content, constraints);
            constraints.insets = JBUI.insets(4, 0);
            addSectionTitle(content, constraints, "Code Review");
            addWindow(content, constraints, quota.getReviewPrimary(), "Primary");
            addWindow(content, constraints, quota.getReviewSecondary(), "Secondary");
        }

        constraints.insets = JBUI.insets(12, 0, 4, 0);
        addSeparator(content, constraints);
        constraints.insets = JBUI.insetsTop(4);

        String fetchedAt = QuotaUiUtil.formatInstant(quota.getFetchedAt());
        if (fetchedAt != null) {
            JBLabel updatedLabel = new JBLabel("Last updated: " + fetchedAt);
            updatedLabel.setFont(updatedLabel.getFont().deriveFont(updatedLabel.getFont().getSize() - 1f));
            updatedLabel.setForeground(JBColor.GRAY);
            content.add(updatedLabel, constraints);
        }

        return panel;
    }

    private JButton createOpenSettingsButton() {
        JButton settingsButton = new JButton(AllIcons.General.Settings);
        settingsButton.setToolTipText("Open settings");
        settingsButton.setMargin(JBUI.emptyInsets());
        settingsButton.setBorder(JBUI.Borders.empty());
        settingsButton.setBorderPainted(false);
        settingsButton.setContentAreaFilled(false);
        settingsButton.setFocusPainted(false);
        settingsButton.setOpaque(false);
        settingsButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        settingsButton.addActionListener(event -> openSettings());
        return settingsButton;
    }

    private void addWindow(JPanel content, GridBagConstraints constraints, UsageWindow window, String fallbackLabel) {
        if (window == null) {
            return;
        }

        int percent = clampPercent((int) Math.round(window.getUsedPercent()));
        String title = describeWindowLabel(window, fallbackLabel);
        String resetText = QuotaUiUtil.formatReset(window.getResetsAt());
        String info = percent + "% used";
        if (resetText != null) {
            info = info + " - " + resetText;
        }

        constraints.insets = JBUI.insetsTop(4);
        content.add(new JBLabel(title), constraints);
        constraints.gridy++;

        constraints.insets = JBUI.emptyInsets();
        content.add(new JBLabel(info), constraints);
        constraints.gridy++;

        JProgressBar bar = new JProgressBar(0, 100);
        bar.setValue(percent);
        bar.setStringPainted(false);
        bar.setPreferredSize(new Dimension(200, 6));
        content.add(bar, constraints);
        constraints.gridy++;

        constraints.insets = JBUI.insetsTop(4);
    }

    private static void addSectionTitle(JPanel content, GridBagConstraints constraints, String titleText) {
        JBLabel label = new JBLabel(titleText);
        label.setFont(label.getFont().deriveFont(label.getFont().getStyle() | Font.BOLD, label.getFont().getSize() + 1));
        label.setForeground(JBColor.BLUE);
        content.add(label, constraints);
        constraints.gridy++;
    }

    private static void addSeparator(JPanel content, GridBagConstraints constraints) {
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setForeground(JBColor.LIGHT_GRAY);
        Dimension size = separator.getMinimumSize();
        separator.setMinimumSize(new Dimension(size.width, 2));
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        content.add(separator, constraints);
        constraints.gridy++;
    }

    private static String describeWindowLabel(UsageWindow window, String fallbackLabel) {
        Integer minutes = window.getWindowMinutes();
        if (minutes == null) {
            return fallbackLabel + " limit";
        }
        if (minutes >= 295 && minutes <= 305) {
            return "5h limit";
        }
        if (minutes >= 10070 && minutes <= 10090) {
            return "Weekly limit";
        }
        if (minutes % (60 * 24 * 7) == 0) {
            int weeks = minutes / (60 * 24 * 7);
            return weeks == 1 ? "Weekly limit" : weeks + "w limit";
        }
        if (minutes % (60 * 24) == 0) {
            int days = minutes / (60 * 24);
            return days + "d limit";
        }
        if (minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours + "h limit";
        }
        return minutes + "m limit";
    }

    private void openSettings() {
        if (project.isDisposed()) {
            return;
        }
        ModalityState modality = statusBar != null
                ? ModalityState.stateForComponent(Objects.requireNonNull(statusBar.getComponent()))
                : ModalityState.defaultModalityState();
        ApplicationManager.getApplication().invokeLater(() ->
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, QuotaSettingsConfigurable.class), modality);
    }

    private static @Nullable String getLimitWarning(OpenAiCodexQuota quota) {
        if (quota == null) {
            return null;
        }
        Boolean limitReached = quota.getLimitReached();
        if (Boolean.TRUE.equals(limitReached)) {
            return "Codex limit reached";
        }
        Boolean allowed = quota.getAllowed();
        if (Boolean.FALSE.equals(allowed)) {
            return "Codex usage not allowed";
        }
        Boolean reviewLimitReached = quota.getReviewLimitReached();
        if (Boolean.TRUE.equals(reviewLimitReached)) {
            return "Code review limit reached";
        }
        Boolean reviewAllowed = quota.getReviewAllowed();
        if (Boolean.FALSE.equals(reviewAllowed)) {
            return "Code review usage not allowed";
        }
        return null;
    }

    private static int clampPercent(int value) {
        return Math.clamp(value, 0, 100);
    }

    private final class UsageWidgetComponent extends JPanel {
        private UsageWidgetComponent() {
            setOpaque(false);
            setBorder(JBUI.Borders.empty(0, 4));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(" ");
        }

        private void updateUsage() {
            revalidate();
            repaint();
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            return buildTooltipText();
        }

        @Override
        public Dimension getPreferredSize() {
            return calculateSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return calculateSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return calculateSize();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2d = (Graphics2D) graphics.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            switch (getDisplayMode()) {
                case ICON_ONLY -> paintIconOnly(g2d);
                case CAKE_DIAGRAM -> paintCakeDiagram(g2d);
                case PERCENTAGE_BAR -> paintPercentageBar(g2d);
            }

            g2d.dispose();
        }

        private void paintIconOnly(Graphics2D g2d) {
            Icon icon = QuotaIcons.STATUS;
            int x = (getWidth() - icon.getIconWidth()) / 2;
            int y = (getHeight() - icon.getIconHeight()) / 2;
            icon.paintIcon(this, g2d, x, y);
        }

        private void paintCakeDiagram(Graphics2D g2d) {
            Icon cakeIcon = getScaledCakeIcon();
            int iconWidth = cakeIcon.getIconWidth();
            int iconHeight = cakeIcon.getIconHeight();
            int x = (getWidth() - iconWidth) / 2;
            int y = (getHeight() - iconHeight) / 2;
            cakeIcon.paintIcon(this, g2d, x, y);
        }

        private void paintPercentageBar(Graphics2D g2d) {
            String text = getBarDisplayText();
            FontMetrics fm = g2d.getFontMetrics();
            int rectWidth = Math.max(fm.stringWidth(text) + 10, STATUS_MIN_WIDTH - 8);
            int rectHeight = fm.getHeight() + 4;
            int x = (getWidth() - rectWidth) / 2;
            int y = (getHeight() - rectHeight) / 2;
            Shape rect = new RoundRectangle2D.Float(x, y, rectWidth, rectHeight, 6f, 6f);

            g2d.setColor(COLOR_BG);
            g2d.fill(rect);

            int percentage = getDisplayPercent();
            if (percentage >= 0) {
                int fillWidth = (int) Math.round(rectWidth * (percentage / 100.0));
                if (percentage > 0 && fillWidth < 4) {
                    fillWidth = 4;
                }
                if (fillWidth > 0) {
                    g2d.setColor(getUsageColor(percentage));
                    Shape previousClip = g2d.getClip();
                    g2d.clip(rect);
                    g2d.fillRect(x, y, fillWidth, rectHeight);
                    g2d.setClip(previousClip);
                }
            } else if (error != null) {
                g2d.setColor(COLOR_GRAY);
                g2d.fill(rect);
            }

            g2d.setColor(COLOR_TEXT);
            g2d.drawString(text, x + 5, y + fm.getAscent() + 2);
        }

        private String getBarDisplayText() {
            QuotaAuthService authService = QuotaAuthService.getInstance();
            if (!authService.isLoggedIn()) {
                return "OpenAI: not logged in";
            }
            if (error != null) {
                return "OpenAI: error";
            }
            UsageWindow primary = quota != null ? quota.getPrimary() : null;
            if (primary == null) {
                return "OpenAI: loading...";
            }
            int percent = clampPercent((int) Math.round(primary.getUsedPercent()));
            String reset = QuotaUiUtil.formatResetCompact(primary.getResetsAt());
            if (reset != null) {
                return percent + "% • " + reset;
            }
            return percent + "%";
        }

        private Icon getCakeIcon() {
            QuotaAuthService authService = QuotaAuthService.getInstance();
            if (!authService.isLoggedIn() || error != null) {
                return QuotaIcons.CAKE_UNKNOWN;
            }
            UsageWindow primary = quota != null ? quota.getPrimary() : null;
            if (primary == null) {
                return QuotaIcons.CAKE_UNKNOWN;
            }
            if (quota != null && Boolean.TRUE.equals(quota.getLimitReached())) {
                return QuotaIcons.CAKE_100;
            }

            int percent = clampPercent((int) Math.round(primary.getUsedPercent()));
            if (percent >= 100) {
                return QuotaIcons.CAKE_100;
            }
            if (percent <= 0) {
                return QuotaIcons.CAKE_0;
            }

            // Round to the best 5er percentage step
            int bucket = Math.min(95, ((percent + 4) / 5) * 5);
            return switch (bucket) {
                case 5 -> QuotaIcons.CAKE_5;
                case 10 -> QuotaIcons.CAKE_10;
                case 15 -> QuotaIcons.CAKE_15;
                case 20 -> QuotaIcons.CAKE_20;
                case 25 -> QuotaIcons.CAKE_25;
                case 30 -> QuotaIcons.CAKE_30;
                case 35 -> QuotaIcons.CAKE_35;
                case 40 -> QuotaIcons.CAKE_40;
                case 45 -> QuotaIcons.CAKE_45;
                case 50 -> QuotaIcons.CAKE_50;
                case 55 -> QuotaIcons.CAKE_55;
                case 60 -> QuotaIcons.CAKE_60;
                case 65 -> QuotaIcons.CAKE_65;
                case 70 -> QuotaIcons.CAKE_70;
                case 75 -> QuotaIcons.CAKE_75;
                case 80 -> QuotaIcons.CAKE_80;
                case 85 -> QuotaIcons.CAKE_85;
                case 90 -> QuotaIcons.CAKE_90;
                case 95 -> QuotaIcons.CAKE_95;
                default -> QuotaIcons.CAKE_UNKNOWN;
            };
        }

        private Icon getScaledCakeIcon() {
            Icon cakeIcon = getCakeIcon();
            Icon statusIcon = QuotaIcons.STATUS;

            int targetWidth = statusIcon.getIconWidth();
            int targetHeight = statusIcon.getIconHeight();
            int iconWidth = cakeIcon.getIconWidth();
            int iconHeight = cakeIcon.getIconHeight();
            if (iconWidth <= 0 || iconHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
                return cakeIcon;
            }
            if (iconWidth <= targetWidth && iconHeight <= targetHeight) {
                return cakeIcon;
            }

            float widthScale = targetWidth / (float) iconWidth;
            float heightScale = targetHeight / (float) iconHeight;
            return IconUtil.scale(cakeIcon, this, Math.min(widthScale, heightScale));
        }

        private QuotaDisplayMode getDisplayMode() {
            return QuotaSettingsState.getInstance().getStatusBarDisplayMode();
        }

        private int getDisplayPercent() {
            QuotaAuthService authService = QuotaAuthService.getInstance();
            if (!authService.isLoggedIn() || error != null) {
                return -1;
            }
            UsageWindow primary = quota != null ? quota.getPrimary() : null;
            if (primary == null) {
                return -1;
            }
            return clampPercent((int) Math.round(primary.getUsedPercent()));
        }

        private Dimension calculateSize() {
            QuotaDisplayMode mode = getDisplayMode();
            if (mode == QuotaDisplayMode.ICON_ONLY) {
                Icon icon = QuotaIcons.STATUS;
                int width = icon.getIconWidth() + STATUS_ICON_PADDING;
                int height = Math.max(icon.getIconHeight(), getFontMetrics(getFont()).getHeight()) + 4;
                return new Dimension(width, height);
            }

            FontMetrics fm = getFontMetrics(getFont());
            if (mode == QuotaDisplayMode.CAKE_DIAGRAM) {
                Icon cakeIcon = getScaledCakeIcon();
                int width = cakeIcon.getIconWidth() + STATUS_ICON_PADDING;
                int height = Math.max(cakeIcon.getIconHeight(), fm.getHeight()) + 4;
                return new Dimension(width, height);
            }

            String text = getBarDisplayText();
            int width = Math.max(fm.stringWidth(text) + 16, STATUS_MIN_WIDTH);
            int height = fm.getHeight() + 6;
            return new Dimension(width, height);
        }

        private Color getUsageColor(int percent) {
            if (percent >= STATUS_CRITICAL_PERCENT) {
                return COLOR_RED;
            }
            if (percent >= STATUS_WARNING_PERCENT) {
                return COLOR_YELLOW;
            }
            return COLOR_GREEN;
        }
    }
}
