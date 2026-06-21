package org.ayosynk.landclaimeconomy;

import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.yaml.bukkit.YamlBukkitConfigurer;
import eu.okaeri.configs.yaml.bukkit.serdes.SerdesBukkit;
import org.ayosynk.landclaimeconomy.commands.MarketCommand;
import org.ayosynk.landclaimeconomy.config.EconomyConfig;
import org.ayosynk.landclaimeconomy.config.MessagesConfig;
import org.ayosynk.landclaimeconomy.db.EconomyDatabase;
import org.ayosynk.landclaimeconomy.managers.AuctionManager;
import org.ayosynk.landclaimeconomy.managers.ClaimCostManager;
import org.ayosynk.landclaimeconomy.managers.InviteCostManager;
import org.ayosynk.landclaimeconomy.managers.MarketManager;
import org.ayosynk.landclaimeconomy.managers.TaxManager;
import org.ayosynk.landclaimeconomy.managers.WarpCostManager;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.ayosynk.landclaimeconomy.util.PlayerNameCache;
import org.ayosynk.landClaimPlugin.LandClaimPlugin;
import org.ayosynk.landClaimPlugin.api.LandClaimAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class LandClaimEconomy extends JavaPlugin {

    private EconomyConfig economyConfig;
    private MessagesConfig messagesConfig;
    private EconomyDatabase database;
    private PlayerNameCache nameCache;

    private ClaimCostManager claimCostManager;
    private WarpCostManager warpCostManager;
    private InviteCostManager inviteCostManager;
    private TaxManager taxManager;
    private MarketManager marketManager;
    private AuctionManager auctionManager;
    private MarketCommand marketCommand;

    @Override
    public void onEnable() {
        // Soft-depend: if either parent is missing, disable cleanly.
        if (Bukkit.getPluginManager().getPlugin("LandClaimPlugin") == null) {
            getLogger().warning("LandClaimPlugin is not installed — disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (!EconomyHook.setup()) {
            getLogger().warning("Vault (or an economy provider) is not installed — disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Load configs.
        this.economyConfig = ConfigManager.create(EconomyConfig.class, (it) -> {
            it.withConfigurer(new YamlBukkitConfigurer(), new SerdesBukkit());
            it.withBindFile(new File(getDataFolder(), "config.yml"));
            it.saveDefaults();
            it.load(true);
        });
        this.messagesConfig = ConfigManager.create(MessagesConfig.class, (it) -> {
            it.withConfigurer(new YamlBukkitConfigurer(), new SerdesBukkit());
            it.withBindFile(new File(getDataFolder(), "messages.yml"));
            it.saveDefaults();
            it.load(true);
        });

        // Wire the database.
        var parent = LandClaimPlugin.getInstance();
        if (parent == null) {
            getLogger().warning("LandClaimPlugin instance is null — disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.database = new EconomyDatabase(this, parent.getDatabaseManager());
        this.database.createTables();

        // Name cache. Registers a listener for AsyncPlayerPreLoginEvent so we never
        // have to do blocking usercache.json I/O from a region/global-region thread.
        this.nameCache = new PlayerNameCache(this);
        this.nameCache.register();

        // Managers.
        this.claimCostManager = new ClaimCostManager(this);
        this.warpCostManager = new WarpCostManager(this);
        this.inviteCostManager = new InviteCostManager(this);
        this.taxManager = new TaxManager(this);
        this.marketManager = new MarketManager(this);
        this.auctionManager = new AuctionManager(this);
        this.marketCommand = new MarketCommand(this, marketManager, auctionManager);

        // Listeners.
        var server = Bukkit.getPluginManager();
        server.registerEvents(claimCostManager, this);
        server.registerEvents(warpCostManager, this);
        server.registerEvents(inviteCostManager, this);

        // Tab completer. We register it against the primary alias only —
        // Bukkit propagates it to the aliases automatically. The command
        // dispatcher in onCommand() below handles all three aliases.
        var claimMarketCmd = getCommand("claimmarket");
        if (claimMarketCmd != null) {
            claimMarketCmd.setTabCompleter(marketCommand);
        }

        // Tax scheduler.
        if (economyConfig.enabled && economyConfig.tax.enabled) {
            taxManager.start();
        }
        // Auction scheduler.
        if (economyConfig.enabled && economyConfig.auction.enabled) {
            auctionManager.start();
        }

        getLogger().info("LandClaimPlugin-Economy v" + getDescription().getVersion()
                + " enabled. Features: claim=" + economyConfig.claimCost.enabled
                + ", warp=" + economyConfig.warpCost.enabled
                + ", invite=" + economyConfig.inviteCost.enabled
                + ", tax=" + economyConfig.tax.enabled
                + ", market=" + economyConfig.market.enabled
                + ", auction=" + economyConfig.auction.enabled);
    }

    @Override
    public void onDisable() {
        if (taxManager != null) taxManager.stop();
        if (auctionManager != null) auctionManager.stop();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                              String label, String[] args) {
        if (command.getName().equalsIgnoreCase("claimmarket")
                || command.getName().equalsIgnoreCase("cmarket")
                || command.getName().equalsIgnoreCase("claimbazaar")) {
            return marketCommand.handle(sender, args);
        }
        return false;
    }

    public EconomyConfig getEconomyConfig() {
        return economyConfig;
    }

    public MessagesConfig getMessages() {
        return messagesConfig;
    }

    public EconomyDatabase getDatabase() {
        return database;
    }

    public LandClaimAPI getParentAPI() {
        return LandClaimAPI.getInstance();
    }

    public PlayerNameCache getNameCache() {
        return nameCache;
    }

    public MarketManager getMarketManager() {
        return marketManager;
    }

    public AuctionManager getAuctionManager() {
        return auctionManager;
    }
}
