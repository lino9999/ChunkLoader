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
        Object configuredCost = config.get("fuel.economy.cost");
        if (!(configuredCost instanceof Number number)) {
            throw new IllegalArgumentException("fuel.economy.cost must be a number");
        }
        double cost = number.doubleValue();
        if (!Double.isFinite(cost) || cost < 0) {
            throw new IllegalArgumentException("fuel.economy.cost must be finite and nonnegative");
        }
        Object worlds = config.get("loader.disabled-worlds");
        if (!(worlds instanceof List<?> names)
                || names.stream().anyMatch(name -> !(name instanceof String text) || text.isBlank())) {
            throw new IllegalArgumentException("loader.disabled-worlds must be a list of world names");
        }
        return new Settings(material, integer(config, "loader.max-per-player", 1, 100000),
                integer(config, "loader.max-total", 1, 100000), bool(config, "loader.allow-breaking"),
                names.stream().map(String.class::cast).distinct().toList(), fuelTicks,
                bool(config, "fuel.xp.enabled"), integer(config, "fuel.xp.levels", 0, 100000),
                bool(config, "fuel.economy.enabled"), cost, bool(config, "display.holograms"),
                bool(config, "display.particles"), integer(config, "display.borders-interval-ticks", 5, 200),
                bool(config, "simulation.random-ticks"), bool(config, "simulation.spawners"),
                integer(config, "simulation.interval-ticks", 1, 20),
                integer(config, "simulation.max-chunks-per-tick", 1, 256),
                integer(config, "simulation.max-random-samples-per-chunk", 1, 65536),
                integer(config, "storage.autosave-seconds", 5, 3600));
    }

    private static int integer(FileConfiguration config, String path, int minimum, int maximum) {
        Object configured = config.get(path);
        if (!(configured instanceof Integer || configured instanceof Long)) {
            throw new IllegalArgumentException(path + " must be a whole number");
        }
        long value = ((Number) configured).longValue();
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return (int) value;
    }

    private static boolean bool(FileConfiguration config, String path) {
        Object value = config.get(path);
        if (!(value instanceof Boolean enabled)) {
            throw new IllegalArgumentException(path + " must be true or false");
        }
        return enabled;
    }
}
