package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Feeds the bestiary on every MythicMobs death. Soft-depend: only registered
 * when MythicMobs is present. Elite and base ids are stored separately; the
 * entry config decides how they roll up for unlocks.
 */
public class BestiaryKillListener implements Listener {

    private final CustomInventoryPlugin plugin;
    private final BestiaryConfig config;
    private final BestiaryData data;

    public BestiaryKillListener(CustomInventoryPlugin plugin, BestiaryConfig config, BestiaryData data) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMythicDeath(MythicMobDeathEvent event) {
        LivingEntity killer = event.getKiller();
        if (!(killer instanceof Player player)) return;
        if (event.getMobType() == null) return;

        String mythicId = event.getMobType().getInternalName();
        if (mythicId == null || mythicId.isBlank()) return;

        BestiaryEntry entry = config.byMythicId(mythicId);
        if (entry == null) return; // untracked (VFX, dummies, tutorial, etc.)

        // Write-through off the main path is fine — UPSERT is cheap — but keep
        // it async so a slow DB never stalls death handling.
        final String id = mythicId;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> data.increment(player.getUniqueId(), id));
    }
}
