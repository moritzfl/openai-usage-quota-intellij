package de.moritzf.quota.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.Consumer;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import de.moritzf.quota.OpenAiCodexQuota;
import de.moritzf.quota.UsageWindow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.util.Objects;

/**
 * Status bar widget that displays the current quota state and shows a detailed popup.
 */
public final class QuotaStatusBarWidget implements StatusBarWidget, StatusBarWidget.IconPresentation {
    private final Project project;
    private final MessageBusConnection connection;
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
    }

    @Override
    public @NotNull String ID() {
        return QuotaStatusBarWidgetFactory.ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        updateWidget();
    }

    @Override
    public @NotNull WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public @NotNull Icon getIcon() {
        return QuotaIcons.STATUS;
    }

    @Override
    public @NotNull String getTooltipText() {
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

    @Override
    public @NotNull Consumer<MouseEvent> getClickConsumer() {
        return this::showPopup;
    }

    @Override
    public void dispose() {
        connection.dispose();
    }

    private void updateWidget() {
        if (statusBar != null) {
            statusBar.updateWidget(ID());
        }
    }

    private void showPopup(MouseEvent event) {
        if (event == null) {
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
        popup.show(new RelativePoint(event));
    }

    private JComponent buildPopupContent() {
        JPanel panel = new JBPanel<>(new BorderLayout());
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(JBUI.Borders.empty(8));
        panel.add(content, BorderLayout.CENTER);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = JBUI.emptyInsets();
        constraints.weightx = 1.0;

        String planLabel = quota != null ? quota.getPlanType() : null;
        String titleText = "OpenAI Usage";
        JBLabel title = new JBLabel(titleText);
        title.setFont(title.getFont().deriveFont(title.getFont().getStyle() | java.awt.Font.BOLD, title.getFont().getSize() + 2));
        content.add(title, constraints);
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
            warningLabel.setFont(warningLabel.getFont().deriveFont(warningLabel.getFont().getStyle() | java.awt.Font.BOLD));
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

    private void addWindow(JPanel content, GridBagConstraints constraints, UsageWindow window, String fallbackLabel) {
        if (window == null) {
            return;
        }

        int percent = (int) Math.round(window.getUsedPercent());
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

        javax.swing.JProgressBar bar = new javax.swing.JProgressBar(0, 100);
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
}
