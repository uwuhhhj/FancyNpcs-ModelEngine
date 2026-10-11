package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-NPC, thread-confined head tracking policy. The caller supplies visible
 * players, including the current target just outside the acquisition range.
 * It publishes the returned immutable pose to ModelEngine separately.
 * Angles use Bukkit's yaw/pitch convention, in degrees; time uses seconds.
 */
public final class SmoothHeadTracking {
    private Config config;
    private boolean initialized;
    private double bodyYaw;
    private double placementYaw;
    private double headYawOffset;
    private double headPitch;
    private UUID targetId;
    private double targetHoldSeconds;
    private double missingSeconds;
    private double desiredYaw;
    private double desiredPitch;
    private boolean targetObserved;
    private boolean followingWithBody;
    private double bodyDelaySeconds;

    public SmoothHeadTracking(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Keep the pose and selected player across reload; new limits converge smoothly. */
    public void reconfigure(Config config) {
        Objects.requireNonNull(config, "config");
        if (!this.config.bodyFollow.equals(config.bodyFollow)) {
            resetBodyFollow();
        }
        this.config = config;
    }

    /** Reset on model creation or an intentional teleport/orientation reset. */
    public void reset(double bodyYaw, double neutralPitch) {
        requireFinite(bodyYaw, "bodyYaw");
        requirePitch(neutralPitch, "neutralPitch");
        this.bodyYaw = wrapDegrees(bodyYaw);
        this.placementYaw = this.bodyYaw;
        this.headYawOffset = 0;
        this.headPitch = clamp(neutralPitch, -this.config.maxPitchDegrees, this.config.maxPitchDegrees);
        this.targetId = null;
        this.targetHoldSeconds = 0;
        this.missingSeconds = 0;
        this.desiredYaw = this.bodyYaw;
        this.desiredPitch = this.headPitch;
        this.targetObserved = false;
        resetBodyFollow();
        this.initialized = true;
    }

    /**
     * Select a stable target and advance one sample. A range of zero disables
     * tracking and smoothly returns the head to the body's current direction.
     * An unchanged placement yaw must not undo the body's tracking rotation.
     * Lag spikes cannot move the head by more than 250 ms of configured speed.
     */
    public Pose tick(double bodyYaw, double neutralPitch, double trackingRange,
                     Collection<Candidate> candidates, double elapsedSeconds) {
        return tick(bodyYaw, neutralPitch, trackingRange, candidates, elapsedSeconds, true);
    }

    /** Held poses can allow head tracking while pausing whole-body rotation. */
    public Pose tick(double bodyYaw, double neutralPitch, double trackingRange,
                     Collection<Candidate> candidates, double elapsedSeconds, boolean allowBodyFollow) {
        return tick(bodyYaw, neutralPitch, trackingRange, candidates, elapsedSeconds, allowBodyFollow, true);
    }

    /** Advance smoothing every tick; only sample players on a slow reconciliation tick. */
    public Pose tick(double bodyYaw, double neutralPitch, double trackingRange,
                     Collection<Candidate> candidates, double elapsedSeconds, boolean allowBodyFollow, boolean refreshTarget) {
        requireFinite(bodyYaw, "bodyYaw");
        requirePitch(neutralPitch, "neutralPitch");
        requireFinite(trackingRange, "trackingRange");
        if (trackingRange < 0) {
            throw new IllegalArgumentException("trackingRange must be nonnegative");
        }
        requireFinite(elapsedSeconds, "elapsedSeconds");
        if (elapsedSeconds <= 0) {
            throw new IllegalArgumentException("elapsedSeconds must be positive");
        }
        Objects.requireNonNull(candidates, "candidates");
        // Reject bad samples before changing state, including on an empty range.
        if (refreshTarget) {
            for (Candidate candidate : candidates) {
                Objects.requireNonNull(candidate, "candidate");
            }
        }
        if (!this.initialized) {
            reset(bodyYaw, neutralPitch);
        }
        if (Math.abs(wrapDegrees(bodyYaw - this.placementYaw)) > 0.000001) {
            // Only an actual placement-orientation change resets the body.
            // The model's live rotation never writes back to NPC save data.
            this.bodyYaw += wrapDegrees(bodyYaw - this.bodyYaw);
            this.placementYaw = wrapDegrees(bodyYaw);
            resetBodyFollow();
        }

        if (trackingRange == 0) {
            clearTarget();
        } else if (refreshTarget) {
            selectTarget(trackingRange, candidates, elapsedSeconds);
        } else if (this.targetId != null) {
            // Hold/grace durations still advance in real server ticks, rather
            // than becoming 20 times longer when player sampling is throttled.
            if (this.targetObserved) {
                this.targetHoldSeconds += elapsedSeconds;
            } else {
                this.missingSeconds += elapsedSeconds;
                if (this.missingSeconds >= this.config.lostTargetGraceSeconds) {
                    clearTarget();
                }
            }
        }

        double wantedOffset = this.targetId == null ? 0 : desiredOffset();
        double wantedPitch = clamp(this.targetId == null ? neutralPitch : this.desiredPitch,
                -this.config.maxPitchDegrees, this.config.maxPitchDegrees);
        double stepSeconds = Math.min(elapsedSeconds, 0.25);
        this.headYawOffset = approach(this.headYawOffset, wantedOffset, this.config.yawSpeedDegreesPerSecond, stepSeconds);
        this.headPitch = approach(this.headPitch, wantedPitch, this.config.pitchSpeedDegreesPerSecond, stepSeconds);
        followWithBody(allowBodyFollow, stepSeconds);
        return new Pose(this.bodyYaw, this.bodyYaw + this.headYawOffset, this.headPitch, this.targetId);
    }

    private void followWithBody(boolean allowed, double seconds) {
        BodyFollow follow = this.config.bodyFollow;
        if (!follow.enabled || !allowed || !this.targetObserved || this.targetId == null) {
            resetBodyFollow();
            return;
        }
        if (!this.followingWithBody) {
            if (Math.abs(this.headYawOffset) < follow.startAngleDegrees - this.config.deadZoneDegrees) {
                this.bodyDelaySeconds = 0;
                return;
            }
            this.bodyDelaySeconds += seconds;
            if (this.bodyDelaySeconds + 1e-9 < follow.delaySeconds) {
                return;
            }
            this.followingWithBody = true;
        }
        if (Math.abs(this.headYawOffset) <= follow.stopAngleDegrees) {
            resetBodyFollow();
            return;
        }
        // Chase the head's actual direction, so even a very fast configured
        // body cannot overtake it. Preserve world-space head yaw while the
        // shoulders catch up; adding body rotation to the head would twitch.
        double step = this.headYawOffset * -Math.expm1(-follow.responsePerSecond * seconds);
        double rotation = clamp(step, -follow.yawSpeedDegreesPerSecond * seconds,
                follow.yawSpeedDegreesPerSecond * seconds);
        this.bodyYaw += rotation;
        this.headYawOffset -= rotation;
    }

    private void resetBodyFollow() {
        this.followingWithBody = false;
        this.bodyDelaySeconds = 0;
    }

    private double desiredOffset() {
        double relative = wrapDegrees(this.desiredYaw - this.bodyYaw);
        // Behind the NPC, +179 and -179 must not alternate the side of its
        // neck. Keep the previous side until the player returns to the front.
        if (Math.abs(relative) > Math.max(120, this.config.maxYawDegrees)
                && Math.abs(this.headYawOffset) > this.config.deadZoneDegrees) {
            relative = Math.copySign(Math.abs(relative), this.headYawOffset);
        }
        return clamp(relative, -this.config.maxYawDegrees, this.config.maxYawDegrees);
    }

    private void selectTarget(double range, Collection<Candidate> candidates, double elapsedSeconds) {
        this.targetObserved = false;
        Candidate current = null;
        Candidate nearest = null;
        double acquisitionSquared = range * range;
        double exitRange = range * this.config.exitRangeMultiplier;
        double exitSquared = exitRange * exitRange;
        for (Candidate candidate : candidates) {
            if (candidate.targetId.equals(this.targetId) && candidate.distanceSquared <= exitSquared) {
                current = candidate;
            }
            if (candidate.distanceSquared <= acquisitionSquared && (nearest == null
                    || candidate.distanceSquared < nearest.distanceSquared
                    || (candidate.distanceSquared == nearest.distanceSquared
                    && candidate.targetId.compareTo(nearest.targetId) < 0))) {
                nearest = candidate;
            }
        }

        if (current != null) {
            this.targetObserved = true;
            this.missingSeconds = 0;
            this.targetHoldSeconds += elapsedSeconds;
            double ratioSquared = this.config.switchDistanceRatio * this.config.switchDistanceRatio;
            if (nearest != null && !nearest.targetId.equals(this.targetId)
                    && this.targetHoldSeconds >= this.config.minimumTargetHoldSeconds
                    && nearest.distanceSquared < current.distanceSquared * ratioSquared) {
                acquire(nearest);
            } else {
                this.desiredYaw = current.yaw;
                this.desiredPitch = current.pitch;
            }
            return;
        }
        if (this.targetId != null) {
            this.missingSeconds += elapsedSeconds;
            if (this.missingSeconds < this.config.lostTargetGraceSeconds) {
                return;
            }
            clearTarget();
        }
        if (nearest != null) {
            acquire(nearest);
        }
    }

    private void acquire(Candidate candidate) {
        this.targetId = candidate.targetId;
        this.desiredYaw = candidate.yaw;
        this.desiredPitch = candidate.pitch;
        this.targetHoldSeconds = 0;
        this.missingSeconds = 0;
        this.targetObserved = true;
        resetBodyFollow();
    }

    private void clearTarget() {
        this.targetId = null;
        this.targetHoldSeconds = 0;
        this.missingSeconds = 0;
        this.targetObserved = false;
        resetBodyFollow();
    }

    private double approach(double current, double desired, double speed, double seconds) {
        double difference = desired - current;
        if (Math.abs(difference) <= this.config.deadZoneDegrees) {
            return current;
        }
        double step = difference * -Math.expm1(-this.config.responsePerSecond * seconds);
        return current + clamp(step, -speed * seconds, speed * seconds);
    }

    static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped >= 180) {
            wrapped -= 360;
        }
        if (wrapped < -180) {
            wrapped += 360;
        }
        return wrapped;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requirePitch(double value, String name) {
        requireFinite(value, name);
        if (value < -90 || value > 90) {
            throw new IllegalArgumentException(name + " must be between -90 and 90");
        }
    }

