package org.ayosynk.landclaimeconomy.managers;

import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landClaimPlugin.api.LandClaimAPI;
import org.ayosynk.landClaimPlugin.api.event.ClaimCreateEvent;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.ayosynk.landclaimeconomy.config.MessagesConfig;

/**
 * Charges the player when they claim a chunk.
 *
 * <p>The first chunk ever claimed by a player is free if
 * {@code firstClaimFree} is true. After that, the cost is
 * {@code firstChunkCost} for the first chunk of a new claim and
 * {@code perChunkCost} for additional chunks in the same claim. The
 * {@code dailyChargeCap} limits how much a single player can be charged
 * per real day.</p>
 *
 * <p>Charging happens at the lowest priority so the parent's claim
 * validation runs first — if the event is cancelled, the addon never
 * sees it. The tax refund logic lives in
 * {@link org.ayosynk.landclaimeconomy.managers.TaxManager}.</p>
 */
public class ClaimCostManager implements Listener {

    private final LandClaimEconomy plugin;

    // Per-player first-claim tracking and per-day charge totals. The
    // daily cap is tracked in-memory to avoid an extra DB query; on
    // restart the cap resets (which is the intuitive behavior).
    private final java.util.Set<UUID> firstClaimDone = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, double[]> dailyCharges = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, long[]> dailyWindow = new ConcurrentHashMap<>();

    public ClaimCostManager(LandClaimEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClaimCreate(ClaimCreateEvent event) {
        var cfg = plugin.getEconomyConfig();
        if (!cfg.enabled || !cfg.claimCost.enabled) return;

        UUID creatorId = event.getCreatorId();
        if (creatorId == null) return;
        Player player = Bukkit.getPlayer(creatorId);
        if (player == null) return;
        if (player.hasPermission("landclaimeconomy.bypass")) return;
        if (player.hasPermission("landclaim.admin")) return;

        UUID pid = player.getUniqueId();
        LandClaimAPI api = LandClaimAPI.getInstance();

        // Determine cost based on whether this is the player's first-ever
        // claim (one free if configured) and whether this is the first
        // chunk of a new claim vs an expansion.
        double cost;
        if (cfg.firstClaimFree && !firstClaimDone.contains(pid)
                && api.getTotalChunksByOwner(pid) == 0) {
            // Their first chunk ever is free.
            firstClaimDone.add(pid);
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().claimFirstFree));
            return;
        }

        // Is this an expansion of an existing claim? We treat the first
        // chunk of a new claim as "firstChunkCost" and additional chunks
        // of the same claim as "perChunkCost".
        boolean isFirstChunkOfNewClaim = !api.canCreateClaim(pid);
        cost = isFirstChunkOfNewClaim ? cfg.firstChunkCost : cfg.perChunkCost;
        if (cost <= 0) return; // free

        // Apply the daily cap.
        cost = applyDailyCap(pid, cost, cfg.dailyChargeCap);

        if (cost <= 0) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().claimDailyCapReached
                            .replace("<cap>", EconomyHook.format(cfg.dailyChargeCap))));
            return;
        }

        if (!EconomyHook.has(player, cost)) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().insufficientFunds
                            .replace("<cost>", EconomyHook.format(cost))
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player)))));
            event.setCancelled(true);
            return;
        }

        if (!EconomyHook.withdraw(player, cost)) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().insufficientFunds
                            .replace("<cost>", EconomyHook.format(cost))
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player)))));
            event.setCancelled(true);
            return;
        }

        addDailyCharge(pid, cost);
        plugin.getDatabase().logTransaction(pid.toString(),
                event.getProfile() != null ? event.getProfile().getProfileId().toString() : null,
                "CLAIM_COST", cost, event.getProfile() != null ? event.getProfile().getName() : null);

        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + plugin.getMessages().claimCharged
                        .replace("<cost>", EconomyHook.format(cost))
                        .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(player)))));
    }

    private double applyDailyCap(UUID pid, double cost, double cap) {
        if (cap <= 0) return cost;
        long now = System.currentTimeMillis();
        long windowStart = now - 24L * 60 * 60 * 1000;
        long[] win = dailyWindow.computeIfAbsent(pid, k -> new long[]{now});
        if (win[0] < windowStart) {
            dailyWindow.put(pid, new long[]{now});
            dailyCharges.put(pid, new double[]{0.0});
            win = dailyWindow.get(pid);
        }
        double[] total = dailyCharges.computeIfAbsent(pid, k -> new double[]{0.0});
        if (total[0] >= cap) return 0;
        return Math.min(cost, cap - total[0]);
    }

    private void addDailyCharge(UUID pid, double amount) {
        double[] total = dailyCharges.computeIfAbsent(pid, k -> new double[]{0.0});
        total[0] += amount;
    }
}
