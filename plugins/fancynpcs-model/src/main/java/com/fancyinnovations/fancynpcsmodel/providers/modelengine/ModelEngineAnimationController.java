package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import com.ticxo.modelengine.api.animation.BlueprintAnimation;
import com.ticxo.modelengine.api.animation.ModelState;
import com.ticxo.modelengine.api.animation.handler.AnimationHandler;
import com.ticxo.modelengine.api.animation.handler.IStateMachineHandler;
import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
import com.ticxo.modelengine.api.animation.property.SimpleProperty;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.utils.config.ConfigProperty;
import com.ticxo.modelengine.core.animation.handler.StateMachineHandler;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Owns ambient and manual animation layers, while ME owns the default state machine.
 * Call on Paper's main thread. R4.1.1 also updates its state machines on a worker,
 * so reads and removals use the same monitor as that exact ME implementation.
 */
public final class ModelEngineAnimationController implements AutoCloseable {
    private final Backend backend;
    private final RandomGenerator random;
    private final Map<Integer, Track> ambient = new LinkedHashMap<>();
    private ModelEngineAnimationSettings settings;
    private Track manualPose;
    private Track manualGesture;
    private boolean closed;
    private boolean randomScheduled;
    private int nextRandomTick;
    private boolean repairScheduled;
    private int nextRepairTick;

    public ModelEngineAnimationController(ActiveModel model, ModelEngineAnimationSettings settings) {
        this(new EngineBackend(model), settings, ThreadLocalRandom.current());
    }

    // The policy can be tested without starting Bukkit or constructing ME's singleton.
    ModelEngineAnimationController(Backend backend, ModelEngineAnimationSettings settings, RandomGenerator random) {
        this.backend = Objects.requireNonNull(backend);
        this.random = Objects.requireNonNull(random);
        this.settings = Objects.requireNonNull(settings);
        configureDefaults();
        repairAmbient();
    }

    public void update(int tick) {
        if (this.closed) {
            return;
        }
        if (finished(this.manualGesture)) {
            this.manualGesture = null;
        }
        if (finished(this.manualPose)) {
            this.manualPose = null;
        }
        // Repair a loop removed by an ME reload/external command without
        // restarting healthy loops or firing a play event every server tick.
        if (!this.repairScheduled || tick - this.nextRepairTick >= 0) {
            this.repairScheduled = true;
            this.nextRepairTick = tick + 20;
            repairAmbient();
        }
        if (!this.settings.idleEnabled() || !this.settings.randomGestures().enabled()) {
            return;
        }
        if (!this.randomScheduled) {
            scheduleRandom(tick);
            return;
        }
        if (tick - this.nextRandomTick < 0) {
            return;
        }
        scheduleRandom(tick);
        if (this.manualGesture != null || (this.manualPose != null && this.settings.pauseRandomGesturesDuringPose())) {
            return;
        }
        List<String> available = this.settings.randomGestures().animations().stream()
                .filter(this.backend::hasAnimation).filter(name -> !name.equals(this.settings.defaultStates().idle()))
                .filter(name -> !this.settings.poseAnimations().contains(name)).toList();
        if (available.isEmpty()) {
            return;
        }
        // Random gestures may coexist with a held pose when explicitly allowed;
        // an explicit /npc play_animation request instead replaces that pose.
        String animation = available.get(this.random.nextInt(available.size()));
        this.manualGesture = this.backend.play(animation, this.settings.gesture(), BlueprintAnimation.LoopMode.ONCE, true);
    }

    /**
     * Returns false for a missing/cancelled animation, preserving existing actions.
     * Playing configured idle is a reset; it never creates a duplicate idle layer.
     * For configured poses --loop holds the last frame, matching the MM HOLD mode.
     */
    public boolean play(String animation, boolean loop) {
        if (this.closed || animation == null || !this.backend.hasAnimation(animation)) {
            return false;
        }
        if (animation.equals(this.settings.defaultStates().idle())) {
            stopManual(false);
            return true;
        }
        boolean pose = this.settings.poseAnimations().contains(animation);
        ModelEngineAnimationSettings.Motion motion = pose ? this.settings.pose() : this.settings.gesture();
        BlueprintAnimation.LoopMode mode = loop
                ? (pose ? BlueprintAnimation.LoopMode.HOLD : BlueprintAnimation.LoopMode.LOOP)
                : BlueprintAnimation.LoopMode.ONCE;
        Track replacement = this.backend.play(animation, motion, mode, true);
        if (replacement == null) {
            return false;
        }
        // Accept first, then remove only the old properties we actually own.
        // The backend's identity check protects same-name, same-layer replays.
        stopManual(false);
        if (pose) {
            this.manualPose = replacement;
        } else {
            this.manualGesture = replacement;
        }
        return true;
    }

