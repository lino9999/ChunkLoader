package io.github.lino.chunkloader.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.spawner.SpawnRule;
import org.bukkit.block.spawner.SpawnerEntry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

public final class FarmSimulation {
    private final JavaPlugin plugin;
    private final Map<ChunkKey, Progress> progress = new HashMap<>();
    private final Map<SpawnerKey, SpawnerEntry> selectedSpawns = new HashMap<>();
    private final Map<Entity, CreatureSpawner> pendingSpawns = new IdentityHashMap<>();
    private boolean spawnEventsRegistered;
    private long clock;
    private long nextWarning;
    private int cursor;

    public FarmSimulation(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void tick(Collection<Chunk> chunks, int elapsedTicks, boolean randomTicks,
                     boolean spawners, int maxSamplesPerChunk, int maxChunksPerTick) {
        if (elapsedTicks <= 0) {
            return;
        }
        clock += elapsedTicks;
        if (!randomTicks && !spawners) {
            progress.clear();
            selectedSpawns.clear();
            return;
        }
        List<Chunk> unique = new ArrayList<>();
        Set<ChunkKey> active = new HashSet<>();
        for (Chunk chunk : chunks) {
            ChunkKey key = key(chunk);
            if (active.add(key)) {
                unique.add(chunk);
                progress.computeIfAbsent(key, ignored -> new Progress(clock - elapsedTicks));
            }
        }
        progress.keySet().retainAll(active);
        selectedSpawns.keySet().removeIf(key -> !active.contains(key.chunk()));
        if (unique.isEmpty() || maxChunksPerTick <= 0 || (!randomTicks && !spawners)) {
            return;
        }
        int limit = Math.min(unique.size(), maxChunksPerTick);
        for (int index = 0; index < limit; index++) {
            Chunk chunk = unique.get(Math.floorMod(cursor++, unique.size()));
            Progress state = progress.get(key(chunk));
            long elapsed = Math.min(200, clock - state.lastVisit);
            state.lastVisit = clock;
            if (!chunk.isLoaded()) {
                continue;
            }
            try {
                if (randomTicks && maxSamplesPerChunk > 0) {
                    randomTicks(chunk, state, elapsed, maxSamplesPerChunk);
                } else {
                    state.sampleCredit = 0;
                }
                if (spawners) {
                    spawners(chunk, state, (int) elapsed);
                }
            } catch (RuntimeException exception) {
                if (clock >= nextWarning) {
                    nextWarning = clock + 1200;
                    plugin.getLogger().log(Level.WARNING, "Farm simulation failed in "
                            + chunk.getWorld().getName() + " at " + chunk.getX() + ", " + chunk.getZ(), exception);
                }
            }
        }
        cursor = Math.floorMod(cursor, unique.size());
    }

    private void randomTicks(Chunk chunk, Progress state, long elapsed, int budget) {
        World world = chunk.getWorld();
        if (chunk.getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING || hasSimulatingPlayer(chunk)) {
            state.sampleCredit = 0;
            return;
        }
        Integer speed = world.getGameRuleValue(GameRules.RANDOM_TICK_SPEED);
        if (speed == null || speed <= 0) {
            state.sampleCredit = 0;
            return;
        }
        int firstSection = Math.floorDiv(world.getMinHeight(), 16);
        int sections = Math.floorDiv(world.getMaxHeight() - 1, 16) - firstSection + 1;
        if (sections <= 0) {
            return;
        }
        state.sampleCredit = Math.min((double) budget * 2,
                state.sampleCredit + (double) speed * elapsed * sections);
        int samples = (int) Math.min(budget, state.sampleCredit);
        state.sampleCredit -= samples;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < samples; index++) {
            int section = firstSection + Math.floorMod(state.sectionCursor++, sections);
            int y = section * 16 + random.nextInt(16);
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            Block block = chunk.getBlock(random.nextInt(16), y, random.nextInt(16));
            if (block.getBlockData().isRandomlyTicked()) {
                block.randomTick();
            }
        }
        state.sectionCursor = Math.floorMod(state.sectionCursor, sections);
    }

