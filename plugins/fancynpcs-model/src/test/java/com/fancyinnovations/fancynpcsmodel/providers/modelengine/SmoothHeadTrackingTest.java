package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SmoothHeadTrackingTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);
    private static final double TICK = 0.05;

    @Test
    void headTurnsGraduallyWithoutMovingTheNpcBody() {
        SmoothHeadTracking tracking = defaults();
        SmoothHeadTracking.Pose pose = tracking.tick(20, 0, 6, List.of(player(FIRST, 3, 80, -30)), TICK);
        assertEquals(20, pose.bodyYaw(), 0);
        assertTrue(pose.headYaw() > 20 && pose.headYaw() <= 29);
        assertTrue(pose.headPitch() < 0 && pose.headPitch() >= -6);
        assertEquals(FIRST, pose.targetId());
    }

    @Test
    void crossingYawBoundaryTakesTheShortPathAndKeepsContinuousAngles() {
        SmoothHeadTracking tracking = defaults();
        SmoothHeadTracking.Pose pose = tracking.tick(179, 0, 6, List.of(player(FIRST, 3, -179, 0)), TICK);
        assertTrue(pose.headYaw() > 179 && pose.headYaw() < 181);
        SmoothHeadTracking.Pose next = tracking.tick(-179, 0, 6, List.of(player(FIRST, 3, 179, 0)), TICK);
        assertEquals(181, next.bodyYaw(), 0);
        assertTrue(Math.abs(next.headYaw() - pose.headYaw()) < 3);
    }

    @Test
    void aPlayerBehindCannotTurnTheHeadOrPitchBeyondConfiguredLimits() {
        SmoothHeadTracking tracking = defaults();
        SmoothHeadTracking.Pose pose = null;
        for (int i = 0; i < 100; i++) {
            pose = tracking.tick(10, 0, 6, List.of(player(FIRST, 2, 170, -89)), TICK);
            assertEquals(10, pose.bodyYaw(), 0);
            assertTrue(Math.abs(pose.headYaw() - pose.bodyYaw()) <= 65);
            assertTrue(Math.abs(pose.headPitch()) <= 35);
        }
        assertEquals(75, pose.headYaw(), 0.26);
        assertEquals(-35, pose.headPitch(), 0.26);
    }

    @Test
    void targetCrossingDirectlyBehindTheNpcDoesNotAlternateNeckSides() {
        SmoothHeadTracking tracking = defaults();
        SmoothHeadTracking.Pose previous = tracking.tick(0, 0, 6, List.of(player(FIRST, 2, 179, 0)), TICK);
        for (int i = 0; i < 30; i++) {
            SmoothHeadTracking.Pose next = tracking.tick(0, 0, 6,
                    List.of(player(FIRST, 2, i % 2 == 0 ? -179 : 179, 0)), TICK);
            assertTrue(next.headYaw() >= previous.headYaw());
            assertTrue(next.headYaw() <= 65);
            previous = next;
        }
        // Once the player reaches the other side in front, tracking can change sides.
        for (int i = 0; i < 40; i++) previous = tracking.tick(0, 0, 6, List.of(player(FIRST, 2, -60, 0)), TICK);
        assertEquals(-60, previous.headYaw(), 0.26);
    }

    @Test
    void nearEqualDistancesAndReversedIterationDoNotSwitchPlayers() {
        SmoothHeadTracking tracking = defaults();
        tracking.tick(0, 0, 6, List.of(player(FIRST, 3, 40, 0), player(SECOND, 3.1, -40, 0)), TICK);
        for (int i = 0; i < 50; i++) {
            double firstDistance = i % 2 == 0 ? 3.1 : 3;
            SmoothHeadTracking.Pose pose = tracking.tick(0, 0, 6,
                    List.of(player(SECOND, 3, -40, 0), player(FIRST, firstDistance, 40, 0)), TICK);
            assertEquals(FIRST, pose.targetId());
        }
    }

    @Test
    void substantiallyCloserPlayerMustWaitForMinimumTargetHold() {
        SmoothHeadTracking tracking = defaults();
        tracking.tick(0, 0, 6, List.of(player(FIRST, 4, 40, 0)), TICK);
        List<SmoothHeadTracking.Candidate> players = List.of(player(FIRST, 4, 40, 0), player(SECOND, 2, -40, 0));
        for (int i = 0; i < 14; i++) {
            assertEquals(FIRST, tracking.tick(0, 0, 6, players, TICK).targetId());
        }
        SmoothHeadTracking.Pose switched = tracking.tick(0, 0, 6, players, TICK);
        assertEquals(SECOND, switched.targetId());
        assertTrue(switched.headYaw() > 0, "switching targets must not snap directly to the other player");
    }

    @Test
    void acquisitionAndExitRangesHaveHysteresis() {
        SmoothHeadTracking tracking = defaults();
        assertNull(tracking.tick(0, 0, 6, List.of(player(FIRST, 6.2, 45, 0)), TICK).targetId());
        assertEquals(FIRST, tracking.tick(0, 0, 6, List.of(player(FIRST, 5.9, 45, 0)), TICK).targetId());
        assertEquals(FIRST, tracking.tick(0, 0, 6, List.of(player(FIRST, 6.8, 45, 0)), TICK).targetId());
        for (int i = 0; i < 3; i++) {
            assertEquals(FIRST, tracking.tick(0, 0, 6, List.of(player(FIRST, 7, 45, 0)), TICK).targetId());
        }
        assertNull(tracking.tick(0, 0, 6, List.of(player(FIRST, 7, 45, 0)), TICK).targetId());
    }

    @Test
    void temporaryMissingTargetIsRetainedThenHeadReturnsSmoothly() {
        SmoothHeadTracking tracking = defaults();
        SmoothHeadTracking.Pose previous = null;
        for (int i = 0; i < 30; i++) {
            previous = tracking.tick(0, 0, 6, List.of(player(FIRST, 2, 50, -20)), TICK);
        }
        for (int i = 0; i < 3; i++) assertEquals(FIRST, tracking.tick(0, 0, 6, List.of(), TICK).targetId());
        SmoothHeadTracking.Pose released = tracking.tick(0, 0, 6, List.of(), TICK);
        assertNull(released.targetId());
        assertTrue(released.headYaw() > 0 && released.headYaw() < previous.headYaw());
        assertTrue(previous.headYaw() - released.headYaw() <= 9);
        for (int i = 0; i < 60; i++) released = tracking.tick(0, 0, 6, List.of(), TICK);
        assertEquals(0, released.headYaw(), 0.26);
        assertEquals(0, released.headPitch(), 0.26);
    }

    @Test
    void disablingTrackingReleasesImmediatelyButDoesNotSnapHead() {
        SmoothHeadTracking tracking = defaults();
        SmoothHeadTracking.Pose previous = null;
        for (int i = 0; i < 20; i++) previous = tracking.tick(0, 0, 6, List.of(player(FIRST, 2, 50, 0)), TICK);
        SmoothHeadTracking.Pose disabled = tracking.tick(0, 0, 0, List.of(player(FIRST, 2, 50, 0)), TICK);
        assertNull(disabled.targetId());
        assertTrue(disabled.headYaw() > 0 && disabled.headYaw() < previous.headYaw());
        assertTrue(previous.headYaw() - disabled.headYaw() <= 9);
    }

    @Test
    void deadZoneSuppressesTinyTargetJitterAndResetClearsTarget() {
        SmoothHeadTracking tracking = defaults();
        for (int i = 0; i < 40; i++) {
            SmoothHeadTracking.Pose pose = tracking.tick(0, 0, 6,
                    List.of(player(FIRST, 2, i % 2 == 0 ? 0.1 : -0.1, 0.1)), TICK);
            assertEquals(0, pose.headYaw(), 0);
            assertEquals(0, pose.headPitch(), 0);
        }
        tracking.reset(90, 10);
        SmoothHeadTracking.Pose reset = tracking.tick(90, 10, 6, List.of(), TICK);
        assertNull(reset.targetId());
        assertEquals(90, reset.bodyYaw(), 0);
        assertEquals(90, reset.headYaw(), 0);
        assertEquals(10, reset.headPitch(), 0);
    }

    @Test
    void malformedInputCannotPoisonAnExistingTrackingState() {
        SmoothHeadTracking tracking = defaults();
        tracking.tick(0, 0, 6, List.of(player(FIRST, 2, 40, 0)), TICK);
        assertThrows(IllegalArgumentException.class, () -> tracking.tick(Double.NaN, 0, 6, List.of(), TICK));
        assertThrows(IllegalArgumentException.class, () -> tracking.tick(0, 0, -1, List.of(), TICK));
        assertThrows(IllegalArgumentException.class, () -> tracking.tick(0, 0, 6, List.of(), Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> player(SECOND, 2, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new SmoothHeadTracking.Config(65, 35, 180, 120, 8, 0.25, 0, 0.75, 0.2, 1.15));
        assertThrows(IllegalArgumentException.class, () -> new SmoothHeadTracking.Config(65, 35, 180, 120, 8, 0.25, 0.8, 0.75, 0.2, 0.9));
        assertEquals(FIRST, tracking.tick(0, 0, 6, List.of(player(FIRST, 2, 40, 0)), TICK).targetId());
    }

    @Test
    void configurationReloadRetainsTargetAndMovesTowardNewLimitsWithoutReset() {
        SmoothHeadTracking tracking = defaults();
        SmoothHeadTracking.Pose previous = null;
        for (int i = 0; i < 30; i++) previous = tracking.tick(0, 0, 6, List.of(player(FIRST, 2, 60, 0)), TICK);
        tracking.reconfigure(new SmoothHeadTracking.Config(30, 35, 100, 120, 8, 0.25, 0.8, 0.75, 0.2, 1.15));
        SmoothHeadTracking.Pose next = tracking.tick(0, 0, 6, List.of(player(FIRST, 2, 60, 0)), TICK);
        assertEquals(FIRST, next.targetId());
        assertTrue(previous.headYaw() - next.headYaw() <= 5);
        assertTrue(next.headYaw() > 30, "the new head limit must not cause an immediate snap");
        for (int i = 0; i < 60; i++) next = tracking.tick(0, 0, 6, List.of(player(FIRST, 2, 60, 0)), TICK);
        assertEquals(30, next.headYaw(), 0.26);
    }

    private static SmoothHeadTracking defaults() {
        return new SmoothHeadTracking(SmoothHeadTracking.Config.defaults());
    }

    private static SmoothHeadTracking.Candidate player(UUID id, double distance, double yaw, double pitch) {
        return new SmoothHeadTracking.Candidate(id, distance * distance, yaw, pitch);
    }
}
