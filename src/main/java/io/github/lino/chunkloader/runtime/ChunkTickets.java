package io.github.lino.chunkloader.runtime;

import io.github.lino.chunkloader.model.BlockPosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkTickets {
    public record Key(UUID worldId, int x, int z) {
        public static Key of(BlockPosition position) {
            return new Key(position.worldId(), position.chunkX(), position.chunkZ());
        }
    }

    private final JavaPlugin plugin;
    private final Map<Key, Integer> references = new HashMap<>();
    private final Map<Key, Chunk> active = new HashMap<>();
    private final Set<Key> pending = new HashSet<>();
    private final ArrayDeque<Key> queue = new ArrayDeque<>();
    private volatile boolean closed;

    public ChunkTickets(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void retain(BlockPosition position) {
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
        Chunk chunk = active.remove(key);
        if (chunk != null) {
            chunk.removePluginChunkTicket(plugin);
        }
    }

    public void pump(int limit) {
        int remaining = Math.min(limit, Math.max(0, 8 - pending.size()));
        while (remaining-- > 0 && !queue.isEmpty()) {
            Key key = queue.remove();
            if (!references.containsKey(key) || active.containsKey(key) || pending.contains(key)) {
                continue;
            }
            World world = Bukkit.getWorld(key.worldId());
            if (world == null) {
                continue;
            }
            pending.add(key);
            world.getChunkAtAsync(key.x(), key.z(), true).whenComplete((chunk, error) -> {
                if (closed || !plugin.isEnabled()) {
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    pending.remove(key);
                    if (closed || !references.containsKey(key) || Bukkit.getWorld(key.worldId()) != world) {
                        return;
                    }
                    if (error != null || chunk == null) {
                        plugin.getLogger().warning("Could not load chunk " + key + "; retrying later");
                        queue.add(key);
                        return;
                    }
                    chunk.addPluginChunkTicket(plugin);
                    active.put(key, chunk);
                });
            });
        }
    }

    public Collection<Chunk> activeChunks() {
        return new ArrayList<>(active.values());
    }

    public boolean active(BlockPosition position) {
        return active.containsKey(Key.of(position));
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
        pending.removeIf(key -> key.worldId().equals(world.getUID()));
        world.removePluginChunkTickets(plugin);
    }

    public void close() {
        closed = true;
        for (World world : Bukkit.getWorlds()) {
            world.removePluginChunkTickets(plugin);
        }
        active.clear();
        references.clear();
        queue.clear();
        pending.clear();
    }
}
