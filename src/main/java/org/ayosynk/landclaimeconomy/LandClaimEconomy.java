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
    private org.ayosynk.landclaimeconomy.commands.TaxCommand taxCommand;

    @Override
    public void onEnable() {
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

        var parent = LandClaimPlugin.getInstance();
        if (parent == null) {
            getLogger().warning("LandClaimPlugin instance is null — disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.database = new EconomyDatabase(this, parent.getDatabaseManager());
        this.database.createTables();

        this.nameCache = new PlayerNameCache(this);
        this.nameCache.register();

        this.claimCostManager = new ClaimCostManager(this);
        this.warpCostManager = new WarpCostManager(this);
        this.inviteCostManager = new InviteCostManager(this);
        this.taxManager = new TaxManager(this);
        this.marketManager = new MarketManager(this);
        this.auctionManager = new AuctionManager(this);
        this.marketCommand = new MarketCommand(this, marketManager, auctionManager);
        this.taxCommand = new org.ayosynk.landclaimeconomy.commands.TaxCommand(this);

        var server = Bukkit.getPluginManager();
        server.registerEvents(claimCostManager, this);
        server.registerEvents(warpCostManager, this);
        server.registerEvents(inviteCostManager, this);
        server.registerEvents(new org.ayosynk.landclaimeconomy.listeners.ClaimCleanupListener(this), this);

        var claimMarketCmd = getCommand("claimmarket");
        if (claimMarketCmd != null) {
            claimMarketCmd.setTabCompleter(marketCommand);
        }

        var claimTaxCmd = getCommand("claimtax");
        if (claimTaxCmd != null) {
            claimTaxCmd.setExecutor(taxCommand);
            claimTaxCmd.setTabCompleter(taxCommand);
        }

        if (economyConfig.enabled && economyConfig.tax.enabled) {
            taxManager.start();
        }
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
        if (command.getName().equalsIgnoreCase("claimtax")) {
            return taxCommand.onCommand(sender, command, label, args);
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

    public TaxManager getTaxManager() {
        return taxManager;
    }

    public MarketManager getMarketManager() {
        return marketManager;
    }

    public AuctionManager getAuctionManager() {
        return auctionManager;
    }

    public MarketCommand getMarketCommand() {
        return marketCommand;
    }
}
