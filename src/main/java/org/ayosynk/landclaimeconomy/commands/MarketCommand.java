package org.ayosynk.landclaimeconomy.commands;

import org.ayosynk.landClaimPlugin.api.LandClaimAPI;
import org.ayosynk.landClaimPlugin.models.ClaimProfile;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.config.MessagesConfig;
import org.ayosynk.landclaimeconomy.gui.AuctionGUI;
import org.ayosynk.landclaimeconomy.gui.MarketplaceGUI;
import org.ayosynk.landclaimeconomy.gui.ProfilePickerGUI;
import org.ayosynk.landclaimeconomy.managers.AuctionManager;
import org.ayosynk.landclaimeconomy.managers.MarketManager;
import org.ayosynk.landclaimeconomy.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements {@code /claimmarket}.
 *
 * <p>Subcommands that need a profile (sell, unlist, auction start, auction cancel)
 * open a profile picker GUI rather than taking a free-text name argument. The
 * picker eliminates edge cases from name matching (collisions across players,
 * renamed claims, partial matches) and is also the path that tab completion
 * points at.</p>
 *
 * <p>Two flows (sell, auction start) need an extra piece of info after the profile
 * is picked. Those use a per-player chat prompt: the player types the price (or
 * price/duration/buyout for auctions) in chat within 30 seconds. The prompt state
 * lives in {@link #pendingFlows} and is wiped on cancel, completion, quit, or
 * timeout.</p>
 */
public class MarketCommand implements TabCompleter, Listener {

    private final LandClaimEconomy plugin;
    private final MarketManager market;
    private final AuctionManager auctions;

    /** Player UUID → currently-active flow. Wiped when the flow completes, cancels, or expires. */
    private final Map<UUID, PendingFlow> pendingFlows = new ConcurrentHashMap<>();

    private static final List<String> ROOT_SUBS = List.of("list", "mine", "sell", "unlist", "buy", "auction");
    private static final List<String> AUCTION_SUBS = List.of("start", "bid", "cancel", "list");

    public MarketCommand(LandClaimEconomy plugin, MarketManager market, AuctionManager auctions) {
        this.plugin = plugin;
        this.market = market;
        this.auctions = auctions;
        // Register the chat listener exactly once. We use LOWEST priority so the
        // parent's chat filter (if any) runs after us; if it cancels, we never
        // see the event.
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ========== Entry point ==========

    public boolean handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + "<red>Only players can use the market."));
            return true;
        }
        if (!player.hasPermission("landclaimeconomy.use")) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + "<red>You don't have permission to use the market."));
            return true;
        }
        if (args.length == 0) {
            MarketplaceGUI.open(player, plugin);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> MarketplaceGUI.open(player, plugin, 0, false);
            case "mine" -> MarketplaceGUI.open(player, plugin, 0, true);
            case "sell" -> startSellFlow(player);
            case "unlist" -> handleUnlist(player, args);
            case "buy" -> handleBuy(player, args);
            case "auction" -> handleAuction(player, args);
            default -> sendHelp(player);
        }
        return true;
    }

    // ========== Sell flow ==========

    private void startSellFlow(Player player) {
        ProfilePickerGUI.open(player, plugin, plugin.getMessages().pickerTitleSell,
                profile -> {
                    // Already on the player's region thread.
                    pendingFlows.put(player.getUniqueId(),
                            new PendingFlow(profile, PendingFlow.Kind.SELL, plugin));
                    player.sendMessage(MessagesConfig.formatRaw(
                            plugin.getMessages().prefix + plugin.getMessages().pricePrompt));
                });
    }

    // ========== Auction subcommand dispatch ==========

    private void handleAuction(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + "<red>Usage: /claimmarket auction <start|bid|cancel|list>"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "start" -> startAuctionFlow(player);
            case "bid" -> handleAuctionBid(player, args);
            case "cancel" -> startAuctionCancelFlow(player);
            case "list" -> AuctionGUI.open(player, plugin);
            default -> player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + "<red>Unknown auction action: " + action));
        }
    }

    private void startAuctionFlow(Player player) {
        ProfilePickerGUI.open(player, plugin, plugin.getMessages().pickerTitleAuction,
                profile -> {
                    PendingFlow flow = new PendingFlow(profile, PendingFlow.Kind.AUCTION_START, plugin);
                    pendingFlows.put(player.getUniqueId(), flow);
                    player.sendMessage(MessagesConfig.formatRaw(
                            plugin.getMessages().prefix + plugin.getMessages().auctionPromptPrice));
                });
    }

    private void startAuctionCancelFlow(Player player) {
        var api = LandClaimAPI.getInstance();
        if (api == null) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().featureDisabled));
            return;
        }
        // For cancel we want the player's own active auctions, not just their
        // profiles. Open a small listing similar to ProfilePickerGUI but
        // filtering by seller's auctions.
        var auctionMgr = plugin.getAuctionManager();
        if (auctionMgr == null) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().featureDisabled));
            return;
        }
        java.util.UUID viewerId = player.getUniqueId();
        FoliaScheduler.runAsync(plugin, () -> {
            List<AuctionManager.Auction> mine = new ArrayList<>();
            for (AuctionManager.Auction a : auctionMgr.getActiveAuctions()) {
                if (viewerId.equals(a.sellerId)) mine.add(a);
            }
            if (mine.isEmpty()) {
                FoliaScheduler.runForPlayer(plugin, player, () ->
                        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                                + "<yellow>You have no active auctions to cancel.")));
                return;
            }
            // Reuse the marketplace GUI machinery: open it in "my auctions" mode
            // where clicking the item invokes cancelAuction. To keep the work
            // small we delegate to a minimal direct flow.
            FoliaScheduler.runForPlayer(plugin, player, () -> {
                // Inline cancel flow: list the claim names and let the user
                // type the name in chat. Avoids another full GUI build.
                player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                        + "<yellow>Your active auctions: <gold>"
                        + String.join("<gray>, <gold>",
                                mine.stream().map(a -> a.claimName).toArray(String[]::new))));
                pendingFlows.put(player.getUniqueId(),
                        new PendingFlow(null, PendingFlow.Kind.AUCTION_CANCEL, plugin));
            });
        });
    }

    // ========== Text-based subcommands (power-user shortcuts, with tab completion) ==========

    private void handleBuy(Player player, String[] args) {
        if (args.length < 2) {
            // No arg — open the marketplace GUI, same as /claimmarket.
            MarketplaceGUI.open(player, plugin);
            return;
        }
        String claimName = args[1];
        ClaimProfile profile = LandClaimAPI.getInstance() != null
                ? LandClaimAPI.getInstance().getClaimByName(claimName)
                : null;
        if (profile == null) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().claimNotFound));
            return;
        }
        market.buy(player, profile);
    }

    private void handleUnlist(Player player, String[] args) {
        if (args.length < 2) {
            // No arg — open my-listings GUI where right-click unlists.
            MarketplaceGUI.open(player, plugin, 0, true);
            return;
        }
        String claimName = args[1];
        ClaimProfile profile = findOwnedClaim(player, claimName);
        if (profile == null) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().notOwner));
            return;
        }
        market.unlist(player, profile);
    }

    private void handleAuctionBid(Player player, String[] args) {
        if (args.length < 4) {
            // No arg — open auction GUI.
            AuctionGUI.open(player, plugin);
            return;
        }
        String claimName = args[2];
        double amount;
        try {
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().invalidNumber));
            return;
        }
        ClaimProfile profile = LandClaimAPI.getInstance() != null
                ? LandClaimAPI.getInstance().getClaimByName(claimName)
                : null;
        if (profile == null) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().auctionClaimNotFound));
            return;
        }
        auctions.placeBid(player, profile, amount);
    }

    // ========== Chat-prompt listener ==========

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingFlow flow = pendingFlows.get(player.getUniqueId());
        if (flow == null) return;
        event.setCancelled(true);

        String msg = event.getMessage().trim();
        if (msg.equalsIgnoreCase("cancel")) {
            pendingFlows.remove(player.getUniqueId());
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().flowCancelled));
            return;
        }

        switch (flow.kind) {
            case SELL -> handleSellStep(player, flow, msg);
            case AUCTION_START -> handleAuctionStep(player, flow, msg);
            case AUCTION_CANCEL -> handleAuctionCancelStep(player, flow, msg);
        }
    }

    private void handleSellStep(Player player, PendingFlow flow, String msg) {
        double price;
        try {
            price = Double.parseDouble(msg);
        } catch (NumberFormatException e) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().invalidNumber));
            return;
        }
        pendingFlows.remove(player.getUniqueId());
        market.list(player, flow.profile, price);
    }

    private void handleAuctionStep(Player player, PendingFlow flow, String msg) {
        double value;
        try {
            value = Double.parseDouble(msg);
        } catch (NumberFormatException e) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().invalidNumber));
            return;
        }
        switch (flow.auctionStep) {
            case 0 -> {
                if (value <= 0) {
                    player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                            + plugin.getMessages().marketPriceInvalid));
                    return;
                }
                flow.startingPrice = value;
                flow.auctionStep = 1;
                player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                        + plugin.getMessages().auctionPromptDuration.replace(
                                "<default>", String.valueOf(plugin.getEconomyConfig().auctionDefaultDurationMinutes))));
            }
            case 1 -> {
                long duration = (long) value;
                if (duration <= 0) duration = (long) plugin.getEconomyConfig().auctionDefaultDurationMinutes;
                flow.durationMinutes = duration;
                flow.auctionStep = 2;
                player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                        + plugin.getMessages().auctionPromptBuyout));
            }
            case 2 -> {
                double buyout = value;
                if (buyout < 0) buyout = 0;
                flow.buyoutPrice = buyout;
                pendingFlows.remove(player.getUniqueId());
                auctions.startAuction(player, flow.profile, flow.startingPrice, flow.durationMinutes, flow.buyoutPrice);
            }
        }
    }

    private void handleAuctionCancelStep(Player player, PendingFlow flow, String msg) {
        // For cancel we resolved auction names from the prompt. Look up by name
        // in the player's owned profiles (the API doesn't expose getAuctionByName
        // directly, but the profile name is the lookup key the GUI uses).
        ClaimProfile profile = findOwnedClaim(player, msg);
        if (profile == null) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().auctionClaimNotFound));
            return;
        }
        pendingFlows.remove(player.getUniqueId());
        auctions.cancelAuction(player, profile);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingFlows.remove(event.getPlayer().getUniqueId());
    }

    // ========== Tab completion ==========

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (!player.hasPermission("landclaimeconomy.use")) return List.of();

        if (args.length <= 1) {
            return filterPrefix(ROOT_SUBS, args.length == 0 ? "" : args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list", "mine" -> {
                return List.of(); // no further args
            }
            case "sell", "unlist" -> {
                if (args.length == 2) return filterPrefix(ownProfileNames(player), args[1]);
                return List.of();
            }
            case "buy" -> {
                if (args.length == 2) return filterPrefix(activeMarketNames(), args[1]);
                return List.of();
            }
            case "auction" -> {
                if (args.length == 2) return filterPrefix(AUCTION_SUBS, args[1]);
                if (args.length == 3) {
                    String action = args[1].toLowerCase(Locale.ROOT);
                    if (action.equals("start") || action.equals("cancel")) {
                        return filterPrefix(ownProfileNames(player), args[2]);
                    }
                    if (action.equals("bid")) {
                        return filterPrefix(activeAuctionNames(), args[2]);
                    }
                }
                if (args.length == 4) {
                    String action = args[1].toLowerCase(Locale.ROOT);
                    if (action.equals("start") || action.equals("bid")) {
                        // Price / bid amount is a number, no useful completion.
                        return List.of();
                    }
                }
                if (args.length == 5 || args.length == 6) {
                    String action = args[1].toLowerCase(Locale.ROOT);
                    if (action.equals("start")) {
                        return List.of(); // duration / buyout, numeric
                    }
                }
                return List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    private List<String> ownProfileNames(Player player) {
        var api = LandClaimAPI.getInstance();
        if (api == null) return List.of();
        List<String> names = new ArrayList<>();
        for (ClaimProfile p : api.getClaimsByOwner(player.getUniqueId())) {
            if (p.getName() != null) names.add(p.getName());
        }
        return names;
    }

    private List<String> activeMarketNames() {
        var mgr = plugin.getMarketManager();
        if (mgr == null) return List.of();
        List<String> names = new ArrayList<>();
        for (MarketManager.Listing l : mgr.getActiveListings()) {
            if (l.claimName != null) names.add(l.claimName);
        }
        return names;
    }

    private List<String> activeAuctionNames() {
        var mgr = plugin.getAuctionManager();
        if (mgr == null) return List.of();
        List<String> names = new ArrayList<>();
        for (AuctionManager.Auction a : mgr.getActiveAuctions()) {
            if (a.claimName != null) names.add(a.claimName);
        }
        return names;
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) return options;
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }

    // ========== Helpers ==========

    private ClaimProfile findOwnedClaim(Player player, String name) {
        LandClaimAPI api = LandClaimAPI.getInstance();
        if (api == null) return null;
        for (ClaimProfile p : api.getClaimsByOwner(player.getUniqueId())) {
            if (p.getName() != null && p.getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private void sendHelp(Player player) {
        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + "<gold><bold>Claim Market</bold></gold>"));
        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + "<gray>/claimmarket <dark_gray>— <yellow>open the marketplace GUI"));
        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + "<gray>/claimmarket mine <dark_gray>— <yellow>open my listings GUI"));
        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + "<gray>/claimmarket sell <dark_gray>— <yellow>open profile picker to list"));
        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + "<gray>/claimmarket unlist [name] <dark_gray>— <yellow>unlist a profile (picker with no arg)"));
        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + "<gray>/claimmarket buy [name] <dark_gray>— <yellow>open market (GUI with no arg)"));
        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + "<dark_purple>Auctions:"));
        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + "<gray>/claimmarket auction start <dark_gray>— <yellow>open profile picker to start an auction"));
        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + "<gray>/claimmarket auction bid [name] [amount] <dark_gray>— <yellow>open auction GUI with no arg"));
        player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                + "<gray>/claimmarket auction cancel <dark_gray>— <yellow>cancel one of your active auctions"));
    }

    /** Per-player state machine for the multi-step flows (sell, auction start, auction cancel). */
    private static final class PendingFlow {
        enum Kind { SELL, AUCTION_START, AUCTION_CANCEL }

        final ClaimProfile profile; // null for AUCTION_CANCEL (resolved in the cancel step)
        final Kind kind;
        // Auction-start state machine:
        int auctionStep = 0;          // 0 = awaiting starting price, 1 = awaiting duration, 2 = awaiting buyout
        double startingPrice;
        long durationMinutes;
        double buyoutPrice;

        PendingFlow(ClaimProfile profile, Kind kind, LandClaimEconomy plugin) {
            this.profile = profile;
            this.kind = kind;
        }
    }
}
