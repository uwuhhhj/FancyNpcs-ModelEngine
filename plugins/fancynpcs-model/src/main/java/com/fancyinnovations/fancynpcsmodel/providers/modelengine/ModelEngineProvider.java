package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import com.fancyinnovations.fancynpcsmodel.providers.ModelProvider;
import com.fancyinnovations.fancynpcsmodel.utils.NpcEntityAccess;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.animation.handler.AnimationHandler;
import com.ticxo.modelengine.api.animation.handler.IStateMachineHandler;
import com.ticxo.modelengine.api.utils.data.io.SavedData;
import com.fancyinnovations.fancynpcsmodel.config.FancyNpcsModelConfigImpl;
import com.ticxo.modelengine.api.entity.BaseEntity;
import com.ticxo.modelengine.api.entity.Dummy;
import com.ticxo.modelengine.api.entity.Hitbox;
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import de.oliver.fancyanalytics.logger.properties.StringProperty;
import de.oliver.fancyanalytics.logger.properties.ThrowableProperty;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcAttribute;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Packet NPC integration using ModelEngine R4.1.1 APIs; verified on Paper 1.21.11. */
public final class ModelEngineProvider implements ModelProvider {
    // Only the Paper main thread mutates ME objects. These maps publish snapshots
    // to FancyNpcs' attribute and action executor threads.
    private final Map<String, AppliedModel> appliedModels = new ConcurrentHashMap<>();
    private final Map<UUID, AppliedModel> dummyToNpc = new ConcurrentHashMap<>();
    private final Map<Npc, Request> pending = new ConcurrentHashMap<>();
    private final Map<Npc, Entity> hiddenNpcs = new HashMap<>();
    private final Map<Interaction, Integer> interactions = new HashMap<>();
    private final BukkitTask tickTask;
    private volatile boolean closed;

