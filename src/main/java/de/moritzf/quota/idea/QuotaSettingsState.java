package de.moritzf.quota.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * Persistent plugin settings shared at application scope.
 */
@State(name = "OpenAiUsageQuotaSettings", storages = @Storage("openai-usage-quota.xml"))
@Service(Service.Level.APP)
public final class QuotaSettingsState implements PersistentStateComponent<QuotaSettingsState> {
    public int refreshMinutes = 5;
    public String statusBarDisplayMode = QuotaDisplayMode.ICON_ONLY.name();

    public static QuotaSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(QuotaSettingsState.class);
    }

    @Override
    public @NotNull QuotaSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull QuotaSettingsState state) {
        this.refreshMinutes = state.refreshMinutes;
        this.statusBarDisplayMode = QuotaDisplayMode.fromStorageValue(state.statusBarDisplayMode).name();
    }

    public @NotNull QuotaDisplayMode getStatusBarDisplayMode() {
        return QuotaDisplayMode.fromStorageValue(statusBarDisplayMode);
    }

    public void setStatusBarDisplayMode(@NotNull QuotaDisplayMode displayMode) {
        this.statusBarDisplayMode = displayMode.name();
    }
}
