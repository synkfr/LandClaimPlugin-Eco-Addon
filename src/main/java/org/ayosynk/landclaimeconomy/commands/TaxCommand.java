package org.ayosynk.landclaimeconomy.commands;

import org.ayosynk.landClaimPlugin.models.ClaimProfile;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.config.MessagesConfig;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TaxCommand implements CommandExecutor, TabCompleter {

    private final LandClaimEconomy plugin;

    public TaxCommand(LandClaimEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + "<red>Only players can check claim tax."));
            return true;
        }

        if (!player.hasPermission("landclaimeconomy.use")) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + "<red>You don't have permission to use this command."));
            return true;
        }

        var cfg = plugin.getEconomyConfig();
        if (!cfg.enabled || !cfg.tax.enabled) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().featureDisabled));
            return true;
        }

        boolean bypass = player.hasPermission("landclaimeconomy.bypass")
                || player.hasPermission("landclaimeconomy.bypass.tax")
                || player.hasPermission("landclaim.admin");

        var api = plugin.getParentAPI();
        if (api == null) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + plugin.getMessages().featureDisabled));
            return true;
        }

        List<ClaimProfile> claims = api.getClaimsByOwner(player.getUniqueId());
        if (claims.isEmpty()) {
            player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                    + "<gray>You do not own any claims."));
            return true;
        }

        double balance = EconomyHook.getBalance(player);
        int totalChunks = api.getTotalChunksByOwner(player.getUniqueId());
        double totalDailyTax = totalChunks * cfg.taxPerChunkPerDay;

        player.sendMessage(MessagesConfig.formatRaw("<gold><bold>===== Claim Tax Status =====</bold></gold>"));
        player.sendMessage(MessagesConfig.formatRaw("<gray>Balance: <green>" + EconomyHook.format(balance)
                + (bypass ? " <yellow>(Tax Bypassed)</yellow>" : "")));
        player.sendMessage(MessagesConfig.formatRaw("<gray>Total Chunks: <yellow>" + totalChunks
                + " <dark_gray>| <gray>Daily Upkeep: <gold>" + EconomyHook.format(totalDailyTax)));
        player.sendMessage(MessagesConfig.formatRaw("<gray>Rate: <gold>"
                + EconomyHook.format(cfg.taxPerChunkPerDay) + "</gold>/chunk/day"));
        player.sendMessage(MessagesConfig.formatRaw("<gray>Grace Period: <yellow>" + cfg.taxGracePeriodDays + " day(s)"));

        var taxMgr = plugin.getTaxManager();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        for (ClaimProfile claim : claims) {
            int chunks = claim.getOwnedChunks().size();
            double claimTax = chunks * cfg.taxPerChunkPerDay;
            long[] ledger = taxMgr != null ? taxMgr.readLedger(claim.getProfileId()) : new long[]{0, 0, 0};
            long lastPaid = ledger[0];
            int unpaidDays = (int) ledger[1];

            String status;
            if (bypass) {
                status = "<green>Exempt";
            } else if (unpaidDays > 0) {
                long deadline = lastPaid + TimeUnit.DAYS.toMillis(cfg.taxGracePeriodDays);
                status = "<red>" + unpaidDays + " unpaid day(s) (Deadline: "
                        + dateFormat.format(new Date(deadline)) + ")";
            } else {
                status = "<green>Paid";
            }

            player.sendMessage(MessagesConfig.formatRaw("<gold>• <yellow>" + claim.getName()
                    + "</yellow> <dark_gray>(<gray>" + chunks + " chunks, " + EconomyHook.format(claimTax)
                    + "/day<dark_gray>) <dark_gray>- " + status));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
