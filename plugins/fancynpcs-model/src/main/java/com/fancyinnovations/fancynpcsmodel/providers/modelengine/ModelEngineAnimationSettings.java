package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable animation settings. All blend durations are seconds, not ticks. */
public record ModelEngineAnimationSettings(
        boolean idleEnabled,
        DefaultStates defaultStates,
        List<LoopAnimation> loopAnimations,
        Motion gesture,
        Motion pose,
        Set<String> poseAnimations,
        Set<String> headTrackingPauseAnimations,
        boolean pauseRandomGesturesDuringPose,
        RandomGestures randomGestures) {

    public ModelEngineAnimationSettings {
        Objects.requireNonNull(defaultStates, "defaultStates");
        Objects.requireNonNull(gesture, "gesture");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(randomGestures, "randomGestures");
        loopAnimations = List.copyOf(loopAnimations);
        poseAnimations = Set.copyOf(poseAnimations);
        headTrackingPauseAnimations = Set.copyOf(headTrackingPauseAnimations);
        Set<Integer> priorities = new HashSet<>();
        for (LoopAnimation loop : loopAnimations) {
            if (!loop.enabled()) continue;
            if (loop.priority() == gesture.priority() || loop.priority() == pose.priority()
                    || !priorities.add(loop.priority())) {
                throw new IllegalArgumentException("Enabled ambient animation layers must have separate priorities from manual actions");
            }
        }
    }

    public static ModelEngineAnimationSettings defaults() {
        Blend regular = new Blend(0.05, 0.05, 1);
        return new ModelEngineAnimationSettings(true,
                new DefaultStates("idle", "walk", "jump", "death", regular),
                List.of(new LoopAnimation(true, "ribbon_sway", 1, regular),
                        new LoopAnimation(true, "tail_hair_sway", 2, regular),
                        new LoopAnimation(true, "blink", 3, regular)),
                new Motion(4, new Blend(0.05, 0.05, 1.35)), new Motion(5, regular),
                Set.of("crouch_idle", "sit", "sleep", "climb_idle"), Set.of("sleep", "nod", "talk", "wave"), true,
                new RandomGestures(false, List.of("smile", "nod"), 160, 400));
    }

    public record Blend(double inSeconds, double outSeconds, double speed) {
        public Blend {
            if (!Double.isFinite(inSeconds) || inSeconds < 0 || !Double.isFinite(outSeconds) || outSeconds < 0
                    || !Double.isFinite(speed) || speed <= 0) {
                throw new IllegalArgumentException("Animation blend durations must be finite and nonnegative; speed must be finite and positive");
            }
        }
    }

    public record DefaultStates(String idle, String walk, String jump, String death, Blend blend) {
        public DefaultStates {
            idle = name(idle);
            walk = name(walk);
            jump = name(jump);
            death = name(death);
            Objects.requireNonNull(blend, "blend");
        }
    }

    public record LoopAnimation(boolean enabled, String animation, int priority, Blend blend) {
        public LoopAnimation {
            animation = name(animation);
            positivePriority(priority);
            Objects.requireNonNull(blend, "blend");
        }
    }

    public record Motion(int priority, Blend blend) {
        public Motion {
            positivePriority(priority);
            Objects.requireNonNull(blend, "blend");
        }
    }

    public record RandomGestures(boolean enabled, List<String> animations, int minIntervalTicks, int maxIntervalTicks) {
        public RandomGestures {
            animations = animations.stream().map(ModelEngineAnimationSettings::name).distinct().toList();
            if (minIntervalTicks < 1 || maxIntervalTicks < minIntervalTicks || maxIntervalTicks == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Random gesture interval must be positive and max must be at least min");
            }
        }
    }

    private static String name(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Animation name must not be blank");
        return name.trim();
    }

    private static void positivePriority(int priority) {
        if (priority <= 0) throw new IllegalArgumentException("Custom animation priority must be positive; zero is reserved for default states");
    }
}
