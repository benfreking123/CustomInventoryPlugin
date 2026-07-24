package com.example.custominventoryplugin.listeners;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.tooltip.TooltipStyleService;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import studio.magemonkey.fabled.api.event.PlayerAttributeChangeEvent;

/**
 * Applies Divinity {@code item_fabled_attr_*} bonuses for VANILLA armor slots,
 * mirroring what {@link AttributeHandler} does for the custom gear-menu slots
 * (rings/accessories). Nothing else on the server applies these: Divinity never
 * calls Fabled's giveAttribute and Fabled's own lore parsing is disabled.
 *
 * Also re-stamps tooltips whenever the player's Fabled attributes change, so
 * the "(total)" figures on stat lines and the req strip don't go stale.
 */
public final class ArmorAttributeListener implements Listener {

    private final CustomInventoryPlugin plugin;
    private final AttributeHandler attributeHandler;
    private final Set<UUID> pendingRestamp = ConcurrentHashMap.newKeySet();

    public ArmorAttributeListener(CustomInventoryPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.attributeHandler = new AttributeHandler(configManager);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        Player player = event.getPlayer();
        String slotId = "armor_" + event.getSlotType().name().toLowerCase(Locale.ROOT);

        ItemStack oldItem = event.getOldItem();
        ItemStack newItem = event.getNewItem();

        // Only the item's LORE changed, not its fabled payload — this fires
        // constantly because our own tooltip restamp rewrites equipped-armor
        // lore (totals / req strip) every tick the player's stats move. Re-
        // applying here would toggle the attribute, re-trigger a restamp, and
        // spin forever (sword/health flicker while the inventory is open).
        // Skip: the applied contribution is already correct and unchanged.
        Map<String, Integer> oldAttrs = fabledAttrs(oldItem);
        Map<String, Integer> newAttrs = fabledAttrs(newItem);
        if (oldAttrs.equals(newAttrs)) return;

        // Revoke whatever the old piece contributed, then apply the new piece.
        // Same remove-then-apply pattern as the ring slots; idempotent if the
        // event re-fires for the same piece (e.g. on join).
        attributeHandler.removeSlotAttributes(player, slotId);
        if (newItem != null && !newItem.getType().isAir()) {
            attributeHandler.applyGearAttributes(player, newItem, null, slotId);
        }
        scheduleRestamp(player);
    }

    /** The {@code item_fabled_attr_*} bonuses baked into a piece's PDC, if any. */
    private static Map<String, Integer> fabledAttrs(ItemStack item) {
        Map<String, Integer> out = new HashMap<>();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return out;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return out;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            String k = key.getKey();
            if (!k.startsWith("item_fabled_attr_")) continue;
            Integer value = pdc.get(key, PersistentDataType.INTEGER);
            if (value != null && value > 0) out.put(k, value);
        }
        return out;
    }

    /**
     * Fabled fires this from giveAttribute/upAttribute/refunds — covers manual
     * point allocation, ring swaps and our own armor deltas in one place.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttributeChange(PlayerAttributeChangeEvent event) {
        scheduleRestamp(event.getPlayer());
    }

    /** Refresh viewer-dependent tooltip state next tick, coalescing bursts. */
    private void scheduleRestamp(Player player) {
        if (!pendingRestamp.add(player.getUniqueId())) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingRestamp.remove(player.getUniqueId());
            TooltipStyleService service = plugin.getTooltipStyleService();
            if (service != null && player.isOnline()) {
                service.stampPlayer(player);
                // Push the refreshed lore to the client so an already-open
                // inventory / hovered item reflects the new req strip + totals
                // immediately (otherwise it stays stale until the next re-sync).
                player.updateInventory();
            }
        });
    }
}
