package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRefreshCadenceTest {
    @Test
    void defaultCadenceRefreshesOncePerSecond() {
        ModelRefreshCadence cadence = new ModelRefreshCadence();
        int refreshes = 0;
        for (int tick = 0; tick < 200; tick++) {
            if (cadence.shouldRefresh(tick, 20)) refreshes++;
        }
        assertEquals(10, refreshes);
    }

    @Test
    void fiveSecondCoreIntervalIsRespected() {
        ModelRefreshCadence cadence = new ModelRefreshCadence();
        assertTrue(cadence.shouldRefresh(10, 100));
        for (int tick = 11; tick < 110; tick++) assertFalse(cadence.shouldRefresh(tick, 100));
        assertTrue(cadence.shouldRefresh(110, 100));
    }

    @Test
    void fastOrInvalidCoreIntervalsCannotRaiseTheAddonAboveOneHertz() {
        for (int configured : new int[]{-1, 0, 1, 3, 19}) {
            ModelRefreshCadence cadence = new ModelRefreshCadence();
            assertTrue(cadence.shouldRefresh(0, configured));
            for (int tick = 1; tick < 20; tick++) assertFalse(cadence.shouldRefresh(tick, configured));
            assertTrue(cadence.shouldRefresh(20, configured));
        }
    }

    @Test
    void increasingIntervalOnReloadDoesNotRunAnEarlyRefresh() {
        ModelRefreshCadence cadence = new ModelRefreshCadence();
        assertTrue(cadence.shouldRefresh(0, 20));
        assertFalse(cadence.shouldRefresh(20, 100));
        assertFalse(cadence.shouldRefresh(99, 100));
        assertTrue(cadence.shouldRefresh(100, 100));
    }

    @Test
    void decreasingIntervalOnReloadUsesTheLastRefreshTime() {
        ModelRefreshCadence cadence = new ModelRefreshCadence();
        assertTrue(cadence.shouldRefresh(0, 100));
        assertFalse(cadence.shouldRefresh(19, 20));
        assertTrue(cadence.shouldRefresh(20, 20));
        assertFalse(cadence.shouldRefresh(21, 20));
    }

    @Test
    void tickCounterWrappingKeepsTheInterval() {
        ModelRefreshCadence cadence = new ModelRefreshCadence();
        int start = Integer.MAX_VALUE - 10;
        assertTrue(cadence.shouldRefresh(start, 20));
        assertFalse(cadence.shouldRefresh(start + 19, 20));
        assertTrue(cadence.shouldRefresh(start + 20, 20));
    }
}
