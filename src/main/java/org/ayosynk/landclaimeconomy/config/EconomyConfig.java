package org.ayosynk.landclaimeconomy.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.Header;

@Header("===========================================================")
@Header("        LandClaimPlugin-Economy - Master Configuration       ")
@Header("===========================================================")
@Header("Every feature below has its own 'enabled' toggle.")
@Header("The master 'enabled' flag is a global kill-switch.")
public class EconomyConfig extends OkaeriConfig {

    public enum ClaimCostMode {
        FIXED,
        DOUBLED,
        PERCENTAGE
    }

    @Comment("Master switch. When false, the addon does nothing.")
    public boolean enabled = true;

    // ========== Claim Cost ==========

    @Comment({
        "Charge players when claiming chunks.",
        "Set 'enabled: true' to activate chunk claim costs."
    })
    public FeatureConfig claimCost = new FeatureConfig();

    @Comment("Pricing mode: FIXED (flat rate), DOUBLED (doubles each claim), or PERCENTAGE (increases by % each claim).")
    public ClaimCostMode claimCostMode = ClaimCostMode.FIXED;

    @Comment("Base price per chunk used for DOUBLED and PERCENTAGE modes.")
    public double baseCost = 25.0;

    @Comment("Price for the first chunk of a new claim (FIXED mode).")
    public double firstChunkCost = 100.0;

    @Comment("Price per additional chunk when expanding a claim (FIXED mode).")
    public double perChunkCost = 25.0;

    @Comment("Percentage increase per owned chunk (PERCENTAGE mode, e.g. 10.0 = 10% increase per chunk).")
    public double percentageIncrease = 10.0;

    @Comment("Maximum price for a single chunk in DOUBLED or PERCENTAGE mode (0 = no limit).")
    public double maxChunkCost = 10000.0;

    @Comment("Whether a player's first-ever claimed chunk is free.")
    public boolean firstClaimFree = true;

    @Comment("Maximum amount a player can be charged for claiming per 24 hours (0 = no daily cap).")
    public double dailyChargeCap = 5000.0;

    // ========== Warp Cost ==========

    @Comment("Charge players when setting claim warps.")
    public FeatureConfig warpCost = new FeatureConfig();

    @Comment("Base cost to set a private claim warp.")
    public double warpCostAmount = 50.0;

    @Comment("Multiplier applied when a warp is set as public (e.g. 2.0 = double price).")
    public double publicWarpMultiplier = 2.0;

    // ========== Invite Cost ==========

    @Comment("Charge players when inviting members or trusted players.")
    public FeatureConfig inviteCost = new FeatureConfig();

    @Comment("Cost per member invite.")
    public double memberInviteCost = 25.0;

    @Comment("Cost per trusted player invite.")
    public double trustedInviteCost = 10.0;

    // ========== Limit Purchases ==========

    @Comment("Allow players to buy extra claim blocks, role slots, member slots, and warp slots.")
    public FeatureConfig limitPurchases = new FeatureConfig();

    @Comment("Cost to buy an additional chunk / claim block.")
    public double claimBlockCost = 50.0;

    @Comment("Cost to buy an additional custom role slot.")
    public double roleSlotCost = 150.0;

    @Comment("Cost to buy an additional claim member slot.")
    public double memberSlotCost = 50.0;

    @Comment("Cost to buy an additional claim warp slot.")
    public double warpSlotCost = 75.0;

    // ========== Daily Tax ==========

    @Comment("Daily per-chunk upkeep tax. Unpaid claims are auto-unclaimed after the grace period.")
    public FeatureConfig tax = new FeatureConfig();

    @Comment("Tax per chunk per tax day.")
    public double taxPerChunkPerDay = 5.0;

    @Comment("Days a claim can remain unpaid before auto-unclaiming (0 = immediate unclaim on missed tax).")
    public int taxGracePeriodDays = 7;

    @Comment("How often (in minutes) the tax check runs.")
    public long taxIntervalMinutes = 60;

    // ========== Marketplace ==========

    @Comment("Server-wide claim marketplace (/claimmarket sell, /claimmarket buy).")
    public FeatureConfig market = new FeatureConfig();

    @Comment("Listing fee charged to the seller when listing a claim (0 = free listing).")
    public double marketListingFee = 100.0;

    @Comment("Maximum allowed listing price (0 = no maximum).")
    public double marketMaxPrice = 1000000.0;

    @Comment("Ratio of the sale price received by the seller (1.0 = 100%, 0.9 = 90% with 10% server cut).")
    public double marketSellerPayoutRatio = 1.0;

    // ========== Auctions ==========

    @Comment("Timed public claim auctions with bidding (/claimmarket auction).")
    public FeatureConfig auction = new FeatureConfig();

    @Comment("Default auction duration in minutes if not specified.")
    public double auctionDefaultDurationMinutes = 60.0;

    @Comment("Minimum bid increment required over the current bid.")
    public double auctionMinBidIncrement = 1.0;

    @Comment("Listing fee charged to start an auction (0 = free).")
    public double auctionListingFee = 100.0;

    @Comment("How often (in seconds) the scheduler checks for ended auctions.")
    public long auctionCheckIntervalSeconds = 30;

    @Comment("Maximum active auctions allowed per player (0 = unlimited).")
    public int auctionMaxPerPlayer = 3;

    @Comment("Sniping protection: seconds added to auction timer if a bid occurs near the end (0 = disabled).")
    public long auctionSnipeWindowSeconds = 60;

    @Comment("Minimum buyout price multiplier relative to starting price (e.g. 2.0 = buyout must be >= 2x starting price).")
    public double auctionMinBuyoutMultiplier = 2.0;

    // ========== Feature Toggle Class ==========

    public static class FeatureConfig extends OkaeriConfig {
        @Comment("Toggle to enable or disable this feature individually.")
        public boolean enabled = false;
    }
}
