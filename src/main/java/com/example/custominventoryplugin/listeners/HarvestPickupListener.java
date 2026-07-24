package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.pickup.DelayedGroundPickup;
import com.example.custominventoryplugin.pickup.PickupPipeline;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Block-harvest and fishing auto-pickup feeds. Harvest drops briefly appear
 * on the ground (owner-locked) before the shared {@link PickupPipeline}
 * vacuums them. Fishing stays instant (single item, already an entity).
 */
public class HarvestPickupListener implements Listener {

    private final CustomInventoryPlugin plugin;
    private final PickupPipeline pipeline;

    public HarvestPickupListener(CustomInventoryPlugin plugin, PickupPipeline pipeline) {
        this.plugin = plugin;
        this.pipeline = pipeline;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        if (player == null || !pipeline.masterEnabled(player)) return;

        var items = event.getItems();
        if (items.isEmpty()) return;

        int delay = plugin.getConfigManager().getAutopickupGroundDelayTicks();
        // Snapshot stacks, cancel vanilla spawn list, re-drop as owned delayed pickups.
        for (Item entity : new java.util.ArrayList<>(items)) {
            ItemStack stack = entity.getItemStack();
            if (stack == null || stack.getType().isAir()) continue;
            DelayedGroundPickup.schedule(plugin, pipeline, player,
                    entity.getLocation(), stack.clone(), delay);
        }
        items.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player player = event.getPlayer();
        if (!pipeline.masterEnabled(player)) return;
        if (!(event.getCaught() instanceof Item caught)) return;

        ItemStack stack = caught.getItemStack();
        if (stack == null || stack.getType().isAir()) return;

        int delay = plugin.getConfigManager().getAutopickupGroundDelayTicks();
        DelayedGroundPickup.schedule(plugin, pipeline, player, caught.getLocation(), stack.clone(), delay);
        caught.remove();
    }
}
