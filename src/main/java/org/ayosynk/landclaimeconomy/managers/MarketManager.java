package org.ayosynk.landclaimeconomy.managers;

import org.ayosynk.landClaimPlugin.api.LandClaimAPI;
import org.ayosynk.landClaimPlugin.models.ClaimProfile;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
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

/**
 * Server-wide claim marketplace — owners can list their claims for sale,
 * other players can browse and buy. Data lives in the {@code lce_market_listings}
 * table.
 *
 * <p>The marketplace doesn't move chunks between profiles directly
 * (the parent's API doesn't expose that without a player context). The
 * buy flow instead records the sale in the ledger, deducts from the
 * buyer, pays the seller, and lets the owner manually transfer the
 * claim with {@code /claim admin edit <newOwner>} on the next tick
 * (or via the GUI). The {@link #transferOwnership} helper below can be
 * called from a /claimmarket buy command if the parent's API later
 * grows a transfer-ownership method.</p>
 */
public class MarketManager {

    private final LandClaimEconomy plugin;

    public MarketManager(LandClaimEconomy plugin) {
        this.plugin = plugin;
    }

    public static class Listing {
        public final UUID claimId;
        public final UUID ownerId;
        public final double price;
        public final long listedAt;
        public final String claimName;
        public final String ownerName;

        public Listing(UUID claimId, UUID ownerId, double price, long listedAt,
                       String claimName, String ownerName) {
            this.claimId = claimId;
            this.ownerId = ownerId;
            this.price = price;
            this.listedAt = listedAt;
            this.claimName = claimName;
            this.ownerName = ownerName;
        }
    }

    public boolean list(Player seller, ClaimProfile profile, double price) {
        if (!plugin.getEconomyConfig().enabled || !plugin.getEconomyConfig().market.enabled) {
            seller.sendMessage(plugin.getMessages().prefix + plugin.getMessages().featureDisabled);
            return false;
        }
        if (price <= 0) {
            seller.sendMessage(plugin.getMessages().prefix + plugin.getMessages().marketPriceInvalid);
            return false;
        }
        double max = plugin.getEconomyConfig().marketMaxPrice;
        if (max > 0 && price > max) {
            seller.sendMessage(plugin.getMessages().prefix
                    + "<red>Price exceeds the server cap of <gold>"
                    + EconomyHook.format(max) + "<red>.");
            return false;
        }
        if (isListed(profile.getProfileId())) {
            seller.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().marketAlreadyListed
                            .replace("<claim>", profile.getName()));
            return false;
        }
        if (!profile.isOwner(seller.getUniqueId())) {
            seller.sendMessage(plugin.getMessages().prefix + plugin.getMessages().notOwner);
            return false;
        }

