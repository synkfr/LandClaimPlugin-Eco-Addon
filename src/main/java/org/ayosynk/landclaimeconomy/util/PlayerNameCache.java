package org.ayosynk.landclaimeconomy.util;

import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Folia-safe player name lookup. On Folia, {@code OfflinePlayer.getName()} does blocking
 * file I/O against {@code usercache.json} on first access for an unknown UUID, which is
 * unacceptable on a region thread or the global region thread. This cache pre-populates
 * names from {@link AsyncPlayerPreLoginEvent} (which fires off-thread) and provides
 * constant-time, non-blocking lookups for the common case.
 *
 * <p>For offline players we've never seen, a fallback path schedules the name lookup
 * on the async scheduler and returns the UUID string until it resolves. UI surfaces
 * that must show a name immediately (marketplace listings, auction browser) should
 * call {@link #getName(UUID)} from an async context where the result is acceptable
 * to be a UUID for the first render; subsequent renders will see the resolved name.
 *
 * <p>Note: the cache is best-effort. If the server restarts, names are re-populated as
 * players log in. Names for players who never log in remain unresolved and will display
 * as their UUID — same behavior as the previous {@code Bukkit.getOfflinePlayer(uuid).getName()}
 * fallback when the cache was cold.
 */
public final class PlayerNameCache implements Listener {

    private final LandClaimEconomy plugin;
    private final ConcurrentMap<UUID, String> names = new ConcurrentHashMap<>();

    public PlayerNameCache(LandClaimEconomy plugin) {
        this.plugin = plugin;
    }

    /** Register this cache as a Bukkit listener. Call from {@code onEnable()}. */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // Fires off-thread; safe to do file I/O here.
        names.put(event.getUniqueId(), event.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Keep the entry — name doesn't change. Drop only on name change (rare;
        // we don't track it because UUIDs are the stable key everywhere).
    }

    /**
     * Resolve a player name. Returns the cached name if present, or schedules an
     * async lookup and returns the UUID string until the lookup completes. The
     * resolved name will appear in {@link #getName(UUID)} on subsequent calls.
     *
     * <p>This method is safe to call from any thread. It does <b>not</b> block.
     */
    public String getName(UUID playerId) {
        if (playerId == null) return "<unknown>";
        String cached = names.get(playerId);
        if (cached != null) return cached;
        // Cold cache: schedule an async lookup. The async thread is allowed to block
        // (that's its purpose), so usercache.json I/O is fine there.
        FoliaScheduler.runAsync(plugin, () -> {
            // Skip if another thread already populated it.
            if (names.containsKey(playerId)) return;
            String name = null;
            try {
                name = Bukkit.getOfflinePlayer(playerId).getName();
            } catch (Throwable ignored) {
                // usercache may be locked or the player may not exist; degrade gracefully.
            }
            if (name != null) names.put(playerId, name);
        });
        return playerId.toString();
    }
}