    private static final ClassValue<Method> METADATA_REFRESH = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Method method = current.getDeclaredMethod("refreshEntityData", Player.class);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) { }
            }
            throw new IllegalStateException("FancyNpcs metadata refresh method is missing");
        }
    };

    public ModelEngineProvider() {
        requireMainThread();
        tickTask = Bukkit.getScheduler().runTaskTimer(FancyNpcsModelPlugin.get(), this::tick, 1, 1);
    }

    @Override public String getId() { return "modelengine"; }
    @Override public String getDisplayName() { return "ModelEngine"; }
    @Override public Collection<String> getPrefixes() { return List.of("me", "modelengine", "meg"); }
    @Override public Collection<String> getModelNames() {
        return List.copyOf(ModelEngineAPI.getAPI().getModelRegistry().getOrderedId());
    }
    @Override public boolean hasModel(String modelName) {
        return modelName != null && ModelEngineAPI.getBlueprint(modelName) != null;
    }

    @Override public void applyModel(Npc npc, String modelName) {
        if (closed || !hasModel(modelName)) return;
        Request request = new Request(npc, modelName);
        pending.put(npc, request);
        onMain(() -> applyRequest(request));
    }

    private void applyRequest(Request request) {
        requireMainThread();
        if (!pending.remove(request.npc, request) || closed) return;
        Npc npc = request.npc;
        if (!isCurrentNpc(npc)) return;
        String npcId = npc.getData().getId();
        AppliedModel current = appliedModels.get(npcId);
        if (current != null && current.npc == npc && current.modelName.equals(request.modelName)
                && healthy(current) && npc.getData().getLocation() != null
                && current.location.getWorld() == npc.getData().getLocation().getWorld()
                && ModelEngineAPI.getBlueprint(request.modelName) == current.model.getBlueprint()) {
            hideNpc(npc);
            synchronize(current);
            return;
        }
        if (current != null) discard(current, current.npc != npc);
        createModel(npc, request.modelName);
    }

    private void createModel(Npc npc, String modelName) {
        requireMainThread();
        ModelBlueprint blueprint = ModelEngineAPI.getBlueprint(modelName);
        Location location = npc.getData().getLocation();
        if (blueprint == null || location == null || location.getWorld() == null) {
            restoreNpcVisibility(npc);
            return;
        }
        Dummy<Npc> dummy = new Dummy<>(npc);
        // FancyNpcs owns visibility. Never let ME's radius detector add observers.
        dummy.setDetectingPlayers(false);
        dummy.syncLocation(location.clone());
        ModeledEntity modeled = null;
        ActiveModel model = null;
        try {
            // The persistent custom_model attribute recreates the model; ME must
            // not save a second independently owned NPC across server restarts.
            modeled = ModelEngineAPI.createModeledEntity(dummy, entity -> entity.setSaved(false));
            if (modeled == null) throw new IllegalStateException("ModelEngine rejected the dummy entity");
            model = ModelEngineAPI.createActiveModel(blueprint, null, active -> {
                SavedData data = new SavedData();
                data.putString("id", "state_machine");
                AnimationHandler handler = ModelEngineAPI.getAnimationHandlerRegistry().createHandler(active, data);
                if (!(handler instanceof IStateMachineHandler))
                    throw new IllegalStateException("ModelEngine state_machine animation handler is unavailable");
                return handler;
            });
            if (model == null) throw new IllegalStateException("ModelEngine rejected model " + modelName);
            double scale = scale(npc);
            model.setScale(scale);
            model.setHitboxScale(scale);
            modeled.addModel(model, true);
            // Own head/body rotation together; bypass ME's automatic controller.
            // SmoothHeadTracking rotates both through explicit modeled setters.
            modeled.setModelRotationLocked(true);
            if (modeled.getModel(blueprint.getName()).orElse(null) != model) {
                throw new IllegalStateException("ModelEngine model attachment was cancelled");
            }
            // addModel generates the live bones; binding before attachment sees
            // an empty bone map and would silently leave only forced placeholders.
            RuntimeHeadBinding.install(model, FancyNpcsModelPlugin.get().getFancyNpcsModelConfig().getMotionSettings().headBinding());
            AppliedModel applied = new AppliedModel(npc, modelName, dummy, modeled, model, location.clone(), scale);
            appliedModels.put(npc.getData().getId(), applied);
            dummyToNpc.put(dummy.getUUID(), applied);
            hideNpc(npc);
            synchronize(applied);
        } catch (RuntimeException | LinkageError failure) {
            AppliedModel published = appliedModels.get(npc.getData().getId());
            if (published != null && published.dummy == dummy) {
                appliedModels.remove(npc.getData().getId(), published);
                dummyToNpc.remove(dummy.getUUID(), published);
            }
            cleanup(dummy, modeled, model, npc);
            restoreNpcVisibility(npc);
            logFailure("Failed to create ModelEngine model", npc, failure);
        }
    }

    @Override public void removeModel(Npc npc) {
        // Requests are keyed by the Npc object, so a stale removal from reload
        // cannot cancel the new Npc with the same persisted id.
        Request request = new Request(npc, null);
        pending.put(npc, request);
        onMain(() -> {
            if (!pending.remove(npc, request)) return;
            AppliedModel applied = appliedModels.get(npc.getData().getId());
            if (applied != null && applied.npc == npc) discard(applied, false);
            // Also restore a model whose pending creation was cancelled.
            restoreNpcVisibility(npc);
        });
    }

    @Override public boolean hasModelApplied(Npc npc) {
        if (closed) return false;
        Request request = pending.get(npc);
        if (request != null) return request.modelName != null;
        AppliedModel applied = appliedModels.get(npc.getData().getId());
        return applied != null && applied.npc == npc;
    }

    @Override public boolean playAnimation(Npc npc, String animation, boolean loop) {
        AppliedModel applied = appliedModels.get(npc.getData().getId());
        if (closed || applied == null || applied.npc != npc || !applied.animationNames.contains(animation)) return false;
        if (Bukkit.isPrimaryThread()) return playNow(applied, animation, loop);
        // Direct API callers on an action thread receive request acceptance; the
        // addon's command/action hooks dispatch on the main thread.
        return onMain(() -> {
            if (!playNow(applied, animation, loop)) {
                FancyNpcsModelPlugin.get().getFancyLogger().warn("ModelEngine animation request was rejected",
                        StringProperty.of("npc_name", npc.getData().getName()), StringProperty.of("animation", animation));
            }
        });
    }

    private boolean playNow(AppliedModel applied, String animation, boolean loop) {
        requireMainThread();
        if (closed || appliedModels.get(applied.npc.getData().getId()) != applied
                || !isCurrentNpc(applied.npc) || !healthy(applied)) return false;
        return applied.animations.play(animation, loop);
    }

    @Override public Collection<String> getAnimationNames(Npc npc) {
        AppliedModel applied = appliedModels.get(npc.getData().getId());
        return applied != null && applied.npc == npc ? applied.animationNames : List.of();
    }
    @Override public boolean shouldCancelNativeInteraction() { return false; }
    @Override public @Nullable Listener createListener() { return new ModelEngineInteractListener(this); }

    public @Nullable Npc getNpcForBase(@Nullable BaseEntity<?> base) {
        AppliedModel applied = base == null ? null : dummyToNpc.get(base.getUUID());
        return !closed && applied != null ? applied.npc : null;
    }

    boolean mayInteract(Npc npc, Player player) {
        requireMainThread();
        AppliedModel applied = appliedModels.get(npc.getData().getId());
        if (applied == null || applied.npc != npc || !isCurrentNpc(npc) || !healthy(applied)
                || !stillRequested(applied) || !visibleTo(npc, player)) return false;
        // R4.1.1 emits BaseEntityInteractEvent before the native reach test.
        AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        double reach = attribute == null ? 3 : attribute.getValue();
        BoundingBox box = applied.dummy.getBoundingBox();
        Vector eye = player.getEyeLocation().toVector();
        double dx = Math.max(box.getMinX() - eye.getX(), Math.max(0, eye.getX() - box.getMaxX()));
        double dy = Math.max(box.getMinY() - eye.getY(), Math.max(0, eye.getY() - box.getMaxY()));
        double dz = Math.max(box.getMinZ() - eye.getZ(), Math.max(0, eye.getZ() - box.getMaxZ()));
        return Double.isFinite(reach) && reach >= 0 && dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /** Called exactly once from NpcPreInteractEvent for native and ME hitbox clicks. */
    public boolean claimInteraction(Npc npc, Player player, ActionTrigger trigger) {
        requireMainThread();
        if (closed || !mayInteract(npc, player)) return false;
        int tick = Bukkit.getCurrentTick();
        interactions.entrySet().removeIf(entry -> tick - entry.getValue() > 40);
        Interaction interaction = new Interaction(npc, player.getUniqueId(), trigger);
        Integer previous = interactions.get(interaction);
        if (previous != null && tick - previous <= 1) return false;
        interactions.put(interaction, tick);
        return true;
    }

    void forgetInteractions(UUID player) {
        requireMainThread();
        interactions.keySet().removeIf(interaction -> interaction.player.equals(player));
    }

    private void tick() {
        if (closed) return;
        int tick = Bukkit.getCurrentTick();
        if (tick % 20 == 0) interactions.entrySet().removeIf(entry -> tick - entry.getValue() > 40);
        for (AppliedModel applied : List.copyOf(appliedModels.values())) {
            try {
                if (!isCurrentNpc(applied.npc) || !stillRequested(applied)) {
                    discard(applied, true);
                    continue;
                }
                Location location = applied.npc.getData().getLocation();
                if (location == null || location.getWorld() == null) {
                    discard(applied, true);
                    continue;
                }
                // Rebuild displays in the destination world and recover models
                // removed by an ME reload without retaining old entity handles.
                if (!healthy(applied) || location.getWorld() != applied.location.getWorld()
                        || !applied.headBindingSettings.equals(FancyNpcsModelPlugin.get().getFancyNpcsModelConfig().getMotionSettings().headBinding())
                        || ModelEngineAPI.getBlueprint(applied.modelName) != applied.model.getBlueprint()) {
                    discard(applied, false);
                    createModel(applied.npc, applied.modelName);
                    continue;
                }
                synchronize(applied);
                applied.failure = "";
            } catch (RuntimeException | LinkageError failure) {
                String message = failure.toString();
                if (!message.equals(applied.failure)) logFailure("Failed to synchronize ModelEngine NPC", applied.npc, failure);
                applied.failure = message;
            }
        }
    }

    private void synchronize(AppliedModel applied) {
        requireMainThread();
        Location target = applied.npc.getData().getLocation().clone();
        FancyNpcsModelConfigImpl.MotionSettings settings = FancyNpcsModelPlugin.get().getFancyNpcsModelConfig().getMotionSettings();
        applied.headTracking.reconfigure(settings.headTracking());
        applied.animations.reconfigure(settings.animations());
        applied.animations.update(Bukkit.getCurrentTick());
        double scale = scale(applied.npc);
        if (Double.compare(scale, applied.scale) != 0) {
            applied.model.setScale(scale);
            applied.model.setHitboxScale(scale);
            applied.scale = scale;
        }
        // ME only writes collision bounds to BukkitEntityData, never Dummy.
        // Match R4.1.1's square INTERACTION entity: max(width, depth) * X
        // hitbox scale. setHitbox alone does not rebuild Dummy's cached box;
        // setLocation rebuilds the cached bounds without resetting rotations.
        Hitbox mainHitbox = applied.model.getBlueprint().getMainHitbox();
        double width = (float) mainHitbox.getMaxWidth() * applied.model.getHitboxScale().x();
        double height = (float) mainHitbox.getHeight() * applied.model.getHitboxScale().y();
        double eyeHeight = (float) mainHitbox.getEyeHeight() * applied.model.getScale().y();
        applied.dummy.setHitbox(new Hitbox(width, height, width, eyeHeight));
        applied.dummy.setLocation(target);
        applied.location = target;
        Set<UUID> desired = new HashSet<>();
        List<SmoothHeadTracking.Candidate> candidates = new ArrayList<>();
        int turnDistance = applied.npc.getData().getTurnToPlayerDistance();
        if (turnDistance < 0) turnDistance = FancyNpcsPlugin.get().getFancyNpcConfig().getTurnToPlayerDistance();
        boolean track = settings.headTrackingEnabled() && applied.npc.getData().isTurnToPlayer()
                && !applied.animations.isHeadTrackingPaused();
        Location head = target.clone().add(0, eyeHeight, 0);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!visibleTo(applied.npc, player)) continue;
            desired.add(player.getUniqueId());
            double distance = player.getLocation().distanceSquared(target);
            if (track) {
                Vector direction = player.getEyeLocation().toVector().subtract(head.toVector());
                if (direction.lengthSquared() > .000001) {
                    Location facing = head.clone().setDirection(direction);
                    candidates.add(new SmoothHeadTracking.Candidate(player.getUniqueId(), distance, facing.getYaw(), facing.getPitch()));
                }
            }
        }
        for (UUID previous : Set.copyOf(applied.viewers)) {
            if (!desired.contains(previous)) applied.dummy.getData().getTracked().removeForcedPairing(previous);
        }
        for (UUID viewer : desired) {
            if (!applied.viewers.contains(viewer)) applied.dummy.getData().getTracked().addForcedPairing(viewer);
        }
        applied.viewers.clear();
        applied.viewers.addAll(desired);
        SmoothHeadTracking.Pose facing = applied.headTracking.tick(target.getYaw(), target.getPitch(),
                track ? Math.max(0, turnDistance) : 0, candidates, .05,
                !settings.headTracking().bodyFollow().pauseDuringPose() || !applied.animations.isPoseActive());
        applied.modeled.setYBodyRot((float) facing.bodyYaw());
        applied.modeled.setYHeadRot((float) facing.headYaw());
        applied.modeled.setXHeadRot((float) facing.headPitch());
        hideNpc(applied.npc);
    }

    private boolean visibleTo(Npc npc, Player player) {
        Location location = npc.getData().getLocation();
        if (!player.isOnline() || location == null || location.getWorld() != player.getWorld()
                || !npc.getData().isSpawnEntity() || !npc.isShownFor(player)
                || !npc.getData().getVisibility().canSee(player, npc)) return false;
        int radius = npc.getData().getVisibilityDistance();
        if (radius < 0) radius = FancyNpcsPlugin.get().getFancyNpcConfig().getVisibilityDistance();
        return radius > 0 && (radius == Integer.MAX_VALUE
                || player.getLocation().distanceSquared(location) <= (double) radius * radius);
    }

    private boolean stillRequested(AppliedModel applied) {
        for (Map.Entry<NpcAttribute, String> entry : applied.npc.getData().getAttributes().entrySet()) {
            if (!entry.getKey().getName().equalsIgnoreCase("custom_model")) continue;
            String value = entry.getValue();
            if (value == null) return false;
            int colon = value.indexOf(':');
            if (colon >= 0) {
                if (!getPrefixes().contains(value.substring(0, colon).toLowerCase(java.util.Locale.ROOT))) return false;
                value = value.substring(colon + 1);
            }
            return value.equals(applied.modelName);
        }
        return false;
    }

    private boolean isCurrentNpc(Npc npc) {
        return FancyNpcsPlugin.get().getNpcManager().getNpcById(npc.getData().getId()) == npc;
    }

    private boolean healthy(AppliedModel applied) {
        return !applied.modeled.isDestroyed() && !applied.model.isDestroyed() && !applied.model.isRemoved()
                && ModelEngineAPI.getModeledEntity(applied.dummy.getUUID()) == applied.modeled
                && applied.modeled.getModel(applied.model.getBlueprint().getName()).orElse(null) == applied.model;
    }

    private void discard(AppliedModel applied, boolean restoreVisibility) {
        requireMainThread();
        appliedModels.remove(applied.npc.getData().getId(), applied);
        dummyToNpc.remove(applied.dummy.getUUID(), applied);
        try {
            try { applied.animations.close(); }
            catch (RuntimeException | LinkageError failure) { logFailure("Failed to stop ModelEngine NPC animations", applied.npc, failure); }
            cleanup(applied.dummy, applied.modeled, applied.model, applied.npc);
        }
        finally { if (restoreVisibility) restoreNpcVisibility(applied.npc); }
    }

    private void cleanup(Dummy<Npc> dummy, @Nullable ModeledEntity modeled, @Nullable ActiveModel model, Npc npc) {
        requireMainThread();
        dummy.setRemoved(true);
        dummy.getData().getTracked().clearForcedPairing();
        try {
            // AddModelEvent can cancel before it binds the active model.
            if (model != null && modeled != null && !model.isDestroyed()
                    && modeled.getModel(model.getBlueprint().getName()).orElse(null) != model) {
                if (model.getModeledEntity() == null) model.setModeledEntity(modeled);
                model.destroy();
            }
        } catch (RuntimeException | LinkageError failure) { logFailure("Failed to destroy unattached ModelEngine model", npc, failure); }
        try {
            if (modeled != null && !modeled.isDestroyed()) modeled.destroy();
        } catch (RuntimeException | LinkageError failure) { logFailure("Failed to destroy ModelEngine NPC", npc, failure); }
        finally {
            // The official API removes it from ME's updater even if rendering
            // cleanup failed. The addon's dummy is permanently marked removed.
            // Also covers a constructor that registered its entity before throwing.
            try { ModelEngineAPI.removeModeledEntity(dummy.getUUID()); }
            catch (RuntimeException | LinkageError failure) { logFailure("Failed to unregister ModelEngine NPC", npc, failure); }
        }
    }

    private void hideNpc(Npc npc) {
        requireMainThread();
        Entity entity = NpcEntityAccess.getBukkitEntity(npc);
        if (entity == null) throw new IllegalStateException("Cannot access FancyNpcs packet entity");
        hiddenNpcs.put(npc, entity);
        if (!entity.isInvisible()) {
            entity.setInvisible(true);
            refreshMetadata(npc);
        }
    }

    private void restoreNpcVisibility(Npc npc) {
        requireMainThread();
        Entity hidden = hiddenNpcs.remove(npc);
        if (hidden == null) return;
        NpcAttribute invisible = FancyNpcsPlugin.get().getAttributeManager().getAttributeByName(EntityType.PLAYER, "invisible");
        boolean userInvisible = invisible != null
                && "true".equalsIgnoreCase(npc.getData().getAttributes().getOrDefault(invisible, "false"));
        if (hidden.isInvisible() != userInvisible) {
            hidden.setInvisible(userInvisible);
            // A reloaded Npc must not send stale metadata over the new entity id.
            if (isCurrentNpc(npc) && NpcEntityAccess.getBukkitEntity(npc) == hidden) refreshMetadata(npc);
        }
    }

    private void refreshMetadata(Npc npc) {
        try {
            Method method = METADATA_REFRESH.get(npc.getClass());
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (npc.isShownFor(player)) method.invoke(npc, player);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Throwable cause = failure instanceof InvocationTargetException invoked ? invoked.getCause() : failure;
            logFailure("Failed to refresh FancyNpcs invisibility metadata", npc, cause);
        }
    }

    @Override public void shutdown() {
        closed = true;
        pending.clear();
        onMain(() -> {
            tickTask.cancel();
            for (AppliedModel applied : List.copyOf(appliedModels.values())) {
                try { discard(applied, true); }
                catch (RuntimeException | LinkageError failure) { logFailure("Failed to clean up ModelEngine NPC on shutdown", applied.npc, failure); }
            }
            for (Npc npc : List.copyOf(hiddenNpcs.keySet())) {
                try { restoreNpcVisibility(npc); }
                catch (RuntimeException | LinkageError failure) { logFailure("Failed to restore NPC visibility on shutdown", npc, failure); }
            }
            appliedModels.clear();
            dummyToNpc.clear();
            hiddenNpcs.clear();
            interactions.clear();
        });
    }

    private boolean onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return true;
        }
        FancyNpcsModelPlugin plugin = FancyNpcsModelPlugin.get();
        if (!plugin.isEnabled()) {
            pending.clear();
            return false;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, action);
            return true;
        } catch (IllegalPluginAccessException disabledDuringSchedule) {
            pending.clear();
            return false;
        }
    }

    private static double scale(Npc npc) {
        double scale = npc.getData().getScale();
        if (!Double.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("NPC scale must be finite and positive");
        return scale;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("ModelEngine NPC operations must run on the Paper main thread");
    }

    private static void logFailure(String message, Npc npc, Throwable failure) {
        FancyNpcsModelPlugin.get().getFancyLogger().warn(message, ThrowableProperty.of(failure),
                StringProperty.of("npc_name", npc.getData().getName()));
    }

    private static final class Request {
        final Npc npc;
        final String modelName;
        Request(Npc npc, String modelName) { this.npc = npc; this.modelName = modelName; }
    }

    private record Interaction(Npc npc, UUID player, ActionTrigger trigger) { }

    private static final class AppliedModel {
        final Npc npc;
        final String modelName;
        final Dummy<Npc> dummy;
        final ModeledEntity modeled;
        final ActiveModel model;
        final List<String> animationNames;
        final Set<UUID> viewers = new HashSet<>();
        Location location;
        double scale;
        String failure = "";
        final SmoothHeadTracking headTracking;
        final ModelEngineAnimationController animations;
        final RuntimeHeadBinding.Settings headBindingSettings;
        AppliedModel(Npc npc, String name, Dummy<Npc> dummy, ModeledEntity modeled, ActiveModel model, Location location, double scale) {
            this.npc = npc;
            this.modelName = name;
            this.dummy = dummy;
            this.modeled = modeled;
            this.model = model;
            this.animationNames = model.getBlueprint().getAnimations().keySet().stream().sorted().toList();
            this.location = location;
            this.scale = scale;
            FancyNpcsModelConfigImpl.MotionSettings settings = FancyNpcsModelPlugin.get().getFancyNpcsModelConfig().getMotionSettings();
            this.headBindingSettings = settings.headBinding();
            this.headTracking = new SmoothHeadTracking(settings.headTracking());
            this.headTracking.reset(location.getYaw(), location.getPitch());
            this.animations = new ModelEngineAnimationController(model, settings.animations());
        }
    }
}
