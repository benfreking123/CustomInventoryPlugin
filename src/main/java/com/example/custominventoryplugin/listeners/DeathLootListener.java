package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.autoloot.AutoLootConfig;
import com.example.custominventoryplugin.autoloot.LootEffectService;
import com.example.custominventoryplugin.pickup.DelayedGroundPickup;
import com.example.custominventoryplugin.pickup.PickupPipeline;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Mob-death auto-loot feed — CIP is the server's drop manager. Registered at
 * {@link EventPriority#HIGHEST} with {@code softdepend: [Divinity]} so it runs
 * AFTER Divinity's DropManager (which injects custom loot into getDrops()) and
 * BEFORE Divinity's LootManager. With the Divinity {@code loot} module disabled,
 * no loot-box skull is created; CIP presents and routes every drop instead.
 *
 * Drops are removed from {@code getDrops()}, spawned as short-lived owned ground
 * items (decorated with their rarity glow/burst), then vacuumed through
 * {@link PickupPipeline}. Spawn-reason / entity-type blacklists (ported from
 * Divinity's old loot config) keep mob-farm behaviour intact.
 */
public class DeathLootListener implements Listener {

    private final CustomInventoryPlugin plugin;
    private final PickupPipeline pipeline;
    private final AutoLootConfig config;
    private final LootEffectService effects;

    public DeathLootListener(CustomInventoryPlugin plugin, PickupPipeline pipeline,
                             AutoLootConfig config, LootEffectService effects) {
        this.plugin = plugin;
        this.pipeline = pipeline;
        this.config = config;
        this.effects = effects;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        if (!pipeline.masterEnabled(killer)) return;
        if (isBlacklisted(event.getEntity())) return;

        List<ItemStack> drops = event.getDrops();
        if (drops.isEmpty()) {
            grantXp(event, killer);
            return;
        }

        int delay = (config != null)
                ? config.getGroundLingerTicks()
                : plugin.getConfigManager().getAutopickupGroundDelayTicks();
        List<ItemStack> toShow = new ArrayList<>();
        Iterator<ItemStack> it = drops.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (stack == null || stack.getType().isAir()) continue;
            toShow.add(stack.clone());
            it.remove(); // keep Divinity loot-box from wrapping these
        }

        var loc = event.getEntity().getLocation().add(0, 0.25, 0);
        for (ItemStack stack : toShow) {
            DelayedGroundPickup.schedule(plugin, pipeline, killer, loc, stack, delay, effects);
        }

        grantXp(event, killer);
    }

    /** Ported from Divinity's loot module: skip spawner mobs, armour stands, etc. */
    private boolean isBlacklisted(Entity entity) {
        if (config == null) return false;
        if (config.getBlacklistEntityTypes().contains(entity.getType().name())) return true;
        CreatureSpawnEvent.SpawnReason reason = entity.getEntitySpawnReason();
        return reason != null && config.getBlacklistSpawnReasons().contains(reason.name());
    }

    /** XP pickup parity: send the mob's XP straight to the killer. */
    private void grantXp(EntityDeathEvent event, Player killer) {
        int xp = event.getDroppedExp();
        if (xp > 0) {
            killer.giveExp(xp);
            event.setDroppedExp(0);
        }
    }
}
