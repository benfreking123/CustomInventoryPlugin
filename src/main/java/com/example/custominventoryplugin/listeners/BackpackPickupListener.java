package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.pickup.PickupPipeline;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Ground auto-pickup feed. When a player walks over an item, route it through
 * the shared {@link PickupPipeline} (bags by priority/mode, then inventory,
 * then overflow bags). Cancels vanilla pickup only when the item is fully
 * absorbed; partial pickups shrink the entity and let vanilla grab the rest.
 *
 * Player Q-drops are exempt: tagged on {@link PlayerDropItemEvent} so AutoPick
 * does not vacuum them back into bags/inventory (vanilla pickup still applies).
 */
public class BackpackPickupListener implements Listener {

    /** PDC marker on Item entities dropped via Q / drag-drop by a player. */
    public static final String PLAYER_DROP_KEY = "player_drop";

    private final CustomInventoryPlugin plugin;
    private final PickupPipeline pipeline;
    private final NamespacedKey playerDropKey;

    public BackpackPickupListener(CustomInventoryPlugin plugin, PickupPipeline pipeline) {
        this.plugin = plugin;
        this.pipeline = pipeline;
        this.playerDropKey = new NamespacedKey(plugin, PLAYER_DROP_KEY);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Item entity = event.getItemDrop();
        entity.getPersistentDataContainer().set(playerDropKey, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Item entity = event.getItem();
        // Owner-locked delayed drops: nobody else may steal them.
        if (entity.getOwner() != null && !entity.getOwner().equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        // Q / player drops: skip AutoPick pipeline (vanilla pickup only).
        if (isPlayerDropped(entity)) {
            return;
        }

        if (!pipeline.masterEnabled(player)) return;

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

    private boolean isPlayerDropped(Item entity) {
        if (entity.getPersistentDataContainer().has(playerDropKey, PersistentDataType.BYTE)) {
            return true;
        }
        // Belt-and-suspenders: Paper sets thrower on player drops; our
        // DelayedGroundPickup path uses World#dropItem (no thrower).
        return entity.getThrower() != null;
    }
}
