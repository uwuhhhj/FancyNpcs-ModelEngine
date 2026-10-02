package com.fancyinnovations.fancynpcsmodel.fancynpcshook;

import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import com.fancyinnovations.fancynpcsmodel.providers.ModelProvider;
import com.fancyinnovations.fancynpcsmodel.providers.ModelProviderRegistry;
import de.oliver.fancyanalytics.logger.properties.StringProperty;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcAttribute;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public class CustomModelAttribute {

    public static final String ATTRIBUTE_NAME = "custom_model";
    public static final String NONE_VALUE = "@none";
    private static final Map<Npc, String> pending = new IdentityHashMap<>();
    private static final Map<Npc, String> reportedFailures = new WeakHashMap<>();

    public static NpcAttribute getModelAttribute() {
        return new NpcAttribute(
                ATTRIBUTE_NAME,
                () -> {
                    List<String> values = new ArrayList<>();
                    values.add(NONE_VALUE);
                    values.addAll(ModelProviderRegistry.suggestionValues());
                    return values;
                },
                List.of(EntityType.PLAYER),
                CustomModelAttribute::setModel
        );
    }

    private static void setModel(Npc npc, String modelName) {
        FancyNpcsModelPlugin plugin = FancyNpcsModelPlugin.get();
        if (plugin == null || !plugin.isEnabled() || ModelProviderRegistry.isEmpty()) return;
        if (Bukkit.isPrimaryThread()) {
            applyCurrent(npc, modelName);
            return;
        }
        // FancyNpcs applies attributes from its NPC thread. Coalesce updates and
        // perform all provider switches on Paper's main thread.
        synchronized (pending) {
            boolean queued = pending.containsKey(npc);
            pending.put(npc, modelName);
            if (queued) return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                String value;
                synchronized (pending) {
                    if (!pending.containsKey(npc)) return;
                    value = pending.remove(npc);
                }
                if (plugin.isEnabled()) applyCurrent(npc, value);
            });
        } catch (RuntimeException error) {
            // The plugin can be disabled after the NPC thread's enabled check.
            // Never leave an unscheduled entry that prevents later updates.
            synchronized (pending) {
                pending.remove(npc);
            }
            if (plugin.isEnabled())
                plugin.getFancyLogger().error("Failed to schedule NPC model update: " + error);
        }
    }

    private static void applyCurrent(Npc npc, String modelName) {
        FancyNpcsPlugin fancyNpcs = FancyNpcsPlugin.get();
        // A reload can replace an NPC with another object sharing the same id.
        // A queued update must also match the latest persistent attribute value.
        if (fancyNpcs == null || fancyNpcs.getNpcManager().getNpcById(npc.getData().getId()) != npc
                || !Objects.equals(modelName, storedValue(npc))) return;
        try {
            applyModel(npc, modelName);
        } catch (RuntimeException | LinkageError error) {
            reportFailure(npc, modelName, "Failed to apply NPC model: " + error);
        }
    }

    private static void applyModel(Npc npc, String modelName) {
        // remove model if model name is "@none"
        if (modelName == null || modelName.isBlank() || modelName.equalsIgnoreCase(NONE_VALUE)) {
            ModelProviderRegistry.removeFromAll(npc);
            reportedFailures.remove(npc);
            return;
        }
        if (npc.getData().getType() != EntityType.PLAYER) {
            ModelProviderRegistry.removeFromAll(npc);
            return;
        }

        ModelProviderRegistry.ResolvedModel resolved = ModelProviderRegistry.resolve(modelName);
        if (resolved == null) {
            // Do not keep an old render after its stored model value changed.
            ModelProviderRegistry.removeFromAll(npc);
            reportFailure(npc, modelName, "Failed to find model in an enabled model plugin");
            return;
        }

        // make sure only the resolved provider has a model attached
        for (ModelProvider provider : ModelProviderRegistry.getProviders()) {
            if (provider != resolved.provider() && provider.hasModelApplied(npc)) {
                provider.removeModel(npc);
            }
        }

        resolved.provider().applyModel(npc, resolved.modelName());
        reportedFailures.remove(npc);
    }

    private static void reportFailure(Npc npc, String modelName, String message) {
        if (reportedFailures.containsKey(npc) && Objects.equals(reportedFailures.get(npc), modelName)) return;
        reportedFailures.put(npc, modelName);
        FancyNpcsModelPlugin.get().getFancyLogger().error(message,
                StringProperty.of("model_name", Objects.toString(modelName, "@none")),
                StringProperty.of("npc_name", npc.getData().getName()));
    }

    public static void restoreStoredModel(Npc npc) {
        if (hasAttribute(npc)) setModel(npc, storedValue(npc));
    }

    public static void clearPending() {
        synchronized (pending) { pending.clear(); }
        reportedFailures.clear();
    }

    /**
     * Removes the models of all providers from the given NPC.
     * This is necessary to prevent old models still existing in the world.
     */
    public static void removeModels(Npc npc) {
        synchronized (pending) { pending.remove(npc); }
        reportedFailures.remove(npc);
        ModelProviderRegistry.removeFromAll(npc);
    }

    /**
     * @return whether the given NPC has the model attribute
     */
    public static boolean hasAttribute(Npc npc) {
        String value = storedValue(npc);
        return value != null && !value.isBlank() && !NONE_VALUE.equalsIgnoreCase(value);
    }

    public static String storedValue(Npc npc) {
        for (Map.Entry<NpcAttribute, String> entry : npc.getData().getAttributes().entrySet()) {
            if (entry.getKey() != null && entry.getKey().getName().equalsIgnoreCase(ATTRIBUTE_NAME)) {
                return entry.getValue();
            }
        }

        return null;
    }
}
