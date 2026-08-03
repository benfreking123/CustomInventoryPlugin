package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.groupdrop.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Feeds the Collections tracker.
 *
 * Pickup alone is not enough: group-drop rewards, {@code customitems give} and
 * backpack transfers never raise a pickup event, so a full inventory sweep runs
 * on close, shortly after join, and on a slow timer. The service keeps a
 * per-player key cache, so a sweep that finds nothing new costs no writes.
 */
public class CollectionsListener implements Listener {

    /** Slow enough to be invisible, frequent enough that a reward is logged promptly. */
    private static final long SWEEP_PERIOD_TICKS = 20L * 60;
    private static final long JOIN_SWEEP_DELAY_TICKS = 40L;

    private final CustomInventoryPlugin plugin;
    private final CollectionsService service;
    private BukkitTask sweepTask;

    public CollectionsListener(CustomInventoryPlugin plugin, CollectionsService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** Periodic catch-all sweep for every online player. */
    public void startSweepTask() {
        stopSweepTask();
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                service.observeInventory(p);
            }
        }, SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
    }

    public void stopSweepTask() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack stack = event.getItem().getItemStack();
        if (service.observe(player, stack)) announce(player, stack);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> service.prime(player.getUniqueId()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) service.observeInventory(player);
        }, JOIN_SWEEP_DELAY_TICKS);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            service.observeInventory(player);
        }
    }

    /**
     * Only pickups announce. A sweep can discover dozens at once (a fresh
     * reward bundle, or the first sweep after a config change) and chat spam
     * would drown the reason the player opened the menu.
     */
    private void announce(Player player, ItemStack stack) {
        CollectionsConfig cfg = plugin.getCollectionsConfig();
        if (cfg == null) return;
        String key = service.keyFor(stack);
        if (key == null) return;
        String name = displayFor(cfg, key);
        player.sendActionBar(Text.c("&d\u2726 New collection entry: &f" + name));
    }

    private String displayFor(CollectionsConfig cfg, String key) {
        for (CollectionEntry e : cfg.entries().values()) {
            if (cfg.keyFor(e).equals(key)) return e.getDisplay();
        }
        for (CollectionsConfig.SetEntry s : cfg.sets().values()) {
            for (String el : s.elements) {
                if (cfg.keyForSetPiece(s.id, el).equals(key)) {
                    return s.display + " &7(" + el + ")";
                }
            }
        }
        return "?";
    }
}
