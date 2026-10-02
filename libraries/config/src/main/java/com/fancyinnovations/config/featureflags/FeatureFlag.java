package com.fancyinnovations.config.featureflags;

/**
 * Use ConfigOption instead of this class.
 */
@Deprecated
public class FeatureFlag {

    private final String name;
    private final String description;
    private final boolean forceDisabled;
    private boolean enabled;

    public FeatureFlag(String name, String description, boolean forceDisabled) {
        this.name = name;
        this.description = description;
        this.forceDisabled = forceDisabled;
        this.enabled = false;
    }

    public boolean isEnabled() {
        if (forceDisabled) return false;

        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isForceDisabled() {
        return forceDisabled;
    }
}