    private boolean hasSimulatingPlayer(Chunk chunk) {
        for (Player player : chunk.getWorld().getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            Location location = player.getLocation();
            int distance = Math.max(8, player.getSimulationDistance()) + 1;
            if (Math.abs((long) (location.getBlockX() >> 4) - chunk.getX()) <= distance
                    && Math.abs((long) (location.getBlockZ() >> 4) - chunk.getZ()) <= distance) {
                return true;
            }
        }
        return false;
    }

    private void spawners(Chunk chunk, Progress state, int elapsed) {
        BlockState[] blocks = chunk.getTileEntities(false);
        if (blocks.length == 0) {
            return;
        }
        int remainingAttempts = 64;
        int limit = Math.min(64, blocks.length);
        for (int index = 0; index < limit; index++) {
            BlockState block = blocks[Math.floorMod(state.tileCursor++, blocks.length)];
            if (block instanceof CreatureSpawner spawner && !spawner.isActivated()) {
                remainingAttempts -= tickSpawner(spawner, elapsed, remainingAttempts);
                if (remainingAttempts <= 0) {
                    break;
                }
            }
        }
        state.tileCursor = Math.floorMod(state.tileCursor, blocks.length);
    }

    private int tickSpawner(CreatureSpawner spawner, int elapsed, int budget) {
        if (spawner.getSpawnCount() <= 0 || spawner.getMaxNearbyEntities() <= 0) {
            return 0;
        }
        if (spawner.getDelay() < 0) {
            reset(spawner);
            spawner.update(false, false);
            return 0;
        }
        if (spawner.getDelay() > 0) {
            spawner.setDelay(Math.max(0, spawner.getDelay() - elapsed));
            spawner.update(false, false);
            return 0;
        }
        EntitySnapshot snapshot = spawner.getSpawnedEntity();
        EntityType type = snapshot == null ? spawner.getSpawnedType() : snapshot.getEntityType();
        if (type == null || !type.isSpawnable() || type.getEntityClass() == null) {
            return 0;
        }
        Location center = spawner.getLocation().add(0.5, 0.5, 0.5);
        int range = Math.max(0, Math.min(128, spawner.getSpawnRange()));
        BoundingBox area = BoundingBox.of(center, range + 0.5, range + 0.5, range + 0.5);
        if (spawner.getWorld().getNearbyEntities(area, entity -> entity.getType() == type).size()
                >= spawner.getMaxNearbyEntities()) {
            reset(spawner);
            spawner.update(false, false);
            return 0;
        }
        int attempts = Math.min(Math.min(spawner.getSpawnCount(), 16), budget);
        SpawnRule rule = spawnRule(spawner, snapshot);
        boolean spawned = false;
        for (int attempt = 0; attempt < attempts; attempt++) {
            Location location = spawnLocation(center, range);
            World world = spawner.getWorld();
            if (location.getY() < world.getMinHeight() || location.getY() >= world.getMaxHeight()
                    || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                continue;
            }
            Entity entity = snapshot == null ? world.createEntity(location, type.getEntityClass())
                    : snapshot.createEntity(world);
            if (!canSpawn(entity, location, rule)) {
                continue;
            }
            if (spawn(entity, location, spawner)) {
                spawned = true;
                if (world.getNearbyEntities(area, nearby -> nearby.getType() == type).size()
                        >= spawner.getMaxNearbyEntities()) {
                    break;
                }
            }
        }
        if (spawned) {
            reset(spawner);
            spawner.update(false, false);
        }
        return attempts;
    }

    private boolean spawn(Entity entity, Location location, CreatureSpawner spawner) {
        if (!spawnEventsRegistered) {
            plugin.getServer().getPluginManager().registerEvent(EntitySpawnEvent.class,
                    new Listener() { }, EventPriority.LOWEST, (listener, event) -> {
                        if (!(event instanceof EntitySpawnEvent spawnEvent) || event instanceof SpawnerSpawnEvent) {
                            return;
                        }
                        CreatureSpawner source = pendingSpawns.get(spawnEvent.getEntity());
                        if (source != null && !new SpawnerSpawnEvent(spawnEvent.getEntity(), source).callEvent()) {
                            spawnEvent.setCancelled(true);
                        }
                    }, plugin, false);
            spawnEventsRegistered = true;
        }
        pendingSpawns.put(entity, spawner);
        try {
            return entity.spawnAt(location, SpawnReason.SPAWNER);
        } finally {
            pendingSpawns.remove(entity);
        }
    }

