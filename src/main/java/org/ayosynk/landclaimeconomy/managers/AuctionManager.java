package org.ayosynk.landclaimeconomy.managers;

import org.ayosynk.landClaimPlugin.api.LandClaimAPI;
import org.ayosynk.landClaimPlugin.models.ClaimProfile;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private BukkitTask checkTask;

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
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkEnded,
                ticks, ticks);
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
            seller.sendMessage(plugin.getMessages().prefix + plugin.getMessages().featureDisabled);
            return false;
        }
        if (startingPrice <= 0) {
            seller.sendMessage(plugin.getMessages().prefix + plugin.getMessages().marketPriceInvalid);
            return false;
        }
        if (durationMinutes <= 0) {
            durationMinutes = (long) plugin.getEconomyConfig().auctionDefaultDurationMinutes;
        }
        if (!profile.isOwner(seller.getUniqueId())) {
            seller.sendMessage(plugin.getMessages().prefix + plugin.getMessages().auctionNotOwner);
            return false;
        }
        if (getAuctionForClaim(profile.getProfileId()) != null) {
            seller.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().auctionAlreadyOnAuction
                            .replace("<claim>", profile.getName()));
            return false;
        }
        int max = plugin.getEconomyConfig().auctionMaxPerPlayer;
        if (max > 0) {
            int active = countActiveAuctions(seller.getUniqueId());
            if (active >= max) {
                seller.sendMessage(plugin.getMessages().prefix
                        + plugin.getMessages().auctionMaxReached.replace("<max>", String.valueOf(max)));
                return false;
            }
        }

        double fee = plugin.getEconomyConfig().auctionListingFee;
        if (fee > 0) {
            if (!EconomyHook.has(seller, fee)) {
                seller.sendMessage(plugin.getMessages().prefix
                        + plugin.getMessages().insufficientFunds
                                .replace("<cost>", EconomyHook.format(fee))
                                .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(seller))));
                return false;
            }
            if (!EconomyHook.withdraw(seller, fee)) {
                seller.sendMessage(plugin.getMessages().prefix
                        + plugin.getMessages().insufficientFunds
                                .replace("<cost>", EconomyHook.format(fee))
                                .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(seller))));
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
            seller.sendMessage(plugin.getMessages().prefix + "<red>Failed to start the auction.");
            return false;
        }

        seller.sendMessage(plugin.getMessages().prefix
                + plugin.getMessages().auctionStarted
                        .replace("<claim>", profile.getName())
                        .replace("<price>", EconomyHook.format(startingPrice))
                        .replace("<duration>", String.valueOf(durationMinutes)));
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
            bidder.sendMessage(plugin.getMessages().prefix + plugin.getMessages().auctionNotActive);
            return false;
        }
        if (auction.sellerId.equals(bidder.getUniqueId())) {
            bidder.sendMessage(plugin.getMessages().prefix + plugin.getMessages().auctionBidOwnClaim);
            return false;
        }
        double minRequired = auction.currentBid + plugin.getEconomyConfig().auctionMinBidIncrement;
        if (amount < minRequired) {
            bidder.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().auctionBidTooLow
                            .replace("<min>", EconomyHook.format(minRequired))
                            .replace("<current>", EconomyHook.format(auction.currentBid)));
            return false;
        }
        if (auction.buyoutPrice > 0 && amount >= auction.buyoutPrice) {
            // Buyout — settle immediately.
            settleAuction(auction, bidder.getUniqueId(), auction.buyoutPrice, true);
            return true;
        }
        if (!EconomyHook.has(bidder, amount)) {
            bidder.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().insufficientFunds
                            .replace("<cost>", EconomyHook.format(amount))
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(bidder))));
            return false;
        }
        if (!EconomyHook.withdraw(bidder, amount)) {
            bidder.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().insufficientFunds
                            .replace("<cost>", EconomyHook.format(amount))
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(bidder))));
            return false;
        }
        // Refund the previous high bidder.
        if (auction.currentBidder != null && auction.currentBid > 0) {
            EconomyHook.deposit(Bukkit.getOfflinePlayer(auction.currentBidder), auction.currentBid);
            Player oldBidder = Bukkit.getPlayer(auction.currentBidder);
            if (oldBidder != null) {
                oldBidder.sendMessage(plugin.getMessages().prefix
                        + plugin.getMessages().auctionBidOutbid
                                .replace("<claim>", auction.claimName)
                                .replace("<amount>", EconomyHook.format(amount)));
            }
        }
        // Update the auction row.
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE " + p + "auctions SET current_bid = ?, current_bidder = ? "
                             + "WHERE auction_id = ? AND status = 'ACTIVE'")) {
            ps.setDouble(1, amount);
            ps.setString(2, bidder.getUniqueId().toString());
            ps.setInt(3, auction.auctionId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                // Race: someone else settled it first. Refund and bail.
                EconomyHook.deposit(bidder, amount);
                bidder.sendMessage(plugin.getMessages().prefix + plugin.getMessages().auctionNotActive);
                return false;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to place bid: " + e.getMessage());
            EconomyHook.deposit(bidder, amount);
            return false;
        }
        plugin.getDatabase().logTransaction(bidder.getUniqueId().toString(),
                auction.claimId.toString(), "AUCTION_BID", -amount, auction.claimName);

        bidder.sendMessage(plugin.getMessages().prefix
                + plugin.getMessages().auctionBidPlaced
                        .replace("<amount>", EconomyHook.format(amount))
                        .replace("<claim>", auction.claimName));
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
            seller.sendMessage(plugin.getMessages().prefix + plugin.getMessages().auctionNotActive);
            return false;
        }
        if (!auction.sellerId.equals(seller.getUniqueId())) {
            seller.sendMessage(plugin.getMessages().prefix + plugin.getMessages().auctionNotOwner);
            return false;
        }
        if (auction.currentBidder != null && auction.currentBid > 0) {
            seller.sendMessage(plugin.getMessages().prefix
                    + "<red>Cannot cancel — bids already placed. Let it run.");
            return false;
        }
        setStatus(auction.auctionId, "CANCELLED");
        seller.sendMessage(plugin.getMessages().prefix
                + plugin.getMessages().auctionCancelled.replace("<claim>", profile.getName()));
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
            // No bids — seller keeps the claim.
            Player seller = Bukkit.getPlayer(auction.sellerId);
            if (seller != null) {
                seller.sendMessage(plugin.getMessages().prefix
                        + plugin.getMessages().auctionEndedNoBids.replace("<claim>", auction.claimName));
            }
            return;
        }

        // Apply seller-payout ratio (same as the marketplace).
        double payout = winningBid * plugin.getEconomyConfig().marketSellerPayoutRatio;
        EconomyHook.deposit(Bukkit.getOfflinePlayer(auction.sellerId), payout);
        plugin.getDatabase().logTransaction(auction.sellerId.toString(),
                auction.claimId.toString(), "AUCTION_SALE", payout, auction.claimName);

        // Transfer the claim via the public API.
        LandClaimAPI api = LandClaimAPI.getInstance();
        if (api != null) {
            api.transferClaim(auction.claimId, winnerId);
        }

        // Notify both sides.
        Player winner = Bukkit.getPlayer(winnerId);
        if (winner != null) {
            String msg = isBuyout ? plugin.getMessages().auctionBuyoutUsed : plugin.getMessages().auctionWonBuyer;
            winner.sendMessage(plugin.getMessages().prefix
                    + msg.replace("<claim>", auction.claimName)
                            .replace("<amount>", EconomyHook.format(winningBid)));
        }
        Player seller = Bukkit.getPlayer(auction.sellerId);
        if (seller != null) {
            seller.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().auctionWonSeller
                            .replace("<claim>", auction.claimName)
                            .replace("<amount>", EconomyHook.format(payout)));
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
                             + "FROM " + p + "auctions WHERE claim_profile_id = ?")) {
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
        String sellerName = "<unknown>";
        try {
            String name = Bukkit.getOfflinePlayer(seller).getName();
            if (name != null) sellerName = name;
        } catch (Throwable ignored) {}
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
}