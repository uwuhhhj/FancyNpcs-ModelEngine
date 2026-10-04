package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeNpcMetadataTest {
    public record Value(int id, Object serializer, Object value) { }
    public record Metadata(int id, List<Value> packedItems) { }
    public record Bundle(Iterable<Object> subPackets) { }

    private final NativeNpcMetadata adapter = new NativeNpcMetadata(Metadata.class, Bundle.class, Value.class);
    NativeNpcMetadataTest() throws ReflectiveOperationException { }

    private static Metadata metadata(byte flags) {
        return new Metadata(42, List.of(new Value(0, "byte", flags), new Value(3, "boolean", true)));
    }

    @Test void onlySelectedPlayersSeeTheNativeBodyHidden() throws Exception {
        Metadata original = metadata((byte) 0);
        Metadata selected = (Metadata) adapter.hideFor(original, id -> id == 42);
        assertEquals((byte) 0x20, selected.packedItems().getFirst().value());
        assertSame(original, adapter.hideFor(original, id -> false));
    }

    @Test void preservesGlowFireAndOtherMetadataWithoutMutatingTheOriginal() throws Exception {
        Metadata original = metadata((byte) 0x41);
        Metadata hidden = (Metadata) adapter.hideFor(original, id -> true);
        assertEquals((byte) 0x61, hidden.packedItems().getFirst().value());
        assertEquals((byte) 0x41, original.packedItems().getFirst().value());
        assertSame(original.packedItems().get(1), hidden.packedItems().get(1));
        assertEquals("byte", hidden.packedItems().getFirst().serializer());
    }

    @Test void userConfiguredInvisibleNpcStaysInvisible() throws Exception {
        Metadata original = metadata((byte) 0x20);
        assertSame(original, adapter.hideFor(original, id -> true));
        assertSame(original, adapter.hideFor(original, id -> false));
    }

    @Test void partialMetadataDoesNotInventOrResetSharedFlags() throws Exception {
        Metadata original = new Metadata(42, List.of(new Value(3, "boolean", false)));
        assertSame(original, adapter.hideFor(original, id -> true));
    }

    @Test void unrelatedEntitiesAndPacketsPassThroughUnchanged() throws Exception {
        Metadata original = metadata((byte) 0);
        assertSame(original, adapter.hideFor(original, id -> id == 99));
        Object unrelated = new Object();
        assertSame(unrelated, adapter.hideFor(unrelated, id -> true));
    }

    @Test void bundledMetadataIsCopiedWhilePreservingOrderAndOtherPackets() throws Exception {
        Object before = new Object();
        Object after = new Object();
        Metadata original = metadata((byte) 0);
        Bundle bundle = new Bundle(List.of(before, original, after));
        Bundle hidden = (Bundle) adapter.hideFor(bundle, id -> true);
        List<Object> children = new ArrayList<>();
        hidden.subPackets().forEach(children::add);
        assertSame(before, children.getFirst());
        assertEquals((byte) 0x20, ((Metadata) children.get(1)).packedItems().getFirst().value());
        assertSame(after, children.getLast());
        assertEquals((byte) 0, original.packedItems().getFirst().value());
    }

    @Test void nestedBundlesAndMultipleNpcUpdatesAreHandled() throws Exception {
        Bundle nested = new Bundle(List.of(metadata((byte) 0)));
        Bundle bundle = new Bundle(List.of(nested, metadata((byte) 1)));
        Bundle hidden = (Bundle) adapter.hideFor(bundle, id -> true);
        List<Object> children = new ArrayList<>();
        hidden.subPackets().forEach(children::add);
        assertNotSame(nested, children.getFirst());
        assertEquals((byte) 0x21, ((Metadata) children.getLast()).packedItems().getFirst().value());
    }

    @Test void bundlesForOverflowViewersDoNotAllocateReplacements() throws Exception {
        Bundle bundle = new Bundle(List.of(metadata((byte) 0), new Object()));
        assertSame(bundle, adapter.hideFor(bundle, id -> false));
    }
}
