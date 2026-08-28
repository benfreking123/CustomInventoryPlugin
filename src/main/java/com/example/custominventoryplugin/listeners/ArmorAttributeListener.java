package com.example.custominventoryplugin.listeners;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerGearData;
import com.example.custominventoryplugin.tooltip.TooltipStyleService;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import studio.magemonkey.fabled.api.event.PlayerAttributeChangeEvent;

/**
 * Applies Divinity {@code item_fabled_attr_*} bonuses for VANILLA armor slots,
 * mirroring what {@link AttributeHandler} does for the custom gear-menu slots
 * (rings/accessories) and {@link MainHandAttributeListener} does for the held
 * weapon. Nothing else on the server applies these: Divinity never calls
 * Fabled's giveAttribute and Fabled's own lore parsing is disabled.
 *
 * Also re-stamps tooltips whenever the player's Fabled attributes change, so
 * the "(total)" figures on stat lines and the req strip don't go stale.
 */
public final class ArmorAttributeListener implements Listener {

    /**
     * Ledger slot ids for the four vanilla armor slots, in the order
     * {@link org.bukkit.inventory.PlayerInventory#getArmorContents()} returns
     * them: boots, leggings, chestplate, helmet.
     */
    static final String[] ARMOR_SLOT_IDS = {"armor_feet", "armor_legs", "armor_chest", "armor_head"};

    private final CustomInventoryPlugin plugin;
    private final AttributeHandler attributeHandler;
    private final Set<UUID> pendingRestamp = ConcurrentHashMap.newKeySet();
    private final Set<UUID> settling = ConcurrentHashMap.newKeySet();

    public ArmorAttributeListener(CustomInventoryPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.attributeHandler = new AttributeHandler(configManager);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        Player player = event.getPlayer();
        if (settling.contains(player.getUniqueId())) return;
        String slotId = "armor_" + event.getSlotType().name().toLowerCase(Locale.ROOT);

        ItemStack oldItem = event.getOldItem();
        ItemStack newItem = event.getNewItem();

        // Only the item's LORE changed, not its fabled payload — this fires
        // constantly because our own tooltip restamp rewrites equipped-armor
        // lore (totals / req strip) every tick the player's stats move. Re-
        // applying here would toggle the attribute, re-trigger a restamp, and
        // spin forever (sword/health flicker while the inventory is open).
        // Skip: the applied contribution is already correct and unchanged.
        Map<String, Integer> oldAttrs = GearAttributes.of(oldItem);
        Map<String, Integer> newAttrs = GearAttributes.of(newItem);
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

    /**
     * Make the four armor ledger rows agree with what the player is actually
     * wearing, the same way the main hand reconciles.
     *
     * PlayerArmorChangeEvent alone is not enough. It only fires on a CHANGE, so
     * a player who logs in already wearing a helmet is never re-read, and the
     * bonus is only correct as long as Fabled's persisted points happen to still
     * match the ledger. Anything that breaks that agreement while they are
     * offline — an admin wipe, a rollback, a piece removed by another plugin, a
     * crash between the two SQL writes — is silent and permanent, because
     * armor is the one grant path with no way back to a known-good state.
     */
    public void reconcile(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        // Same rule as the hand: no ledger in memory means no way to take a
        // grant back, so granting now would make it permanent.
        if (!PlayerGearData.isLoaded(uuid)) return;

        ItemStack[] worn = player.getInventory().getArmorContents();
        for (int i = 0; i < ARMOR_SLOT_IDS.length; i++) {
            String slotId = ARMOR_SLOT_IDS[i];
            ItemStack piece = i < worn.length ? worn[i] : null;
            Map<String, Integer> desired = GearAttributes.of(piece);
            Map<String, Integer> applied = PlayerGearData.getPlayerSlotAttributes(uuid, slotId);
            if (desired.equals(applied)) continue;

            attributeHandler.removeSlotAttributes(player, slotId);
            if (!desired.isEmpty()) {
                attributeHandler.applyGearAttributes(player, piece, null, slotId);
            }
        }
    }

    /**
     * Login. Armor is restored asynchronously like the held item, so hold the
     * first pass off for a second — reconciling against a half-restored
     * inventory would revoke the chestplate the player is still wearing and
     * re-grant it a moment later. The settle flag also suppresses the
     * PlayerArmorChangeEvent burst the restore itself fires, which would
     * otherwise race this pass.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        settling.add(uuid);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            settling.remove(uuid);
            reconcile(player);
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Same reasoning as the hand: revoke nothing, both halves of the
        // bookkeeping outlive the session. Only drop scheduling state.
        UUID uuid = event.getPlayer().getUniqueId();
        pendingRestamp.remove(uuid);
        settling.remove(uuid);
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
