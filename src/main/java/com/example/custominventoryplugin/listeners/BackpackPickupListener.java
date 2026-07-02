package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.pickup.PickupPipeline;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Ground auto-pickup feed. When a player walks over an item, route it through
 * the shared {@link PickupPipeline} (bags by priority/mode, then inventory,
 * then overflow bags). Cancels vanilla pickup only when the item is fully
 * absorbed; partial pickups shrink the entity and let vanilla grab the rest.
 */
public class BackpackPickupListener implements Listener {

    private final CustomInventoryPlugin plugin;
    private final PickupPipeline pipeline;

    public BackpackPickupListener(CustomInventoryPlugin plugin, PickupPipeline pipeline) {
        this.plugin = plugin;
        this.pipeline = pipeline;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!pipeline.masterEnabled(player)) return;

        Item entity = event.getItem();
        ItemStack stack = entity.getItemStack();
        if (stack == null || stack.getType().isAir()) return;

        PickupPipeline.Session session = pipeline.begin(player);
        int absorbed = session.route(stack);
        session.flush();
        if (absorbed <= 0) return;

        if (stack.getAmount() <= 0) {
            event.setCancelled(true);
            entity.remove();
        } else {
            entity.setItemStack(stack);
        }
        if (session.bagDeposited() > 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.2f);
        }
    }
}
