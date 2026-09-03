package org.ayosynk.landclaimeconomy.listeners;

import org.ayosynk.landClaimPlugin.api.event.ClaimDeleteEvent;
import org.ayosynk.landClaimPlugin.api.event.ClaimTransferEvent;
import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

public class ClaimCleanupListener implements Listener {

    private final LandClaimEconomy plugin;

    public ClaimCleanupListener(LandClaimEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClaimDelete(ClaimDeleteEvent event) {
        if (event.getProfile() == null) return;
        UUID profileId = event.getProfile().getProfileId();
        cleanupClaim(profileId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClaimTransfer(ClaimTransferEvent event) {
        if (event.getProfile() == null) return;
        UUID profileId = event.getProfile().getProfileId();
        cleanupClaim(profileId);
    }

    private void cleanupClaim(UUID profileId) {
        if (plugin.getMarketManager() != null) {
            plugin.getMarketManager().deleteListingBySystem(profileId);
        }
        if (plugin.getAuctionManager() != null) {
            plugin.getAuctionManager().cancelAuctionBySystem(profileId);
        }
    }
}
