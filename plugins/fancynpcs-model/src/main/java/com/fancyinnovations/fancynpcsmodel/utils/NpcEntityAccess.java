package com.fancyinnovations.fancynpcsmodel.utils;

import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import de.oliver.fancyanalytics.logger.properties.StringProperty;
import de.oliver.fancyanalytics.logger.properties.ThrowableProperty;
import de.oliver.fancynpcs.api.Npc;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Provides access to the (packet based) entity behind a FancyNpcs NPC.
 */
public final class NpcEntityAccess {

    private static final ClassValue<EntityAccess> ENTITY_ACCESS = new ClassValue<>() {
        @Override
        protected EntityAccess computeValue(Class<?> type) {
            Field npcField = null;
            try {
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    try {
                        npcField = current.getDeclaredField("npc");
                        break;
                    } catch (NoSuchFieldException ignored) {
                        continue;
                    }
                }
                if (npcField == null) {
                    throw new NoSuchFieldException(type.getName() + ".npc");
                }
                npcField.setAccessible(true);
                // All retained NPC implementations declare an NMS Entity field with a public Bukkit getter.
                Method bukkitEntityMethod = npcField.getType().getMethod("getBukkitEntity");
                return new EntityAccess(npcField, bukkitEntityMethod, null);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return new EntityAccess(npcField, null, failure);
            }
        }
    };

    private NpcEntityAccess() {
    }

    /**
     * @return the bukkit entity behind the given NPC or null if it could not be resolved
     */
    public static @Nullable Entity getBukkitEntity(Npc npc) {
        EntityAccess access = ENTITY_ACCESS.get(npc.getClass());
        if (access.npcField() == null) {
            FancyNpcsModelPlugin.get().getFancyLogger().error(
                    "Failed to get NMS entity from NPC",
                    ThrowableProperty.of(access.failure()),
                    StringProperty.of("npc_name", npc.getData().getName())
            );
            return null;
        }

        Object nmsEntity;
        try {
            nmsEntity = access.npcField().get(npc);
        } catch (IllegalAccessException | RuntimeException failure) {
            FancyNpcsModelPlugin.get().getFancyLogger().error(
                    "Failed to get NMS entity from NPC",
                    ThrowableProperty.of(failure),
                    StringProperty.of("npc_name", npc.getData().getName())
            );
            return null;
        }
        if (nmsEntity == null) {
            FancyNpcsModelPlugin.get().getFancyLogger().error("Failed to get NMS entity from NPC");
            return null;
        }

        if (access.bukkitEntityMethod() == null) {
            FancyNpcsModelPlugin.get().getFancyLogger().error(
                    "Failed to invoke getBukkitEntity method on NMS entity",
                    ThrowableProperty.of(access.failure()),
                    StringProperty.of("npc_name", npc.getData().getName())
            );
            return null;
        }

        try {
            return (Entity) access.bukkitEntityMethod().invoke(nmsEntity);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException failure) {
            FancyNpcsModelPlugin.get().getFancyLogger().error(
                    "Failed to invoke getBukkitEntity method on NMS entity",
                    ThrowableProperty.of(failure),
                    StringProperty.of("npc_name", npc.getData().getName())
            );
            return null;
        }
    }

    private record EntityAccess(@Nullable Field npcField, @Nullable Method bukkitEntityMethod,
                                @Nullable Throwable failure) {
    }
}