    public boolean isPoseHeld() {
        return !this.closed && this.manualPose != null && !finished(this.manualPose)
                && this.manualPose.loopMode() != BlueprintAnimation.LoopMode.ONCE;
    }

    public boolean isPoseActive() {
        return !this.closed && this.manualPose != null && !finished(this.manualPose);
    }

    public boolean isHeadTrackingPaused() {
        return !this.closed && (pausesHead(this.manualPose) || pausesHead(this.manualGesture));
    }

    public void reconfigure(ModelEngineAnimationSettings settings) {
        if (this.closed) {
            throw new IllegalStateException("Animation controller is closed");
        }
        Objects.requireNonNull(settings);
        if (this.settings.equals(settings)) {
            return;
        }
        clearOwned();
        this.backend.restoreDefaults();
        this.settings = settings;
        this.randomScheduled = false;
        this.repairScheduled = false;
        configureDefaults();
        repairAmbient();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            clearOwned();
        } finally {
            this.backend.restoreDefaults();
        }
    }

    private boolean pausesHead(Track track) {
        return track != null && !finished(track) && this.settings.headTrackingPauseAnimations().contains(track.animation());
    }

    private boolean finished(Track track) {
        return track == null || !this.backend.isCurrent(track) || track.isEnded();
    }

    private void configureDefaults() {
        ModelEngineAnimationSettings.DefaultStates defaults = this.settings.defaultStates();
        setDefault(ModelState.IDLE, defaults.idle(), defaults.blend());
        setDefault(ModelState.WALK, defaults.walk(), defaults.blend());
        setDefault(ModelState.JUMP, defaults.jump(), defaults.blend());
        setDefault(ModelState.DEATH, defaults.death(), defaults.blend());
    }

    private void setDefault(ModelState state, String name, ModelEngineAnimationSettings.Blend blend) {
        if (this.backend.hasAnimation(name)) {
            this.backend.setDefault(state, name, blend);
        }
    }

    private void repairAmbient() {
        if (!this.settings.idleEnabled()) {
            return;
        }
        for (ModelEngineAnimationSettings.LoopAnimation loop : this.settings.loopAnimations()) {
            if (!loop.enabled() || !this.backend.hasAnimation(loop.animation())) {
                continue;
            }
            Track existing = this.ambient.get(loop.priority());
            if (!finished(existing)) {
                continue;
            }
            Track started = this.backend.play(loop.animation(), new ModelEngineAnimationSettings.Motion(loop.priority(), loop.blend()),
                    BlueprintAnimation.LoopMode.LOOP, false);
            if (started != null) {
                this.ambient.put(loop.priority(), started);
            }
        }
    }

    private void scheduleRandom(int tick) {
        ModelEngineAnimationSettings.RandomGestures randomSettings = this.settings.randomGestures();
        int interval = this.random.nextInt(randomSettings.minIntervalTicks(), randomSettings.maxIntervalTicks() + 1);
        this.nextRandomTick = tick + interval;
        this.randomScheduled = true;
    }

    private void stopManual(boolean immediate) {
        Track pose = this.manualPose;
        Track gesture = this.manualGesture;
        this.manualPose = null;
        this.manualGesture = null;
        try {
            if (pose != null) {
                this.backend.stop(pose, immediate);
            }
        } finally {
            if (gesture != null) {
                this.backend.stop(gesture, immediate);
            }
        }
    }

    private void clearOwned() {
        List<Track> tracks = new ArrayList<>(this.ambient.values());
        if (this.manualPose != null) {
            tracks.add(this.manualPose);
        }
        if (this.manualGesture != null) {
            tracks.add(this.manualGesture);
        }
        this.ambient.clear();
        this.manualPose = null;
        this.manualGesture = null;
        RuntimeException failure = null;
        for (Track track : tracks) {
            try {
                this.backend.stop(track, true);
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    interface Track {
        String animation();

        BlueprintAnimation.LoopMode loopMode();

        boolean isEnded();
    }

    interface Backend {
        boolean hasAnimation(String name);

        void setDefault(ModelState state, String animation, ModelEngineAnimationSettings.Blend blend);

        Track play(String animation, ModelEngineAnimationSettings.Motion motion, BlueprintAnimation.LoopMode loop, boolean force);

        boolean isCurrent(Track track);

        void stop(Track track, boolean immediate);

        void restoreDefaults();
    }

    private static final class EngineBackend implements Backend {
        private final ActiveModel model;
        private final IStateMachineHandler handler;
        private final Object monitor;
        private final Map<ModelState, AnimationHandler.DefaultProperty> originalDefaults = new EnumMap<>(ModelState.class);
        private final Map<ModelState, AnimationHandler.DefaultProperty> ownedDefaults = new EnumMap<>(ModelState.class);

        EngineBackend(ActiveModel model) {
            this.model = Objects.requireNonNull(model);
            if (!(model.getAnimationHandler() instanceof IStateMachineHandler stateMachine)) {
                throw new IllegalArgumentException("Layered NPC animations require ModelEngine's state_machine handler (usm=true)");
            }
            this.handler = stateMachine;
            this.monitor = this.handler instanceof StateMachineHandler implementation ? implementation.getStateMachines() : this.handler;
        }

        @Override
        public boolean hasAnimation(String name) {
            return this.model.getBlueprint().getAnimations().containsKey(name);
        }

        @Override
        public void setDefault(ModelState state, String animation, ModelEngineAnimationSettings.Blend blend) {
            synchronized (this.monitor) {
                AnimationHandler.DefaultProperty previous = this.handler.getDefaultProperty(state);
                this.originalDefaults.putIfAbsent(state, previous);
                AnimationHandler.DefaultProperty property = new AnimationHandler.DefaultProperty(state, animation,
                        blend.inSeconds(), blend.outSeconds(), blend.speed());
                this.handler.setDefaultProperty(property);
                this.ownedDefaults.put(state, property);
                this.handler.refreshState(previous == null ? property : previous);
            }
        }

        @Override
        public Track play(String animation, ModelEngineAnimationSettings.Motion motion, BlueprintAnimation.LoopMode loop, boolean force) {
            if (motion.priority() == ConfigProperty.DEFAULT_PRIORITY.getInt()) {
                throw new IllegalArgumentException("Custom animation priority collides with ModelEngine Default-Animation-Priority");
            }
            BlueprintAnimation blueprint = this.model.getBlueprint().getAnimations().get(animation);
            if (blueprint == null) {
                return null;
            }
            ModelEngineAnimationSettings.Blend blend = motion.blend();
            SimpleProperty property = new SimpleProperty(this.model, blueprint, blend.inSeconds(), blend.outSeconds(), blend.speed());
            // Configure before queueing; ME may materialize it on its worker.
            property.setForceLoopMode(loop);
            return this.handler.playAnimation(motion.priority(), property, force)
                    ? new EngineTrack(animation, motion.priority(), loop, property) : null;
        }

        @Override
        public boolean isCurrent(Track track) {
            synchronized (this.monitor) {
                return current((EngineTrack) track);
            }
        }

        private boolean current(EngineTrack track) {
            if (this.handler instanceof StateMachineHandler implementation) {
                StateMachineHandler.AnimationStateMachine machine = implementation.getStateMachines().get(track.priority());
                // getAnimation deliberately hides LERPOUT in ME; effective
                // property identity keeps head tracking paused until blend ends.
                return machine != null && machine.getEffectiveAnimation() == track.property();
            }
            return this.handler.getAnimation(track.priority(), this.model.getBlueprint(), track.animation()) == track.property();
        }

        @Override
        public void stop(Track owned, boolean immediate) {
            EngineTrack track = (EngineTrack) owned;
            synchronized (this.monitor) {
                if (!current(track)) {
                    // A newly queued replacement may hide the old current
                    // property. Stop that exact property directly: a by-name
                    // removal here could cancel the new same-name request.
                    if (this.handler instanceof StateMachineHandler implementation) {
                        StateMachineHandler.AnimationStateMachine machine = implementation.getStateMachines().get(track.priority());
                        if (machine != null && machine.getCurrentAnimation() == track.property()) {
                            track.property().stop();
                        }
                    }
                    return;
                }
                if (immediate) {
                    this.handler.forceStopAnimation(track.priority(), this.model.getBlueprint(), track.animation());
                } else {
                    track.property().stop();
                }
            }
        }

        @Override
        public void restoreDefaults() {
            synchronized (this.monitor) {
                for (Map.Entry<ModelState, AnimationHandler.DefaultProperty> entry : this.ownedDefaults.entrySet()) {
                    AnimationHandler.DefaultProperty original = this.originalDefaults.get(entry.getKey());
                    if (original != null && this.handler.getDefaultProperty(entry.getKey()) == entry.getValue()) {
                        this.handler.setDefaultProperty(original);
                        this.handler.refreshState(entry.getValue());
                    }
                }
                this.ownedDefaults.clear();
                this.originalDefaults.clear();
            }
        }
    }

    private record EngineTrack(String animation, int priority, BlueprintAnimation.LoopMode loopMode,
                               IAnimationProperty property) implements Track {
        @Override
        public boolean isEnded() {
            return this.property.isEnded();
        }
    }
}
