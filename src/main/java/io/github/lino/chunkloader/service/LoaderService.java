package io.github.lino.chunkloader.service;

import io.github.lino.chunkloader.ChunkLoaderPlugin;
import io.github.lino.chunkloader.api.ChunkLoaderApi;
import io.github.lino.chunkloader.model.BlockPosition;
import io.github.lino.chunkloader.model.Loader;
import io.github.lino.chunkloader.runtime.ChunkTickets;
import io.github.lino.chunkloader.storage.LoaderStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class LoaderService implements ChunkLoaderApi {
    private final ChunkLoaderPlugin plugin;
    private final LoaderStore store;
    private final ChunkTickets tickets;
    private final Map<UUID, Loader> loaders = new LinkedHashMap<>();
    private final Map<BlockPosition, UUID> positions = new HashMap<>();
    private final Map<UUID, Integer> ownerCounts = new HashMap<>();
    private final List<BiPredicate<Loader, UUID>> accessChecks = new ArrayList<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ChunkLoader-storage").factory());
    private boolean saving;
    private boolean dirty;
    private volatile boolean storageHealthy = true;

    public LoaderService(ChunkLoaderPlugin plugin, LoaderStore store, ChunkTickets tickets) throws IOException {
        this.plugin = plugin;
        this.store = store;
        this.tickets = tickets;
        for (Loader loader : store.load()) {
            if (!loader.isExpired()) {
                Material material = Material.matchMaterial(loader.material());
                if (material == null || !material.isBlock()) {
                    throw new IOException("Unknown loader block material: " + loader.material());
                }
                World world = Bukkit.getWorld(loader.position().worldId());
                if (Math.abs((long) loader.position().x()) > 30000000 || Math.abs((long) loader.position().z()) > 30000000
                        || world != null && (loader.position().y() < world.getMinHeight()
                        || loader.position().y() >= world.getMaxHeight())) {
                    throw new IOException("Loader position is outside the world bounds: " + loader.position());
                }
                index(loader);
            }
        }
    }

    private void index(Loader loader) {
        loaders.put(loader.id(), loader);
        positions.put(loader.position(), loader.id());
        ownerCounts.merge(loader.ownerId(), 1, Integer::sum);
        tickets.retain(loader.position());
    }

    public boolean canPlace(Player player, World world) {
        return storageHealthy && loaders.size() < plugin.settings().maxTotal()
                && !plugin.settings().disabledWorlds().contains(world.getName())
                && (player.hasPermission("chunkloader.bypass")
                || ownerCounts.getOrDefault(player.getUniqueId(), 0) < plugin.settings().maxPerPlayer());
    }

    @Override
    public Loader create(UUID owner, BlockPosition position, long ticks, String material) {
        mainThread();
        if (!storageHealthy || positions.containsKey(position) || loaders.size() >= plugin.settings().maxTotal()) {
            throw new IllegalStateException("Loader cannot be created at this location");
        }
        Material type = Material.matchMaterial(material);
        if (type == null || !type.isBlock() || !type.isItem() || !type.isSolid()) {
            throw new IllegalArgumentException("Invalid loader material");
        }
        World world = Bukkit.getWorld(position.worldId());
        if (world == null || position.y() < world.getMinHeight() || position.y() >= world.getMaxHeight()
                || Math.abs((long) position.x()) > 30000000 || Math.abs((long) position.z()) > 30000000
                || !world.isChunkLoaded(position.chunkX(), position.chunkZ())) {
            throw new IllegalArgumentException("Loader position must be inside a loaded world chunk");
        }
        Loader loader = new Loader(UUID.randomUUID(), owner, position, ticks, type.name());
        if (loader.isExpired()) {
            throw new IllegalArgumentException("Loader must have remaining time");
        }
        index(loader);
        dirty = true;
        saveAsync();
        return loader;
    }

    @Override
    public Loader addTime(UUID id, long ticks) {
        mainThread();
        if (!storageHealthy) {
            throw new IllegalStateException("Storage is unavailable");
        }
        Loader previous = loaders.get(id);
        if (previous == null) {
            throw new IllegalStateException("Loader no longer exists");
        }
        Loader updated = previous.addTicks(ticks);
        loaders.put(id, updated);
        dirty = true;
        saveAsync();
        return updated;
    }

    @Override
    public boolean remove(UUID id, boolean dropItem) {
        mainThread();
        Loader loader = loaders.remove(id);
        if (loader == null) {
            return false;
        }
        positions.remove(loader.position());
        ownerCounts.computeIfPresent(loader.ownerId(), (key, count) -> count <= 1 ? null : count - 1);
        World world = Bukkit.getWorld(loader.position().worldId());
        if (world != null && world.isChunkLoaded(loader.position().chunkX(), loader.position().chunkZ())) {
            Block block = world.getBlockAt(loader.position().x(), loader.position().y(), loader.position().z());
            if (block.getType().name().equals(loader.material())) {
                block.setType(Material.AIR, false);
            }
            if (dropItem && !loader.isExpired()) {
                world.dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), plugin.items().create(
                        Material.valueOf(loader.material()), loader.remainingTicks(), 1, plugin.messages()));
            }
            if (plugin.settings().particles()) {
                world.spawnParticle(Particle.CLOUD, block.getLocation().add(0.5, 0.5, 0.5), 12, 0.3, 0.3, 0.3, 0.01);
            }
        }
        tickets.release(loader.position());
        plugin.displays().remove(loader.id());
        dirty = true;
        saveAsync();
        return true;
    }

    public void elapse(long ticks) {
        mainThread();
        for (Loader loader : List.copyOf(loaders.values())) {
            if (!tickets.active(loader.position())) {
                continue;
            }
            World world = Bukkit.getWorld(loader.position().worldId());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(loader.position().x(), loader.position().y(), loader.position().z());
            if (!block.getType().name().equals(loader.material())) {
                remove(loader.id(), false);
                continue;
            }
            Loader updated = loader.tick(ticks);
            if (updated.isExpired()) {
                remove(loader.id(), false);
            } else if (!updated.equals(loader)) {
                loaders.put(loader.id(), updated);
                dirty = true;
            }
        }
    }

    @Override
    public List<Loader> loaders() {
        mainThread();
        return List.copyOf(loaders.values());
    }

    @Override
    public Loader find(BlockPosition position) {
        mainThread();
        return loaders.get(positions.get(position));
    }

    @Override
    public Loader get(UUID id) {
        mainThread();
        return loaders.get(id);
    }

    @Override
    public boolean hasAccess(Loader loader, UUID player) {
        mainThread();
        if (!loaders.containsKey(loader.id())) {
            return false;
        }
        if (loader.ownerId().equals(player)) {
            return true;
        }
        for (BiPredicate<Loader, UUID> check : List.copyOf(accessChecks)) {
            try {
                if (check.test(loader, player)) {
                    return true;
                }
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "An access check failed", error);
            }
        }
        return false;
    }

    public boolean hasAccess(Loader loader, Player player) {
        return player.hasPermission("chunkloader.use") && (player.hasPermission("chunkloader.bypass")
                || hasAccess(loader, player.getUniqueId()));
    }

    @Override
    public AutoCloseable registerAccessCheck(BiPredicate<Loader, UUID> check) {
        mainThread();
        accessChecks.add(Objects.requireNonNull(check));
        return () -> {
            mainThread();
            accessChecks.remove(check);
        };
    }

    public void saveAsync() {
        mainThread();
        if (saving || !dirty) {
            return;
        }
        saving = true;
        dirty = false;
        List<Loader> snapshot = List.copyOf(loaders.values());
        writer.submit(() -> {
            try {
                store.save(snapshot);
                storageHealthy = true;
            } catch (IOException error) {
                storageHealthy = false;
                plugin.getLogger().log(Level.SEVERE, "Could not save loaders. Check disk space and permissions", error);
            } finally {
                if (plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        saving = false;
                        if (!storageHealthy) {
                            dirty = true;
                        } else {
                            saveAsync();
                        }
                    });
                }
            }
        });
    }

    public void close() throws IOException {
        mainThread();
        List<Loader> snapshot = List.copyOf(loaders.values());
        writer.submit(() -> {
            try {
                store.save(snapshot);
            } catch (IOException error) {
                plugin.getLogger().log(Level.SEVERE, "Final loader save failed", error);
            }
        });
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for the final loader save");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while saving loaders", error);
        }
    }

    private static void mainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ChunkLoader API must be called on the main server thread");
        }
    }

    public static BlockPosition position(Block block) {
        return new BlockPosition(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