    private boolean canSpawn(Entity entity, Location location, SpawnRule rule) {
        if (entity instanceof Mob mob && location.getWorld().getDifficulty() == Difficulty.PEACEFUL
                && mob.shouldDespawnInPeaceful()) {
            return false;
        }
        Block block = location.getBlock();
        if (rule != null) {
            if (block.getLightFromBlocks() < rule.getMinBlockLight()
                    || block.getLightFromBlocks() > rule.getMaxBlockLight()
                    || block.getLightFromSky() < rule.getMinSkyLight()
                    || block.getLightFromSky() > rule.getMaxSkyLight()) {
                return false;
            }
        } else if (entity.getSpawnCategory() == SpawnCategory.MONSTER
                && entity.getType() != EntityType.BLAZE
                && (block.getLightFromBlocks() > 0 || block.getLightLevel() > 7)) {
            return false;
        } else if (entity.getType() == EntityType.BLAZE && block.getLightLevel() > 11) {
            return false;
        }
        return !entity.collidesAt(location);
    }

    private SpawnRule spawnRule(CreatureSpawner spawner, EntitySnapshot snapshot) {
        SpawnerEntry selected = selectedSpawns.get(spawnerKey(spawner));
        if (selected != null && snapshot != null
                && selected.getSnapshot().getAsString().equals(snapshot.getAsString())) {
            return selected.getSpawnRule();
        }
        if (snapshot != null) {
            for (SpawnerEntry entry : spawner.getPotentialSpawns()) {
                if (entry.getSnapshot().getAsString().equals(snapshot.getAsString())) {
                    return entry.getSpawnRule();
                }
            }
        }
        return null;
    }

    private void reset(CreatureSpawner spawner) {
        int minimum = Math.max(1, spawner.getMinSpawnDelay());
        int maximum = Math.max(minimum, spawner.getMaxSpawnDelay());
        spawner.setDelay(maximum == minimum ? minimum
                : ThreadLocalRandom.current().nextInt(minimum, maximum));
        List<SpawnerEntry> entries = spawner.getPotentialSpawns();
        long total = 0;
        for (SpawnerEntry entry : entries) {
            total += Math.max(0, entry.getSpawnWeight());
        }
        if (total > 0) {
            long choice = ThreadLocalRandom.current().nextLong(total);
            for (SpawnerEntry entry : entries) {
                choice -= Math.max(0, entry.getSpawnWeight());
                if (choice < 0) {
                    spawner.setSpawnedEntity(entry);
                    selectedSpawns.put(spawnerKey(spawner), entry);
                    break;
                }
            }
        }
    }

    private Location spawnLocation(Location center, int range) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return center.clone().add((random.nextDouble() - random.nextDouble()) * range,
                random.nextInt(3) - 1 - 0.5,
                (random.nextDouble() - random.nextDouble()) * range);
    }

    private ChunkKey key(Chunk chunk) {
        return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    private SpawnerKey spawnerKey(CreatureSpawner spawner) {
        return new SpawnerKey(new ChunkKey(spawner.getWorld().getUID(),
                spawner.getX() >> 4, spawner.getZ() >> 4), spawner.getX(), spawner.getY(), spawner.getZ());
    }

    private record ChunkKey(UUID world, int x, int z) { }

    private record SpawnerKey(ChunkKey chunk, int x, int y, int z) { }

    private static final class Progress {
        private long lastVisit;
        private double sampleCredit;
        private int sectionCursor;
        private int tileCursor;

        private Progress(long lastVisit) {
            this.lastVisit = lastVisit;
        }
    }
}
