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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final Map<UUID, Long> countedAt = new HashMap<>();
    private final List<BiPredicate<Loader, UUID>> accessChecks = new ArrayList<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ChunkLoader-storage").factory());
    private CompletableFuture<Void> saveTask;
    private boolean dirty;
    private boolean storageHealthy = true;
    private boolean closed;

    public LoaderService(ChunkLoaderPlugin plugin, LoaderStore store, ChunkTickets tickets) throws IOException {
        this.plugin = plugin;
        this.store = store;
        this.tickets = tickets;
        for (Loader loader : store.load()) {
            Material material = Material.matchMaterial(loader.material());
            if (material == null || !material.isBlock() || !material.isItem() || !material.isSolid()) {
                throw new IOException("Unknown loader block material: " + loader.material());
            }
            World world = Bukkit.getWorld(loader.position().worldId());
            if (Math.abs((long) loader.position().x()) > 30000000 || Math.abs((long) loader.position().z()) > 30000000
                    || world != null && (loader.position().y() < world.getMinHeight()
                    || loader.position().y() >= world.getMaxHeight())) {
                throw new IOException("Loader position is outside the world bounds: " + loader.position());
            }
            index(new Loader(loader.id(), loader.ownerId(), loader.position(), loader.remainingTicks(), material.name()));
        }
    }

    private void index(Loader loader) {
        loaders.put(loader.id(), loader);
        positions.put(loader.position(), loader.id());
        ownerCounts.merge(loader.ownerId(), 1, Integer::sum);
        countedAt.put(loader.id(), tickets.currentTick());
        tickets.retain(loader.position());
    }

    public boolean canPlace(Player player, World world) {
        mainThread();
        finishSave();
        return !closed && storageHealthy && loaders.size() < plugin.settings().maxTotal()
                && !plugin.settings().disabledWorlds().contains(world.getName())
                && (player.hasPermission("chunkloader.bypass")
                || ownerCounts.getOrDefault(player.getUniqueId(), 0) < plugin.settings().maxPerPlayer());
    }

    @Override
    public Loader create(UUID owner, BlockPosition position, long ticks, String material) {
        mainThread();
        finishSave();
        if (closed || !storageHealthy || positions.containsKey(position) || loaders.size() >= plugin.settings().maxTotal()) {
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
        if (world.getBlockAt(position.x(), position.y(), position.z()).getType() != type) {
            throw new IllegalArgumentException("The loader block does not match its material");
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
        finishSave();
        if (closed || !storageHealthy) {
            throw new IllegalStateException("Storage is unavailable");
        }
        Loader previous = get(id);
        if (previous == null || previous.isExpired() || !isPresent(previous)) {
            throw new IllegalStateException("Loader no longer exists");
        }
        Loader updated = previous.addTicks(ticks);
        loaders.put(id, updated);
        countedAt.put(id, tickets.currentTick());
        dirty = true;
        saveAsync();
        return updated;
    }

    @Override
    public boolean remove(UUID id, boolean dropItem) {
        mainThread();
        if (closed) {
            throw new IllegalStateException("Loader service is closed");
        }
        return remove(id, dropItem, true);
    }

    public boolean discard(UUID id) {
        mainThread();
        if (closed) {
            return false;
        }
        return remove(id, false, false);
    }

    private boolean remove(UUID id, boolean dropItem, boolean changeBlock) {
        Loader current = get(id);
        Loader loader = loaders.remove(id);
        if (loader == null) {
            return false;
        }
        positions.remove(loader.position());
        ownerCounts.computeIfPresent(loader.ownerId(), (key, count) -> count <= 1 ? null : count - 1);
        countedAt.remove(id);
        try {
            World world = Bukkit.getWorld(loader.position().worldId());
            if (changeBlock && world != null && isPresent(loader)) {
                Block block = world.getBlockAt(loader.position().x(), loader.position().y(), loader.position().z());
                block.setType(Material.AIR, false);
                if (dropItem && !current.isExpired()) {
                    world.dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), plugin.items().create(
                            Material.valueOf(loader.material()), current.remainingTicks(), 1, plugin.messages()));
                }
                if (plugin.settings().particles()) {
                    world.spawnParticle(Particle.CLOUD, block.getLocation().add(0.5, 0.5, 0.5), 12, 0.3, 0.3, 0.3, 0.01);
                }
            }
        } finally {
            dirty = true;
            try {
                tickets.release(loader.position());
            } finally {
                try {
                    plugin.displays().remove(loader.id());
                } finally {
                    saveAsync();
                }
            }
        }
        return true;
    }

    public void elapse() {
        mainThread();
        for (Loader loader : List.copyOf(loaders.values())) {
            try {
                elapse(loader);
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not update loader " + loader.id(), error);
            }
        }
    }

    private void elapse(Loader loader) {
        if (!tickets.active(loader.position())) {
            return;
        }
        World world = Bukkit.getWorld(loader.position().worldId());
        if (world == null) {
            return;
        }
        if (loader.position().y() < world.getMinHeight() || loader.position().y() >= world.getMaxHeight()) {
            plugin.getLogger().warning("Removing a loader outside the current world height: " + loader.position());
            remove(loader.id(), false);
            return;
        }
        Block block = world.getBlockAt(loader.position().x(), loader.position().y(), loader.position().z());
        if (!block.getType().name().equals(loader.material())) {
            remove(loader.id(), false);
            return;
        }
        Loader updated = current(loader);
        if (updated.isExpired()) {
            remove(loader.id(), false);
        } else if (!updated.equals(loader)) {
            loaders.put(loader.id(), updated);
            countedAt.put(loader.id(), tickets.currentTick());
            dirty = true;
        }
    }

    @Override
    public List<Loader> loaders() {
        mainThread();
        return snapshot();
    }

    @Override
    public Loader find(BlockPosition position) {
        mainThread();
        return get(positions.get(position));
    }

    @Override
    public Loader get(UUID id) {
        mainThread();
        Loader loader = loaders.get(id);
        return loader == null ? null : current(loader);
    }

    private Loader current(Loader loader) {
        long since = tickets.activeSince(loader.position());
        if (since < 0 || loader.isInfinite()) {
            return loader;
        }
        long elapsed = tickets.currentTick() - Math.max(since, countedAt.getOrDefault(loader.id(), tickets.currentTick()));
        return elapsed > 0 ? loader.tick(elapsed) : loader;
    }

    private List<Loader> snapshot() {
        return loaders.values().stream().map(this::current).toList();
    }

    public boolean isPresent(Loader loader) {
        mainThread();
        World world = Bukkit.getWorld(loader.position().worldId());
        return world != null && loader.position().y() >= world.getMinHeight()
                && loader.position().y() < world.getMaxHeight()
                && world.isChunkLoaded(loader.position().chunkX(), loader.position().chunkZ())
                && world.getBlockAt(loader.position().x(), loader.position().y(), loader.position().z()).getType().name().equals(loader.material());
    }

    @Override
    public boolean hasAccess(Loader loader, UUID player) {
        mainThread();
        Loader registered = get(loader.id());
        if (closed || registered == null || registered.isExpired()) {
            return false;
        }
        if (registered.ownerId().equals(player)) {
            return true;
        }
        for (BiPredicate<Loader, UUID> check : List.copyOf(accessChecks)) {
            try {
                if (check.test(registered, player)) {
                    return loaders.containsKey(registered.id());
                }
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "An access check failed", error);
            }
        }
        return false;
    }

    public boolean hasAccess(Loader loader, Player player) {
        mainThread();
        Loader registered = get(loader.id());
        return !closed && registered != null && !registered.isExpired() && player.hasPermission("chunkloader.use") && (player.hasPermission("chunkloader.bypass")
                || hasAccess(loader, player.getUniqueId()));
    }

    @Override
    public AutoCloseable registerAccessCheck(BiPredicate<Loader, UUID> check) {
        mainThread();
        if (closed) {
            throw new IllegalStateException("Loader service is closed");
        }
        accessChecks.add(Objects.requireNonNull(check));
        return () -> {
            mainThread();
            accessChecks.remove(check);
        };
    }

    public void saveAsync() {
        mainThread();
        finishSave();
        if (closed || saveTask != null || !dirty && storageHealthy) {
            return;
        }
        dirty = false;
        List<Loader> snapshot = snapshot();
        saveTask = CompletableFuture.runAsync(() -> {
            try {
                store.save(snapshot);
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        }, writer);
    }

    private void finishSave() {
        if (saveTask == null || !saveTask.isDone()) {
            return;
        }
        try {
            saveTask.join();
            storageHealthy = true;
        } catch (CompletionException error) {
            storageHealthy = false;
            dirty = true;
            plugin.getLogger().log(Level.SEVERE, "Could not save loaders. Check disk space and permissions", error.getCause());
        } finally {
            saveTask = null;
        }
    }

    public void pollPersistence() {
        mainThread();
        boolean completed = saveTask != null && saveTask.isDone();
        finishSave();
        if (completed && dirty && storageHealthy && !closed) {
            saveAsync();
        }
    }

    public void close() throws IOException {
        mainThread();
        if (closed) {
            return;
        }
        elapse();
        closed = true;
        accessChecks.clear();
        List<Loader> snapshot = snapshot();
        var finalWrite = writer.submit(() -> {
            try {
                store.save(snapshot);
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        });
        writer.shutdown();
        try {
            finalWrite.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            throw new IOException("Final loader save failed", error.getCause());
        } catch (TimeoutException error) {
            throw new IOException("Timed out waiting for the final loader save", error);
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
