package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.pickup.PickupPipeline;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.List;

/**
 * Mob-death auto-pickup feed. Registered at {@link EventPriority#HIGHEST} with
 * {@code softdepend: [Divinity]} so it runs:
 *   • AFTER Divinity's DropManager (HIGHEST, registered first because Divinity
 *     enables before us) which has already injected custom loot into getDrops()
 *   • BEFORE Divinity's LootManager (MONITOR) which wraps getDrops() into a
 *     loot-box skull and clears the list.
 *
 * We consume from {@code getDrops()} whatever the player's bags/inventory can
 * absorb. Anything left stays in the list, so the skull only forms for the
 * remainder. If the player's master toggle is OFF, we no-op and the skull /
 * vanilla drops behave exactly as without this plugin.
 */
public class DeathLootListener implements Listener {

    private final CustomInventoryPlugin plugin;
    private final PickupPipeline pipeline;

    public DeathLootListener(CustomInventoryPlugin plugin, PickupPipeline pipeline) {
        this.plugin = plugin;
        this.pipeline = pipeline;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        if (!pipeline.masterEnabled(killer)) return;

        List<ItemStack> drops = event.getDrops();
        if (drops.isEmpty()) {
            grantXp(event, killer);
            return;
        }

        PickupPipeline.Session session = pipeline.begin(killer);
        Iterator<ItemStack> it = drops.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (stack == null || stack.getType().isAir()) continue;
            session.route(stack);
            if (stack.getAmount() <= 0) it.remove();
        }
        session.flush();

        if (session.bagDeposited() > 0) {
            killer.playSound(killer.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.2f);
        }
        grantXp(event, killer);
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
