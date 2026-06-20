package org.ayosynk.landclaimeconomy.config;

import eu.okaeri.configs.OkaeriConfig;

public class MessagesConfig extends OkaeriConfig {

    public String prefix = "<dark_gray>[<gold>Economy<dark_gray>]</gold> ";

    public String insufficientFunds = "<red>You need <gold><cost></gold> to do this, but you only have <gold><balance></gold>.";
    public String featureDisabled = "<gray>This economy feature is disabled on this server.";

    // Claim cost
    public String claimCharged = "<green>Charged <gold><cost></gold> for claiming a chunk. New balance: <gold><balance></gold>.";
    public String claimFirstFree = "<green>Your first chunk is on the house!";
    public String claimDailyCapReached = "<yellow>You've hit today's claim-charge cap of <gold><cap></gold>. No charge applied.";

    // Warp cost
    public String warpCharged = "<green>Charged <gold><cost></gold> for setting warp <gold><name></gold>. New balance: <gold><balance></gold>.";
    public String warpDeletedRefund = "<green>Refunded <gold><amount></gold> for deleted warp <gold><name></gold>.";

    // Invite cost
    public String memberInviteCharged = "<green>Charged <gold><cost></gold> for inviting <gold><player></gold> as a member.";
    public String trustedInviteCharged = "<green>Charged <gold><cost></gold> for inviting <gold><player></gold> as trusted.";

    // Tax
    public String taxPaid = "<green>Paid <gold><amount></gold> in tax for <gold><chunks></gold> chunk(s) of <gold><claim></gold>.";
    public String taxInsufficient = "<red>Your tax balance is short by <gold><amount></gold>. Pay before <gold><deadline></gold> or your chunks will be auto-unclaimed.";
    public String taxAutoUnclaimed = "<red><gold><count></gold> chunk(s) of <gold><claim></gold> were auto-unclaimed because the tax went unpaid for <gold><days></gold> day(s).";

    // Market
    public String marketListed = "<green>Listed <gold><claim></gold> for sale at <gold><price></gold>. Buyers can run <gold>/claimmarket buy <claim></gold>.";
    public String marketUnlisted = "<gray>Removed the listing for <gold><claim></gold>.";
    public String marketSold = "<green>Sold <gold><claim></gold> to <gold><buyer></gold> for <gold><price></gold>!";
    public String marketBought = "<green>You now own <gold><claim></gold>. <gold><price></gold> was transferred to <gold><seller></gold>.";
    public String marketNotListed = "<red><gold><claim></gold> is not listed for sale.";
    public String marketAlreadyListed = "<red><gold><claim></gold> is already listed. Unlist it first.";
    public String marketPriceInvalid = "<red>Price must be a positive number.";
    public String marketHeader = "<gold><bold>Claim Marketplace</bold></gold>";
    public String marketEmpty = "<gray>No claims are currently listed. Run <gold>/claimmarket sell <price></gold> to list one of yours.";
    public String marketEntry = "<gray>- <gold><claim></gold> by <yellow><owner></yellow> — <green><price></green>";
    public String marketRefund = "<red>The claim transfer failed. Your <gold><amount></gold> has been refunded. An admin has been notified.";

    // Auctions
    public String auctionStarted = "<green>Auction for <gold><claim></gold> started! Starting price <gold><price></gold>, ends in <gold><duration></gold> minutes.";
    public String auctionBidPlaced = "<green>Bid placed: <gold><amount></gold> on <gold><claim></gold>. You are the current high bidder.";
    public String auctionBidOutbid = "<yellow>You have been outbid on <gold><claim></gold>! Current bid: <gold><amount></gold>.";
    public String auctionWonBuyer = "<green>You won the auction for <gold><claim></gold> at <gold><amount></gold>!";
    public String auctionWonSeller = "<green>Your auction for <gold><claim></gold> sold for <gold><amount></gold>.";
    public String auctionEndedNoBids = "<gray>Auction for <gold><claim></gold> ended with no bids. The claim is yours again.";
    public String auctionBidTooLow = "<red>Your bid must be at least <gold><min></gold> (current bid is <gold><current></gold>).";
    public String auctionBuyoutUsed = "<green>You bought <gold><claim></gold> for the buyout price of <gold><amount></gold>!";
    public String auctionNotActive = "<red>No active auction for that claim.";
    public String auctionClaimNotFound = "<red>Claim not found.";
    public String auctionNotOwner = "<red>You don't own a claim with that name.";
    public String auctionMaxReached = "<red>You already have <gold><max></gold> active auctions. Cancel one first.";
    public String auctionAlreadyOnAuction = "<red><gold><claim></gold> is already on auction.";
    public String auctionCancelled = "<gray>Auction for <gold><claim></gold> cancelled.";
    public String auctionBidOwnClaim = "<red>You can't bid on your own auction.";
    public String auctionHeader = "<dark_purple><bold>Active Auctions</bold></dark_purple>";
    public String auctionEntry = "<gray>- <gold><claim></gold> by <yellow><owner></yellow> — <green><current></green> (<yellow><ends></yellow>)";

    // Misc
    public String reload = "<green>LandClaimPlugin-Economy configuration reloaded.";
    public String notOwner = "<red>You don't own a claim with that name.";
    public String claimNotFound = "<red>No claim found by that name.";
}
