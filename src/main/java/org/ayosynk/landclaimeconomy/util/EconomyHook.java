package org.ayosynk.landclaimeconomy.util;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Thin wrapper around the Vault Economy service. Returns null if Vault or
 * an economy provider is not present — callers should always null-check
 * the result and bail out cleanly.
 */
public final class EconomyHook {

    private static Economy provider;

    private EconomyHook() {}

    public static boolean setup() {
        if (provider != null) return true;
        if (Bukkit.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager()
                .getRegistration(Economy.class);
        if (rsp == null) return false;
        provider = rsp.getProvider();
        return provider != null;
    }

    public static Economy get() {
        return provider;
    }

    public static boolean isAvailable() {
        return provider != null;
    }

    public static double getBalance(OfflinePlayer player) {
        if (provider == null) return 0.0;
        return provider.getBalance(player);
    }

    public static boolean has(OfflinePlayer player, double amount) {
        if (provider == null) return false;
        return provider.has(player, amount);
    }

    public static boolean withdraw(OfflinePlayer player, double amount) {
        if (provider == null) return false;
        return provider.withdrawPlayer(player, amount).transactionSuccess();
    }

    public static boolean deposit(OfflinePlayer player, double amount) {
        if (provider == null) return false;
        return provider.depositPlayer(player, amount).transactionSuccess();
    }

    public static String format(double amount) {
        if (provider == null) return String.format("%.2f", amount);
        return provider.format(amount);
    }
}
