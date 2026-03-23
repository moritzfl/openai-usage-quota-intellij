package de.moritzf.quota.idea;

import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;

/**
 * Central icon references used by the plugin UI.
 */
public final class QuotaIcons {
    public static final Icon STATUS = IconLoader.getIcon("/icons/quota.svg", QuotaIcons.class);
    public static final Icon CAKE_0 = IconLoader.getIcon("/icons/clock_loader_0.svg", QuotaIcons.class);
    public static final Icon CAKE_10 = IconLoader.getIcon("/icons/clock_loader_10.svg", QuotaIcons.class);
    public static final Icon CAKE_20 = IconLoader.getIcon("/icons/clock_loader_20.svg", QuotaIcons.class);
    public static final Icon CAKE_40 = IconLoader.getIcon("/icons/clock_loader_40.svg", QuotaIcons.class);
    public static final Icon CAKE_60 = IconLoader.getIcon("/icons/clock_loader_60.svg", QuotaIcons.class);
    public static final Icon CAKE_80 = IconLoader.getIcon("/icons/clock_loader_80.svg", QuotaIcons.class);
    public static final Icon CAKE_90 = IconLoader.getIcon("/icons/clock_loader_90.svg", QuotaIcons.class);
    public static final Icon CAKE_LIMIT_REACHED = IconLoader.getIcon("/icons/quota_limit_reached.svg", QuotaIcons.class);
    public static final Icon CAKE_UNKNOWN = IconLoader.getIcon("/icons/quota_unknown.svg", QuotaIcons.class);

    private QuotaIcons() {
    }
}
