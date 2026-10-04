package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

/** Slow reconciliation follows FancyNpcs visibility cadence, with a one-second floor. */
final class ModelRefreshCadence {
    private boolean initialized;
    private int lastRefreshTick;

    boolean shouldRefresh(int tick, int visibilityInterval) {
        int interval = Math.max(20, visibilityInterval);
        // Subtraction also handles Bukkit's signed tick counter wrapping.
        if (initialized && tick - lastRefreshTick < interval) return false;
        initialized = true;
        lastRefreshTick = tick;
        return true;
    }
}
