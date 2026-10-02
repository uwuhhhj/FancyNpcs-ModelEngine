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
        if (closed) return;
        if (finished(manualGesture)) manualGesture = null;
        if (finished(manualPose)) manualPose = null;
        // Repair a loop removed by an ME reload/external command without
        // restarting healthy loops or firing a play event every server tick.
        if (!repairScheduled || tick - nextRepairTick >= 0) {
            repairScheduled = true;
            nextRepairTick = tick + 20;
            repairAmbient();
        }
        if (!settings.idleEnabled() || !settings.randomGestures().enabled()) return;
        if (!randomScheduled) {
            scheduleRandom(tick);
            return;
        }
        if (tick - nextRandomTick < 0) return;
        scheduleRandom(tick);
        if (manualGesture != null || (manualPose != null && settings.pauseRandomGesturesDuringPose())) return;
        List<String> available = settings.randomGestures().animations().stream()
                .filter(backend::hasAnimation).filter(name -> !name.equals(settings.defaultStates().idle()))
                .filter(name -> !settings.poseAnimations().contains(name)).toList();
        if (available.isEmpty()) return;
        // Random gestures may coexist with a held pose when explicitly allowed;
        // an explicit /npc play_animation request instead replaces that pose.
        String animation = available.get(random.nextInt(available.size()));
        manualGesture = backend.play(animation, settings.gesture(), BlueprintAnimation.LoopMode.ONCE, true);
    }

    /**
     * Returns false for a missing/cancelled animation, preserving existing actions.
     * Playing configured idle is a reset; it never creates a duplicate idle layer.
     * For configured poses --loop holds the last frame, matching the MM HOLD mode.
     */
    public boolean play(String animation, boolean loop) {
        if (closed || animation == null || !backend.hasAnimation(animation)) return false;
        if (animation.equals(settings.defaultStates().idle())) {
            stopManual(false);
            return true;
        }
        boolean pose = settings.poseAnimations().contains(animation);
        ModelEngineAnimationSettings.Motion motion = pose ? settings.pose() : settings.gesture();
        BlueprintAnimation.LoopMode mode = loop
                ? (pose ? BlueprintAnimation.LoopMode.HOLD : BlueprintAnimation.LoopMode.LOOP)
                : BlueprintAnimation.LoopMode.ONCE;
        Track replacement = backend.play(animation, motion, mode, true);
        if (replacement == null) return false;
        // Accept first, then remove only the old properties we actually own.
        // The backend's identity check protects same-name, same-layer replays.
        stopManual(false);
        if (pose) manualPose = replacement;
        else manualGesture = replacement;
        return true;
    }

    public boolean isPoseHeld() {
        return !closed && manualPose != null && !finished(manualPose)
                && manualPose.loopMode() != BlueprintAnimation.LoopMode.ONCE;
    }

    public boolean isHeadTrackingPaused() {
        return !closed && (pausesHead(manualPose) || pausesHead(manualGesture));
    }

    public void reconfigure(ModelEngineAnimationSettings settings) {
        if (closed) throw new IllegalStateException("Animation controller is closed");
        Objects.requireNonNull(settings);
        if (this.settings.equals(settings)) return;
        clearOwned();
        backend.restoreDefaults();
        this.settings = settings;
        randomScheduled = false;
        repairScheduled = false;
        configureDefaults();
        repairAmbient();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { clearOwned(); }
        finally { backend.restoreDefaults(); }
    }

    private boolean pausesHead(Track track) {
        return track != null && !finished(track) && settings.headTrackingPauseAnimations().contains(track.animation());
    }

    private boolean finished(Track track) {
        return track == null || !backend.isCurrent(track) || track.isEnded();
    }

    private void configureDefaults() {
        ModelEngineAnimationSettings.DefaultStates defaults = settings.defaultStates();
        setDefault(ModelState.IDLE, defaults.idle(), defaults.blend());
        setDefault(ModelState.WALK, defaults.walk(), defaults.blend());
        setDefault(ModelState.JUMP, defaults.jump(), defaults.blend());
        setDefault(ModelState.DEATH, defaults.death(), defaults.blend());
    }

    private void setDefault(ModelState state, String name, ModelEngineAnimationSettings.Blend blend) {
        if (backend.hasAnimation(name)) backend.setDefault(state, name, blend);
    }

    private void repairAmbient() {
        if (!settings.idleEnabled()) return;
        for (ModelEngineAnimationSettings.LoopAnimation loop : settings.loopAnimations()) {
            if (!loop.enabled() || !backend.hasAnimation(loop.animation())) continue;
            Track existing = ambient.get(loop.priority());
            if (!finished(existing)) continue;
            Track started = backend.play(loop.animation(), new ModelEngineAnimationSettings.Motion(loop.priority(), loop.blend()),
                    BlueprintAnimation.LoopMode.LOOP, false);
            if (started != null) ambient.put(loop.priority(), started);
        }
    }

    private void scheduleRandom(int tick) {
        ModelEngineAnimationSettings.RandomGestures randomSettings = settings.randomGestures();
        int interval = random.nextInt(randomSettings.minIntervalTicks(), randomSettings.maxIntervalTicks() + 1);
        nextRandomTick = tick + interval;
        randomScheduled = true;
    }

    private void stopManual(boolean immediate) {
        Track pose = manualPose;
        Track gesture = manualGesture;
        manualPose = null;
        manualGesture = null;
        try { if (pose != null) backend.stop(pose, immediate); }
        finally { if (gesture != null) backend.stop(gesture, immediate); }
    }

    private void clearOwned() {
        List<Track> tracks = new ArrayList<>(ambient.values());
        if (manualPose != null) tracks.add(manualPose);
        if (manualGesture != null) tracks.add(manualGesture);
        ambient.clear();
        manualPose = null;
        manualGesture = null;
        RuntimeException failure = null;
        for (Track track : tracks) {
            try { backend.stop(track, true); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
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
            handler = stateMachine;
            monitor = handler instanceof StateMachineHandler implementation ? implementation.getStateMachines() : handler;
        }

        @Override public boolean hasAnimation(String name) {
            return model.getBlueprint().getAnimations().containsKey(name);
        }

        @Override public void setDefault(ModelState state, String animation, ModelEngineAnimationSettings.Blend blend) {
            synchronized (monitor) {
                AnimationHandler.DefaultProperty previous = handler.getDefaultProperty(state);
                originalDefaults.putIfAbsent(state, previous);
                AnimationHandler.DefaultProperty property = new AnimationHandler.DefaultProperty(state, animation,
                        blend.inSeconds(), blend.outSeconds(), blend.speed());
                handler.setDefaultProperty(property);
                ownedDefaults.put(state, property);
                handler.refreshState(previous == null ? property : previous);
            }
        }

        @Override public Track play(String animation, ModelEngineAnimationSettings.Motion motion, BlueprintAnimation.LoopMode loop, boolean force) {
            if (motion.priority() == ConfigProperty.DEFAULT_PRIORITY.getInt()) {
                throw new IllegalArgumentException("Custom animation priority collides with ModelEngine Default-Animation-Priority");
            }
            BlueprintAnimation blueprint = model.getBlueprint().getAnimations().get(animation);
            if (blueprint == null) return null;
            ModelEngineAnimationSettings.Blend blend = motion.blend();
            SimpleProperty property = new SimpleProperty(model, blueprint, blend.inSeconds(), blend.outSeconds(), blend.speed());
            // Configure before queueing; ME may materialize it on its worker.
            property.setForceLoopMode(loop);
            return handler.playAnimation(motion.priority(), property, force)
                    ? new EngineTrack(animation, motion.priority(), loop, property) : null;
        }

        @Override public boolean isCurrent(Track track) {
            synchronized (monitor) { return current((EngineTrack) track); }
        }

        private boolean current(EngineTrack track) {
            if (handler instanceof StateMachineHandler implementation) {
                StateMachineHandler.AnimationStateMachine machine = implementation.getStateMachines().get(track.priority());
                // getAnimation deliberately hides LERPOUT in ME; effective
                // property identity keeps head tracking paused until blend ends.
                return machine != null && machine.getEffectiveAnimation() == track.property();
            }
            return handler.getAnimation(track.priority(), model.getBlueprint(), track.animation()) == track.property();
        }

        @Override public void stop(Track owned, boolean immediate) {
            EngineTrack track = (EngineTrack) owned;
            synchronized (monitor) {
                if (!current(track)) {
                    // A newly queued replacement may hide the old current
                    // property. Stop that exact property directly: a by-name
                    // removal here could cancel the new same-name request.
                    if (handler instanceof StateMachineHandler implementation) {
                        StateMachineHandler.AnimationStateMachine machine = implementation.getStateMachines().get(track.priority());
                        if (machine != null && machine.getCurrentAnimation() == track.property()) track.property().stop();
                    }
                    return;
                }
                if (immediate) handler.forceStopAnimation(track.priority(), model.getBlueprint(), track.animation());
                else track.property().stop();
            }
        }

        @Override public void restoreDefaults() {
            synchronized (monitor) {
                for (Map.Entry<ModelState, AnimationHandler.DefaultProperty> entry : ownedDefaults.entrySet()) {
                    AnimationHandler.DefaultProperty original = originalDefaults.get(entry.getKey());
                    if (original != null && handler.getDefaultProperty(entry.getKey()) == entry.getValue()) {
                        handler.setDefaultProperty(original);
                        handler.refreshState(entry.getValue());
                    }
                }
                ownedDefaults.clear();
                originalDefaults.clear();
            }
        }
    }

    private record EngineTrack(String animation, int priority, BlueprintAnimation.LoopMode loopMode,
                               IAnimationProperty property) implements Track {
        @Override public boolean isEnded() { return property.isEnded(); }
    }
}
