package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import com.ticxo.modelengine.api.animation.BlueprintAnimation;
import com.ticxo.modelengine.api.animation.ModelState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises action ownership and scheduling without a Minecraft server or an ME singleton. */
class ModelEngineAnimationControllerTest {
    @Test void initialAmbientLayersStayIndependentAndHealthyLoopsNeverRestart() {
        FakeBackend backend = new FakeBackend();
        ModelEngineAnimationController controller = controller(backend, ModelEngineAnimationSettings.defaults());
        assertEquals(Map.of(ModelState.IDLE, "idle", ModelState.WALK, "walk", ModelState.JUMP, "jump", ModelState.DEATH, "death"), backend.defaults);
        assertEquals(List.of("ribbon_sway", "tail_hair_sway", "blink"), backend.plays.stream().map(track -> track.name).toList());
        assertTrue(backend.plays.stream().allMatch(track -> !track.force && track.loop == BlueprintAnimation.LoopMode.LOOP));
        for (int tick = 0; tick < 1000; tick++) controller.update(tick);
        assertEquals(3, backend.plays.size(), "Automatic default idle must not be played as an extra manual loop");
        assertEquals(0, backend.stops.size());
    }

    @Test void onceGestureUsesOnceEvenForAHoldingBlueprintAndReturnsToExistingDefault() {
        FakeBackend backend = new FakeBackend();
        ModelEngineAnimationController controller = controller(backend, ModelEngineAnimationSettings.defaults());
        assertTrue(controller.play("wave", false));
        FakeTrack wave = backend.layers.get(4);
        assertEquals(BlueprintAnimation.LoopMode.ONCE, wave.loop);
        assertEquals(0.05, wave.motion.blend().inSeconds());
        assertTrue(controller.isHeadTrackingPaused());
        wave.ended = true;
        controller.update(1);
        assertFalse(controller.isHeadTrackingPaused());
        assertEquals("idle", backend.defaults.get(ModelState.IDLE));
        assertEquals(3, backend.layers.values().stream().filter(track -> track.priority <= 3).count());
        assertEquals(0, backend.plays.stream().filter(track -> track.name.equals("idle")).count());
    }

    @Test void sameNameReplayCannotStopTheNewPropertyAndCancelledReplacementKeepsOldPose() {
        FakeBackend backend = new FakeBackend();
        ModelEngineAnimationController controller = controller(backend, ModelEngineAnimationSettings.defaults());
        assertTrue(controller.play("wave", false));
        FakeTrack first = backend.layers.get(4);
        assertTrue(controller.play("wave", false));
        FakeTrack replacement = backend.layers.get(4);
        assertNotSame(first, replacement);
        assertFalse(replacement.ended);
        assertTrue(controller.play("sleep", true));
        FakeTrack sleep = backend.layers.get(5);
        backend.reject.add("nod");
        assertFalse(controller.play("nod", false));
        assertSame(sleep, backend.layers.get(5));
        assertTrue(controller.isPoseHeld());
        assertTrue(controller.isHeadTrackingPaused());
        assertTrue(backend.stops.stream().noneMatch(track -> track.priority <= 3));
    }

    @Test void idleWithOrWithoutLoopClearsHeldPoseAndDoesNotDuplicateAutomaticIdle() {
        for (boolean idleLoop : List.of(false, true)) {
            FakeBackend backend = new FakeBackend();
            ModelEngineAnimationController controller = controller(backend, ModelEngineAnimationSettings.defaults());
            assertTrue(controller.play("sit", true));
            assertEquals(BlueprintAnimation.LoopMode.HOLD, backend.layers.get(5).loop);
            assertTrue(controller.isPoseHeld());
            assertTrue(controller.play("idle", idleLoop));
            assertFalse(controller.isPoseHeld());
            assertFalse(backend.layers.containsKey(5));
            assertEquals(0, backend.plays.stream().filter(track -> track.name.equals("idle")).count());
            assertEquals(3, backend.layers.size());
        }
    }

    @Test void optionalRandomGesturesRespectPauseAndCanOverlayPoseWithoutCancellingIt() {
        for (boolean pauseDuringPose : List.of(false, true)) {
            FakeBackend backend = new FakeBackend();
            ModelEngineAnimationController controller = controller(backend, randomSettings(pauseDuringPose));
            controller.play("sleep", true);
            FakeTrack sleep = backend.layers.get(5);
            controller.update(0);
            controller.update(4);
            assertFalse(backend.layers.containsKey(4));
            controller.update(5);
            assertSame(sleep, backend.layers.get(5));
            assertEquals(!pauseDuringPose, backend.layers.containsKey(4));
            if (!pauseDuringPose) assertEquals("nod", backend.layers.get(4).name);
        }
    }

    @Test void disablingAmbientLeavesDefaultStatesAndManualCommandsAvailable() {
        FakeBackend backend = new FakeBackend();
        ModelEngineAnimationSettings defaults = ModelEngineAnimationSettings.defaults();
        ModelEngineAnimationController controller = controller(backend, defaults);
        controller.reconfigure(new ModelEngineAnimationSettings(false, defaults.defaultStates(), defaults.loopAnimations(),
                defaults.gesture(), defaults.pose(), defaults.poseAnimations(), defaults.headTrackingPauseAnimations(), true, defaults.randomGestures()));
        assertTrue(backend.layers.isEmpty());
        assertEquals("idle", backend.defaults.get(ModelState.IDLE));
        assertTrue(controller.play("wave", false));
        controller.update(1000);
        assertEquals(Set.of(4), backend.layers.keySet());
    }

