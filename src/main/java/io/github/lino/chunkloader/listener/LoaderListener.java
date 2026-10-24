package io.github.lino.chunkloader.listener;

import io.github.lino.chunkloader.ChunkLoaderPlugin;
import io.github.lino.chunkloader.model.Durations;
import io.github.lino.chunkloader.model.Loader;
import io.github.lino.chunkloader.service.LoaderService;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class LoaderListener implements Listener {
    private final ChunkLoaderPlugin plugin;
    private final Set<UUID> removalChecks = new HashSet<>();

    public LoaderListener(ChunkLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        long ticks = plugin.items().ticks(event.getItemInHand());
        if (ticks == 0) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("chunkloader.use")) {
            event.setCancelled(true);
            plugin.messages().send(player, "no-permission");
            return;
        }
        if (!event.canBuild() || !plugin.loaders().canPlace(player, event.getBlock().getWorld())
                || isLoader(event.getBlock())) {
            event.setCancelled(true);
            plugin.messages().send(player, "limit");
            return;
        }
        Block block = event.getBlock();
        Material material = block.getType();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled()) {
                return;
            }
            if (block.getType() != material || !plugin.loaders().canPlace(player, block.getWorld())
                    || isLoader(block)) {
                if (block.getType() == material && !isLoader(block)) {
                    block.setType(Material.AIR, false);
                }
                if (player.getGameMode() != GameMode.CREATIVE) {
                    block.getWorld().dropItemNaturally(block.getLocation(), plugin.items().create(material, ticks, 1, plugin.messages()));
                }
                plugin.messages().send(player, "limit");
                return;
            }
            try {
                plugin.loaders().create(player.getUniqueId(), LoaderService.position(block), ticks, material.name());
                plugin.messages().send(player, "placed", "time", Durations.format(ticks));
            } catch (IllegalArgumentException | IllegalStateException error) {
                if (!isLoader(block)) {
                    block.setType(Material.AIR, false);
                    if (player.getGameMode() != GameMode.CREATIVE) {
                        block.getWorld().dropItemNaturally(block.getLocation(), plugin.items().create(material, ticks, 1, plugin.messages()));
                    }
                }
                plugin.messages().send(player, "limit");
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Loader loader = plugin.loaders().find(LoaderService.position(event.getClickedBlock()));
        if (loader == null) {
            return;
        }
        boolean denied = event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY;
        event.setCancelled(true);
        if (denied || !plugin.loaders().hasAccess(loader, event.getPlayer())) {
            plugin.messages().send(event.getPlayer(), "no-permission");
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            plugin.menus().confirm(event.getPlayer(), loader);
            return;
        }
        var item = event.getPlayer().getInventory().getItemInMainHand();
        long ticks = plugin.items().ticks(item);
        if (ticks == 0) {
            plugin.menus().open(event.getPlayer(), loader);
            return;
        }
        if (loader.isInfinite()) {
            plugin.messages().send(event.getPlayer(), "infinite");
            return;
        }
        try {
            Loader updated = plugin.loaders().addTime(loader.id(), ticks);
            if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
                item.subtract(1);
            }
            plugin.messages().send(event.getPlayer(), "refueled", "time", Durations.format(updated.remainingTicks()));
        } catch (IllegalArgumentException | ArithmeticException | IllegalStateException error) {
            plugin.messages().send(event.getPlayer(), "invalid-input");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Loader loader = plugin.loaders().find(LoaderService.position(event.getBlock()));
        if (loader == null || removalChecks.contains(loader.id())) {
            return;
        }
        event.setCancelled(true);
        if (!plugin.loaders().hasAccess(loader, event.getPlayer())) {
            plugin.messages().send(event.getPlayer(), "no-permission");
        } else if (plugin.settings().allowBreaking()) {
            requestRemoval(event.getPlayer(), loader.id());
        } else {
            plugin.menus().confirm(event.getPlayer(), loader);
        }
    }

    public void requestRemoval(Player player, UUID id) {
        Loader loader = plugin.loaders().get(id);
        if (loader == null || !plugin.loaders().hasAccess(loader, player)) {
            plugin.messages().send(player, "not-found");
            return;
        }
        var world = Bukkit.getWorld(loader.position().worldId());
        if (world == null || !world.isChunkLoaded(loader.position().chunkX(), loader.position().chunkZ())) {
            return;
        }
        Block block = world.getBlockAt(loader.position().x(), loader.position().y(), loader.position().z());
        BlockBreakEvent check = new BlockBreakEvent(block, player);
        removalChecks.add(id);
        try {
            Bukkit.getPluginManager().callEvent(check);
        } finally {
            removalChecks.remove(id);
        }
        if (!check.isCancelled() && plugin.loaders().remove(id, plugin.settings().allowBreaking())) {
            plugin.messages().send(player, "removed");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> isLoader(block) || isLoader(block.getRelative(event.getDirection())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> isLoader(block) || isLoader(block.getRelative(event.getDirection())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isLoader);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isLoader);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void burn(BlockBurnEvent event) {
        if (isLoader(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fade(BlockFadeEvent event) {
        if (isLoader(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void flow(BlockFromToEvent event) {
        if (isLoader(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityChange(EntityChangeBlockEvent event) {
        if (isLoader(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        plugin.displays().forget(event.getPlayer());
    }

    @EventHandler
    public void loadWorld(WorldLoadEvent event) {
        plugin.tickets().worldLoaded(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unloadWorld(WorldUnloadEvent event) {
        plugin.tickets().worldUnloaded(event.getWorld());
        plugin.loaders().saveAsync();
    }

    private boolean isLoader(Block block) {
        return plugin.loaders().find(LoaderService.position(block)) != null;
    }
}
