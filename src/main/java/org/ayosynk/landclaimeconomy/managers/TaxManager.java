package org.ayosynk.landclaimeconomy.managers;

import org.ayosynk.landClaimPlugin.api.LandClaimAPI;
import org.ayosynk.landClaimPlugin.models.ClaimProfile;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.config.MessagesConfig;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.ayosynk.landclaimeconomy.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Daily per-chunk upkeep tax with an auto-unclaim grace period.
 *
 * <p>The scheduler runs every {@code taxIntervalMinutes} minutes. On each
 * tick it walks every claim, computes the owed tax (chunks × per-day
 * rate × days since last payment), tries to withdraw it, and increments
 * the per-claim {@code unpaid_days} counter on failure. When
 * {@code unpaid_days > taxGracePeriodDays} the claim is auto-unclaimed
 * chunk by chunk from the edge inward.</p>
 *
 * <p>The grace period is intentional: a player who goes offline for a
 * few days shouldn't lose their land. The grace window is set in
 * {@code taxGracePeriodDays} and admins can bypass it with the
 * {@code landclaimeconomy.bypass.tax} permission.</p>
 */
public class TaxManager {

    private final LandClaimEconomy plugin;
    private FoliaScheduler.ScheduledHandle task;

    public TaxManager(LandClaimEconomy plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) return;
        long ticks = plugin.getEconomyConfig().taxIntervalMinutes * 60 * 20L;
        task = FoliaScheduler.runTaskTimer(plugin, this::tick, ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    void tick() {
        if (!plugin.getEconomyConfig().enabled
                || !plugin.getEconomyConfig().tax.enabled) return;
        if (!EconomyHook.isAvailable()) return;

        long now = System.currentTimeMillis();
        long dayMs = TimeUnit.DAYS.toMillis(1);

        for (ClaimProfile profile : new java.util.ArrayList<>(
                plugin.getParentAPI().getAllClaimProfiles())) {
            try {
                tickClaim(profile, now, dayMs);
            } catch (Exception ex) {
                plugin.getLogger().warning("Tax tick failed for claim " + profile.getName() + ": " + ex.getMessage());
            }
        }
    }

    private void tickClaim(ClaimProfile profile, long now, long dayMs) {
        var cfg = plugin.getEconomyConfig();
        int chunkCount = profile.getOwnedChunks().size();
        if (chunkCount == 0) return;

        // Skip if owner is online and has the bypass permission.
        UUID ownerId = profile.getOwnerId();
        if (ownerId != null) {
            org.bukkit.entity.Player online = Bukkit.getPlayer(ownerId);
            if (online != null && online.hasPermission("landclaimeconomy.bypass.tax")) return;
        }

        long[] ledger = readLedger(profile.getProfileId());
        long lastPaid = ledger[0];
        int unpaidDays = (int) ledger[1];
        int lastChunks = (int) ledger[2];

        // First-ever tax: start the clock now.
        if (lastPaid == 0) {
            writeLedger(profile.getProfileId(), now, 0, chunkCount);
            return;
        }

        // How many full tax days have elapsed?
        long elapsedMs = now - lastPaid;
        long elapsedDays = elapsedMs / dayMs;
        if (elapsedDays <= 0) return;

        // Charge for the elapsed days. If the owner is offline, deposit
        // fails silently and unpaidDays accumulates.
        double owed = elapsedDays * cfg.taxPerChunkPerDay * chunkCount;
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerId);
        boolean paid = EconomyHook.withdraw(owner, owed);

        if (paid) {
            // Reset the clock. Note we DON'T log a transaction per chunk per
            // day here — that would spam the ledger. The tax tick is
            // logged as a single row per tick.
            writeLedger(profile.getProfileId(), now, 0, chunkCount);
            plugin.getDatabase().logTransaction(ownerId.toString(),
                    profile.getProfileId().toString(), "TAX", -owed,
                    profile.getName());
            // tickClaim runs on the global region thread; the owner may be on
            // a different region. Route the message through runForPlayer.
            if (owner.isOnline() && owner.getPlayer() != null) {
                org.bukkit.entity.Player ownerPlayer = owner.getPlayer();
                String msg = plugin.getMessages().prefix
                        + plugin.getMessages().taxPaid
                                .replace("<amount>", EconomyHook.format(owed))
                                .replace("<chunks>", String.valueOf(chunkCount))
                                .replace("<claim>", profile.getName());
                FoliaScheduler.runForPlayer(plugin, ownerPlayer, () -> ownerPlayer.sendMessage(MessagesConfig.formatRaw(msg)));
            }
        } else {
            int newUnpaidDays = unpaidDays + (int) elapsedDays;
            if (newUnpaidDays > cfg.taxGracePeriodDays && cfg.taxGracePeriodDays > 0) {
                int unclaimed = plugin.getParentAPI().unclaimAll(profile.getProfileId());
                if (unclaimed > 0) {
                    plugin.getLogger().info("Auto-unclaimed " + unclaimed
                            + " chunk(s) of claim '" + profile.getName()
                            + "' (owner " + ownerId + ") — tax went unpaid for "
                            + newUnpaidDays + " day(s).");
                }
                if (owner.isOnline() && owner.getPlayer() != null) {
                    org.bukkit.entity.Player ownerPlayer = owner.getPlayer();
                    String msg = plugin.getMessages().prefix
                            + plugin.getMessages().taxAutoUnclaimed
                                    .replace("<count>", String.valueOf(unclaimed))
                                    .replace("<claim>", profile.getName())
                                    .replace("<days>", String.valueOf(cfg.taxGracePeriodDays));
                    FoliaScheduler.runForPlayer(plugin, ownerPlayer, () -> ownerPlayer.sendMessage(MessagesConfig.formatRaw(msg)));
                }
                writeLedger(profile.getProfileId(), now, 0, 0);
            } else {
                writeLedger(profile.getProfileId(), lastPaid, newUnpaidDays, chunkCount);
                if (owner.isOnline() && owner.getPlayer() != null) {
                    long deadline = lastPaid + TimeUnit.DAYS.toMillis(cfg.taxGracePeriodDays);
                    org.bukkit.entity.Player ownerPlayer = owner.getPlayer();
                    String msg = plugin.getMessages().prefix
                            + plugin.getMessages().taxInsufficient
                                    .replace("<amount>", EconomyHook.format(owed))
                                    .replace("<deadline>", new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(deadline)));
                    FoliaScheduler.runForPlayer(plugin, ownerPlayer, () -> ownerPlayer.sendMessage(MessagesConfig.formatRaw(msg)));
                }
            }
        }
    }

    public long[] readLedger(UUID profileId) {
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_paid_at, unpaid_days, last_known_chunks FROM "
                             + p + "tax_ledger WHERE claim_profile_id = ?")) {
            ps.setString(1, profileId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new long[]{
                            rs.getLong(1),
                            rs.getInt(2),
                            rs.getInt(3)
                    };
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to read tax ledger: " + e.getMessage());
        }
        return new long[]{0, 0, 0};
    }

    private void writeLedger(UUID profileId, long lastPaidAt, int unpaidDays, int chunkCount) {
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "REPLACE INTO " + p + "tax_ledger (claim_profile_id, last_paid_at, unpaid_days, last_known_chunks) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, profileId.toString());
            ps.setLong(2, lastPaidAt);
            ps.setInt(3, unpaidDays);
            ps.setInt(4, chunkCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to write tax ledger: " + e.getMessage());
        }
    }
}
