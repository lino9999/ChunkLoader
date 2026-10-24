package io.github.lino.chunkloader.command;

import io.github.lino.chunkloader.ChunkLoaderPlugin;
import io.github.lino.chunkloader.model.Durations;
import io.github.lino.chunkloader.model.Loader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class LoaderCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("help", "show", "list", "give", "reload", "settings");
    private static final int PAGE_SIZE = 10;
    private final ChunkLoaderPlugin plugin;

    public LoaderCommand(ChunkLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(action)) {
            plugin.messages().send(sender, "invalid-input");
            return true;
        }
        if (!action.equals("help") && !sender.hasPermission("chunkloader." + action)) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        try {
            switch (action) {
                case "help" -> {
                    require(args.length <= 1);
                    plugin.messages().send(sender, "help");
                }
                case "show" -> {
                    require(args.length == 1);
                    Player player = player(sender);
                    if (player != null) {
                        boolean enabled = plugin.displays().toggleBorders(player);
                        plugin.messages().send(player, enabled ? "show-on" : "show-off");
                    }
                }
                case "list" -> list(sender, args);
                case "give" -> give(sender, args);
                case "reload" -> {
                    require(args.length == 1);
                    reload(sender);
                }
                case "settings" -> {
                    require(args.length == 1);
                    Player player = player(sender);
                    if (player != null) {
                        plugin.menus().openSettings(player);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown command");
            }
        } catch (IllegalArgumentException exception) {
            plugin.messages().send(sender, "invalid-input");
        }
        return true;
    }

    private void list(CommandSender sender, String[] args) {
        require(args.length == 1 || args.length == 2);
        int page = args.length == 2 ? Integer.parseInt(args[1]) : 1;
        require(page > 0);
        Player player = player(sender);
        if (player == null) {
            return;
        }
        boolean administrator = sender.hasPermission("chunkloader.admin");
        List<Loader> loaders = plugin.loaders().loaders().stream()
                .filter(loader -> administrator || loader.ownerId().equals(player.getUniqueId()))
                .toList();
        if (loaders.isEmpty()) {
            require(page == 1);
            plugin.messages().send(sender, "list-empty");
            return;
        }
        int pages = (loaders.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        require(page <= pages);
        plugin.messages().send(sender, "list-header", "count", Integer.toString(loaders.size()),
                "page", Integer.toString(page), "pages", Integer.toString(pages));
        int start = (page - 1) * PAGE_SIZE;
        for (Loader loader : loaders.subList(start, Math.min(start + PAGE_SIZE, loaders.size()))) {
            var position = loader.position();
            World world = Bukkit.getWorld(position.worldId());
            plugin.messages().send(sender, "list-entry", "id", loader.id().toString(),
                    "world", world == null ? position.worldId().toString() : world.getName(),
                    "x", Integer.toString(position.x()), "y", Integer.toString(position.y()),
                    "z", Integer.toString(position.z()), "time", Durations.format(loader.remainingTicks()));
        }
    }

    private void give(CommandSender sender, String[] args) {
        require(args.length == 3 || args.length == 4);
        long ticks = Durations.parse(args[2]);
        int amount = args.length == 4 ? Integer.parseInt(args[3]) : 1;
        require(amount >= 1 && amount <= 64);
        boolean everyone = args[1].equalsIgnoreCase("all") || args[1].equalsIgnoreCase("@a");
        List<Player> recipients;
        if (everyone) {
            recipients = List.copyOf(Bukkit.getOnlinePlayers());
        } else {
            Player target = Bukkit.getPlayerExact(args[1]);
            recipients = target == null ? List.of() : List.of(target);
        }
        if (recipients.isEmpty()) {
            plugin.messages().send(sender, "not-found");
            return;
        }
        String time = Durations.format(ticks);
        for (Player recipient : recipients) {
            int remaining = amount;
            while (remaining > 0) {
                int stackSize = Math.min(remaining, plugin.settings().material().getMaxStackSize());
                ItemStack stack = plugin.items().create(plugin.settings().material(), ticks, stackSize, plugin.messages());
                for (ItemStack leftover : recipient.getInventory().addItem(stack).values()) {
                    recipient.getWorld().dropItemNaturally(recipient.getLocation(), leftover);
                }
                remaining -= stackSize;
            }
            plugin.messages().send(recipient, "received", "amount", Integer.toString(amount), "time", time);
        }
        plugin.messages().send(sender, "given", "amount", Integer.toString(amount),
                "player", everyone ? "all online players" : recipients.getFirst().getName(), "time", time);
    }

    private void reload(CommandSender sender) {
        try {
            plugin.reloadSettings();
            plugin.messages().send(sender, "reloaded");
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not reload ChunkLoader configuration", exception);
            plugin.messages().send(sender, "reload-failed");
        }
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "players-only");
        return null;
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid command arguments");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return matching(SUBCOMMANDS.stream()
                    .filter(action -> action.equals("help") || sender.hasPermission("chunkloader." + action))
                    .toList(), args[0]);
        }
        if (args.length < 2 || !sender.hasPermission("chunkloader." + args[0].toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        if (args[0].equalsIgnoreCase("give")) {
            return switch (args.length) {
                case 2 -> {
                    List<String> candidates = new ArrayList<>(List.of("all", "@a"));
                    Bukkit.getOnlinePlayers().forEach(player -> candidates.add(player.getName()));
                    yield matching(candidates, args[1]);
                }
                case 3 -> matching(List.of("30m", "1h", "2h", "1d", "infinite"), args[2]);
                case 4 -> matching(List.of("1", "8", "16", "32", "64"), args[3]);
                default -> List.of();
            };
        }
        if (args[0].equalsIgnoreCase("list") && args.length == 2 && sender instanceof Player player) {
            boolean administrator = sender.hasPermission("chunkloader.admin");
            long count = plugin.loaders().loaders().stream()
                    .filter(loader -> administrator || loader.ownerId().equals(player.getUniqueId())).count();
            int pages = Math.max(1, (int) ((count + PAGE_SIZE - 1) / PAGE_SIZE));
            List<String> candidates = new ArrayList<>(pages);
            for (int page = 1; page <= pages; page++) {
                candidates.add(Integer.toString(page));
            }
            return matching(candidates, args[1]);
        }
        return List.of();
    }

    private static List<String> matching(List<String> candidates, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(normalized))
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