    public record Candidate(UUID targetId, double distanceSquared, double yaw, double pitch) {
        public Candidate {
            Objects.requireNonNull(targetId, "targetId");
            requireFinite(distanceSquared, "distanceSquared");
            if (distanceSquared < 0) {
                throw new IllegalArgumentException("distanceSquared must be nonnegative");
            }
            requireFinite(yaw, "yaw");
            requirePitch(pitch, "pitch");
        }
    }

    /** Continuous yaw values avoid a numeric +180/-180 discontinuity. */
    public record Pose(double bodyYaw, double headYaw, double headPitch, UUID targetId) {
    }

    public record Config(double maxYawDegrees, double maxPitchDegrees,
                         double yawSpeedDegreesPerSecond, double pitchSpeedDegreesPerSecond,
                         double responsePerSecond, double deadZoneDegrees,
                         double switchDistanceRatio, double minimumTargetHoldSeconds,
                         double lostTargetGraceSeconds, double exitRangeMultiplier, BodyFollow bodyFollow) {
        public Config {
            Objects.requireNonNull(bodyFollow, "bodyFollow");
            positive(maxYawDegrees, "maxYawDegrees");
            if (maxYawDegrees >= 180) {
                throw new IllegalArgumentException("maxYawDegrees must be less than 180");
            }
            positive(maxPitchDegrees, "maxPitchDegrees");
            if (maxPitchDegrees > 90) {
                throw new IllegalArgumentException("maxPitchDegrees must not exceed 90");
            }
            positive(yawSpeedDegreesPerSecond, "yawSpeedDegreesPerSecond");
            positive(pitchSpeedDegreesPerSecond, "pitchSpeedDegreesPerSecond");
            positive(responsePerSecond, "responsePerSecond");
            nonnegative(deadZoneDegrees, "deadZoneDegrees");
            if (deadZoneDegrees >= Math.min(maxYawDegrees, maxPitchDegrees)) {
                throw new IllegalArgumentException("deadZoneDegrees must be smaller than head limits");
            }
            positive(switchDistanceRatio, "switchDistanceRatio");
            if (switchDistanceRatio > 1) {
                throw new IllegalArgumentException("switchDistanceRatio must not exceed 1");
            }
            nonnegative(minimumTargetHoldSeconds, "minimumTargetHoldSeconds");
            nonnegative(lostTargetGraceSeconds, "lostTargetGraceSeconds");
            requireFinite(exitRangeMultiplier, "exitRangeMultiplier");
            if (exitRangeMultiplier < 1) {
                throw new IllegalArgumentException("exitRangeMultiplier must be at least 1");
            }
            if (bodyFollow.enabled && bodyFollow.startAngleDegrees > maxYawDegrees) {
                throw new IllegalArgumentException("Body start angle must not exceed the head yaw limit");
            }
        }

        public static Config defaults() {
            return new Config(65, 35, 180, 120, 8, 0.25, 0.8, 0.75, 0.2, 1.15, BodyFollow.defaults());
        }

        public Config withBodyFollow(BodyFollow follow) {
            return new Config(this.maxYawDegrees, this.maxPitchDegrees, this.yawSpeedDegreesPerSecond,
                    this.pitchSpeedDegreesPerSecond, this.responsePerSecond, this.deadZoneDegrees,
                    this.switchDistanceRatio, this.minimumTargetHoldSeconds, this.lostTargetGraceSeconds,
                    this.exitRangeMultiplier, follow);
        }

        private static void positive(double value, String name) {
            requireFinite(value, name);
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }

        private static void nonnegative(double value, String name) {
            requireFinite(value, name);
            if (value < 0) {
                throw new IllegalArgumentException(name + " must be nonnegative");
            }
        }
    }

    public record BodyFollow(boolean enabled, double startAngleDegrees, double stopAngleDegrees,
                             double delaySeconds, double yawSpeedDegreesPerSecond,
                             double responsePerSecond, boolean pauseDuringPose) {
        public BodyFollow {
            Config.positive(startAngleDegrees, "body startAngleDegrees");
            if (startAngleDegrees >= 180) {
                throw new IllegalArgumentException("Body start angle must be less than 180");
            }
            Config.nonnegative(stopAngleDegrees, "body stopAngleDegrees");
            if (stopAngleDegrees >= startAngleDegrees) {
                throw new IllegalArgumentException("Body stop angle must be less than start angle");
            }
            Config.nonnegative(delaySeconds, "body delaySeconds");
            Config.positive(yawSpeedDegreesPerSecond, "body yawSpeedDegreesPerSecond");
            Config.positive(responsePerSecond, "body responsePerSecond");
        }

        public static BodyFollow defaults() {
            return new BodyFollow(true, 35, 2, 0.3, 90, 5, true);
        }

        public static BodyFollow disabled() {
            return new BodyFollow(false, 35, 2, 0.3, 90, 5, true);
        }
    }
}
