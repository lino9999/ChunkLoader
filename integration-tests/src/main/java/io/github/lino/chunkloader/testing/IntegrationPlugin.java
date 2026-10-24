package io.github.lino.chunkloader.testing;

import io.github.lino.chunkloader.api.ChunkLoaderApi;
import io.github.lino.chunkloader.model.BlockPosition;
import io.github.lino.chunkloader.model.Loader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Furnace;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class IntegrationPlugin extends JavaPlugin implements Listener {
    private final List<String> checks = new ArrayList<>();
    private final UUID owner = UUID.fromString("c30d3e17-cffc-4207-9577-90733c84f9cc");
    private ChunkLoaderApi api;
    private World world;
    private Chunk primary;
    private Chunk baseline;
    private Loader first;
    private Loader second;
    private Loader expiring;
    private Integer previousRandomTickSpeed;
    private boolean finished;
    private boolean cancelSpawnerSpawns = true;
    private int cancelledSpawnerAttempts;
    private boolean invalidSpawnerEventLocation;
    private boolean spawnerSpawnReasonObserved;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, () -> guarded(this::begin), 20);
        Bukkit.getScheduler().runTaskLater(this,
                () -> guarded(() -> { throw new IllegalStateException("Integration test timed out"); }), 2400);
    }

    private void begin() throws Exception {
        api = Bukkit.getServicesManager().load(ChunkLoaderApi.class);
        require(api != null, "API service registered");
        require(Bukkit.getOnlinePlayers().isEmpty(), "No players connected");
        world = Bukkit.getWorlds().getFirst();
        Path marker = getDataFolder().toPath().resolve("restart-loader.txt");
        if (Files.exists(marker)) {
            UUID id = UUID.fromString(Files.readString(marker).trim());
            Loader restored = api.get(id);
            require(restored != null && restored.isInfinite(), "Infinite loader restored after restart");
            Bukkit.getScheduler().runTaskLater(this, () -> guarded(() -> {
                require(world.getChunkAt(256, 256).getPluginChunkTickets().stream()
                        .anyMatch(plugin -> plugin.getName().equals("ChunkLoader")), "Ticket restored after restart");
                require(api.remove(id, false), "Restored loader removable");
                Files.delete(marker);
                complete("integration-result.json", true, false);
            }), 40);
            return;
        }
        for (Loader loader : api.loaders()) {
            if (loader.ownerId().equals(owner)) {
                api.remove(loader.id(), false);
            }
        }
        world.getChunkAtAsync(256, 256, true).thenCombine(world.getChunkAtAsync(258, 256, true),
                (chunk, control) -> {
                    Bukkit.getScheduler().runTask(this, () -> guarded(() -> prepare(chunk, control)));
                    return null;
                }).exceptionally(error -> {
                    Bukkit.getScheduler().runTask(this, () -> guarded(() -> { throw new IllegalStateException(error); }));
                    return null;
                });
    }

    private void prepare(Chunk chunk, Chunk control) throws Exception {
        primary = chunk;
        baseline = control;
        baseline.addPluginChunkTicket(this);
        previousRandomTickSpeed = world.getGameRuleValue(GameRules.RANDOM_TICK_SPEED);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 128);
        int x = chunk.getX() * 16;
        int z = chunk.getZ() * 16;
        for (Entity entity : world.getNearbyEntities(new org.bukkit.Location(world, x + 8, 120, z + 8), 32, 32, 32)) {
            if ("ChunkLoader integration pig".equals(entity.getCustomName())) {
                entity.remove();
            }
        }
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int y = 119; y <= 126; y++) {
                    world.getBlockAt(x + dx, y, z + dz).setType(y == 119 ? Material.STONE : Material.AIR, false);
                }
            }
        }
        crops(chunk);
        crops(control);
        world.getBlockAt(x + 10, 120, z + 10).setType(Material.FURNACE);
        Furnace furnace = (Furnace) world.getBlockAt(x + 10, 120, z + 10).getState();
        furnace.getInventory().setSmelting(new ItemStack(Material.RAW_IRON, 4));
        furnace.getInventory().setFuel(new ItemStack(Material.COAL, 4));
        world.getBlockAt(x + 12, 120, z + 10).setType(Material.CHEST);
        world.getBlockAt(x + 12, 121, z + 10).setType(Material.HOPPER);
        Hopper hopper = (Hopper) world.getBlockAt(x + 12, 121, z + 10).getState();
        hopper.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 8));
        world.getBlockAt(x + 11, 120, z + 4).setType(Material.SPAWNER);
        CreatureSpawner spawner = (CreatureSpawner) world.getBlockAt(x + 11, 120, z + 4).getState();
        Pig template = world.createEntity(spawner.getLocation(), Pig.class);
        template.setCustomName("ChunkLoader integration pig");
        spawner.setSpawnedEntity(template.createSnapshot());
        spawner.setMinSpawnDelay(1);
        spawner.setMaxSpawnDelay(1);
        spawner.setDelay(0);
        spawner.setSpawnRange(2);
        spawner.setSpawnCount(4);
        spawner.setMaxNearbyEntities(4);
        spawner.update();
        first = create(x + 9, 125, z + 1, -1);
        second = create(x + 10, 125, z + 1, 10000);
        expiring = create(x + 11, 125, z + 1, 40);
        require(api.find(first.position()).id().equals(first.id()), "Position lookup");
        require(api.hasAccess(first, owner), "Owner access");
        UUID visitor = UUID.randomUUID();
        require(!api.hasAccess(first, visitor), "Untrusted visitor denied");
        try (AutoCloseable registration = api.registerAccessCheck((loader, player) -> player.equals(visitor))) {
            require(api.hasAccess(first, visitor), "Access extension accepted");
        }
        require(!api.hasAccess(first, visitor), "Access extension unregisters");
        require(api.addTime(second.id(), 100).remainingTicks() >= 10000, "Fuel added through API");
        Bukkit.getScheduler().runTaskLater(this, () -> guarded(this::verifyTicket), 100);
        Bukkit.getScheduler().runTaskLater(this, () -> guarded(this::verifyFarms), 400);
    }

    private Loader create(int x, int y, int z, long ticks) {
        world.getBlockAt(x, y, z).setType(Material.END_PORTAL_FRAME);
        return api.create(owner, new BlockPosition(world.getUID(), x, y, z), ticks, Material.END_PORTAL_FRAME.name());
    }

    private void crops(Chunk chunk) {
        int x = chunk.getX() * 16;
        int z = chunk.getZ() * 16;
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                world.getBlockAt(x + dx, 120, z + dz).setType(Material.FARMLAND, false);
                Farmland farmland = (Farmland) world.getBlockAt(x + dx, 120, z + dz).getBlockData();
                farmland.setMoisture(farmland.getMaximumMoisture());
                world.getBlockAt(x + dx, 120, z + dz).setBlockData(farmland, false);
                world.getBlockAt(x + dx, 121, z + dz).setType(Material.WHEAT, false);
            }
        }
    }

    private void verifyTicket() {
        require(primary.getPluginChunkTickets().stream().anyMatch(plugin -> plugin.getName().equals("ChunkLoader")),
                "Plugin chunk ticket installed");
        require(primary.getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING, "Chunk entity ticking");
        require(cancelledSpawnerAttempts > 0, "Spawner cancellation event delivered");
        require(!invalidSpawnerEventLocation, "Spawner event receives correct unspawned entity location");
        require(world.getNearbyEntities(new org.bukkit.Location(world, primary.getX() * 16 + 8, 120,
                        primary.getZ() * 16 + 8), 32, 32, 32).stream()
                        .noneMatch(entity -> "ChunkLoader integration pig".equals(entity.getCustomName())),
                "Cancelled spawner event prevents insertion into world");
        cancelSpawnerSpawns = false;
        require(api.remove(second.id(), false), "Overlapping loader removed");
        require(primary.getPluginChunkTickets().stream().anyMatch(plugin -> plugin.getName().equals("ChunkLoader")),
                "Overlapping loader retains ticket");
    }

    private void verifyFarms() throws Exception {
        require(api.get(expiring.id()) == null, "Timed loader expired");
        require(api.get(first.id()).isInfinite(), "Infinite loader remains active");
        require(grown(primary), "Crops grow without players");
        int x = primary.getX() * 16;
        int z = primary.getZ() * 16;
        Furnace furnace = (Furnace) world.getBlockAt(x + 10, 120, z + 10).getState();
        require(furnace.getInventory().getResult() != null, "Furnace smelts without players");
        Chest chest = (Chest) world.getBlockAt(x + 12, 120, z + 10).getState();
        require(chest.getBlockInventory().contains(Material.COBBLESTONE), "Hopper transfers without players");
        long pigs = world.getNearbyEntities(world.getBlockAt(x + 11, 120, z + 4).getLocation(), 8, 8, 8)
                .stream().filter(entity -> entity instanceof Pig
                        && "ChunkLoader integration pig".equals(entity.getCustomName())).count();
        require(pigs > 0, "Spawner preserves custom mob snapshot without players");
        require(spawnerSpawnReasonObserved, "Creature spawn reason is SPAWNER");
        boolean baselineGrew = grown(baseline);
        getLogger().info("BASELINE_CROP_GREW=" + baselineGrew);
        Files.createDirectories(getDataFolder().toPath());
        Files.writeString(getDataFolder().toPath().resolve("restart-loader.txt"), first.id().toString());
        complete("integration-stage1.json", true, baselineGrew);
    }

    private boolean grown(Chunk chunk) {
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                if (chunk.getBlock(dx, 121, dz).getBlockData() instanceof Ageable crop && crop.getAge() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        if (!"ChunkLoader integration pig".equals(event.getEntity().getCustomName())) {
            return;
        }
        if (event.getSpawner() == null || event.getEntity().getWorld() != event.getSpawner().getWorld()
                || event.getEntity().getLocation().distanceSquared(event.getSpawner().getLocation()) > 16
                || event.getEntity().isInWorld()) {
            invalidSpawnerEventLocation = true;
        }
        if (cancelSpawnerSpawns) {
            cancelledSpawnerAttempts++;
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if ("ChunkLoader integration pig".equals(event.getEntity().getCustomName())
                && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            spawnerSpawnReasonObserved = true;
        }
    }

    private void require(boolean condition, String description) {
        if (!condition) {
            throw new IllegalStateException(description);
        }
        checks.add(description);
        getLogger().info("PASS: " + description);
    }

    private void guarded(Checked action) {
        if (finished) {
            return;
        }
        try {
            action.run();
        } catch (Exception | AssertionError exception) {
            getLogger().log(java.util.logging.Level.SEVERE, "Integration test failed", exception);
            try {
                checks.add("FAILED: " + exception.getMessage());
                complete("integration-result.json", false, false);
            } catch (Exception writeFailure) {
                getLogger().log(java.util.logging.Level.SEVERE, "Could not write integration result", writeFailure);
                Bukkit.shutdown();
            }
        }
    }

    private void complete(String name, boolean passed, boolean baselineGrew) throws Exception {
        finished = true;
        if (previousRandomTickSpeed != null) {
            world.setGameRule(GameRules.RANDOM_TICK_SPEED, previousRandomTickSpeed);
        }
        if (baseline != null) {
            baseline.removePluginChunkTicket(this);
        }
        String entries = checks.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        Files.writeString(Path.of(name), "{\"passed\":" + passed + ",\"baselineCropGrew\":" + baselineGrew
                + ",\"checks\":[" + entries + "]}");
        getLogger().info("INTEGRATION_" + (passed ? "PASS" : "FAIL"));
        Bukkit.shutdown();
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}
