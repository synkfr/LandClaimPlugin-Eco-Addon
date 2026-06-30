package org.ayosynk.landclaimeconomy.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.*;

/**
 * Master config for the LandClaimPlugin-Economy addon.
 *
 * <p>Every feature has its own section with an {@code enabled} toggle. The
 * master {@code economy.enabled} flag is a global kill-switch — set to
 * {@code false} to disable every feature at once without touching the
 * individual sections.</p>
 */
@Header("===========================================================")
@Header("        LandClaimPlugin-Economy - Master Configuration       ")
@Header("===========================================================")
@Header("Every feature below has its own 'enabled' toggle. The master")
@Header("'economy.enabled' flag is a global kill-switch.")
public class EconomyConfig extends OkaeriConfig {

    @Comment("Master switch. When false, the addon does nothing — no charges, no tax, no market.")
    public boolean enabled = true;

    // ========== Claim cost ==========

    @Comment({
        "Charge the player when they claim a chunk.",
        "Disabled by default; flip 'enabled' to true to start charging."
    })
    public FeatureConfig claimCost = new FeatureConfig();

    @Comment("Cost in Vault currency for the FIRST chunk in a claim (subsequent chunks in the same claim are free).")
    public double firstChunkCost = 100.0;

    @Comment("Cost in Vault currency per additional chunk when expanding an existing claim.")
    public double perChunkCost = 25.0;

    @Comment("If true, the player's first chunk ever claimed is free.")
    public boolean firstClaimFree = true;

    @Comment("Maximum amount a single player can be charged per real day. 0 = no cap.")
    public double dailyChargeCap = 5000.0;

    // ========== Warp cost ==========

    @Comment("Charge the player when they set a warp.")
    public FeatureConfig warpCost = new FeatureConfig();

    @Comment("Base cost for setting a private warp.")
    public double warpCostAmount = 50.0;

    @Comment("Multiplier applied to the cost when the warp is set as public (e.g. 2.0 = double cost).")
    public double publicWarpMultiplier = 2.0;

    // ========== Invite cost ==========

    @Comment("Charge the player when they invite a member or trusted player.")
    public FeatureConfig inviteCost = new FeatureConfig();

    @Comment("Cost per member invite.")
    public double memberInviteCost = 25.0;

    @Comment("Cost per trusted player invite.")
    public double trustedInviteCost = 10.0;

    // ========== Buy limits costs ==========
    @Comment("Cost to buy an additional chunk/claim block.")
    public double claimBlockCost = 50.0;

    @Comment("Cost to buy an additional custom role slot.")
    public double roleSlotCost = 150.0;

    @Comment("Cost to buy an additional claim member slot.")
    public double memberSlotCost = 50.0;

    @Comment("Cost to buy an additional claim warp slot.")
    public double warpSlotCost = 75.0;

    // ========== Daily tax ==========

    @Comment("Daily per-chunk upkeep tax. Unpaid chunks are auto-unclaimed after the grace period.")
    public FeatureConfig tax = new FeatureConfig();

    @Comment("Tax per chunk per tax day.")
    public double taxPerChunkPerDay = 5.0;

    @Comment("How many tax days a claim can go unpaid before chunks are auto-unclaimed. 0 = no grace (unpaid chunks unclaim immediately).")
    public int taxGracePeriodDays = 7;

    @Comment("How often (in minutes) the tax scheduler runs to deduct and check for unpaid claims.")
    public long taxIntervalMinutes = 60;

    // ========== Market ==========

    @Comment("Server-wide claim marketplace — /claimmarket sell and /claimmarket buy.")
    public FeatureConfig market = new FeatureConfig();

    @Comment("Fee charged to the seller when listing a claim on the market. 0 = free listing.")
    public double marketListingFee = 100.0;

    @Comment("Maximum listing price. 0 = no cap.")
    public double marketMaxPrice = 1000000.0;

    @Comment("Percentage of the sale price that the seller receives. 1.0 = 100%, 0.9 = 90% (10% tax to server).")
    public double marketSellerPayoutRatio = 1.0;

    // ========== Auction ==========

    @Comment({
        "Time-limited public auctions for claims — players bid against each",
        "other until the timer runs out. The highest bidder wins and is",
        "transferred the claim via the parent's transferClaim() API."
    })
    public FeatureConfig auction = new FeatureConfig();

    @Comment("Default auction duration in minutes if the seller doesn't specify one.")
    public double auctionDefaultDurationMinutes = 60.0;

    @Comment("Minimum bid increment. A new bid must exceed the current bid by at least this amount.")
    public double auctionMinBidIncrement = 1.0;

    @Comment("Listing fee charged to the seller when starting an auction. 0 = free.")
    public double auctionListingFee = 100.0;

    @Comment("How often (in seconds) the auction scheduler runs to check for ended auctions.")
    public long auctionCheckIntervalSeconds = 30;

    @Comment("Maximum simultaneous auctions a single player can have active. 0 = unlimited.")
    public int auctionMaxPerPlayer = 3;

    @Comment({
        "Sniping protection: if a bid lands in the last N seconds of an auction,",
        "the auction timer is extended by the same N seconds. This prevents",
        "the 'snipe at the last second' exploit where a buyer waits until the",
        "very end and steals the claim before anyone can counter-bid.",
        "Set to 0 to disable sniping protection."
    })
    public long auctionSnipeWindowSeconds = 60;

    @Comment("Minimum buyout price as a multiplier of the starting price. e.g. 2.0 means buyout must be at least 2x the starting price.")
    public double auctionMinBuyoutMultiplier = 2.0;

    // ========== FeatureConfig inner class ==========

    public static class FeatureConfig extends OkaeriConfig {
        @Comment("Master toggle for this feature. Disabling it turns the feature off without removing its config keys.")
        public boolean enabled = false;
    }
}
