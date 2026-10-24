package io.github.lino.chunkloader.ui;

import io.github.lino.chunkloader.ChunkLoaderPlugin;
import io.github.lino.chunkloader.model.Durations;
import io.github.lino.chunkloader.model.Loader;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class LoaderMenus implements Listener {
    private enum Type { LOADER, CONFIRM, SETTINGS }

    private static final class Menu implements InventoryHolder {
        private final Type type;
        private final UUID loaderId;
        private Inventory inventory;

        private Menu(Type type, UUID loaderId) {
            this.type = type;
            this.loaderId = loaderId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final ChunkLoaderPlugin plugin;
    private BiConsumer<Player, UUID> removeAction;

    public LoaderMenus(ChunkLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    public void removalHandler(BiConsumer<Player, UUID> action) {
        removeAction = action;
    }

    public void open(Player player, Loader loader) {
        if (!plugin.loaders().hasAccess(loader, player)) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        Menu menu = create(Type.LOADER, loader.id(), "menu-title");
        menu.inventory.setItem(13, icon(Material.valueOf(loader.material()), "remaining", "time", Durations.format(loader.remainingTicks())));
        if (plugin.settings().xpEnabled()) {
            menu.inventory.setItem(11, icon(Material.EXPERIENCE_BOTTLE, "xp-button", "time",
                    Durations.format(plugin.settings().fuelTicks()), "levels", Integer.toString(plugin.settings().xpLevels())));
        }
        if (plugin.settings().economyEnabled() && plugin.economy().available()) {
            menu.inventory.setItem(15, icon(Material.SUNFLOWER, "economy-button", "time",
                    Durations.format(plugin.settings().fuelTicks()), "cost", Double.toString(plugin.settings().economyCost())));
        }
        menu.inventory.setItem(22, icon(Material.BARRIER, "remove-button"));
        player.openInventory(menu.inventory);
    }

    public void confirm(Player player, Loader loader) {
        Menu menu = create(Type.CONFIRM, loader.id(), "confirm-title");
        menu.inventory.setItem(11, icon(Material.LIME_TERRACOTTA, "cancel-button"));
        menu.inventory.setItem(15, icon(Material.RED_TERRACOTTA, "confirm-button"));
        player.openInventory(menu.inventory);
    }

    public void openSettings(Player player) {
        if (!player.hasPermission("chunkloader.settings")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        Menu menu = create(Type.SETTINGS, null, "settings-title");
        setting(menu, 10, "display.holograms", plugin.settings().holograms());
        setting(menu, 11, "display.particles", plugin.settings().particles());
        setting(menu, 12, "fuel.xp.enabled", plugin.settings().xpEnabled());
        setting(menu, 13, "fuel.economy.enabled", plugin.settings().economyEnabled());
        setting(menu, 14, "loader.allow-breaking", plugin.settings().allowBreaking());
        setting(menu, 15, "simulation.random-ticks", plugin.settings().randomTicks());
        setting(menu, 16, "simulation.spawners", plugin.settings().spawners());
        player.openInventory(menu.inventory);
    }

    private void setting(Menu menu, int slot, String key, boolean enabled) {
        menu.inventory.setItem(slot, icon(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                enabled ? "setting-on" : "setting-off", "setting", key));
    }

    private Menu create(Type type, UUID loaderId, String title) {
        Menu menu = new Menu(type, loaderId);
        menu.inventory = Bukkit.createInventory(menu, 27, plugin.messages().text(title));
        return menu;
    }

    private ItemStack icon(Material material, String message, String... placeholders) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(plugin.messages().text(message, placeholders));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= 27) {
            return;
        }
        int slot = event.getRawSlot();
        if (menu.inventory.getItem(slot) == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != menu) {
                return;
            }
            if (menu.type == Type.SETTINGS) {
                if (!player.hasPermission("chunkloader.settings")) {
                    player.closeInventory();
                    return;
                }
                Map<Integer, String> keys = Map.of(10, "display.holograms", 11, "display.particles",
                        12, "fuel.xp.enabled", 13, "fuel.economy.enabled", 14, "loader.allow-breaking",
                        15, "simulation.random-ticks", 16, "simulation.spawners");
                String key = keys.get(slot);
                if (key != null) {
                    plugin.toggleSetting(key);
                    openSettings(player);
                }
                return;
            }
            Loader loader = plugin.loaders().get(menu.loaderId);
            if (loader == null || !plugin.loaders().hasAccess(loader, player)) {
                player.closeInventory();
                plugin.messages().send(player, "not-found");
                return;
            }
            if (menu.type == Type.CONFIRM) {
                player.closeInventory();
                if (slot == 15) {
                    removeAction.accept(player, loader.id());
                }
                return;
            }
            if (slot == 22) {
                confirm(player, loader);
                return;
            }
            if (slot == 11 && plugin.settings().xpEnabled() || slot == 15 && plugin.settings().economyEnabled()) {
                buyTime(player, loader, slot == 11);
                Loader current = plugin.loaders().get(loader.id());
                if (current != null) {
                    open(player, current);
                }
            }
        });
    }

    private void buyTime(Player player, Loader loader, boolean xp) {
        if (!plugin.loaders().isPresent(loader)) {
            plugin.messages().send(player, "not-found");
            return;
        }
        if (loader.isInfinite()) {
            plugin.messages().send(player, "infinite");
            return;
        }
        try {
            loader.addTicks(plugin.settings().fuelTicks());
        } catch (ArithmeticException | IllegalArgumentException error) {
            plugin.messages().send(player, "invalid-input");
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            try {
                plugin.loaders().addTime(loader.id(), plugin.settings().fuelTicks());
            } catch (IllegalStateException error) {
                plugin.messages().send(player, "invalid-input");
                return;
            }
        } else if (xp) {
            if (player.getLevel() < plugin.settings().xpLevels()) {
                plugin.messages().send(player, "xp-missing", "levels", Integer.toString(plugin.settings().xpLevels()));
                return;
            }
            try {
                plugin.loaders().addTime(loader.id(), plugin.settings().fuelTicks());
            } catch (IllegalStateException error) {
                plugin.messages().send(player, "invalid-input");
                return;
            }
            player.giveExpLevels(-plugin.settings().xpLevels());
        } else {
            if (!plugin.economy().withdraw(player, plugin.settings().economyCost())) {
                plugin.messages().send(player, "economy-missing");
                return;
            }
            try {
                plugin.loaders().addTime(loader.id(), plugin.settings().fuelTicks());
            } catch (RuntimeException error) {
                if (!plugin.economy().refund(player, plugin.settings().economyCost())) {
                    plugin.getLogger().severe("Could not refund failed loader purchase for " + player.getUniqueId());
                }
                plugin.messages().send(player, "economy-missing");
                return;
            }
        }
        plugin.messages().send(player, "refueled", "time", Durations.format(plugin.loaders().get(loader.id()).remainingTicks()));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    public void close() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                player.closeInventory();
            }
        }
    }
}
