package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

/** Slow reconciliation follows FancyNpcs visibility cadence, with a one-second floor. */
final class ModelRefreshCadence {
    private boolean initialized;
    private int lastRefreshTick;

    boolean shouldRefresh(int tick, int visibilityInterval) {
        int interval = Math.max(20, visibilityInterval);
        // Subtraction also handles Bukkit's signed tick counter wrapping.
        if (this.initialized && tick - this.lastRefreshTick < interval) {
            return false;
        }
        this.initialized = true;
        this.lastRefreshTick = tick;
        return true;
    }
}
