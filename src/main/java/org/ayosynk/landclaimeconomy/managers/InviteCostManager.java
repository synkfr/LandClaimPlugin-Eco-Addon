package org.ayosynk.landclaimeconomy.managers;

import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Charges the player when they invite someone as a member or trusted
 * player. Hooks Bukkit's PlayerCommandPreprocessEvent since the parent
 * doesn't fire a custom event for invite actions.
 */
public class InviteCostManager implements Listener {

    private final LandClaimEconomy plugin;

    public InviteCostManager(LandClaimEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getEconomyConfig().enabled
                || !plugin.getEconomyConfig().inviteCost.enabled) return;

        String msg = event.getMessage();
        String[] parts = msg.split(" ");
        if (parts.length < 4) return;
        if (!parts[0].equalsIgnoreCase("/claim")
                && !parts[0].equalsIgnoreCase("/c")) return;

        String sub = parts[1].toLowerCase();
        String kind = null;
        if (sub.equals("member") && parts[2].equalsIgnoreCase("invite")) kind = "MEMBER";
        else if (sub.equals("trust") && parts[2].equalsIgnoreCase("invite")) kind = "TRUSTED";
        if (kind == null) return;

        Player player = event.getPlayer();
        if (player.hasPermission("landclaimeconomy.bypass")) return;
        if (player.hasPermission("landclaim.admin")) return;

        double cost = "MEMBER".equals(kind)
                ? plugin.getEconomyConfig().memberInviteCost
                : plugin.getEconomyConfig().trustedInviteCost;
        if (cost <= 0) return;

        if (!EconomyHook.has(player, cost)) {
            event.setCancelled(true);
            player.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().insufficientFunds
                            .replace("<cost>", EconomyHook.format(cost))
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player))));
            return;
        }
        if (!EconomyHook.withdraw(player, cost)) {
            event.setCancelled(true);
            return;
        }
        String targetName = parts[3];
        plugin.getDatabase().logTransaction(player.getUniqueId().toString(),
                null, kind + "_INVITE", cost, targetName);
        String messageKey = "MEMBER".equals(kind)
                ? plugin.getMessages().memberInviteCharged
                : plugin.getMessages().trustedInviteCharged;
        final double finalCost = cost;
        final String finalKind = kind;
        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(plugin.getMessages().prefix
                + messageKey
                        .replace("<cost>", EconomyHook.format(finalCost))
                        .replace("<player>", targetName)
                        .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player)))));
    }
}
