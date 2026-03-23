package de.moritzf.quota.idea;

import com.intellij.util.messages.Topic;

/**
 * Broadcast when plugin settings affecting UI rendering have changed.
 */
public interface QuotaSettingsListener {
    Topic<QuotaSettingsListener> TOPIC =
            Topic.create("openai.usage.quota.settings", QuotaSettingsListener.class);

    void onSettingsChanged();
}
