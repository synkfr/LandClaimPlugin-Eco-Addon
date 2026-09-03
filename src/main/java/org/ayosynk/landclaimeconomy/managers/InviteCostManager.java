package org.ayosynk.landclaimeconomy.managers;

import org.ayosynk.landClaimPlugin.api.event.ClaimMemberAddEvent;
import org.ayosynk.landClaimPlugin.api.event.ClaimTrustAddEvent;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.config.MessagesConfig;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.ayosynk.landclaimeconomy.util.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class InviteCostManager implements Listener {

    private final LandClaimEconomy plugin;

    public InviteCostManager(LandClaimEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMemberInvite(ClaimMemberAddEvent event) {
        if (!plugin.getEconomyConfig().enabled || !plugin.getEconomyConfig().inviteCost.enabled) {
            return;
        }

        Player player = event.getInviter();
        if (player == null) return;
        if (player.hasPermission("landclaimeconomy.bypass") || player.hasPermission("landclaim.admin")) {
            return;
        }

        double cost = plugin.getEconomyConfig().memberInviteCost;
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

        String targetName = plugin.getNameCache().getName(event.getMemberId());
        plugin.getDatabase().logTransaction(player.getUniqueId().toString(),
                event.getProfile().getProfileId().toString(), "MEMBER_INVITE", cost, targetName);

        final double finalCost = cost;
        FoliaScheduler.runForPlayer(plugin, player, () -> player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + plugin.getMessages().memberInviteCharged
                        .replace("<cost>", EconomyHook.format(finalCost))
                        .replace("<player>", targetName)
                        .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player))))));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTrustInvite(ClaimTrustAddEvent event) {
        if (!plugin.getEconomyConfig().enabled || !plugin.getEconomyConfig().inviteCost.enabled) {
            return;
        }

        Player player = event.getInviter();
        if (player == null) return;
        if (player.hasPermission("landclaimeconomy.bypass") || player.hasPermission("landclaim.admin")) {
            return;
        }

        double cost = plugin.getEconomyConfig().trustedInviteCost;
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

        String targetName = plugin.getNameCache().getName(event.getTargetId());
        plugin.getDatabase().logTransaction(player.getUniqueId().toString(),
                event.getProfile().getProfileId().toString(), "TRUSTED_INVITE", cost, targetName);

        final double finalCost = cost;
        FoliaScheduler.runForPlayer(plugin, player, () -> player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + plugin.getMessages().trustedInviteCharged
                        .replace("<cost>", EconomyHook.format(finalCost))
                        .replace("<player>", targetName)
                        .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player))))));
    }
}
