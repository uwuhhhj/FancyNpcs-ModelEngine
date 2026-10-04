package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import com.fancyinnovations.fancynpcsmodel.config.FancyNpcsModelConfigImpl.ViewerProtection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ModelViewerSlotsTest {
    private static final List<UUID> PLAYERS = IntStream.range(0, 25).mapToObj(i -> new UUID(0, i)).toList();

    @Test void elevenViewersOnlyAdmitFirstTen() {
        assertEquals(PLAYERS.subList(0, 10), new ArrayList<>(new ModelViewerSlots().select(PLAYERS.subList(0, 11), 10)));
    }

    @Test void onlinePlayerIterationOrderCannotReplaceExistingViewers() {
        ModelViewerSlots slots = new ModelViewerSlots();
        Set<UUID> original = slots.select(PLAYERS, 10);
        List<UUID> reordered = new ArrayList<>(PLAYERS);
        Collections.reverse(reordered);
        assertEquals(original, slots.select(reordered, 10));
    }

    @Test void oldestWaitingPlayerFillsVacancyAheadOfNewArrival() {
        ModelViewerSlots slots = new ModelViewerSlots();
        slots.select(PLAYERS.subList(0, 12), 10);
        List<UUID> next = new ArrayList<>(PLAYERS.subList(1, 13));
        Collections.reverse(next);
        assertEquals(PLAYERS.subList(1, 11), new ArrayList<>(slots.select(next, 10)));
    }

    @Test void returningPlayerRejoinsTheEndOfTheQueue() {
        ModelViewerSlots slots = new ModelViewerSlots();
        slots.select(PLAYERS.subList(0, 12), 10);
        slots.select(PLAYERS.subList(1, 12), 10);
        assertEquals(PLAYERS.subList(1, 11), new ArrayList<>(slots.select(PLAYERS.subList(0, 12), 10)));
    }

    @Test void reducedAndIncreasedLimitsTakeEffectWithoutReshuffling() {
        ModelViewerSlots slots = new ModelViewerSlots();
        slots.select(PLAYERS, 10);
        assertEquals(PLAYERS.subList(0, 3), new ArrayList<>(slots.select(PLAYERS, 3)));
        assertEquals(PLAYERS.subList(0, 15), new ArrayList<>(slots.select(PLAYERS, 15)));
    }

    @Test void zeroLimitRetainsWaitOrderForSubsequentRecovery() {
        ModelViewerSlots slots = new ModelViewerSlots();
        assertTrue(slots.select(PLAYERS, 0).isEmpty());
        List<UUID> reordered = new ArrayList<>(PLAYERS);
        Collections.reverse(reordered);
        assertEquals(PLAYERS.subList(0, 10), new ArrayList<>(slots.select(reordered, 10)));
    }

    @Test void everyNpcOwnsAnIndependentLimit() {
        assertEquals(10, new ModelViewerSlots().select(PLAYERS, 10).size());
        assertEquals(10, new ModelViewerSlots().select(PLAYERS, 10).size());
    }

    @Test void quitRemovesThePlayerBeforeTheNextRefresh() {
        ModelViewerSlots slots = new ModelViewerSlots();
        slots.select(PLAYERS, 10);
        slots.remove(PLAYERS.getFirst());
        assertEquals(PLAYERS.subList(1, 11), new ArrayList<>(slots.select(PLAYERS.subList(1, 25), 10)));
    }

    @Test void disabledProtectionAdmitsEveryoneAndNegativeLimitsFailClosed() {
        assertEquals(25, new ModelViewerSlots().select(PLAYERS, new ViewerProtection(false, 10).capacity()).size());
        assertTrue(new ModelViewerSlots().select(PLAYERS, new ViewerProtection(true, -1).capacity()).isEmpty());
    }

    @Test void asynchronousAdmissionCannotBypassAChangedLimitOrAReleasedSlot() {
        ModelViewerSlots slots = new ModelViewerSlots();
        slots.select(PLAYERS, 10);
        assertTrue(slots.hasSlot(PLAYERS.get(9), 10));
        assertFalse(slots.hasSlot(PLAYERS.get(9), 3));
        assertFalse(slots.hasSlot(PLAYERS.getFirst(), 0));
        slots.remove(PLAYERS.getFirst());
        assertFalse(slots.hasSlot(PLAYERS.getFirst(), 10));
        assertTrue(slots.hasSlot(PLAYERS.get(10), 10));
    }
}
