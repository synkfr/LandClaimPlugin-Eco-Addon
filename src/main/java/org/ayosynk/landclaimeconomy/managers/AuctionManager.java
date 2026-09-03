package org.ayosynk.landclaimeconomy.managers;

import org.ayosynk.landClaimPlugin.api.LandClaimAPI;
import org.ayosynk.landClaimPlugin.models.ClaimProfile;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.ayosynk.landclaimeconomy.util.FoliaScheduler;
import org.ayosynk.landclaimeconomy.util.PlayerNameCache;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.ayosynk.landclaimeconomy.config.MessagesConfig;

/**
 * Time-limited claim auctions. Owners start an auction on one of their
 * claims; other players bid against each other; when the timer expires
 * the highest bidder wins and the claim is transferred via the public
 * {@code transferClaim} API.
 *
 * <p>Auctions live in the {@code lce_auctions} table and are checked
 * every {@code auctionCheckIntervalSeconds} seconds by a Folia/Bukkit
 * scheduler. Ended auctions are settled asynchronously so a slow SQLite
 * query doesn't block the main thread.</p>
 */
public class AuctionManager {

    private final LandClaimEconomy plugin;
    private FoliaScheduler.ScheduledHandle checkTask;

    public static class Auction {
        public final int auctionId;
        public final UUID claimId;
        public final UUID sellerId;
        public final String claimName;
        public final String sellerName;
        public final double startingPrice;
        public final double currentBid;
        public final UUID currentBidder;
        public final double buyoutPrice;
        public final long endsAt;
        public final long startedAt;
        public final String status;

        public Auction(int auctionId, UUID claimId, UUID sellerId, String claimName,
                       String sellerName, double startingPrice, double currentBid,
                       UUID currentBidder, double buyoutPrice, long endsAt,
                       long startedAt, String status) {
            this.auctionId = auctionId;
            this.claimId = claimId;
            this.sellerId = sellerId;
            this.claimName = claimName;
            this.sellerName = sellerName;
            this.startingPrice = startingPrice;
            this.currentBid = currentBid;
            this.currentBidder = currentBidder;
            this.buyoutPrice = buyoutPrice;
            this.endsAt = endsAt;
            this.startedAt = startedAt;
            this.status = status;
        }

        public boolean isActive() {
            return "ACTIVE".equalsIgnoreCase(status);
        }

        public long secondsRemaining() {
            return Math.max(0L, (endsAt - System.currentTimeMillis()) / 1000L);
        }
    }

