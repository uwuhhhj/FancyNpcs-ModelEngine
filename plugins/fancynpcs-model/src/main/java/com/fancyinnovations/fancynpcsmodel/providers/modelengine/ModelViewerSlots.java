package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Arrival order is retained while a player remains eligible, including waiters. */
final class ModelViewerSlots {
    private final LinkedHashSet<UUID> waitingOrder = new LinkedHashSet<>();

    Set<UUID> select(Collection<UUID> eligible, int capacity) {
        Set<UUID> present = Set.copyOf(eligible);
        waitingOrder.retainAll(present);
        waitingOrder.addAll(eligible);
        LinkedHashSet<UUID> selected = new LinkedHashSet<>();
        for (UUID player : waitingOrder) {
            if (selected.size() >= Math.max(0, capacity)) break;
            selected.add(player);
        }
        return selected;
    }

    void remove(UUID player) { waitingOrder.remove(player); }

    boolean hasSlot(UUID player, int capacity) {
        int remaining = Math.max(0, capacity);
        for (UUID candidate : waitingOrder) {
            if (remaining-- == 0) break;
            if (candidate.equals(player)) return true;
        }
        return false;
    }
}
