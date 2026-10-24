package io.github.lino.chunkloader.integration;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class EconomyBridge {
    public boolean available() {
        return provider() != null;
    }

    public boolean withdraw(Player player, double amount) {
        Economy economy = provider();
        return economy != null && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public boolean refund(Player player, double amount) {
        Economy economy = provider();
        return economy != null && economy.depositPlayer(player, amount).transactionSuccess();
    }

    private Economy provider() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            return null;
        }
        var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }
}
