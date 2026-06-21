package org.ayosynk.landclaimeconomy.gui;

import net.kyori.adventure.text.Component;
import org.ayosynk.landClaimPlugin.gui.GuiHelper;
import org.ayosynk.landClaimPlugin.gui.framework.ClickAction;
import org.ayosynk.landClaimPlugin.gui.framework.GuiItem;
import org.ayosynk.landClaimPlugin.gui.framework.PaginatedGui;
import org.ayosynk.landClaimPlugin.gui.framework.SlotDefinition;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.managers.MarketManager;
import org.ayosynk.landclaimeconomy.util.EconomyHook;
import org.ayosynk.landclaimeconomy.util.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ayosynk.landclaimeconomy.config.MessagesConfig;

/**
 * Server-wide marketplace GUI. Lists every active market listing with
 * the claim name, owner, and price. Left-click to buy, right-click to
 * unlist (only if the viewer owns the listing).
 */
public class MarketplaceGUI {

    private static final String TITLE = "<gold><bold>Claim Marketplace</bold></gold>";

    public static void open(Player player, LandClaimEconomy plugin) {
        open(player, plugin, 0, false);
    }

    /**
     * Open the marketplace at the given page.
     *
     * @param myOnly when true, only show listings the viewer owns (so
     *               they can quickly unlist them). Used as a "My
     *               Listings" sub-view.
     */
    public static void open(Player player, LandClaimEconomy plugin, int page, boolean myOnly) {
        // Capture player state on the calling thread (which on Folia is already the
        // player's region thread, since open() is invoked from a sync event handler)
        // so the async block below doesn't need to touch Player at all.
        java.util.UUID viewerId = player.getUniqueId();
        FoliaScheduler.runAsync(plugin, () -> {
            var market = plugin.getMarketManager();
            if (market == null) {
                FoliaScheduler.runForPlayer(plugin, player,
                        () -> player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                                + plugin.getMessages().featureDisabled)));
                return;
            }

            List<MarketManager.Listing> all = market.getActiveListings();
            if (myOnly) {
                all.removeIf(l -> !l.ownerId.equals(viewerId));
            }

            if (all.isEmpty()) {
                FoliaScheduler.runForPlayer(plugin, player,
                        () -> player.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                                + (myOnly
                                        ? "<gray>You don't have any active listings."
                                        : plugin.getMessages().marketEmpty))));
                return;
            }

            List<GuiItem> contentItems = new ArrayList<>();
            for (MarketManager.Listing l : all) {
                contentItems.add(new GuiItem() {
                    @Override
                    public ItemStack render(Player viewer) {
                        // Build a player-head style placeholder. We use a
                        // chest as a stand-in since we don't have a
                        // // skin to render.
                        ItemStack item = new ItemStack(org.bukkit.Material.CHEST);
                        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.displayName(Component.text("§6" + l.claimName));
                            List<Component> lore = new ArrayList<>();
                            lore.add(Component.text("§7Owner: §e" + l.ownerName));
                            lore.add(Component.text("§7Price: §a"
                                    + EconomyHook.format(l.price)));
                            lore.add(Component.text(""));
                            lore.add(Component.text("§7Left-click to buy"));
                            if (l.ownerId.equals(viewer.getUniqueId())) {
                                lore.add(Component.text("§cRight-click to unlist"));
                            }
                            meta.lore(lore);
                            item.setItemMeta(meta);
                        }
                        return item;
                    }

                    @Override
                    public ClickAction clickAction() {
                        return (p, e) -> {
                            if (e.getClick() == ClickType.RIGHT
                                    && l.ownerId.equals(p.getUniqueId())) {
                                p.closeInventory();
                                // Look up the live claim profile by ID and unlist it.
                                org.ayosynk.landClaimPlugin.api.LandClaimAPI api =
                                        org.ayosynk.landClaimPlugin.api.LandClaimAPI.getInstance();
                                org.ayosynk.landClaimPlugin.models.ClaimProfile profile =
                                        api != null ? api.getClaimById(l.claimId) : null;
                                if (profile != null) {
                                    FoliaScheduler.runForPlayer(plugin, p,
                                            () -> market.unlist(p, profile));
                                }
                                FoliaScheduler.runForPlayerLater(plugin, p,
                                        () -> open(p, plugin, 0, true), 5L);
                                return;
                            }
                            if (e.getClick() == ClickType.LEFT) {
                                if (l.ownerId.equals(p.getUniqueId())) {
                                    p.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                                            + "<red>You already own this claim."));
                                    return;
                                }
                                p.closeInventory();
                                // Look up the claim profile via the new
                                // public API so the buy flow gets the
                                // correct target.
                                org.ayosynk.landClaimPlugin.api.LandClaimAPI api =
                                        org.ayosynk.landClaimPlugin.api.LandClaimAPI.getInstance();
                                org.ayosynk.landClaimPlugin.models.ClaimProfile profile =
                                        api != null ? api.getClaimById(l.claimId) : null;
                                if (profile == null) {
                                    p.sendMessage(MessagesConfig.formatRaw(plugin.getMessages().prefix
                                            + plugin.getMessages().claimNotFound));
                                    return;
                                }
                                org.ayosynk.landClaimPlugin.models.ClaimProfile finalProfile = profile;
                                FoliaScheduler.runForPlayer(plugin, p,
                                        () -> market.buy(p, finalProfile));
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
            ingredients.put('<', GuiHelper.buildSlot("ARROW", myOnly
                            ? "<yellow>Back to all listings"
                            : "<yellow>Back to main menu",
                    java.util.List.of(),
                    (p, e) -> {
                        p.closeInventory();
                        if (myOnly) {
                            FoliaScheduler.runForPlayerLater(plugin, p,
                                    () -> open(p, plugin, 0, false), 5L);
                        }
                    }));

            Component title = GuiHelper.MM.deserialize(TITLE);
            PaginatedGui gui = new PaginatedGui(title, 4, structure, ingredients, 'x');

            gui.setPrevButton(27,
                    GuiHelper.buildItemStack("ARROW", "<gray>Previous Page", java.util.List.of()),
                    GuiHelper.buildItemStack("BLACK_STAINED_GLASS_PANE", " ", java.util.List.of()));
            gui.setNextButton(35,
                    GuiHelper.buildItemStack("ARROW", "<gray>Next Page", java.util.List.of()),
                    GuiHelper.buildItemStack("BLACK_STAINED_GLASS_PANE", " ", java.util.List.of()));

            FoliaScheduler.runForPlayer(plugin, player, () -> {
                gui.setContent(contentItems, player);
                gui.open(player);
            });
        });
    }
}
