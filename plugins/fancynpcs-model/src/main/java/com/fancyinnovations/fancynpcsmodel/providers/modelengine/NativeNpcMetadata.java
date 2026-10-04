package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/** Copies outgoing metadata; never mutates the shared NPC entity or packet. */
final class NativeNpcMetadata {
    private final Class<?> metadataType;
    private final Class<?> bundleType;
    private final Method entityId;
    private final Method packedItems;
    private final Method valueId;
    private final Method serializer;
    private final Method value;
    private final Method subPackets;
    private final Constructor<?> metadataConstructor;
    private final Constructor<?> valueConstructor;
    private final Constructor<?> bundleConstructor;

    NativeNpcMetadata(ClassLoader loader) throws ReflectiveOperationException {
        this(Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket", false, loader),
                Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket", false, loader),
                Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue", false, loader));
    }

    NativeNpcMetadata(Class<?> metadataType, Class<?> bundleType, Class<?> valueType) throws ReflectiveOperationException {
        this.metadataType = metadataType;
        this.bundleType = bundleType;
        entityId = metadataType.getMethod("id");
        packedItems = metadataType.getMethod("packedItems");
        valueId = valueType.getMethod("id");
        serializer = valueType.getMethod("serializer");
        value = valueType.getMethod("value");
        subPackets = bundleType.getMethod("subPackets");
        metadataConstructor = metadataType.getConstructor(int.class, List.class);
        valueConstructor = valueType.getConstructor(int.class, serializer.getReturnType(), Object.class);
        bundleConstructor = bundleType.getConstructor(Iterable.class);
    }

    Object hideFor(Object packet, IntPredicate hiddenEntity) throws ReflectiveOperationException {
        if (packet.getClass() == metadataType) {
            int id = (int) entityId.invoke(packet);
            if (!hiddenEntity.test(id)) return packet;
            List<?> items = (List<?>) packedItems.invoke(packet);
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                if ((int) valueId.invoke(item) != 0 || !(value.invoke(item) instanceof Byte flags)) continue;
                byte invisible = (byte) (flags | 0x20);
                if (invisible == flags) return packet;
                List<Object> copy = new ArrayList<>(items);
                copy.set(i, valueConstructor.newInstance(0, serializer.invoke(item), invisible));
                return metadataConstructor.newInstance(id, copy);
            }
            // Partial metadata without shared flags must preserve earlier flags.
            return packet;
        }
        if (packet.getClass() == bundleType) {
            Iterable<?> children = (Iterable<?>) subPackets.invoke(packet);
            List<Object> copy = null;
            int index = 0;
            for (Object child : children) {
                Object replacement = hideFor(child, hiddenEntity);
                if (copy == null && replacement != child) {
                    copy = new ArrayList<>();
                    int prefix = 0;
                    for (Object original : children) {
                        if (prefix++ == index) break;
                        copy.add(original);
                    }
                }
                if (copy != null) copy.add(replacement);
                index++;
            }
            return copy == null ? packet : bundleConstructor.newInstance(copy);
        }
        return packet;
    }
}
