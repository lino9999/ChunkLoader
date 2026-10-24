package io.github.lino.chunkloader.runtime;

import io.github.lino.chunkloader.model.BlockPosition;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkTickets {
    public record Key(UUID worldId, int x, int z) {
        public static Key of(BlockPosition position) {
            return new Key(position.worldId(), position.chunkX(), position.chunkZ());
        }
    }

    private final JavaPlugin plugin;
    private final Map<Key, Integer> references = new HashMap<>();
    private record ActiveChunk(Chunk chunk, long since) { }
    private static final class Request {
        private final Key key;
        private final World world;

        private Request(Key key, World world) {
            this.key = key;
            this.world = world;
        }

        private Key key() { return key; }
        private World world() { return world; }
    }

    private final Map<Key, ActiveChunk> active = new LinkedHashMap<>();
    private final Map<Key, Request> pending = new HashMap<>();
    private final Set<Request> inFlight = new HashSet<>();
    private final Set<Key> queue = new LinkedHashSet<>();
    private final Map<Key, Long> retryAt = new HashMap<>();
    private List<Chunk> activeSnapshot = List.of();
    private long tick;
    private volatile boolean closed;

    public ChunkTickets(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void retain(BlockPosition position) {
        if (closed) {
            throw new IllegalStateException("Chunk tickets are closed");
        }
        Key key = Key.of(position);
        if (references.merge(key, 1, Integer::sum) == 1) {
            queue.add(key);
        }
    }

    public void release(BlockPosition position) {
        Key key = Key.of(position);
        Integer count = references.get(key);
        if (count == null) {
            return;
        }
        if (count > 1) {
            references.put(key, count - 1);
            return;
        }
        references.remove(key);
        queue.remove(key);
        retryAt.remove(key);
        ActiveChunk acquired = active.remove(key);
        if (acquired != null) {
            refreshSnapshot();
            acquired.chunk().removePluginChunkTicket(plugin);
        }
    }

    public void pump(int limit) {
        if (closed) {
            return;
        }
        tick++;
        int remaining = Math.min(limit, Math.max(0, 8 - inFlight.size()));
        int candidates = Math.min(queue.size(), Math.max(8, remaining * 4));
        while (remaining > 0 && candidates-- > 0 && !queue.isEmpty()) {
            var iterator = queue.iterator();
            Key key = iterator.next();
            iterator.remove();
            if (!references.containsKey(key) || active.containsKey(key) || pending.containsKey(key)) {
                continue;
            }
            if (retryAt.getOrDefault(key, 0L) > tick) {
                queue.add(key);
                continue;
            }
            World world = Bukkit.getWorld(key.worldId());
            if (world == null) {
                continue;
            }
            Request request = new Request(key, world);
            pending.put(key, request);
            inFlight.add(request);
            remaining--;
            try {
                world.getChunkAtAsync(key.x(), key.z(), true).whenComplete((chunk, error) -> dispatch(request, chunk, error));
            } catch (RuntimeException error) {
                complete(request, null, error);
            }
        }
    }

    private void dispatch(Request request, Chunk chunk, Throwable error) {
        if (closed || !plugin.isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            complete(request, chunk, error);
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> complete(request, chunk, error));
        } catch (IllegalPluginAccessException stopped) {
            if (!closed && plugin.isEnabled()) {
                throw stopped;
            }
        }
    }

    private void complete(Request request, Chunk chunk, Throwable error) {
        inFlight.remove(request);
        Key key = request.key();
        if (!pending.remove(key, request) || closed || !references.containsKey(key)) {
            return;
        }
        if (Bukkit.getWorld(key.worldId()) != request.world()) {
            queue.add(key);
            return;
        }
        if (error == null && chunk != null) {
            try {
                chunk.addPluginChunkTicket(plugin);
                active.put(key, new ActiveChunk(chunk, tick));
                retryAt.remove(key);
                refreshSnapshot();
                return;
            } catch (RuntimeException failure) {
                error = failure;
            }
        }
        retryAt.put(key, tick + 200);
        queue.add(key);
        plugin.getLogger().log(Level.WARNING, "Could not load chunk " + key + "; retrying in 10 seconds", error);
    }

    private void refreshSnapshot() {
        activeSnapshot = active.values().stream().map(ActiveChunk::chunk).toList();
    }

    public Collection<Chunk> activeChunks() {
        return activeSnapshot;
    }

    public boolean active(BlockPosition position) {
        return active.containsKey(Key.of(position));
    }

    public long currentTick() {
        return tick;
    }

    public long activeSince(BlockPosition position) {
        ActiveChunk acquired = active.get(Key.of(position));
        return acquired == null ? -1 : acquired.since();
    }

    public void worldLoaded(World world) {
        for (Key key : references.keySet()) {
            if (key.worldId().equals(world.getUID())) {
                queue.add(key);
            }
        }
    }

    public void worldUnloaded(World world) {
        active.entrySet().removeIf(entry -> entry.getKey().worldId().equals(world.getUID()));
        pending.keySet().removeIf(key -> key.worldId().equals(world.getUID()));
        queue.removeIf(key -> key.worldId().equals(world.getUID()));
        retryAt.keySet().removeIf(key -> key.worldId().equals(world.getUID()));
        refreshSnapshot();
        world.removePluginChunkTickets(plugin);
    }

    public void close() {
        closed = true;
        for (World world : Bukkit.getWorlds()) {
            try {
                world.removePluginChunkTickets(plugin);
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not remove chunk tickets in " + world.getName(), error);
            }
        }
        active.clear();
        references.clear();
        queue.clear();
        pending.clear();
        inFlight.clear();
        retryAt.clear();
        activeSnapshot = List.of();
    }
}
