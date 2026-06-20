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
 * Charges the player when they run {@code /claim setwarp <name> [public]}.
 *
 * <p>The parent plugin doesn't fire a custom event for warp creation, so
 * we hook Bukkit's {@link PlayerCommandPreprocessEvent} and cancel
 * the command if the player can't afford the cost. The tax refund on
 * warp deletion is the inverse — handled by intercepting
 * {@code /claim delwarp}.</p>
 */
public class WarpCostManager implements Listener {

    private final LandClaimEconomy plugin;

    public WarpCostManager(LandClaimEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getEconomyConfig().enabled
                || !plugin.getEconomyConfig().warpCost.enabled) return;

        String msg = event.getMessage();
        String[] parts = msg.split(" ");
        if (parts.length < 3) return;
        // Match "/claim setwarp <name>" or "/claim setwarp <name> public|private"
        if (!parts[0].equalsIgnoreCase("/claim")
                && !parts[0].equalsIgnoreCase("/c")) return;
        if (!parts[1].equalsIgnoreCase("setwarp")) return;

        Player player = event.getPlayer();
        if (player.hasPermission("landclaimeconomy.bypass")) return;
        if (player.hasPermission("landclaim.admin")) return;

        double cost = plugin.getEconomyConfig().warpCostAmount;
        // Apply public-warp multiplier if the optional visibility arg is "public".
        if (parts.length >= 4 && parts[3].equalsIgnoreCase("public")) {
            cost *= plugin.getEconomyConfig().publicWarpMultiplier;
        }
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
        final String name = parts[2];
        final double finalCost = cost;
        plugin.getDatabase().logTransaction(player.getUniqueId().toString(),
                null, "WARP_COST", finalCost, name);
        // Defer the success message to next tick so the parent's
        // "warp set" message lands first and we don't race the chat order.
        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(plugin.getMessages().prefix
                + plugin.getMessages().warpCharged
                        .replace("<cost>", EconomyHook.format(finalCost))
                        .replace("<name>", name)
                        .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player)))));
    }
}
