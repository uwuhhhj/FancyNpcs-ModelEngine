package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static com.fancyinnovations.fancynpcsmodel.providers.modelengine.NativeNpcMetadataTest.*;

class PerPlayerNpcVisibilityTest {
    private static final UUID SELECTED = new UUID(0, 1);
    private static final UUID OVERFLOW = new UUID(0, 2);
    private final List<Throwable> failures = new ArrayList<>();
    private final PerPlayerNpcVisibility visibility = new PerPlayerNpcVisibility(
            new NativeNpcMetadata(Metadata.class, Bundle.class, Value.class), this.failures::add);

    PerPlayerNpcVisibilityTest() throws ReflectiveOperationException {
    }

    private EmbeddedChannel connect(UUID player) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("model_engine_packet_handler", new ChannelOutboundHandlerAdapter());
        this.visibility.ensureReady(player, channel);
        channel.runPendingTasks();
        assertTrue(this.visibility.ensureReady(player, channel));
        return channel;
    }

    private static Metadata metadata() {
        return new Metadata(42, List.of(new Value(0, "byte", (byte) 0)));
    }

    @Test
    void samePacketCanBeSentToModelAndVanillaViewersWithoutSharedMutation() {
        EmbeddedChannel selected = connect(SELECTED);
        EmbeddedChannel overflow = connect(OVERFLOW);
        try {
            this.visibility.setViewers(42, Set.of(SELECTED));
            Metadata original = metadata();
            selected.writeOutbound(original);
            overflow.writeOutbound(original);
            Metadata hidden = selected.readOutbound();
            assertEquals((byte) 0x20, hidden.packedItems().getFirst().value());
            assertSame(original, overflow.readOutbound());
            assertEquals((byte) 0, original.packedItems().getFirst().value());
            assertTrue(this.failures.isEmpty());
        } finally {
            this.visibility.close();
            selected.finishAndReleaseAll();
            overflow.finishAndReleaseAll();
        }
    }

    @Test
    void demotionOrRemovalImmediatelyStopsHidingTheVanillaBody() {
        EmbeddedChannel selected = connect(SELECTED);
        try {
            this.visibility.setViewers(42, Set.of(SELECTED));
            assertEquals(Set.of(SELECTED), this.visibility.clearNpc(42));
            Metadata original = metadata();
            selected.writeOutbound(original);
            assertSame(original, selected.readOutbound());
        } finally {
            this.visibility.close();
            selected.finishAndReleaseAll();
        }
    }

    @Test
    void quittingPlayerCannotRetainAHiddenBodyOnReconnect() {
        EmbeddedChannel first = connect(SELECTED);
        this.visibility.setViewers(42, Set.of(SELECTED));
        this.visibility.forgetPlayer(SELECTED);
        first.runPendingTasks();
        assertNull(first.pipeline().get("fancynpcs_model_visibility"));
        EmbeddedChannel second = connect(SELECTED);
        try {
            Metadata original = metadata();
            second.writeOutbound(original);
            assertSame(original, second.readOutbound());
        } finally {
            this.visibility.close();
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void shutdownRemovesTheHandlerAndForwardsVanillaPackets() {
        EmbeddedChannel selected = connect(SELECTED);
        this.visibility.setViewers(42, Set.of(SELECTED));
        this.visibility.close();
        selected.runPendingTasks();
        try {
            assertNull(selected.pipeline().get("fancynpcs_model_visibility"));
            Metadata original = metadata();
            selected.writeOutbound(original);
            assertSame(original, selected.readOutbound());
            assertFalse(this.visibility.ensureReady(SELECTED, selected));
        } finally {
            selected.finishAndReleaseAll();
        }
    }

    @Test
    void absentModelEnginePipelineFailsClosedWithoutDroppingPlayerPackets() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            this.visibility.ensureReady(SELECTED, channel);
            channel.runPendingTasks();
            assertFalse(this.visibility.ensureReady(SELECTED, channel));
            assertEquals(1, this.failures.size());
            Metadata original = metadata();
            channel.writeOutbound(original);
            assertSame(original, channel.readOutbound());
        } finally {
            this.visibility.close();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void readinessNotifiesOnceSoAdmissionDoesNotWaitForAnotherSlowRefresh() throws Exception {
        List<UUID> ready = new ArrayList<>();
        PerPlayerNpcVisibility notifying = new PerPlayerNpcVisibility(
                new NativeNpcMetadata(Metadata.class, Bundle.class, Value.class), this.failures::add, ready::add);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("model_engine_packet_handler", new ChannelOutboundHandlerAdapter());
        try {
            notifying.ensureReady(SELECTED, channel);
            channel.runPendingTasks();
            assertTrue(notifying.ensureReady(SELECTED, channel));
            assertEquals(List.of(SELECTED), ready);
        } finally {
            notifying.close();
            channel.finishAndReleaseAll();
        }
    }
}
