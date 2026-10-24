package io.github.lino.chunkloader.runtime;

import io.github.lino.chunkloader.ChunkLoaderPlugin;
import io.github.lino.chunkloader.model.Durations;
import io.github.lino.chunkloader.model.Loader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

public final class LoaderDisplays {
    private final ChunkLoaderPlugin plugin;
    private final ChunkTickets tickets;
    private final Map<UUID, TextDisplay> holograms = new HashMap<>();
    private final Set<UUID> borders = new HashSet<>();

    public LoaderDisplays(ChunkLoaderPlugin plugin, ChunkTickets tickets) {
        this.plugin = plugin;
        this.tickets = tickets;
    }

    public void update() {
        Set<UUID> visible = new HashSet<>();
        if (plugin.settings().holograms()) {
            for (Loader loader : plugin.loaders().loaders()) {
                World world = Bukkit.getWorld(loader.position().worldId());
                if (world == null || !tickets.active(loader.position())) {
                    continue;
                }
                visible.add(loader.id());
                TextDisplay display = holograms.get(loader.id());
                if (display == null || !display.isValid()) {
                    Location location = new Location(world, loader.position().x() + 0.5,
                            loader.position().y() + 1.5, loader.position().z() + 0.5);
                    display = world.spawn(location, TextDisplay.class, entity -> {
                        entity.setPersistent(false);
                        entity.setBillboard(Display.Billboard.CENTER);
                        entity.setSeeThrough(false);
                        entity.setShadowed(true);
                        entity.setViewRange(0.6f);
                        entity.setInvulnerable(true);
                    });
                    holograms.put(loader.id(), display);
                }
                Component text = plugin.messages().text("loader-name", "time", Durations.format(loader.remainingTicks()));
                if (!display.text().equals(text)) {
                    display.text(text);
                }
            }
        }
        for (UUID id : Set.copyOf(holograms.keySet())) {
            if (!visible.contains(id)) {
                remove(id);
            }
        }
    }

    public void remove(UUID loaderId) {
        TextDisplay display = holograms.remove(loaderId);
        if (display != null) {
            display.remove();
        }
    }

    public boolean toggleBorders(Player player) {
        if (borders.remove(player.getUniqueId())) {
            return false;
        }
        borders.add(player.getUniqueId());
        return true;
    }

    public void forget(Player player) {
        borders.remove(player.getUniqueId());
    }

    public void drawBorders() {
        for (UUID playerId : Set.copyOf(borders)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                borders.remove(playerId);
                continue;
            }
            Location origin = player.getLocation();
            double y = Math.max(player.getWorld().getMinHeight(), Math.min(origin.getY(), player.getWorld().getMaxHeight() - 1));
            var nearby = tickets.activeChunks().stream()
                    .filter(chunk -> chunk.getWorld().equals(player.getWorld())
                            && Math.abs((long) chunk.getX() - (origin.getBlockX() >> 4)) <= 8
                            && Math.abs((long) chunk.getZ() - (origin.getBlockZ() >> 4)) <= 8)
                    .sorted(java.util.Comparator.comparingDouble(chunk ->
                            Math.pow(chunk.getX() * 16.0 + 8 - origin.getX(), 2)
                                    + Math.pow(chunk.getZ() * 16.0 + 8 - origin.getZ(), 2)))
                    .limit(16).toList();
            for (var chunk : nearby) {
                int x = chunk.getX() << 4;
                int z = chunk.getZ() << 4;
                for (int offset = 0; offset <= 16; offset += 2) {
                    particle(player, x + offset, y, z);
                    particle(player, x + offset, y, z + 16);
                    particle(player, x, y, z + offset);
                    particle(player, x + 16, y, z + offset);
                }
            }
        }
    }

    private void particle(Player player, double x, double y, double z) {
        player.spawnParticle(Particle.HAPPY_VILLAGER, x, y, z, 1, 0, 0, 0, 0);
    }

    public void close() {
        holograms.values().forEach(TextDisplay::remove);
        holograms.clear();
        borders.clear();
    }
}
