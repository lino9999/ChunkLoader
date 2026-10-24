package io.github.lino.chunkloader;

import io.github.lino.chunkloader.api.ChunkLoaderApi;
import io.github.lino.chunkloader.command.LoaderCommand;
import io.github.lino.chunkloader.config.Messages;
import io.github.lino.chunkloader.config.Settings;
import io.github.lino.chunkloader.integration.EconomyBridge;
import io.github.lino.chunkloader.item.LoaderItems;
import io.github.lino.chunkloader.listener.LoaderListener;
import io.github.lino.chunkloader.runtime.ChunkTickets;
import io.github.lino.chunkloader.runtime.FarmSimulation;
import io.github.lino.chunkloader.runtime.LoaderDisplays;
import io.github.lino.chunkloader.service.LoaderService;
import io.github.lino.chunkloader.storage.LoaderStore;
import io.github.lino.chunkloader.ui.LoaderMenus;
import java.io.File;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkLoaderPlugin extends JavaPlugin {
    private Settings settings;
    private Messages messages;
    private LoaderItems items;
    private EconomyBridge economy;
    private ChunkTickets tickets;
    private LoaderDisplays displays;
    private LoaderService loaders;
    private LoaderMenus menus;
    private FarmSimulation simulation;
    private long ticks;
    private long lastSimulationTick;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            if (!new File(getDataFolder(), "messages.yml").exists()) {
                saveResource("messages.yml", false);
            }
            reloadSettings();
            items = new LoaderItems(this);
            economy = new EconomyBridge();
            tickets = new ChunkTickets(this);
            displays = new LoaderDisplays(this, tickets);
            loaders = new LoaderService(this, new LoaderStore(getDataFolder().toPath().resolve("loaders.json")), tickets);
            menus = new LoaderMenus(this);
            simulation = new FarmSimulation(this);
            LoaderListener listener = new LoaderListener(this);
            menus.removalHandler(listener::requestRemoval);
            Bukkit.getPluginManager().registerEvents(listener, this);
            Bukkit.getPluginManager().registerEvents(menus, this);
            Bukkit.getServicesManager().register(ChunkLoaderApi.class, loaders, this, ServicePriority.Normal);
            LoaderCommand command = new LoaderCommand(this);
            Objects.requireNonNull(getCommand("chunkloader")).setExecutor(command);
            Objects.requireNonNull(getCommand("chunkloader")).setTabCompleter(command);
            Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1);
            getLogger().info("ChunkLoader by Lino enabled with " + loaders.loaders().size() + " loader(s)");
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "ChunkLoader could not start safely", error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void tick() {
        ticks++;
        tickets.pump(settings.maxChunksPerTick());
        loaders.pollPersistence();
        if (ticks % settings.simulationInterval() == 0) {
            int elapsed = (int) Math.min(200, ticks - lastSimulationTick);
            lastSimulationTick = ticks;
            simulation.tick(tickets.activeChunks(), elapsed, settings.randomTicks(),
                    settings.spawners(), settings.maxRandomSamples(), settings.maxChunksPerTick());
        }
        if (ticks % 20 == 0) {
            loaders.elapse();
            displays.update();
        }
        if (ticks % settings.borderInterval() == 0) {
            displays.drawBorders();
        }
        if (ticks % (settings.autosaveSeconds() * 20L) == 0) {
            loaders.saveAsync();
        }
    }

    public void reloadSettings() throws Exception {
        YamlConfiguration candidate = new YamlConfiguration();
        candidate.load(new File(getDataFolder(), "config.yml"));
        Settings nextSettings = Settings.read(candidate);
        Messages nextMessages = new Messages(new File(getDataFolder(), "messages.yml"));
        settings = nextSettings;
        messages = nextMessages;
        reloadConfig();
        if (menus != null) {
            menus.close();
        }
    }

    public void toggleSetting(String key) {
        boolean previous = getConfig().getBoolean(key);
        getConfig().set(key, !previous);
        try {
            Settings nextSettings = Settings.read(getConfig());
            saveConfig();
            settings = nextSettings;
        } catch (IllegalArgumentException error) {
            getConfig().set(key, previous);
            getLogger().log(Level.WARNING, "Could not update setting " + key, error);
        }
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        Bukkit.getServicesManager().unregisterAll(this);
        if (menus != null) {
            closeComponent("menus", menus::close);
        }
        if (loaders != null) {
            closeComponent("loader storage", loaders::close);
        }
        if (displays != null) {
            closeComponent("displays", displays::close);
        }
        if (tickets != null) {
            closeComponent("chunk tickets", tickets::close);
        }
    }

    private void closeComponent(String name, AutoCloseable component) {
        try {
            component.close();
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "Could not close " + name, error);
        }
    }

    public Settings settings() { return settings; }
    public Messages messages() { return messages; }
    public LoaderItems items() { return items; }
    public EconomyBridge economy() { return economy; }
    public ChunkTickets tickets() { return tickets; }
    public LoaderDisplays displays() { return displays; }
    public LoaderService loaders() { return loaders; }
    public LoaderMenus menus() { return menus; }
}