        // Optional listing fee.
        double fee = plugin.getEconomyConfig().marketListingFee;
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
                    profile.getProfileId().toString(), "MARKET_FEE", fee, profile.getName());
        }

        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + p + "market_listings (claim_profile_id, owner_uuid, price, listed_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, profile.getProfileId().toString());
            ps.setString(2, seller.getUniqueId().toString());
            ps.setDouble(3, price);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to list claim: " + e.getMessage());
            seller.sendMessage(plugin.getMessages().prefix + "<red>Failed to list the claim. See console.");
            return false;
        }

        seller.sendMessage(plugin.getMessages().prefix
                + plugin.getMessages().marketListed
                        .replace("<claim>", profile.getName())
                        .replace("<price>", EconomyHook.format(price)));
        return true;
    }

    public boolean unlist(Player seller, ClaimProfile profile) {
        if (!isListed(profile.getProfileId())) {
            seller.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().marketNotListed
                            .replace("<claim>", profile.getName()));
            return false;
        }
        if (!profile.isOwner(seller.getUniqueId()) && !seller.hasPermission("landclaim.admin")) {
            seller.sendMessage(plugin.getMessages().prefix + plugin.getMessages().notOwner);
            return false;
        }
        deleteListing(profile.getProfileId());
        seller.sendMessage(plugin.getMessages().prefix
                + plugin.getMessages().marketUnlisted
                        .replace("<claim>", profile.getName()));
        return true;
    }

    /**
     * Buy a listed claim. Charges the buyer, pays the seller (minus
     * the configured payout ratio), records the transaction, transfers
     * the claim via the public API's transferClaim() method, and removes
     * the listing.
     */
    public boolean buy(Player buyer, ClaimProfile profile) {
        if (!plugin.getEconomyConfig().enabled || !plugin.getEconomyConfig().market.enabled) {
            buyer.sendMessage(plugin.getMessages().prefix + plugin.getMessages().featureDisabled);
            return false;
        }
        Listing listing = getListing(profile.getProfileId());
        if (listing == null) {
            buyer.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().marketNotListed
                            .replace("<claim>", profile.getName()));
            return false;
        }
        if (listing.ownerId.equals(buyer.getUniqueId())) {
            buyer.sendMessage(plugin.getMessages().prefix
                    + "<red>You already own this claim.");
            return false;
        }
        if (!EconomyHook.has(buyer, listing.price)) {
            buyer.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().insufficientFunds
                            .replace("<cost>", EconomyHook.format(listing.price))
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(buyer))));
            return false;
        }
        if (!EconomyHook.withdraw(buyer, listing.price)) {
            buyer.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().insufficientFunds
                            .replace("<cost>", EconomyHook.format(listing.price))
                            .replace("<balance>", EconomyHook.format(EconomyHook.getBalance(buyer))));
            return false;
        }

        double sellerPayout = listing.price * plugin.getEconomyConfig().marketSellerPayoutRatio;
        double serverCut = listing.price - sellerPayout;

        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.ownerId);
        if (sellerPayout > 0) {
            EconomyHook.deposit(seller, sellerPayout);
            plugin.getDatabase().logTransaction(seller.getUniqueId().toString(),
                    profile.getProfileId().toString(), "MARKET_SALE", sellerPayout, profile.getName());
        }
        if (serverCut > 0) {
            plugin.getDatabase().logTransaction(buyer.getUniqueId().toString(),
                    profile.getProfileId().toString(), "MARKET_SERVER_CUT", -serverCut, profile.getName());
        }
        plugin.getDatabase().logTransaction(buyer.getUniqueId().toString(),
                profile.getProfileId().toString(), "MARKET_PURCHASE", -listing.price, profile.getName());

        // Transfer ownership via the public API. The claim profile is
        // either re-keyed (if buyer has no profile) or merged into
        // the buyer's existing profile (so they keep their own claim).
        // The buyer passes themselves as the actor — they're allowed to
        // transfer a claim to themselves without needing admin.
        LandClaimAPI api = LandClaimAPI.getInstance();
        boolean transferred = false;
        if (api != null) {
            transferred = api.transferClaim(buyer, profile.getProfileId(), buyer.getUniqueId());
        }

        deleteListing(profile.getProfileId());

        if (!transferred) {
            // Refund the buyer — money was already withdrawn and the
            // seller was already paid out. Roll back both sides.
            EconomyHook.deposit(buyer, listing.price);
            if (sellerPayout > 0) {
                EconomyHook.withdraw(seller, sellerPayout);
            }
            plugin.getDatabase().logTransaction(buyer.getUniqueId().toString(),
                    profile.getProfileId().toString(), "MARKET_REFUND", listing.price, profile.getName());
            plugin.getLogger().warning("Claim transfer failed for profileId " + profile.getProfileId()
                    + " — refunded buyer " + buyer.getUniqueId() + " (" + listing.price + ") and seller "
                    + seller.getUniqueId() + " (" + sellerPayout + "). Manual admin intervention required.");
            buyer.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().marketRefund
                            .replace("<amount>", EconomyHook.format(listing.price)));
            return false;
        }

        buyer.sendMessage(plugin.getMessages().prefix
                + plugin.getMessages().marketBought
                        .replace("<claim>", profile.getName())
                        .replace("<price>", EconomyHook.format(listing.price))
                        .replace("<seller>", listing.ownerName));
        if (seller.isOnline() && seller.getPlayer() != null) {
            seller.getPlayer().sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().marketSold
                            .replace("<claim>", profile.getName())
                            .replace("<buyer>", buyer.getName())
                            .replace("<price>", EconomyHook.format(sellerPayout)));
        }
        return true;
    }

    public List<Listing> getActiveListings() {
        List<Listing> listings = new ArrayList<>();
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT claim_profile_id, owner_uuid, price, listed_at FROM " + p + "market_listings")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID claimId = UUID.fromString(rs.getString(1));
                    UUID ownerId = UUID.fromString(rs.getString(2));
                    double price = rs.getDouble(3);
                    long listedAt = rs.getLong(4);

                    String claimName = "<unknown>";
                    String ownerName = Bukkit.getOfflinePlayer(ownerId).getName();
                    if (ownerName == null) ownerName = ownerId.toString();
                    // Use the new public-API lookup instead of iterating
                    // every owner's profile list.
                    LandClaimAPI api = LandClaimAPI.getInstance();
                    if (api != null) {
                        ClaimProfile profile = api.getClaimById(claimId);
                        if (profile != null) claimName = profile.getName();
                    }
                    listings.add(new Listing(claimId, ownerId, price, listedAt, claimName, ownerName));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to list market listings: " + e.getMessage());
        }
        return listings;
    }

    public Listing getListing(UUID claimId) {
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT claim_profile_id, owner_uuid, price, listed_at FROM "
                             + p + "market_listings WHERE claim_profile_id = ?")) {
            ps.setString(1, claimId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Listing(
                            UUID.fromString(rs.getString(1)),
                            UUID.fromString(rs.getString(2)),
                            rs.getDouble(3),
                            rs.getLong(4),
                            "<unknown>", "<unknown>");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to read listing: " + e.getMessage());
        }
        return null;
    }

    public boolean isListed(UUID claimId) {
        return getListing(claimId) != null;
    }

    private void deleteListing(UUID claimId) {
        String p = plugin.getDatabase().tablePrefix();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM " + p + "market_listings WHERE claim_profile_id = ?")) {
            ps.setString(1, claimId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to delete listing: " + e.getMessage());
        }
    }
}
