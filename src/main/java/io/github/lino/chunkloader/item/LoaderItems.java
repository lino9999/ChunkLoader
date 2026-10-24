package io.github.lino.chunkloader.item;

import io.github.lino.chunkloader.config.Messages;
import io.github.lino.chunkloader.model.Durations;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class LoaderItems {
    private final NamespacedKey timeKey;
    private final NamespacedKey versionKey;

    public LoaderItems(Plugin plugin) {
        timeKey = new NamespacedKey(plugin, "remaining_ticks");
        versionKey = new NamespacedKey(plugin, "item_version");
    }

    public ItemStack create(Material material, long ticks, int amount, Messages messages) {
        if (ticks <= 0 && ticks != -1 || amount < 1 || amount > material.getMaxStackSize()) {
            throw new IllegalArgumentException("Invalid loader item duration or amount");
        }
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.text("loader-name", "time", Durations.format(ticks)));
        meta.lore(List.of(messages.text("loader-lore")));
        meta.getPersistentDataContainer().set(timeKey, PersistentDataType.LONG, ticks);
        meta.getPersistentDataContainer().set(versionKey, PersistentDataType.INTEGER, 1);
        item.setItemMeta(meta);
        return item;
    }

    public long ticks(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getType().isBlock()) {
            return 0;
        }
        var data = item.getItemMeta().getPersistentDataContainer();
        if (!data.has(versionKey, PersistentDataType.INTEGER) || !data.has(timeKey, PersistentDataType.LONG)
                || data.getOrDefault(versionKey, PersistentDataType.INTEGER, 0) != 1) {
            return 0;
        }
        long ticks = data.getOrDefault(timeKey, PersistentDataType.LONG, 0L);
        return ticks > 0 || ticks == -1 ? ticks : 0;
    }
}
