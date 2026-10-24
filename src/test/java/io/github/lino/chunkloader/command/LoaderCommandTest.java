package io.github.lino.chunkloader.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.lino.chunkloader.ChunkLoaderPlugin;
import io.github.lino.chunkloader.config.Messages;
import io.github.lino.chunkloader.config.Settings;
import io.github.lino.chunkloader.item.LoaderItems;
import io.github.lino.chunkloader.model.BlockPosition;
import io.github.lino.chunkloader.model.Loader;
import io.github.lino.chunkloader.service.LoaderService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoaderCommandTest {
    private ChunkLoaderPlugin plugin;
    private Messages messages;
    private LoaderItems items;
    private LoaderService service;
    private Settings settings;
    private LoaderCommand executor;
    private Command command;
    private CommandSender sender;

    @BeforeEach
    void prepare() {
        plugin = mock(ChunkLoaderPlugin.class);
        messages = mock(Messages.class);
        items = mock(LoaderItems.class);
        service = mock(LoaderService.class);
        settings = mock(Settings.class);
        command = mock(Command.class);
        sender = mock(CommandSender.class);
        when(plugin.messages()).thenReturn(messages);
        when(plugin.items()).thenReturn(items);
        when(plugin.loaders()).thenReturn(service);
        when(plugin.settings()).thenReturn(settings);
        executor = new LoaderCommand(plugin);
    }

    @Test
    void permissionDenialStopsGiveBeforeLookingUpPlayersOrCreatingItems() {
        execute("give", "Lino", "1h");
        verify(messages).send(sender, "no-permission");
        verifyNoInteractions(items, service);
    }

    @Test
    void helpIsAvailableWithoutPermissionsAndTabCompletionHidesPrivilegedCommands() {
        execute();
        verify(messages).send(sender, "help");
        assertEquals(List.of("help"), executor.onTabComplete(sender, command, "cl", new String[]{""}));
        assertTrue(executor.onTabComplete(sender, command, "cl", new String[]{"give", ""}).isEmpty());
    }

    @Test
    void invalidDurationsAndAmountsNeverCreateItems() {
        when(sender.hasPermission("chunkloader.give")).thenReturn(true);
        execute("give", "Lino", "9223372036854775807d");
        execute("give", "Lino", "1h", "65");
        execute("give", "Lino", "1h", "0");
        execute("give", "Lino", "1h", "2147483648");
        verify(messages, times(4)).send(sender, "invalid-input");
        verifyNoInteractions(items);
    }

    @Test
    void giveSplitsUnstackableItemsAndDropsInventoryOverflowWithoutLosingTime() {
        when(sender.hasPermission("chunkloader.give")).thenReturn(true);
        Material material = mock(Material.class);
        when(material.getMaxStackSize()).thenReturn(1);
        when(settings.material()).thenReturn(material);
        Player target = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        World world = mock(World.class);
        Location location = new Location(world, 1, 64, 2);
        ItemStack stack = mock(ItemStack.class);
        ItemStack overflow = mock(ItemStack.class);
        when(target.getName()).thenReturn("Lino");
        when(target.getInventory()).thenReturn(inventory);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(location);
        when(items.create(material, -1, 1, messages)).thenReturn(stack);
        when(inventory.addItem(stack)).thenReturn(new HashMap<>(Map.of(0, overflow)));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Lino")).thenReturn(target);
            execute("give", "Lino", "infinite", "3");
        }
        verify(items, times(3)).create(material, -1, 1, messages);
        verify(world, times(3)).dropItemNaturally(location, overflow);
        verify(messages).send(target, "received", "amount", "3", "time", "Infinite");
        verify(messages).send(sender, "given", "amount", "3", "player", "Lino", "time", "Infinite");
    }

    @Test
    void missingGiveTargetDoesNotCreateOrDiscardAnItem() {
        when(sender.hasPermission("chunkloader.give")).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            execute("give", "offline", "30m");
        }
        verify(messages).send(sender, "not-found");
        verifyNoInteractions(items);
    }

    @Test
    void listOnlyShowsOwnedLoadersAndHandlesAnUnloadedWorld() {
        Player player = mock(Player.class);
        UUID owner = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        Loader owned = new Loader(UUID.randomUUID(), owner, new BlockPosition(worldId, -1, -20, 33), 1200, "LODESTONE");
        Loader foreign = new Loader(UUID.randomUUID(), UUID.randomUUID(), new BlockPosition(worldId, 4, 80, 2), -1, "LODESTONE");
        when(player.hasPermission("chunkloader.list")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(owner);
        when(service.loaders()).thenReturn(List.of(owned, foreign));
        try (var bukkit = mockStatic(Bukkit.class)) {
            executor.onCommand(player, command, "cl", new String[]{"list"});
        }
        verify(messages).send(player, "list-header", "count", "1", "page", "1", "pages", "1");
        verify(messages).send(player, "list-entry", "id", owned.id().toString(), "world", worldId.toString(),
                "x", "-1", "y", "-20", "z", "33", "time", "1m");
        verify(messages, times(1)).send(eq(player), eq("list-entry"), any(String[].class));
    }

    @Test
    void veryLargePageIsRejectedWithoutIntegerOverflow() {
        Player player = mock(Player.class);
        when(player.hasPermission("chunkloader.list")).thenReturn(true);
        when(player.hasPermission("chunkloader.admin")).thenReturn(true);
        when(service.loaders()).thenReturn(List.of(new Loader(UUID.randomUUID(), UUID.randomUUID(),
                new BlockPosition(UUID.randomUUID(), 0, 64, 0), 20, "LODESTONE")));
        executor.onCommand(player, command, "cl", new String[]{"list", "2147483647"});
        verify(messages).send(player, "invalid-input");
        verify(messages, never()).send(eq(player), eq("list-entry"), any(String[].class));
    }

    @Test
    void consoleCannotUsePlayerCommandsAndFailedReloadReportsFailure() throws Exception {
        when(sender.hasPermission("chunkloader.show")).thenReturn(true);
        when(sender.hasPermission("chunkloader.reload")).thenReturn(true);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        doThrow(new IllegalArgumentException("Broken configuration")).when(plugin).reloadSettings();
        execute("show");
        execute("reload");
        verify(messages).send(sender, "players-only");
        verify(messages).send(sender, "reload-failed");
        verify(messages, never()).send(sender, "reloaded");
    }

    private void execute(String... args) {
        assertTrue(executor.onCommand(sender, command, "chunkloader", args));
    }
}