    public AuctionManager(LandClaimEconomy plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (checkTask != null) return;
        long ticks = plugin.getEconomyConfig().auctionCheckIntervalSeconds * 20L;
        checkTask = FoliaScheduler.runTaskTimer(plugin, this::checkEnded, ticks, ticks);
    }

    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }

    /**
     * Start a new auction. Charges the listing fee, validates limits,
     * and inserts the row.
     */
    public boolean startAuction(Player seller, ClaimProfile profile, double startingPrice,
                                 long durationMinutes, double buyoutPrice) {
        if (!plugin.getEconomyConfig().enabled || !plugin.getEconomyConfig().auction.enabled) {
            seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix + plugin.getMessages().featureDisabled));
            return false;
        }
        if (startingPrice <= 0) {
            seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix + plugin.getMessages().marketPriceInvalid));
            return false;
        }
        if (durationMinutes <= 0) {
            durationMinutes = (long) plugin.getEconomyConfig().auctionDefaultDurationMinutes;
        }
        if (!profile.isOwner(seller.getUniqueId())) {
            seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix + plugin.getMessages().auctionNotOwner));
            return false;
        }
        if (getAuctionForClaim(profile.getProfileId()) != null) {
            seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().auctionAlreadyOnAuction
                            .replace("<claim>", profile.getName())));
            return false;
        }
        if (plugin.getMarketManager() != null && plugin.getMarketManager().isListed(profile.getProfileId())) {
            seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().marketAlreadyListed
                            .replace("<claim>", profile.getName())));
            return false;
        }
        int max = plugin.getEconomyConfig().auctionMaxPerPlayer;
        if (max > 0) {
            int active = countActiveAuctions(seller.getUniqueId());
            if (active >= max) {
                seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                        + plugin.getMessages().auctionMaxReached.replace("<max>", String.valueOf(max))));
                return false;
            }
        }
        // Validate buyout: must be at least N × starting price so the
        // buyout represents a meaningful premium. Otherwise sellers could
        // accidentally set a buyout that's basically the starting price.
        if (buyoutPrice > 0) {
            double minBuyout = startingPrice * plugin.getEconomyConfig().auctionMinBuyoutMultiplier;
            if (buyoutPrice < minBuyout) {
                seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                        + "<red>Buyout price must be at least <gold>"
                        + EconomyHook.format(minBuyout)
                        + "</gold> (<gold>"
                        + plugin.getEconomyConfig().auctionMinBuyoutMultiplier
                        + "x</gold> the starting price of <gold>"
                        + EconomyHook.format(startingPrice) + "</gold>)."));
                return false;
            }
        }

        double fee = plugin.getEconomyConfig().auctionListingFee;
        if (fee > 0) {
            if (!EconomyHook.has(seller, fee)) {
                seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                        + plugin.getMessages().insufficientFunds
                                .replace("<cost>", EconomyHook.format(fee))
                                .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(seller)))));
                return false;
            }
            if (!EconomyHook.withdraw(seller, fee)) {
                seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                        + plugin.getMessages().insufficientFunds
                                .replace("<cost>", EconomyHook.format(fee))
                                .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(seller)))));
                return false;
            }
            plugin.getDatabase().logTransaction(seller.getUniqueId().toString(),
                    profile.getProfileId().toString(), "AUCTION_FEE", fee, profile.getName());
        }

        long now = System.currentTimeMillis();
        long endsAt = now + durationMinutes * 60L * 1000L;
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + p + "auctions (claim_profile_id, seller_uuid, "
                             + "starting_price, current_bid, current_bidder, buyout_price, "
                             + "ends_at, started_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')")) {
            ps.setString(1, profile.getProfileId().toString());
            ps.setString(2, seller.getUniqueId().toString());
            ps.setDouble(3, startingPrice);
            ps.setDouble(4, startingPrice);
            if (seller.getUniqueId() != null) ps.setNull(5, java.sql.Types.VARCHAR);
            else ps.setString(5, null);
            ps.setDouble(6, buyoutPrice);
            ps.setLong(7, endsAt);
            ps.setLong(8, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to insert auction row: " + e.getMessage());
            seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix + "<red>Failed to start the auction."));
            return false;
        }

        seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + plugin.getMessages().auctionStarted
                        .replace("<claim>", profile.getName())
                        .replace("<price>", EconomyHook.format(startingPrice))
                        .replace("<duration>", String.valueOf(durationMinutes))));
        return true;
    }

    /**
     * Place a bid on an existing auction. Validates against the min
     * increment and the buyout price, and handles refund of the previous
     * high bidder.
     */
    public boolean placeBid(Player bidder, ClaimProfile profile, double amount) {
        Auction auction = getAuctionForClaim(profile.getProfileId());
        if (auction == null || !auction.isActive()) {
            bidder.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix + plugin.getMessages().auctionNotActive));
            return false;
        }
        if (auction.sellerId.equals(bidder.getUniqueId())) {
            bidder.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix + plugin.getMessages().auctionBidOwnClaim));
            return false;
        }
        double minRequired = auction.currentBid + plugin.getEconomyConfig().auctionMinBidIncrement;
        if (amount < minRequired) {
            bidder.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().auctionBidTooLow
                            .replace("<min>", EconomyHook.format(minRequired))
                            .replace("<current>", EconomyHook.format(auction.currentBid))));
            return false;
        }
        if (auction.buyoutPrice > 0 && amount >= auction.buyoutPrice) {
            double buyoutAmount = auction.buyoutPrice;
            if (!EconomyHook.has(bidder, buyoutAmount)) {
                bidder.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                        + plugin.getMessages().insufficientFunds
                                .replace("<cost>", EconomyHook.format(buyoutAmount))
                                .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(bidder)))));
                return false;
            }
            if (!EconomyHook.withdraw(bidder, buyoutAmount)) {
                bidder.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                        + plugin.getMessages().insufficientFunds
                                .replace("<cost>", EconomyHook.format(buyoutAmount))
                                .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(bidder)))));
                return false;
            }
            if (auction.currentBidder != null && auction.currentBid > 0) {
                EconomyHook.deposit(Bukkit.getOfflinePlayer(auction.currentBidder), auction.currentBid);
                Player oldBidder = Bukkit.getPlayer(auction.currentBidder);
                if (oldBidder != null) {
                    String msg = plugin.getMessages().prefix
                            + plugin.getMessages().auctionBidOutbid
                                    .replace("<claim>", auction.claimName)
                                    .replace("<amount>", EconomyHook.format(buyoutAmount));
                    FoliaScheduler.runForPlayer(plugin, oldBidder, () -> oldBidder.sendMessage(MessagesConfig.formatRaw(msg)));
                }
            }
            settleAuction(auction, bidder.getUniqueId(), buyoutAmount, true);
            return true;
        }
        if (!EconomyHook.has(bidder, amount)) {
            bidder.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().insufficientFunds
                            .replace("<cost>", EconomyHook.format(amount))
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(bidder)))));
            return false;
        }
        if (!EconomyHook.withdraw(bidder, amount)) {
            bidder.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().insufficientFunds
                            .replace("<cost>", EconomyHook.format(amount))
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(bidder)))));
            return false;
        }
        if (auction.currentBidder != null && auction.currentBid > 0) {
            EconomyHook.deposit(Bukkit.getOfflinePlayer(auction.currentBidder), auction.currentBid);
            Player oldBidder = Bukkit.getPlayer(auction.currentBidder);
            if (oldBidder != null) {
                String msg = plugin.getMessages().prefix
                        + plugin.getMessages().auctionBidOutbid
                                .replace("<claim>", auction.claimName)
                                .replace("<amount>", EconomyHook.format(amount));
                FoliaScheduler.runForPlayer(plugin, oldBidder, () -> oldBidder.sendMessage(MessagesConfig.formatRaw(msg)));
            }
        }
        long snipeWindowMs = plugin.getEconomyConfig().auctionSnipeWindowSeconds * 1000L;
        long currentEndsAt = auction.endsAt;
        long now = System.currentTimeMillis();
        long newEndsAt = currentEndsAt;
        if (snipeWindowMs > 0 && currentEndsAt - now <= snipeWindowMs) {
            newEndsAt = currentEndsAt + snipeWindowMs;
            Player seller = Bukkit.getPlayer(auction.sellerId);
            if (seller != null) {
                String msg = plugin.getMessages().prefix
                        + "<yellow>Auction for <gold>" + auction.claimName
                        + "</gold> extended by <gold>"
                        + plugin.getEconomyConfig().auctionSnipeWindowSeconds
                        + "</gold>s due to a last-minute bid.";
                FoliaScheduler.runForPlayer(plugin, seller, () -> seller.sendMessage(MessagesConfig.formatRaw(msg)));
            }
        }
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE " + p + "auctions SET current_bid = ?, current_bidder = ?, ends_at = ? "
                             + "WHERE auction_id = ? AND status = 'ACTIVE' AND current_bid < ?")) {
            ps.setDouble(1, amount);
            ps.setString(2, bidder.getUniqueId().toString());
            ps.setLong(3, newEndsAt);
            ps.setInt(4, auction.auctionId);
            ps.setDouble(5, amount);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                EconomyHook.deposit(bidder, amount);
                bidder.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix + plugin.getMessages().auctionNotActive));
                return false;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to place bid: " + e.getMessage());
            EconomyHook.deposit(bidder, amount);
            return false;
        }
        plugin.getDatabase().logTransaction(bidder.getUniqueId().toString(),
                auction.claimId.toString(), "AUCTION_BID", -amount, auction.claimName);

        bidder.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + plugin.getMessages().auctionBidPlaced
                        .replace("<amount>", EconomyHook.format(amount))
                        .replace("<claim>", auction.claimName)));
        return true;
    }

    /**
     * Cancel an auction. Only the seller may cancel, and only if there
     * are no bids (refunds would be messy otherwise — sellers who want
     * to bail after bids come in should just let it run).
     */
    public boolean cancelAuction(Player seller, ClaimProfile profile) {
        Auction auction = getAuctionForClaim(profile.getProfileId());
        if (auction == null || !auction.isActive()) {
            seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix + plugin.getMessages().auctionNotActive));
            return false;
        }
        if (!auction.sellerId.equals(seller.getUniqueId())) {
            seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix + plugin.getMessages().auctionNotOwner));
            return false;
        }
        if (auction.currentBidder != null && auction.currentBid > 0) {
            seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + "<red>Cannot cancel — bids already placed. Let it run."));
            return false;
        }
        setStatus(auction.auctionId, "CANCELLED");
        seller.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + plugin.getMessages().auctionCancelled.replace("<claim>", profile.getName())));
        return true;
    }

    /**
     * Periodically called by the scheduler. Ends and settles any
     * active auction whose deadline has passed.
     */
    void checkEnded() {
        if (!plugin.getEconomyConfig().enabled
                || !plugin.getEconomyConfig().auction.enabled) return;
        long now = System.currentTimeMillis();
        String p = plugin.getDatabase().tablePrefix();
        List<Auction> expired = new ArrayList<>();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT auction_id, claim_profile_id, seller_uuid, starting_price, "
                             + "current_bid, current_bidder, buyout_price, ends_at, started_at, status "
                             + "FROM " + p + "auctions WHERE status = 'ACTIVE' AND ends_at <= ?")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    expired.add(readAuction(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to scan auctions: " + e.getMessage());
        }
        for (Auction a : expired) {
            settleAuction(a, a.currentBidder, a.currentBid, false);
        }
    }

    private void settleAuction(Auction auction, UUID winnerId, double winningBid, boolean isBuyout) {
        // Update status first so we don't double-settle.
        setStatus(auction.auctionId, "SETTLED");

        if (winnerId == null || winningBid <= 0) {
            // No bids — seller keeps the claim. Route the message through
            // runForPlayer because this method is invoked from checkEnded
            // (global region) where direct player-state access would crash on Folia.
            Player seller = Bukkit.getPlayer(auction.sellerId);
            if (seller != null) {
                String msg = plugin.getMessages().prefix
                        + plugin.getMessages().auctionEndedNoBids.replace("<claim>", auction.claimName);
                FoliaScheduler.runForPlayer(plugin, seller, () -> seller.sendMessage(MessagesConfig.formatRaw(msg)));
            }
            return;
        }

        double payout = winningBid * plugin.getEconomyConfig().marketSellerPayoutRatio;
        EconomyHook.deposit(Bukkit.getOfflinePlayer(auction.sellerId), payout);
        plugin.getDatabase().logTransaction(auction.sellerId.toString(),
                auction.claimId.toString(), "AUCTION_SALE", payout, auction.claimName);

        LandClaimAPI api = LandClaimAPI.getInstance();
        boolean transferred = false;
        if (api != null) {
            transferred = api.transferClaim(auction.claimId, winnerId);
        }

        if (!transferred) {
            EconomyHook.deposit(Bukkit.getOfflinePlayer(winnerId), winningBid);
            if (payout > 0) {
                EconomyHook.withdraw(Bukkit.getOfflinePlayer(auction.sellerId), payout);
            }
            plugin.getLogger().warning("Auction settlement failed for auction " + auction.auctionId
                    + " — refunded winner " + winnerId + " (" + winningBid + ") and seller "
                    + auction.sellerId + " (" + payout + "). Manual admin intervention required.");
            Player winnerPlayer = Bukkit.getPlayer(winnerId);
            if (winnerPlayer != null) {
                String msg = plugin.getMessages().prefix
                        + plugin.getMessages().marketRefund
                                .replace("<amount>", EconomyHook.format(winningBid));
                FoliaScheduler.runForPlayer(plugin, winnerPlayer, () -> winnerPlayer.sendMessage(MessagesConfig.formatRaw(msg)));
            }
            return;
        }

        Player winner = Bukkit.getPlayer(winnerId);
        if (winner != null) {
            String msgTemplate = isBuyout ? plugin.getMessages().auctionBuyoutUsed : plugin.getMessages().auctionWonBuyer;
            String msg = plugin.getMessages().prefix
                    + msgTemplate.replace("<claim>", auction.claimName)
                            .replace("<amount>", EconomyHook.format(winningBid));
            FoliaScheduler.runForPlayer(plugin, winner, () -> winner.sendMessage(MessagesConfig.formatRaw(msg)));
        }
        Player seller = Bukkit.getPlayer(auction.sellerId);
        if (seller != null) {
            String msg = plugin.getMessages().prefix
                    + plugin.getMessages().auctionWonSeller
                            .replace("<claim>", auction.claimName)
                            .replace("<amount>", EconomyHook.format(payout));
            FoliaScheduler.runForPlayer(plugin, seller, () -> seller.sendMessage(MessagesConfig.formatRaw(msg)));
        }
    }

    public List<Auction> getActiveAuctions() {
        List<Auction> auctions = new ArrayList<>();
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT auction_id, claim_profile_id, seller_uuid, starting_price, "
                             + "current_bid, current_bidder, buyout_price, ends_at, started_at, status "
                             + "FROM " + p + "auctions WHERE status = 'ACTIVE' ORDER BY ends_at ASC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) auctions.add(readAuction(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to list auctions: " + e.getMessage());
        }
        return auctions;
    }

    public Auction getAuctionForClaim(UUID claimId) {
        if (claimId == null) return null;
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT auction_id, claim_profile_id, seller_uuid, starting_price, "
                             + "current_bid, current_bidder, buyout_price, ends_at, started_at, status "
                             + "FROM " + p + "auctions WHERE claim_profile_id = ? AND status = 'ACTIVE'")) {
            ps.setString(1, claimId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readAuction(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to read auction: " + e.getMessage());
        }
        return null;
    }

    private int countActiveAuctions(UUID sellerId) {
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM " + p + "auctions WHERE seller_uuid = ? AND status = 'ACTIVE'")) {
            ps.setString(1, sellerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to count auctions: " + e.getMessage());
        }
        return 0;
    }

    private void setStatus(int auctionId, String status) {
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE " + p + "auctions SET status = ? WHERE auction_id = ?")) {
            ps.setString(1, status);
            ps.setInt(2, auctionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to update auction status: " + e.getMessage());
        }
    }

    private Auction readAuction(ResultSet rs) throws SQLException {
        UUID seller = UUID.fromString(rs.getString("seller_uuid"));
        // Use the name cache instead of Bukkit.getOfflinePlayer().getName() so we
        // never do blocking usercache.json I/O from a region/global-region thread.
        String sellerName = plugin.getNameCache().getName(seller);
        String claimName = "<unknown>";
        try {
            LandClaimAPI api = LandClaimAPI.getInstance();
            if (api != null) {
                ClaimProfile p = api.getClaimById(UUID.fromString(rs.getString("claim_profile_id")));
                if (p != null) claimName = p.getName();
            }
        } catch (Throwable ignored) {}
        String bidderStr = rs.getString("current_bidder");
        UUID bidder = bidderStr == null ? null : UUID.fromString(bidderStr);
        return new Auction(
                rs.getInt("auction_id"),
                UUID.fromString(rs.getString("claim_profile_id")),
                seller,
                claimName,
                sellerName,
                rs.getDouble("starting_price"),
                rs.getDouble("current_bid"),
                bidder,
                rs.getDouble("buyout_price"),
                rs.getLong("ends_at"),
                rs.getLong("started_at"),
                rs.getString("status"));
    }

    public boolean cancelAuctionBySystem(UUID claimId) {
        Auction auction = getAuctionForClaim(claimId);
        if (auction == null || !auction.isActive()) return false;
        setStatus(auction.auctionId, "CANCELLED");
        if (auction.currentBidder != null && auction.currentBid > 0) {
            EconomyHook.deposit(Bukkit.getOfflinePlayer(auction.currentBidder), auction.currentBid);
            Player bidder = Bukkit.getPlayer(auction.currentBidder);
            if (bidder != null) {
                String msg = plugin.getMessages().prefix
                        + plugin.getMessages().marketRefund
                                .replace("<amount>", EconomyHook.format(auction.currentBid));
                FoliaScheduler.runForPlayer(plugin, bidder, () -> bidder.sendMessage(MessagesConfig.formatRaw(msg)));
            }
        }
        return true;
    }
}