    @Test void missingOptionalLoopsAreSkippedAndClosePreservesExternalProperties() {
        FakeBackend backend = new FakeBackend();
        backend.names.remove("blink");
        ModelEngineAnimationController controller = controller(backend, ModelEngineAnimationSettings.defaults());
        assertEquals(Set.of(1, 2), backend.layers.keySet());
        FakeTrack external = new FakeTrack("external", new ModelEngineAnimationSettings.Motion(1,
                new ModelEngineAnimationSettings.Blend(0, 0, 1)), BlueprintAnimation.LoopMode.LOOP, true);
        backend.layers.put(1, external);
        controller.close();
        assertEquals(Map.of(1, external), backend.layers);
        assertFalse(controller.play("wave", false));
        controller.close();
        assertEquals(1, backend.defaultsRestored);
    }

    @Test void invalidBlendAndCollidingLayersAreRejectedBeforePlayingAnything() {
        assertThrows(IllegalArgumentException.class, () -> new ModelEngineAnimationSettings.Blend(Double.NaN, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ModelEngineAnimationSettings.Blend(0, 0, 0));
        ModelEngineAnimationSettings defaults = ModelEngineAnimationSettings.defaults();
        assertThrows(IllegalArgumentException.class, () -> new ModelEngineAnimationSettings(true, defaults.defaultStates(),
                List.of(new ModelEngineAnimationSettings.LoopAnimation(true, "blink", 4, defaults.gesture().blend())),
                defaults.gesture(), defaults.pose(), defaults.poseAnimations(), defaults.headTrackingPauseAnimations(), true, defaults.randomGestures()));
    }

    @Test void sameSettingsAppliedEveryTickPreserveHeldPoseAndAmbientTimelines() {
        FakeBackend backend = new FakeBackend();
        ModelEngineAnimationSettings settings = ModelEngineAnimationSettings.defaults();
        ModelEngineAnimationController controller = controller(backend, settings);
        controller.play("sleep", true);
        FakeTrack sleep = backend.layers.get(5);
        for (int tick = 0; tick < 100; tick++) {
            controller.reconfigure(ModelEngineAnimationSettings.defaults());
            controller.update(tick);
        }
        assertSame(sleep, backend.layers.get(5));
        assertEquals(4, backend.plays.size());
        assertTrue(backend.stops.isEmpty());
        assertTrue(controller.isPoseHeld());
    }

    private static ModelEngineAnimationController controller(FakeBackend backend, ModelEngineAnimationSettings settings) {
        return new ModelEngineAnimationController(backend, settings, new Random(7));
    }

    private static ModelEngineAnimationSettings randomSettings(boolean pause) {
        ModelEngineAnimationSettings defaults = ModelEngineAnimationSettings.defaults();
        return new ModelEngineAnimationSettings(true, defaults.defaultStates(), defaults.loopAnimations(), defaults.gesture(), defaults.pose(),
                defaults.poseAnimations(), defaults.headTrackingPauseAnimations(), pause,
                new ModelEngineAnimationSettings.RandomGestures(true, List.of("missing", "nod"), 5, 5));
    }

    private static final class FakeBackend implements ModelEngineAnimationController.Backend {
        final Set<String> names = new HashSet<>(List.of("idle", "walk", "jump", "death", "ribbon_sway", "tail_hair_sway", "blink", "wave", "nod", "talk", "smile", "sit", "sleep"));
        final Set<String> reject = new HashSet<>();
        final Map<Integer, FakeTrack> layers = new HashMap<>();
        final Map<ModelState, String> defaults = new EnumMap<>(ModelState.class);
        final List<FakeTrack> plays = new ArrayList<>();
        final List<FakeTrack> stops = new ArrayList<>();
        int defaultsRestored;

        @Override public boolean hasAnimation(String name) { return names.contains(name); }
        @Override public void setDefault(ModelState state, String name, ModelEngineAnimationSettings.Blend blend) { defaults.put(state, name); }
        @Override public ModelEngineAnimationController.Track play(String name, ModelEngineAnimationSettings.Motion motion, BlueprintAnimation.LoopMode loop, boolean force) {
            if (reject.contains(name) || (!force && layers.containsKey(motion.priority()))) return null;
            FakeTrack track = new FakeTrack(name, motion, loop, force);
            layers.put(motion.priority(), track);
            plays.add(track);
            return track;
        }
        @Override public boolean isCurrent(ModelEngineAnimationController.Track track) {
            FakeTrack fake = (FakeTrack) track;
            return layers.get(fake.priority) == fake;
        }
        @Override public void stop(ModelEngineAnimationController.Track track, boolean immediate) {
            FakeTrack fake = (FakeTrack) track;
            if (!isCurrent(fake)) return;
            layers.remove(fake.priority);
            fake.ended = true;
            stops.add(fake);
        }
        @Override public void restoreDefaults() { defaultsRestored++; defaults.clear(); }
    }

    private static final class FakeTrack implements ModelEngineAnimationController.Track {
        final String name;
        final int priority;
        final ModelEngineAnimationSettings.Motion motion;
        final BlueprintAnimation.LoopMode loop;
        final boolean force;
        boolean ended;
        FakeTrack(String name, ModelEngineAnimationSettings.Motion motion, BlueprintAnimation.LoopMode loop, boolean force) {
            this.name = name; this.priority = motion.priority(); this.motion = motion; this.loop = loop; this.force = force;
        }
        @Override public String animation() { return name; }
        @Override public BlueprintAnimation.LoopMode loopMode() { return loop; }
        @Override public boolean isEnded() { return ended; }
    }
}
