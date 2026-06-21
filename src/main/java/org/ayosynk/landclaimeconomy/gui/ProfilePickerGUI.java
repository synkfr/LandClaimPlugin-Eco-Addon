package org.ayosynk.landclaimeconomy.gui;

import net.kyori.adventure.text.Component;
import org.ayosynk.landClaimPlugin.gui.GuiHelper;
import org.ayosynk.landClaimPlugin.gui.framework.ClickAction;
import org.ayosynk.landClaimPlugin.gui.framework.GuiItem;
import org.ayosynk.landClaimPlugin.gui.framework.PaginatedGui;
import org.ayosynk.landClaimPlugin.gui.framework.SlotDefinition;
import org.ayosynk.landClaimPlugin.models.ClaimProfile;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landclaimeconomy.config.MessagesConfig;
import org.ayosynk.landclaimeconomy.util.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * GUI that lets a player pick one of their own {@link ClaimProfile}s.
 *
 * <p>Replaces the previous free-text {@code <claim-name>} argument in commands
 * like {@code /claimmarket sell} and {@code /claimmarket auction start}. Free-text
 * name matching was the source of multiple edge cases (name collisions across
 * players, renamed claims, ambiguous partial matches); the picker removes all
 * of them by letting the player click the exact profile they want.</p>
 *
 * <p>The picker is async-loaded on Folia: profile enumeration goes through
 * {@code runAsync} (parent's claim cache is read-safe from any thread) and the
 * GUI is opened on the player's region thread via {@code runForPlayer}.</p>
 */
public final class ProfilePickerGUI {

    private ProfilePickerGUI() {}

    /**
     * Open the picker. The player's profiles are loaded asynchronously; if the
     * player has none, a single message is shown instead of an empty GUI.
     *
     * @param player      the viewer
     * @param plugin      addon plugin instance (for async + messages)
     * @param titleRaw    MiniMessage title for the GUI
     * @param onPicked    invoked on the player's region thread when the player
     *                    clicks a profile; the consumer receives the picked profile
     */
    public static void open(Player player, LandClaimEconomy plugin, String titleRaw,
                            Consumer<ClaimProfile> onPicked) {
        java.util.UUID viewerId = player.getUniqueId();
        FoliaScheduler.runAsync(plugin, () -> {
            var api = org.ayosynk.landClaimPlugin.api.LandClaimAPI.getInstance();
            List<ClaimProfile> own = api == null
                    ? List.of()
                    : new ArrayList<>(api.getClaimsByOwner(viewerId));

            if (own.isEmpty()) {
                FoliaScheduler.runForPlayer(plugin, player, () ->
                        player.sendMessage(MessagesConfig.formatRaw(
                                plugin.getMessages().prefix + plugin.getMessages().pickerEmpty)));
                return;
            }

            // Filter out profiles that already have a market listing or auction
            // (they cannot be re-listed) so the picker only shows actionable profiles.
            var market = plugin.getMarketManager();
            var auctionMgr = plugin.getAuctionManager();
            List<ClaimProfile> actionable = new ArrayList<>();
            for (ClaimProfile p : own) {
                boolean alreadyListed = market != null && market.isListed(p.getProfileId());
                boolean onAuction = auctionMgr != null && auctionMgr.getAuctionForClaim(p.getProfileId()) != null;
                if (!alreadyListed && !onAuction) actionable.add(p);
            }
            if (actionable.isEmpty()) {
                FoliaScheduler.runForPlayer(plugin, player, () ->
                        player.sendMessage(MessagesConfig.formatRaw(
                                plugin.getMessages().prefix
                                        + "<yellow>All your claim profiles are already listed or on auction. "
                                        + "Unlist or cancel one first.")));
                return;
            }

            List<GuiItem> items = new ArrayList<>(actionable.size());
            for (ClaimProfile p : actionable) {
                items.add(buildItem(plugin, p, viewerId, onPicked));
            }

            String[] structure = {
                    "x x x x x x x x x",
                    "x x x x x x x x x",
                    "x x x x x x x x x",
                    "P n n n < n n n N"
            };
            Map<Character, SlotDefinition> ingredients = new HashMap<>();
            ingredients.put('n', GuiHelper.buildSlot("BLACK_STAINED_GLASS_PANE", " ", java.util.List.of()));
            ingredients.put('<', GuiHelper.buildSlot("BARRIER",
                    "<red><bold>Cancel</bold></red>",
                    java.util.List.of("<gray>Close this menu without picking."),
                    (viewer, e) -> viewer.closeInventory()));

            Component title = GuiHelper.MM.deserialize(titleRaw);
            PaginatedGui gui = new PaginatedGui(title, 4, structure, ingredients, 'x');
            gui.setPrevButton(27,
                    GuiHelper.buildItemStack("ARROW", "<gray>Previous Page", java.util.List.of()),
                    GuiHelper.buildItemStack("BLACK_STAINED_GLASS_PANE", " ", java.util.List.of()));
            gui.setNextButton(35,
                    GuiHelper.buildItemStack("ARROW", "<gray>Next Page", java.util.List.of()),
                    GuiHelper.buildItemStack("BLACK_STAINED_GLASS_PANE", " ", java.util.List.of()));

            // Capture for the region-thread open below.
            List<GuiItem> finalItems = items;
            FoliaScheduler.runForPlayer(plugin, player, () -> {
                gui.setContent(finalItems, player);
                gui.open(player);
            });
        });
    }

    private static GuiItem buildItem(LandClaimEconomy plugin, ClaimProfile p,
                                     java.util.UUID viewerId, Consumer<ClaimProfile> onPicked) {
        return new GuiItem() {
            @Override
            public ItemStack render(Player viewer) {
                ItemStack item = new ItemStack(org.bukkit.Material.GRASS_BLOCK);
                org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.displayName(Component.text("§6" + p.getName()));
                    List<Component> lore = new ArrayList<>();
                    int chunkCount = p.getOwnedChunks() == null ? 0 : p.getOwnedChunks().size();
                    lore.add(Component.text("§7" + plugin.getMessages().pickerProfileEntry
                            .replace("<chunks>", String.valueOf(chunkCount))));
                    lore.add(Component.text(""));
                    lore.add(Component.text("§eClick to pick this profile"));
                    meta.lore(lore);
                    item.setItemMeta(meta);
                }
                return item;
            }

            @Override
            public ClickAction clickAction() {
                return (clicker, e) -> {
                    if (e.getClick() != ClickType.LEFT) return;
                    clicker.closeInventory();
                    // Fire the consumer on the player's region thread (we're already
                    // on it because the click event fires there on Folia).
                    onPicked.accept(p);
                };
            }
        };
    }
}
