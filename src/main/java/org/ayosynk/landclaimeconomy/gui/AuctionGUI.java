package org.ayosynk.landclaimeconomy.gui;

import net.kyori.adventure.text.Component;
import org.ayosynk.landClaimPlugin.api.LandClaimAPI;
import org.ayosynk.landClaimPlugin.gui.GuiHelper;
import org.ayosynk.landClaimPlugin.gui.framework.ClickAction;
import org.ayosynk.landClaimPlugin.gui.framework.GuiItem;
import org.ayosynk.landClaimPlugin.gui.framework.PaginatedGui;
import org.ayosynk.landClaimPlugin.gui.framework.SlotDefinition;
import org.ayosynk.landClaimPlugin.models.ClaimProfile;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.managers.AuctionManager;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.ayosynk.landclaimeconomy.util.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ayosynk.landclaimeconomy.config.MessagesConfig;

/**
 * GUI browser for active claim auctions. Click an auction to start a
 * bidding flow (the actual amount is typed in chat, since Bedrock
 * doesn't have native number pickers and Java players get faster
 * input via /claimmarket bid anyway).
 */
public class AuctionGUI {

    private static final String TITLE = "<dark_purple><bold>Claim Auctions</bold></dark_purple>";

    public static void open(Player player, LandClaimEconomy plugin) {
        FoliaScheduler.runAsync(plugin, () -> {
            AuctionManager mgr = plugin.getAuctionManager();
            if (mgr == null) {
                FoliaScheduler.runForPlayer(plugin, player,
                        () -> player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                                + plugin.getMessages().featureDisabled)));
                return;
            }
            List<AuctionManager.Auction> auctions = mgr.getActiveAuctions();
            if (auctions.isEmpty()) {
                FoliaScheduler.runForPlayer(plugin, player,
                        () -> player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                                + "<gray>No active auctions right now. Run <gold>/claimmarket auction start <name> <price></gold> to start one.")));
                return;
            }

            List<GuiItem> items = new ArrayList<>();
            for (AuctionManager.Auction a : auctions) {
                items.add(new GuiItem() {
                    @Override
                    public ItemStack render(Player viewer) {
                        ItemStack item = new ItemStack(org.bukkit.Material.CLOCK);
                        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.displayName(Component.text("§d" + a.claimName));
                            List<Component> lore = new ArrayList<>();
                            lore.add(Component.text("§7Seller: §e" + a.sellerName));
                            lore.add(Component.text("§7Starting: §a"
                                    + EconomyHook.format(a.startingPrice)));
                            lore.add(Component.text("§7Current bid: §a"
                                    + EconomyHook.format(a.currentBid)));
                            if (a.buyoutPrice > 0) {
                                lore.add(Component.text("§7Buyout: §6"
                                        + EconomyHook.format(a.buyoutPrice)));
                            }
                            lore.add(Component.text(""));
                            long secs = a.secondsRemaining();
                            long hours = secs / 3600;
                            long mins = (secs % 3600) / 60;
                            long ss = secs % 60;
                            lore.add(Component.text("§7Ends in: §e"
                                    + String.format("%dh %02dm %02ds", hours, mins, ss)));
                            lore.add(Component.text(""));
                            lore.add(Component.text("§7Left-click to bid"));
                            if (a.sellerId.equals(viewer.getUniqueId())) {
                                lore.add(Component.text("§cRight-click to cancel"));
                            }
                            meta.lore(lore);
                            item.setItemMeta(meta);
                        }
                        return item;
                    }

                                     public ClickAction clickAction() {
                        return (p, e) -> {
                            if (e.isRightClick() && a.sellerId.equals(p.getUniqueId())) {
                                p.closeInventory();
                                ClaimProfile profile = lookupProfile(a.claimId);
                                if (profile != null) {
                                    FoliaScheduler.runForPlayer(plugin, p,
                                            () -> mgr.cancelAuction(p, profile));
                                }
                                return;
                            }
                            if (e.isLeftClick()) {
                                p.closeInventory();
                                ClaimProfile profile = lookupProfile(a.claimId);
                                if (profile != null) {
                                    plugin.getMarketCommand().startBidFlow(p, profile,
                                            a.currentBid + plugin.getEconomyConfig().auctionMinBidIncrement,
                                            a.buyoutPrice);
                                }
                            }
                        };
                    }
                });
            }

            String[] structure = {
                    "x x x x x x x x x",
                    "x x x x x x x x x",
                    "x x x x x x x x x",
                    "P n n n < n n n N"
            };
            Map<Character, SlotDefinition> ingredients = new HashMap<>();
            ingredients.put('n', GuiHelper.buildSlot("BLACK_STAINED_GLASS_PANE", " ", java.util.List.of()));
            ingredients.put('<', GuiHelper.buildSlot("ARROW", "<yellow>Back", java.util.List.of(),
                    (p, e) -> p.closeInventory()));

            Component title = GuiHelper.MM.deserialize(TITLE);
            PaginatedGui gui = new PaginatedGui(title, 4, structure, ingredients, 'x');
            gui.setPrevButton(27,
                    GuiHelper.buildItemStack("ARROW", "<gray>Previous Page", java.util.List.of()),
                    GuiHelper.buildItemStack("BLACK_STAINED_GLASS_PANE", " ", java.util.List.of()));
            gui.setNextButton(35,
                    GuiHelper.buildItemStack("ARROW", "<gray>Next Page", java.util.List.of()),
                    GuiHelper.buildItemStack("BLACK_STAINED_GLASS_PANE", " ", java.util.List.of()));

            FoliaScheduler.runForPlayer(plugin, player, () -> {
                gui.setContent(items, player);
                gui.open(player);
            });
        });
    }

    private static ClaimProfile lookupProfile(UUID claimId) {
        LandClaimAPI api = LandClaimAPI.getInstance();
        return api != null ? api.getClaimById(claimId) : null;
    }
}