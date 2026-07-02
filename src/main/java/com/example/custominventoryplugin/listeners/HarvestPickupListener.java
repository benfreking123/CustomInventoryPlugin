package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.pickup.PickupPipeline;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.List;

/**
 * Block-harvest and fishing auto-pickup feeds, routed through the shared
 * {@link PickupPipeline}. Block XP stays vanilla (no player is attached to the
 * XP source); fishing XP is granted directly to the angler for parity.
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

        List<Item> items = event.getItems();
        if (items.isEmpty()) return;

        PickupPipeline.Session session = pipeline.begin(player);
        Iterator<Item> it = items.iterator();
        while (it.hasNext()) {
            Item entity = it.next();
            ItemStack stack = entity.getItemStack();
            if (stack == null || stack.getType().isAir()) continue;
            session.route(stack);
            if (stack.getAmount() <= 0) {
                it.remove();                 // never spawns as a ground item
            } else {
                entity.setItemStack(stack);  // partial — drop the leftover
            }
        }
        session.flush();

        if (session.bagDeposited() > 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.2f);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player player = event.getPlayer();
        if (!pipeline.masterEnabled(player)) return;
        if (!(event.getCaught() instanceof Item caught)) return;

        ItemStack stack = caught.getItemStack();
        if (stack == null || stack.getType().isAir()) return;

        int absorbed = pipeline.routeSingle(player, stack);
        if (absorbed <= 0) return;

        if (stack.getAmount() <= 0) {
            caught.remove();
        } else {
            caught.setItemStack(stack);
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.2f);

        int xp = event.getExpToDrop();
        if (xp > 0) {
            player.giveExp(xp);
            event.setExpToDrop(0);
        }
    }
}
