package com.fancyinnovations.fancynpcsmodel.providers;

import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcAttribute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure provider routing tests; deliberately never creates Npc or a Bukkit server. */
class ModelProviderRegistryTest {
    private Field providerField;
    private Object originalProviders;

    @BeforeEach
    void saveRegistry() throws ReflectiveOperationException {
        providerField = ModelProviderRegistry.class.getDeclaredField("providers");
        providerField.setAccessible(true);
        originalProviders = providerField.get(null);
        providers();
    }

    @AfterEach
    void restoreRegistry() throws ReflectiveOperationException {
        providerField.set(null, originalProviders);
    }

    @Test
    void unprefixedNamesKeepBetterModelPriorityWhenBothHaveTheModel() throws Exception {
        FakeProvider betterModel = betterModel();
        FakeProvider modelEngine = modelEngine();
        providers(betterModel, modelEngine);

        assertSame(betterModel, ModelProviderRegistry.resolve("fox").provider());
        assertSame(modelEngine, ModelProviderRegistry.resolve("me:fox").provider());
        assertSame(betterModel, ModelProviderRegistry.resolve("bm:fox").provider());
    }

    @Test
    void allDocumentedPrefixesResolveWithOnlyModelEngineInstalled() throws Exception {
        FakeProvider modelEngine = modelEngine();
        providers(modelEngine);

        for (String prefix : List.of("me", "meg", "modelengine", "ME")) {
            ModelProviderRegistry.ResolvedModel resolved = ModelProviderRegistry.resolve(prefix + ":fox");
            assertNotNull(resolved, prefix);
            assertSame(modelEngine, resolved.provider());
            assertEquals("fox", resolved.modelName());
        }
    }

    @Test
    void singleProviderSuggestionsRemainValidAfterFancyNpcsReloadsSavedAttributes() throws Exception {
        providers(modelEngine());
        NpcAttribute attribute = CustomModelAttribute.getModelAttribute();

        for (String value : List.of("fox", "me:fox", "meg:fox", "modelengine:fox", "@none")) {
            assertTrue(attribute.isValidValue(value), "Saved attribute must survive reload: " + value);
            assertTrue(attribute.getPossibleValues().contains(value));
        }
    }

    @Test
    void betterModelOnlySuggestionsIncludeBothExplicitPrefixes() throws Exception {
        providers(betterModel());
        assertTrue(ModelProviderRegistry.suggestionValues().containsAll(List.of("fox", "bm:fox", "bettermodel:fox")));
    }

    @Test
    void malformedAndUnknownProviderPrefixesNeverFallBackToAnotherProvider() throws Exception {
        providers(modelEngine());
        for (String value : List.of("", " ", ":fox", "me:", "unknown:fox", "bm:fox", "me:missing"))
            assertNull(ModelProviderRegistry.resolve(value), value);
        assertNull(ModelProviderRegistry.resolve(null));
    }

    @Test
    void providerViewCannotBeMutatedByCallers() throws Exception {
        providers(modelEngine());
        assertThrows(UnsupportedOperationException.class, () -> ModelProviderRegistry.getProviders().clear());
    }

    @Test
    void removeAttemptsEveryProviderEvenIfOneCleanupFails() throws Exception {
        FakeProvider first = betterModel();
        FakeProvider second = modelEngine();
        first.failRemove = true;
        providers(first, second);

        assertThrows(IllegalStateException.class, () -> ModelProviderRegistry.removeFromAll(null));
        assertEquals(1, first.removals);
        assertEquals(1, second.removals);
    }

    @Test
    void shutdownClearsRegistryAndClosesRemainingProvidersAfterOneFailure() throws Exception {
        FakeProvider first = betterModel();
        FakeProvider second = modelEngine();
        first.failClose = true;
        providers(first, second);

        assertDoesNotThrow(ModelProviderRegistry::shutdown);
        assertEquals(1, first.closes);
        assertEquals(1, second.closes);
        assertTrue(ModelProviderRegistry.isEmpty());
    }

    private void providers(ModelProvider... values) throws ReflectiveOperationException {
        providerField.set(null, List.of(values));
    }

    private static FakeProvider modelEngine() {
        return new FakeProvider("modelengine", List.of("me", "modelengine", "meg"));
    }

    private static FakeProvider betterModel() {
        return new FakeProvider("bettermodel", List.of("bm", "bettermodel"));
    }

    private static final class FakeProvider implements ModelProvider {
        private final String id;
        private final List<String> prefixes;
        private final Set<String> models = Set.of("fox");
        private boolean failRemove, failClose;
        private int removals, closes;

        private FakeProvider(String id, List<String> prefixes) { this.id = id; this.prefixes = prefixes; }
        @Override public String getId() { return id; }
        @Override public String getDisplayName() { return id; }
        @Override public Collection<String> getPrefixes() { return prefixes; }
        @Override public Collection<String> getModelNames() { return models; }
        @Override public boolean hasModel(String name) { return models.contains(name); }
        @Override public void applyModel(Npc npc, String name) { fail("No test should apply a live NPC model"); }
        @Override public void removeModel(Npc npc) {
            removals++;
            if (failRemove) throw new IllegalStateException("test removal failure");
        }
        @Override public boolean hasModelApplied(Npc npc) { return false; }
        @Override public boolean playAnimation(Npc npc, String name, boolean loop) { return false; }
        @Override public Collection<String> getAnimationNames(Npc npc) { return List.of(); }
        @Override public boolean shouldCancelNativeInteraction() { return false; }
        @Override public void shutdown() {
            closes++;
            if (failClose) throw new IllegalStateException("test close failure");
        }
    }
}
