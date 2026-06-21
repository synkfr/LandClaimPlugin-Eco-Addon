package org.ayosynk.landclaimeconomy.config;

import eu.okaeri.configs.OkaeriConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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
    public String marketListed = "<green>Listed <gold><claim></gold> for sale at <gold><price></gold>. Buyers can browse with <gold>/claimmarket</gold>.";
    public String marketUnlisted = "<gray>Removed the listing for <gold><claim></gold>.";
    public String marketSold = "<green>Sold <gold><claim></gold> to <gold><buyer></gold> for <gold><price></gold>!";
    public String marketBought = "<green>You now own <gold><claim></gold>. <gold><price></gold> was transferred to <gold><seller></gold>.";
    public String marketNotListed = "<red><gold><claim></gold> is not listed for sale.";
    public String marketAlreadyListed = "<red><gold><claim></gold> is already listed. Unlist it first.";
    public String marketPriceInvalid = "<red>Price must be a positive number.";
    public String marketHeader = "<gold><bold>Claim Marketplace</bold></gold>";
    public String marketEmpty = "<gray>No claims are currently listed. Use <gold>/claimmarket sell</gold> to list one of yours.";
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

    // Profile picker / flow
    public String pickerTitleSell = "<gold><bold>Pick a profile to list</bold></gold>";
    public String pickerTitleAuction = "<gold><bold>Pick a profile to auction</bold></gold>";
    public String pickerTitleCancel = "<red><bold>Pick an auction to cancel</bold></red>";
    public String pickerEmpty = "<gray>You have no claim profiles. Claim some chunks first.";
    public String pickerProfileEntry = "<gray><chunks></gray> chunk(s)";
    public String pricePrompt = "<yellow>Type the listing price in chat (or type <gold>cancel</gold> to abort).";
    public String auctionPromptPrice = "<yellow>Type the starting price for the auction (or <gold>cancel</gold>).";
    public String auctionPromptDuration = "<yellow>Type the duration in minutes (or <gold>cancel</gold>). Default: <gold><default></gold>.";
    public String auctionPromptBuyout = "<yellow>Type the buyout price, or <gold>0</gold> for none (or <gold>cancel</gold>).";
    public String flowCancelled = "<gray>Action cancelled.";
    public String invalidNumber = "<red>That's not a valid number.";

    // Misc
    public String reload = "<green>LandClaimPlugin-Economy configuration reloaded.";
    public String notOwner = "<red>You don't own a claim with that name.";
    public String claimNotFound = "<red>No claim found by that name.";

    // ========== Formatting helpers ==========

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /**
     * Format a MiniMessage template into a legacy-section-encoded string suitable for
     * {@code Player.sendMessage(String)}. Prepends the configured prefix and substitutes
     * the supplied placeholder/value pairs (alternating keys and values).
     *
     * <p>Why this exists: {@code Player.sendMessage(String)} interprets the argument as
     * legacy {@code §}-colored text, not as MiniMessage tags. Without deserializing first,
     * the {@code <red>} / {@code <gold>} tags in our messages appear as literal text in
     * chat. This helper does the deserialize + legacy-serialize pipeline so the call site
     * can stay a one-liner.</p>
     *
     * @param template MiniMessage template (may contain placeholders like {@code <claim>})
     * @param placeholderValuePairs alternating placeholder keys and replacement values
     * @return legacy-encoded string ready for {@code sendMessage(String)}
     */
    public String format(String template, String... placeholderValuePairs) {
        for (int i = 0; i + 1 < placeholderValuePairs.length; i += 2) {
            template = template.replace(placeholderValuePairs[i], placeholderValuePairs[i + 1]);
        }
        return formatRaw(prefix + template);
    }

    /**
     * Like {@link #format(String, String...)} but skips the prefix — useful for messages
     * that already include their own prefix (admin broadcasts, etc.) or for raw chat
     * rendering of strings that should not be prefixed.
     */
    public String formatUnprefixed(String template, String... placeholderValuePairs) {
        for (int i = 0; i + 1 < placeholderValuePairs.length; i += 2) {
            template = template.replace(placeholderValuePairs[i], placeholderValuePairs[i + 1]);
        }
        return formatRaw(template);
    }

    /** Deserialize a MiniMessage string and re-serialize as legacy {@code §} text. */
    public static String formatRaw(String miniMessage) {
        Component comp = MM.deserialize(miniMessage);
        return LEGACY.serialize(comp);
    }
}
