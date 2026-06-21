package org.ayosynk.landclaimeconomy.util;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Unified scheduler facade that works on both Paper and Folia.
 *
 * <p>Mirror of {@code LandClaimPlugin.util.FoliaScheduler}, duplicated here because the
 * addon is a separate plugin (Bukkit plugin classloaders are isolated) and cannot reach
 * into the parent's shaded jar at runtime. Keep this file in sync with the parent.</p>
 *
 * <p>On Paper (and Bukkit/Spigot), {@code Bukkit.getScheduler()} schedules against a single
 * main thread. On Folia, that scheduler does not exist; the world is sharded into independent
 * region threads, and code touching an entity, block, chunk, or world must run on the thread
 * that owns that region. Region-less code can run on the {@link GlobalRegionScheduler}, and
 * fully async work can run on the {@link AsyncScheduler} or any executor.</p>
 */
public final class FoliaScheduler {

    private static final boolean IS_FOLIA = detectFolia();
    private static final Method GLOBAL_REGION_SCHEDULER_METHOD;
    private static final Method ASYNC_SCHEDULER_METHOD;
    private static final Method REGION_SCHEDULER_METHOD;
    private static final Method PLAYER_SCHEDULER_METHOD;

    static {
        Method global = null;
        Method async = null;
        Method region = null;
        Method player = null;
        if (IS_FOLIA) {
            try {
                Class<?> serverClass = Bukkit.getServer().getClass();
                global = serverClass.getMethod("getGlobalRegionScheduler");
                async = serverClass.getMethod("getAsyncScheduler");
                region = serverClass.getMethod("getRegionScheduler");
                player = Player.class.getMethod("getScheduler");
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Folia detected but scheduler methods missing", e);
            }
        }
        GLOBAL_REGION_SCHEDULER_METHOD = global;
        ASYNC_SCHEDULER_METHOD = async;
        REGION_SCHEDULER_METHOD = region;
        PLAYER_SCHEDULER_METHOD = player;
    }

    private FoliaScheduler() {}

    /** True if the running server is Folia. */
    public static boolean isFolia() {
        return IS_FOLIA;
    }

