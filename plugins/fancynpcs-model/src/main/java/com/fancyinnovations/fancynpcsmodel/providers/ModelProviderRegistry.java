package com.fancyinnovations.fancynpcsmodel.providers;

import com.fancyinnovations.fancynpcsmodel.providers.bettermodel.BetterModelProvider;
import com.fancyinnovations.fancynpcsmodel.providers.modelengine.ModelEngineProvider;
import de.oliver.fancyanalytics.logger.ExtendedFancyLogger;
import de.oliver.fancynpcs.api.Npc;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps track of all available model providers.
 * <p>
 * Providers are checked in registration order when resolving unprefixed model
 * names: BetterModel first (for backwards compatibility), then ModelEngine.
 * A model name can be prefixed (e.g. "bm:my_model" or "me:my_model") to force
 * a specific provider.
 */
public final class ModelProviderRegistry {

    private static volatile List<ModelProvider> providers = List.of();
    private static ExtendedFancyLogger logger;

    private ModelProviderRegistry() {
    }

    public static void init(ExtendedFancyLogger logger) {
        shutdown();
        ModelProviderRegistry.logger = logger;
        List<ModelProvider> available = new ArrayList<>();

        if (Bukkit.getPluginManager().isPluginEnabled("BetterModel")) {
            try {
                available.add(new BetterModelProvider());
                logger.info("Found BetterModel - enabling BetterModel support");
            } catch (LinkageError | RuntimeException error) {
                logger.error("Cannot enable BetterModel support: " + error);
            }
        }

        if (Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) {
            try {
                available.add(new ModelEngineProvider());
                logger.info("Found ModelEngine - enabling ModelEngine support");
            } catch (LinkageError | RuntimeException error) {
                logger.error("Cannot enable ModelEngine support: " + error);
            }
        }
        providers = List.copyOf(available);
    }

    public static List<ModelProvider> getProviders() {
        return providers;
    }

    public static boolean isEmpty() {
        return providers.isEmpty();
    }

    /**
     * Resolves a raw model value (optionally prefixed with a provider prefix,
     * e.g. "bm:my_model" or "modelengine:my_model") to a provider and model name.
     *
     * @return the resolved model or null if no installed provider has a model with that name
     */
    public static @Nullable ResolvedModel resolve(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        int colon = value.indexOf(':');
        if (colon >= 0) {
            if (colon == 0 || colon == value.length() - 1) return null;
            String prefix = value.substring(0, colon).toLowerCase(Locale.ROOT);
            String modelName = value.substring(colon + 1);

            for (ModelProvider provider : providers) {
                if (provider.getPrefixes().contains(prefix)) {
                    return provider.hasModel(modelName) ? new ResolvedModel(provider, modelName) : null;
                }
            }
            return null;
        }

        for (ModelProvider provider : providers) {
            if (provider.hasModel(value)) {
                return new ResolvedModel(provider, value);
            }
        }

        return null;
    }

    /**
     * @return the provider that currently has a model attached to the NPC or null
     */
    public static @Nullable ModelProvider getActiveProvider(Npc npc) {
        for (ModelProvider provider : providers) {
            if (provider.hasModelApplied(npc)) {
                return provider;
            }
        }

        return null;
    }

    /**
     * Removes the models of all providers from the NPC.
     */
    public static void removeFromAll(Npc npc) {
        RuntimeException failure = null;
        for (ModelProvider provider : providers) {
            try {
                provider.removeModel(npc);
            } catch (RuntimeException | LinkageError error) {
                if (failure == null) failure = new IllegalStateException("Failed to remove NPC models", error);
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    /**
     * @return all model names and every supported prefix, including for a single provider.
     * These values also validate attributes loaded from FancyNpcs' persistent storage.
     */
    public static List<String> suggestionValues() {
        Set<String> names = new LinkedHashSet<>();

        for (ModelProvider provider : providers) {
            names.addAll(provider.getModelNames());
        }

        for (ModelProvider provider : providers) {
            for (String prefix : provider.getPrefixes()) {
                for (String modelName : provider.getModelNames()) {
                    names.add(prefix + ":" + modelName);
                }
            }
        }

        return new ArrayList<>(names);
    }

    public static void shutdown() {
        List<ModelProvider> previous = providers;
        providers = List.of();
        for (ModelProvider provider : previous) {
            try {
                provider.shutdown();
            } catch (RuntimeException | LinkageError error) {
                if (logger != null) logger.error("Failed to close " + provider.getDisplayName() + ": " + error);
            }
        }
    }

    public record ResolvedModel(ModelProvider provider, String modelName) {
    }
}
