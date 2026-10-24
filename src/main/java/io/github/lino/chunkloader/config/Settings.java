package io.github.lino.chunkloader.config;

import io.github.lino.chunkloader.model.Durations;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

public record Settings(Material material, int maxPerPlayer, int maxTotal, boolean allowBreaking,
        List<String> disabledWorlds, long fuelTicks, boolean xpEnabled, int xpLevels,
        boolean economyEnabled, double economyCost, boolean holograms, boolean particles,
        int borderInterval, boolean randomTicks, boolean spawners, int simulationInterval,
        int maxChunksPerTick, int maxRandomSamples, int autosaveSeconds) {
    public static Settings read(FileConfiguration config) {
        Material material = Material.matchMaterial(config.getString("loader.material", "END_PORTAL_FRAME"));
        if (material == null || !material.isBlock() || !material.isItem() || !material.isSolid()) {
            throw new IllegalArgumentException("loader.material must be a solid, placeable block item");
        }
        long fuelTicks = Durations.parse(config.getString("fuel.duration", "30m"));
        if (fuelTicks <= 0) {
            throw new IllegalArgumentException("fuel.duration must be finite and positive");
        }
        double cost = config.getDouble("fuel.economy.cost", 5000);
        if (!Double.isFinite(cost) || cost < 0) {
            throw new IllegalArgumentException("fuel.economy.cost must be finite and nonnegative");
        }
        return new Settings(material, integer(config, "loader.max-per-player", 1, 100000),
                integer(config, "loader.max-total", 1, 100000), config.getBoolean("loader.allow-breaking"),
                List.copyOf(config.getStringList("loader.disabled-worlds")), fuelTicks,
                config.getBoolean("fuel.xp.enabled"), integer(config, "fuel.xp.levels", 0, 100000),
                config.getBoolean("fuel.economy.enabled"), cost, config.getBoolean("display.holograms"),
                config.getBoolean("display.particles"), integer(config, "display.borders-interval-ticks", 5, 200),
                config.getBoolean("simulation.random-ticks"), config.getBoolean("simulation.spawners"),
                integer(config, "simulation.interval-ticks", 1, 20),
                integer(config, "simulation.max-chunks-per-tick", 1, 256),
                integer(config, "simulation.max-random-samples-per-chunk", 1, 65536),
                integer(config, "storage.autosave-seconds", 5, 3600));
    }

    private static int integer(FileConfiguration config, String path, int minimum, int maximum) {
        int value = config.getInt(path, minimum - 1);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }
}
