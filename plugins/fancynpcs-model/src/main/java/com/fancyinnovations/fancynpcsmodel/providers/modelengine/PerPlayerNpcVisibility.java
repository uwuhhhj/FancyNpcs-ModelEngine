package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import com.ticxo.modelengine.api.ModelEngineAPI;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Only players with a model slot hide the vanilla body. Overflow stays vanilla. */
final class PerPlayerNpcVisibility implements AutoCloseable {
    private static final String HANDLER = "fancynpcs_model_visibility";
    private final Map<Integer, Set<UUID>> hiddenFor = new ConcurrentHashMap<>();
    private final Map<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final NativeNpcMetadata metadata;
    private final Consumer<Throwable> reportFailure;
    private final Consumer<UUID> onReady;
    private volatile boolean closed;

    PerPlayerNpcVisibility(Consumer<Throwable> reportFailure, Consumer<UUID> onReady) {
        this.reportFailure = reportFailure;
        this.onReady = onReady;
        try {
            metadata = new NativeNpcMetadata(Bukkit.getServer().getClass().getClassLoader());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot prepare per-player FancyNpcs metadata", failure);
        }
    }

    PerPlayerNpcVisibility(NativeNpcMetadata metadata, Consumer<Throwable> reportFailure) {
        this(metadata, reportFailure, ignored -> { });
    }

    PerPlayerNpcVisibility(NativeNpcMetadata metadata, Consumer<Throwable> reportFailure, Consumer<UUID> onReady) {
        this.metadata = metadata;
        this.reportFailure = reportFailure;
        this.onReady = onReady;
    }

    boolean ensureReady(Player player) {
        if (closed || !player.isOnline()) return false;
        UUID viewer = player.getUniqueId();
        Connection existing = connections.get(viewer);
        if (existing != null) return existing.ready && !existing.failed && existing.channel.isActive();
        var pipeline = ModelEngineAPI.getNetworkHandler().getPipeline(viewer).orElse(null);
        if (pipeline == null) return false;
        return ensureReady(viewer, pipeline.getChannel());
    }

    boolean ensureReady(UUID viewer, Channel channel) {
        if (closed || !channel.isActive()) return false;
        Connection existing = connections.get(viewer);
        if (existing != null) return existing.ready && !existing.failed && existing.channel.isActive();
        Connection connection = new Connection(channel);
        if (connections.putIfAbsent(viewer, connection) != null) return false;
        channel.eventLoop().execute(() -> {
            if (closed || connections.get(viewer) != connection || !channel.isActive()) return;
            try {
                // Outbound traversal reaches this handler before ME and encoding.
                if (channel.pipeline().get("model_engine_packet_handler") == null)
                    throw new IllegalStateException("ModelEngine player packet handler is missing");
                channel.pipeline().addAfter("model_engine_packet_handler", HANDLER,
                        new ChannelOutboundHandlerAdapter() {
                            @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                                Object outgoing = message;
                                if (!closed && !connection.failed && !hiddenFor.isEmpty()) {
                                    try {
                                        outgoing = metadata.hideFor(message, id -> {
                                            Set<UUID> viewers = hiddenFor.get(id);
                                            return viewers != null && viewers.contains(viewer);
                                        });
                                    } catch (ReflectiveOperationException | RuntimeException failure) {
                                        connection.failed = true;
                                        reportFailure.accept(failure);
                                    }
                                }
                                // A failed adapter must not drop unrelated player packets.
                                super.write(context, outgoing, promise);
                            }
                        });
                connection.ready = true;
                onReady.accept(viewer);
            } catch (RuntimeException failure) {
                connection.failed = true;
                reportFailure.accept(failure);
            }
        });
        return connection.ready && !connection.failed;
    }

    void setViewers(int entityId, Set<UUID> viewers) {
        if (viewers.isEmpty()) hiddenFor.remove(entityId);
        else hiddenFor.put(entityId, Set.copyOf(viewers));
    }

    Set<UUID> getViewers(int entityId) {
        return hiddenFor.getOrDefault(entityId, Set.of());
    }

    Set<UUID> clearNpc(int entityId) {
        Set<UUID> previous = hiddenFor.remove(entityId);
        return previous == null ? Set.of() : previous;
    }

    void forgetPlayer(UUID player) {
        for (Integer entityId : hiddenFor.keySet()) {
            hiddenFor.computeIfPresent(entityId, (id, viewers) -> {
                if (!viewers.contains(player)) return viewers;
                Set<UUID> copy = new java.util.HashSet<>(viewers);
                copy.remove(player);
                return copy.isEmpty() ? null : Set.copyOf(copy);
            });
        }
        Connection connection = connections.remove(player);
        if (connection != null) removeHandler(connection);
    }

    private void removeHandler(Connection connection) {
        if (!connection.channel.isOpen()) return;
        try {
            connection.channel.eventLoop().execute(() -> {
                if (connection.channel.pipeline().get(HANDLER) != null)
                    connection.channel.pipeline().remove(HANDLER);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // A shutting-down channel cannot send any more packets.
        }
    }

    @Override public void close() {
        closed = true;
        hiddenFor.clear();
        connections.values().forEach(this::removeHandler);
        connections.clear();
    }

    private static final class Connection {
        final Channel channel;
        volatile boolean ready;
        volatile boolean failed;
        Connection(Channel channel) { this.channel = channel; }
    }
}