    /** Runs the task on the global region thread (Folia) or the main thread (Paper). */
    public static void runTask(Plugin plugin, Runnable runnable) {
        if (IS_FOLIA) {
            try {
                GlobalRegionScheduler scheduler = (GlobalRegionScheduler) GLOBAL_REGION_SCHEDULER_METHOD.invoke(Bukkit.getServer());
                scheduler.run(plugin, (Consumer<ScheduledTask>) task -> runnable.run());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to run task on Folia global region", e);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /** Runs the task on the global region thread after {@code delay} ticks. */
    public static void runTaskLater(Plugin plugin, Runnable runnable, long delay) {
        if (IS_FOLIA) {
            try {
                GlobalRegionScheduler scheduler = (GlobalRegionScheduler) GLOBAL_REGION_SCHEDULER_METHOD.invoke(Bukkit.getServer());
                scheduler.runDelayed(plugin, (Consumer<ScheduledTask>) task -> runnable.run(), delay);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to schedule delayed task on Folia", e);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
        }
    }

    /** Runs the task asynchronously (no Bukkit API access allowed). */
    public static void runAsync(Plugin plugin, Runnable runnable) {
        if (IS_FOLIA) {
            try {
                AsyncScheduler scheduler = (AsyncScheduler) ASYNC_SCHEDULER_METHOD.invoke(Bukkit.getServer());
                scheduler.runNow(plugin, (Consumer<ScheduledTask>) task -> runnable.run());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to run async task on Folia", e);
            }
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        }
    }

    /** Runs the task asynchronously after {@code delay} ticks. */
    public static void runAsyncLater(Plugin plugin, Runnable runnable, long delay) {
        if (IS_FOLIA) {
            try {
                AsyncScheduler scheduler = (AsyncScheduler) ASYNC_SCHEDULER_METHOD.invoke(Bukkit.getServer());
                scheduler.runDelayed(plugin, (Consumer<ScheduledTask>) task -> runnable.run(), delay * 50L, TimeUnit.MILLISECONDS);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to schedule delayed async task on Folia", e);
            }
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delay);
        }
    }

    /** Schedules a repeating task on the global region thread (Folia) or main thread (Paper). */
    public static ScheduledHandle runTaskTimer(Plugin plugin, Runnable runnable, long delay, long period) {
        if (IS_FOLIA) {
            try {
                GlobalRegionScheduler scheduler = (GlobalRegionScheduler) GLOBAL_REGION_SCHEDULER_METHOD.invoke(Bukkit.getServer());
                long safeDelay = Math.max(1L, delay);
                ScheduledTask task = scheduler.runAtFixedRate(plugin, (Consumer<ScheduledTask>) t -> runnable.run(), safeDelay, period);
                return new ScheduledHandle(task, ScheduledHandle.Type.GLOBAL);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to schedule repeating global task on Folia", e);
            }
        } else {
            int id = Bukkit.getScheduler().runTaskTimer(plugin, runnable, delay, period).getTaskId();
            return new ScheduledHandle(id, ScheduledHandle.Type.BUKKIT_ID);
        }
    }

    /** Schedules a task on the region's thread that owns the given location. */
    public static void runAtLocation(Plugin plugin, Location location, Runnable runnable) {
        if (IS_FOLIA) {
            try {
                RegionScheduler scheduler = (RegionScheduler) REGION_SCHEDULER_METHOD.invoke(Bukkit.getServer());
                scheduler.execute(plugin, location, runnable);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to run task at location on Folia", e);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /** Schedules a task on the thread that owns the given entity. */
    public static void runForEntity(Plugin plugin, Entity entity, Runnable runnable) {
        if (IS_FOLIA) {
            try {
                Method getEntityScheduler = entity.getClass().getMethod("getScheduler");
                Object entityScheduler = getEntityScheduler.invoke(entity);
                Method runMethod = entityScheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Runnable.class);
                runMethod.invoke(entityScheduler, plugin, (Consumer<ScheduledTask>) t -> runnable.run(), null);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to run task for entity on Folia", e);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * Convenience: ensures the runnable runs on the player's region thread, falling back to
     * scheduling on the main thread on Paper. The player may be null &mdash; in that case the
     * runnable runs on the global region / main thread.
     */
    public static void runForPlayer(Plugin plugin, Player player, Runnable runnable) {
        if (player == null) {
            runTask(plugin, runnable);
            return;
        }
        if (IS_FOLIA) {
            try {
                Object playerScheduler = PLAYER_SCHEDULER_METHOD.invoke(player);
                Method runMethod = playerScheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Runnable.class);
                runMethod.invoke(playerScheduler, plugin, (Consumer<ScheduledTask>) t -> runnable.run(), null);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to run task for player on Folia", e);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /** Schedules a delayed task on the player's region thread. */
    public static void runForPlayerLater(Plugin plugin, Player player, Runnable runnable, long delay) {
        if (player == null) {
            runTaskLater(plugin, runnable, delay);
            return;
        }
        if (IS_FOLIA) {
            try {
                Object playerScheduler = PLAYER_SCHEDULER_METHOD.invoke(player);
                Method runDelayed = playerScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                runDelayed.invoke(playerScheduler, plugin, (Consumer<ScheduledTask>) t -> runnable.run(), delay);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to schedule delayed task for player on Folia", e);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
        }
    }

    /** Schedules an open-inventory call for a player on the player's region thread. */
    public static void openInventory(Plugin plugin, Player player, org.bukkit.inventory.Inventory inventory) {
        runForPlayer(plugin, player, () -> player.openInventory(inventory));
    }

    private static boolean detectFolia() {
        try {
            Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Opaque handle to a scheduled task. Use {@link #cancel()} to stop it. */
    public static final class ScheduledHandle {
        enum Type { BUKKIT_ID, GLOBAL, PLAYER, PLAYER_RECURRING }
        private final Object handle;
        private final Type type;
        private boolean cancelled = false;

        ScheduledHandle(Object handle, Type type) {
            this.handle = handle;
            this.type = type;
        }

        public void cancel() {
            if (cancelled || handle == null) return;
            cancelled = true;
            try {
                switch (type) {
                    case BUKKIT_ID -> {
                        int id = (int) handle;
                        Bukkit.getScheduler().cancelTask(id);
                    }
                    case GLOBAL, PLAYER -> {
                        Method cancelMethod = handle.getClass().getMethod("cancel");
                        cancelMethod.invoke(handle);
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }
}
