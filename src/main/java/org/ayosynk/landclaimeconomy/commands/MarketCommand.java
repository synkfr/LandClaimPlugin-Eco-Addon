package org.ayosynk.landclaimeconomy.commands;

import org.ayosynk.landClaimPlugin.api.LandClaimAPI;
import org.ayosynk.landClaimPlugin.models.ClaimProfile;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.gui.AuctionGUI;
import org.ayosynk.landclaimeconomy.gui.MarketplaceGUI;
import org.ayosynk.landclaimeconomy.managers.AuctionManager;
import org.ayosynk.landclaimeconomy.managers.MarketManager;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Implements {@code /claimmarket sell <name> <price>},
 * {@code /claimmarket buy <name>}, {@code /claimmarket unlist <name>},
 * and {@code /claimmarket list}.
 */
public class MarketCommand {

    private final LandClaimEconomy plugin;
    private final MarketManager market;
    private final AuctionManager auctions;

    public MarketCommand(LandClaimEconomy plugin, MarketManager market, AuctionManager auctions) {
        this.plugin = plugin;
        this.market = market;
        this.auctions = auctions;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().prefix + "<red>Only players can use the market.");
            return true;
        }
        if (!player.hasPermission("landclaimeconomy.use")) {
            player.sendMessage(plugin.getMessages().prefix + "<red>You don't have permission to use the market.");
            return true;
        }
        if (args.length == 0) {
            // No args — open the marketplace GUI.
            MarketplaceGUI.open(player, plugin);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "list" -> MarketplaceGUI.open(player, plugin, 0, false);
            case "mine" -> MarketplaceGUI.open(player, plugin, 0, true);
            case "sell" -> handleSell(player, args);
            case "buy" -> handleBuy(player, args);
            case "unlist" -> handleUnlist(player, args);
            case "auction" -> handleAuction(player, args);
            default -> sendHelp(player);
        }
        return true;
    }

    private void handleAuction(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().prefix
                    + "<red>Usage: /claimmarket auction <start|bid|cancel|list> ...");
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "start" -> handleAuctionStart(player, args);
            case "bid" -> handleAuctionBid(player, args);
            case "cancel" -> handleAuctionCancel(player, args);
            case "list" -> AuctionGUI.open(player, plugin);
            default -> player.sendMessage(plugin.getMessages().prefix
                    + "<red>Unknown auction action: " + action);
        }
    }

    private void handleAuctionStart(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage(plugin.getMessages().prefix
                    + "<red>Usage: /claimmarket auction start <claim> <starting-price> [duration-minutes] [buyout-price]");
            return;
        }
        String claimName = args[2];
        double starting;
        try {
            starting = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessages().prefix + "<red>Starting price must be a number.");
            return;
        }
        long duration = args.length >= 5 ? Long.parseLong(args[4]) : -1;
        double buyout = args.length >= 6 ? Double.parseDouble(args[5]) : 0;
        ClaimProfile profile = findOwnedClaim(player, claimName);
        if (profile == null) {
            player.sendMessage(plugin.getMessages().prefix + plugin.getMessages().auctionNotOwner);
            return;
        }
        auctions.startAuction(player, profile, starting, duration, buyout);
    }

    private void handleAuctionBid(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage(plugin.getMessages().prefix
                    + "<red>Usage: /claimmarket auction bid <claim> <amount>");
            return;
        }
        String claimName = args[2];
        double amount;
        try {
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessages().prefix + "<red>Bid amount must be a number.");
            return;
        }
        ClaimProfile profile = LandClaimAPI.getInstance() != null
                ? LandClaimAPI.getInstance().getClaimByName(claimName)
                : null;
        if (profile == null) {
            player.sendMessage(plugin.getMessages().prefix + plugin.getMessages().auctionClaimNotFound);
            return;
        }
        auctions.placeBid(player, profile, amount);
    }

    private void handleAuctionCancel(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getMessages().prefix
                    + "<red>Usage: /claimmarket auction cancel <claim>");
            return;
        }
        String claimName = args[2];
        ClaimProfile profile = findOwnedClaim(player, claimName);
        if (profile == null) {
            player.sendMessage(plugin.getMessages().prefix + plugin.getMessages().auctionNotOwner);
            return;
        }
        auctions.cancelAuction(player, profile);
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getMessages().prefix
                + "<gold><bold>Claim Market</bold></gold>");
        player.sendMessage(plugin.getMessages().prefix
                + "<gray>/claimmarket <dark_gray>— <yellow>open the marketplace GUI");
        player.sendMessage(plugin.getMessages().prefix
                + "<gray>/claimmarket mine <dark_gray>— <yellow>open my listings GUI");
        player.sendMessage(plugin.getMessages().prefix
                + "<gray>/claimmarket sell <name> <price> <dark_gray>— <yellow>list your claim");
        player.sendMessage(plugin.getMessages().prefix
                + "<gray>/claimmarket unlist <name> <dark_gray>— <yellow>remove a listing");
        player.sendMessage(plugin.getMessages().prefix
                + "<gray>/claimmarket buy <name> <dark_gray>— <yellow>buy a listed claim");
        player.sendMessage(plugin.getMessages().prefix
                + "<dark_purple>Auctions:");
        player.sendMessage(plugin.getMessages().prefix
                + "<gray>/claimmarket auction start <name> <price> [min] [buyout]");
        player.sendMessage(plugin.getMessages().prefix
                + "<gray>/claimmarket auction bid <name> <amount>");
        player.sendMessage(plugin.getMessages().prefix
                + "<gray>/claimmarket auction cancel <name>");
        player.sendMessage(plugin.getMessages().prefix
                + "<gray>/claimmarket auction list <dark_gray>— <yellow>open the auction GUI");
    }

    private void handleList(Player player) {
        List<MarketManager.Listing> listings = market.getActiveListings();
        if (listings.isEmpty()) {
            player.sendMessage(plugin.getMessages().prefix + plugin.getMessages().marketEmpty);
            return;
        }
        player.sendMessage(plugin.getMessages().prefix + plugin.getMessages().marketHeader);
        for (MarketManager.Listing l : listings) {
            player.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().marketEntry
                            .replace("<claim>", l.claimName)
                            .replace("<owner>", l.ownerName)
                            .replace("<price>", EconomyHook.format(l.price)));
        }
    }

    private void handleSell(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getMessages().prefix
                    + "<red>Usage: /claimmarket sell <claim-name> <price>");
            return;
        }
        String claimName = args[1];
        double price;
        try {
            price = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessages().prefix + "<red>Price must be a number.");
            return;
        }
        ClaimProfile profile = findOwnedClaim(player, claimName);
        if (profile == null) {
            player.sendMessage(plugin.getMessages().prefix
                    + plugin.getMessages().notOwner.replace("name", claimName));
            return;
        }
        market.list(player, profile, price);
    }

    private void handleBuy(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().prefix
                    + "<red>Usage: /claimmarket buy <claim-name>");
            return;
        }
        String claimName = args[1];
        ClaimProfile profile = LandClaimAPI.getInstance() != null
                ? LandClaimAPI.getInstance().getClaimByName(claimName)
                : null;
        if (profile == null) {
            player.sendMessage(plugin.getMessages().prefix + plugin.getMessages().claimNotFound);
            return;
        }
        market.buy(player, profile);
    }

    private void handleUnlist(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().prefix
                    + "<red>Usage: /claimmarket unlist <claim-name>");
            return;
        }
        String claimName = args[1];
        ClaimProfile profile = findOwnedClaim(player, claimName);
        if (profile == null) {
            player.sendMessage(plugin.getMessages().prefix + plugin.getMessages().notOwner);
            return;
        }
        market.unlist(player, profile);
    }

    private ClaimProfile findOwnedClaim(Player player, String name) {
        LandClaimAPI api = LandClaimAPI.getInstance();
        if (api == null) return null;
        for (ClaimProfile p : api.getClaimsByOwner(player.getUniqueId())) {
            if (p.getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }
}
