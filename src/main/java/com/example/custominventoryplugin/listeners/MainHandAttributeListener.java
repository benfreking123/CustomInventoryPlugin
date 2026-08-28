package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerGearData;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Applies Divinity {@code item_fabled_attr_*} bonuses for BOTH HANDS,
 * completing the set alongside {@link AttributeHandler} for the custom
 * gear-menu slots and {@link ArmorAttributeListener} for vanilla armor.
 * The two hands are separate ledger slots with different qualifying rules —
 * the main hand takes any weapon or tool, the off hand takes wands only — but
 * they reconcile together because one action routinely moves both.
 * Without this every attribute rolled onto a sword, bow or wand is inert:
 * Divinity never calls Fabled's giveAttribute, and Fabled's own lore parsing
 * is off because our tooltip reflow broke its parse.
 *
 * There is no PlayerArmorChangeEvent equivalent for the hand, so the held item
 * is not tracked by an event at all — it is RECONCILED. Every handler below
 * just asks for a reconcile pass; the pass compares the held item's payload
 * against the ledger of what we last gave, and does the smallest correction
 * that makes them agree. Missing an event therefore costs latency, not
 * correctness, and TooltipListener's 5-tick hand sweep is the backstop that
 * covers the paths nothing here names.
 */
public final class MainHandAttributeListener implements Listener {

    /**
     * Ledger slot id, sharing {@code cip_player_slot_attrs} with the gear-menu
     * slots ('0'..'11') and the armor slots ('armor_head'..'armor_feet').
     * Must stay within the column's VARCHAR(16).
     */
    static final String SLOT_ID = "mainhand";

    /** Off-hand ledger slot. Also within VARCHAR(16). */
    public static final String OFFHAND_SLOT_ID = "offhand";

    static final NamespacedKey DIVINITY_ITEM_ID =
            new NamespacedKey("divinity", "item_id");

    /** Same jewelry vocabulary the tooltip service and the gear slots match on. */
    private static final Pattern ACCESSORY_ID =
            Pattern.compile("ring|amulet|bracelet|relic|talisman|charm|necklace");

    /**
     * Divinity item ids are the template's file name — {@code CommonWand},
     * {@code UncommonWand} — so a lowercased substring match covers every wand
     * template including ones not written yet.
     */
    private static final Pattern WAND_ID = Pattern.compile("wand");

    private final CustomInventoryPlugin plugin;
    private final AttributeHandler attributeHandler;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Set<UUID> settling = ConcurrentHashMap.newKeySet();

    public MainHandAttributeListener(CustomInventoryPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.attributeHandler = new AttributeHandler(configManager);
    }

    /**
     * Make the player's granted main-hand attributes match the item actually in
     * their main hand. Safe to call as often as you like — this is the whole
     * design, and the 5-tick sweep calls it for every online player.
     */
    public void reconcile(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();

        // The ledger is what lets us take the bonus back. Grant nothing while it
        // is not in memory (login before the row load, or a load that failed on
        // a SQL error) or the grant becomes permanent: Fabled's invested-points
        // pool would hold points no later pass knows to subtract.
        if (!PlayerGearData.isLoaded(uuid)) return;
        if (settling.contains(uuid)) return;

        reconcileSlot(player, uuid, SLOT_ID,
                player.getInventory().getItemInMainHand(),
                GearAttributes.ofHeld(player.getInventory().getItemInMainHand()));
        reconcileSlot(player, uuid, OFFHAND_SLOT_ID,
                player.getInventory().getItemInOffHand(),
                GearAttributes.ofOffHand(player.getInventory().getItemInOffHand()));
    }

    private void reconcileSlot(Player player, UUID uuid, String slotId,
                               ItemStack held, Map<String, Integer> desired) {
        Map<String, Integer> applied = PlayerGearData.getPlayerSlotAttributes(uuid, slotId);

        // The restamp loop lives here. Our own tooltip pass rewrites held-item
        // lore on every attribute change (totals, req strip), and a lore rewrite
        // is a new ItemStack as far as any equip-style event is concerned. Doing
        // the remove-then-apply on that would move the player's stats, which
        // restamps, which fires again, forever — the flicker ArmorAttributeListener
        // documents. Lore never touches the PDC ints, so an unchanged payload
        // compares equal here and the pass stops.
        if (desired.equals(applied)) return;

        attributeHandler.removeSlotAttributes(player, slotId);
        if (!desired.isEmpty()) {
            attributeHandler.applyGearAttributes(player, held, null, slotId);
        }
    }

