package org.ayosynk.landclaimeconomy.managers;

import org.ayosynk.landClaimPlugin.api.event.WarpCreateEvent;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.config.MessagesConfig;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.ayosynk.landclaimeconomy.util.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class WarpCostManager implements Listener {

    private final LandClaimEconomy plugin;

    public WarpCostManager(LandClaimEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWarpCreate(WarpCreateEvent event) {
        if (!plugin.getEconomyConfig().enabled || !plugin.getEconomyConfig().warpCost.enabled) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) return;
        if (player.hasPermission("landclaimeconomy.bypass") || player.hasPermission("landclaim.admin")) {
            return;
        }

        double cost = plugin.getEconomyConfig().warpCostAmount;
        if (event.getWarp().isPublic()) {
            cost *= plugin.getEconomyConfig().publicWarpMultiplier;
        }
        if (cost <= 0) return;

        if (!EconomyHook.has(player, cost)) {
            event.setCancelled(true);
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().insufficientFunds
                            .replace("<cost>", EconomyHook.format(cost))
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player)))));
            return;
        }

        if (!EconomyHook.withdraw(player, cost)) {
            event.setCancelled(true);
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().insufficientFunds
                            .replace("<cost>", EconomyHook.format(cost))
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player)))));
            return;
        }

        final String name = event.getWarp().getName();
        final double finalCost = cost;
        plugin.getDatabase().logTransaction(player.getUniqueId().toString(),
                event.getProfile().getProfileId().toString(), "WARP_COST", finalCost, name);

        FoliaScheduler.runForPlayer(plugin, player, () -> player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + plugin.getMessages().warpCharged
                        .replace("<cost>", EconomyHook.format(finalCost))
                        .replace("<name>", name)
                        .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player))))));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWarpPrivacyChange(org.ayosynk.landClaimPlugin.api.event.WarpPrivacyChangeEvent event) {
        if (!plugin.getEconomyConfig().enabled || !plugin.getEconomyConfig().warpCost.enabled) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) return;
        if (player.hasPermission("landclaimeconomy.bypass") || player.hasPermission("landclaim.admin")) {
            return;
        }

        if (event.isNewIsPublic()) {
            double multiplier = plugin.getEconomyConfig().publicWarpMultiplier;
            if (multiplier <= 1.0) return;
            double extraCost = plugin.getEconomyConfig().warpCostAmount * (multiplier - 1.0);
            if (extraCost <= 0) return;

            if (!EconomyHook.has(player, extraCost)) {
                event.setCancelled(true);
                player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                        + plugin.getMessages().insufficientFunds
                                .replace("<cost>", EconomyHook.format(extraCost))
                                .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player)))));
                return;
            }

            if (!EconomyHook.withdraw(player, extraCost)) {
                event.setCancelled(true);
                player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                        + plugin.getMessages().insufficientFunds
                                .replace("<cost>", EconomyHook.format(extraCost))
                                .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player)))));
                return;
            }

            final String name = event.getWarp().getName();
            final double finalCost = extraCost;
            plugin.getDatabase().logTransaction(player.getUniqueId().toString(),
                    event.getProfile().getProfileId().toString(), "WARP_PUBLIC_UPGRADE", finalCost, name);

            FoliaScheduler.runForPlayer(plugin, player, () -> player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().warpCharged
                            .replace("<cost>", EconomyHook.format(finalCost))
                            .replace("<name>", name)
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player))))));
        }
    }
}
