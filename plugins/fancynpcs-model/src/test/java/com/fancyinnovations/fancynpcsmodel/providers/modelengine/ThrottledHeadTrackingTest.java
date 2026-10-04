package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ThrottledHeadTrackingTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);
    private static final double STEP = .05;

    @Test
    void cachedTargetContinuesTurningWithoutConsumingNewCandidates() {
        SmoothHeadTracking tracking = new SmoothHeadTracking(SmoothHeadTracking.Config.defaults());
        SmoothHeadTracking.Pose first = tracking.tick(0, 0, 8,
                List.of(new SmoothHeadTracking.Candidate(FIRST, 9, 60, 20)), STEP, true, true);
        SmoothHeadTracking.Pose next = tracking.tick(0, 0, 8,
                List.of(new SmoothHeadTracking.Candidate(SECOND, 1, -60, -20)), STEP, true, false);
        assertEquals(FIRST, next.targetId());
        assertTrue(next.headYaw() > first.headYaw());
        assertTrue(next.headPitch() > first.headPitch());
        assertTrue(next.headYaw() - first.headYaw() <= 9);
    }

    @Test
    void targetHoldTimeAdvancesBetweenOneSecondSamples() {
        SmoothHeadTracking tracking = new SmoothHeadTracking(SmoothHeadTracking.Config.defaults());
        tracking.tick(0, 0, 8, List.of(new SmoothHeadTracking.Candidate(FIRST, 16, 40, 0)), STEP, true, true);
        for (int tick = 1; tick < 20; tick++) tracking.tick(0, 0, 8, List.of(), STEP, true, false);
        SmoothHeadTracking.Pose next = tracking.tick(0, 0, 8, List.of(
                new SmoothHeadTracking.Candidate(FIRST, 16, 40, 0),
                new SmoothHeadTracking.Candidate(SECOND, 1, -40, 0)), STEP, true, true);
        assertEquals(SECOND, next.targetId());
    }

    @Test
    void missingTargetGraceExpiresWithoutWaitingForAnotherSample() {
        SmoothHeadTracking tracking = new SmoothHeadTracking(SmoothHeadTracking.Config.defaults());
        tracking.tick(0, 0, 8, List.of(new SmoothHeadTracking.Candidate(FIRST, 9, 60, 0)), STEP, true, true);
        assertEquals(FIRST, tracking.tick(0, 0, 8, List.of(), STEP, true, true).targetId());
        SmoothHeadTracking.Pose pose = null;
        for (int tick = 0; tick < 4; tick++) pose = tracking.tick(0, 0, 8, List.of(), STEP, true, false);
        assertNull(pose.targetId());
    }

    @Test
    void turningOffTrackingClearsCachedTargetImmediately() {
        SmoothHeadTracking tracking = new SmoothHeadTracking(SmoothHeadTracking.Config.defaults());
        tracking.tick(0, 0, 8, List.of(new SmoothHeadTracking.Candidate(FIRST, 9, 60, 0)), STEP, true, true);
        assertNull(tracking.tick(0, 0, 0, List.of(), STEP, true, false).targetId());
    }
}