    /**
     * Whether an item's attributes are earned by holding it. Armor is earned by
     * wearing it and jewelry by socketing it into a gear-menu slot, so carrying
     * a spare in your fist must grant nothing: otherwise the hand is a seventh,
     * free accessory slot and a way to wear two chestplates' worth of stats.
     * Weapons, wands and tools have no other home, so they qualify.
     */
    static boolean appliesFromHand(String materialName, String divinityItemId) {
        if (materialName == null) return false;
        String material = materialName.toUpperCase(Locale.ROOT);
        if (material.endsWith("_HELMET") || material.endsWith("_CHESTPLATE")
                || material.endsWith("_LEGGINGS") || material.endsWith("_BOOTS")
                || material.equals("ELYTRA")) {
            return false;
        }
        return divinityItemId == null
                || !ACCESSORY_ID.matcher(divinityItemId.toLowerCase(Locale.ROOT)).find();
    }

    /**
     * Whether a Divinity item is a wand, the only weapon class allowed in the
     * off hand. Unlike {@link #appliesFromHand} this fails closed on a null id:
     * the off hand must opt IN, so an item we cannot identify contributes
     * nothing rather than defaulting to allowed.
     */
    static boolean isWand(String divinityItemId) {
        return divinityItemId != null
                && WAND_ID.matcher(divinityItemId.toLowerCase(Locale.ROOT)).find();
    }

    // ─── the ways a held item changes ──────────────────────────────────────
    //
    // Every one of these reconciles NEXT tick rather than reading the event's
    // own item, because they all fire before the hand settles: the held slot
    // has not moved yet on PlayerItemHeldEvent, the stack is still in hand on
    // PlayerDropItemEvent, the inventory is still full on PlayerDeathEvent. A
    // next-tick read also needs no special case for a cancelled event — the
    // TooltipListener requirement check cancels hotbar selects and hand swaps,
    // and the settled hand is simply the old one.

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerItemHeldEvent event) {
        scheduleReconcile(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHandSwap(PlayerSwapHandItemsEvent event) {
        scheduleReconcile(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) scheduleReconcile(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) scheduleReconcile(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        scheduleReconcile(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) scheduleReconcile(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        scheduleReconcile(event.getPlayer());
    }

    /** Death empties the hand next tick (unless keepInventory), so revoke there. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        scheduleReconcile(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        scheduleReconcile(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        scheduleReconcile(event.getPlayer());
    }

    /**
     * Login. Held-item state is restored asynchronously by MySqlPlayerBridge, so
     * hold every pass off for a second: reconciling against a half-restored
     * inventory would revoke the weapon the player is still holding and re-grant
     * it a moment later, two pointless writes and a visible stat dip per login.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        settling.add(uuid);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            settling.remove(uuid);
            // Audit BEFORE reconciling. Reconcile is a repair, so running it
            // first would quietly erase the evidence — the ledger would already
            // agree with the item by the time anyone looked, and a leak that
            // recurs every login would never be reported once.
            AttributeAuditService.warnOnDrift(player, plugin.getLogger(), "login");
            reconcile(player);
        }, 20L);
    }

    /**
     * Logout revokes NOTHING, deliberately. Both halves of the bookkeeping
     * outlive the session — the ledger in cip_player_slot_attrs and the points
     * in Fabled's own SQL store — so leaving them alone is what keeps them
     * agreeing, and the first pass after the next login is a no-op for a player
     * who logs back in holding the same sword. Revoking here would race Fabled's
     * own save: if it has already written the player out, the subtraction is
     * lost from the pool while the ledger delete still commits, and the bonus
     * becomes permanent. Only the in-memory scheduling state is dropped, so a
     * queued pass cannot run against a player whose rows have been unloaded.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pending.remove(uuid);
        settling.remove(uuid);
    }

    /** Reconcile next tick, coalescing the burst of events one action fires. */
    private void scheduleReconcile(Player player) {
        UUID uuid = player.getUniqueId();
        if (!pending.add(uuid)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(uuid);
            reconcile(player);
        });
    }
}
