package com.example.custominventoryplugin.tooltip;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies tooltip frames on join/pickup/open, and toggles detail lore on F
 * (swap-offhand) while hovering an inventory slot.
 */
public final class TooltipListener implements Listener {

    private final JavaPlugin plugin;
    private final TooltipStyleService service;

    public TooltipListener(JavaPlugin plugin, TooltipStyleService service) {
        this.plugin = plugin;
        this.service = service;
        // Catch-all for unmet items that reach a hand via paths the events below
        // don't cover (auto-pickup, /give, inventory drag). Runs every 5 ticks.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweepHands, 20L, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Delay one tick so synced inventories (MySqlPlayerBridge) settle.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> service.stampPlayer(player), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Item entity = event.getItem();
        ItemStack stack = entity.getItemStack();
        service.stamp(stack, player);
        entity.setItemStack(stack);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> service.stampPlayer(player));
    }

    /**
     * Restamp after plugin GUIs close — ops that mutate inventory gear from a
     * click inside another inventory (e.g. Smithy's Targeting Prism / Target
     * Lock Seal pick GUIs) otherwise leave the gear un-reflowed until the next
     * inventory open.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> service.stampPlayer(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Stamp newly moved/given stacks (itemgen give, shift-clicks, etc.)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack cur = event.getCurrentItem();
            ItemStack curCursor = event.getCursor();
            if (cur != null) service.stamp(cur, player);
            if (curCursor != null) service.stamp(curCursor, player);
        });
    }

    // NOT ignoreCancelled: when the hovered item has an unmet requirement,
    // another plugin (Divinity/Fabled) cancels the offhand swap first — we must
    // still intercept F so the detail page toggles regardless of equippability.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwapOffhand(InventoryClickEvent event) {
        if (event.getClick() != ClickType.SWAP_OFFHAND) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack hovered = event.getCurrentItem();
        if (hovered == null || hovered.getType().isAir()) return;

        // Lazy stamp so freshly given gear gets a detail page before toggle
        service.stamp(hovered, player);
        if (!service.hasDetailPage(hovered)) return;

        event.setCancelled(true);
        if (service.toggleDetailPage(hovered, player)) {
            event.setCurrentItem(hovered);
            player.updateInventory();
        }
    }

    // ─── attribute requirement enforcement (basic) ─────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        ItemStack equipped = event.getNewItem();
        if (equipped == null || equipped.getType().isAir()) return;
        Player player = event.getPlayer();
        String unmet = service.unmetAttrRequirement(equipped, player);
        if (unmet == null) return;

        // Fired after the change: pull the piece back off next tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack current = switch (event.getSlotType()) {
                case HEAD -> player.getInventory().getHelmet();
                case CHEST -> player.getInventory().getChestplate();
                case LEGS -> player.getInventory().getLeggings();
                case FEET -> player.getInventory().getBoots();
                default -> null;
            };
            if (current == null || !current.isSimilar(equipped)) return;
            switch (event.getSlotType()) {
                case HEAD -> player.getInventory().setHelmet(null);
                case CHEST -> player.getInventory().setChestplate(null);
                case LEGS -> player.getInventory().setLeggings(null);
                case FEET -> player.getInventory().setBoots(null);
                default -> { }
            }
            player.getInventory().addItem(current).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            player.sendMessage(Component.text(unmet + " to wear this.", NamedTextColor.RED));
        });
    }

    // Selecting a hotbar slot whose item you don't qualify for: keep the old
    // slot selected so the unmet item never becomes the held item.
    @EventHandler(ignoreCancelled = true)
    public void onSlotChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack next = player.getInventory().getItem(event.getNewSlot());
        String unmet = heldDenial(player, next);
        if (unmet == null) return;
        event.setCancelled(true);
        player.sendActionBar(Component.text(unmet + " to wield this.", NamedTextColor.RED));
    }

    // Swapping an unmet item into either hand.
    @EventHandler(ignoreCancelled = true)
    public void onHandSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (heldDenial(player, event.getMainHandItem()) != null
                || heldDenial(player, event.getOffHandItem()) != null) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("You can't wield that yet.", NamedTextColor.RED));
        }
    }

    /**
     * Move any unmet held item out of both hands into the backpack/inventory
     * (dropped only if the inventory is full). Non-worn gear only — armor is
     * gated by wearing, not holding, so you can still carry it in hand.
     */
    private void sweepHands() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            evictIfUnmet(player, true);
            evictIfUnmet(player, false);
        }
    }

    private void evictIfUnmet(Player player, boolean mainHand) {
        PlayerInventory inv = player.getInventory();
        ItemStack held = mainHand ? inv.getItemInMainHand() : inv.getItemInOffHand();
        if (heldDenial(player, held) == null) return;

        ItemStack moved = held.clone();
        if (mainHand) inv.setItemInMainHand(null);
        else inv.setItemInOffHand(null);

        int slot = firstFreeStorageSlot(inv, mainHand ? inv.getHeldItemSlot() : -1);
        if (slot >= 0) inv.setItem(slot, moved);
        else player.getWorld().dropItemNaturally(player.getLocation(), moved);

        player.sendActionBar(Component.text(heldDenialMessage(player, moved) + " to wield this.",
                NamedTextColor.RED));
    }

    /** Denial message if this item can't be held (unmet, non-worn), else null. */
    private String heldDenial(Player player, ItemStack item) {
        if (item == null || item.getType().isAir() || isWorn(item)) return null;
        return service.unmetAttrRequirement(item, player);
    }

    private String heldDenialMessage(Player player, ItemStack item) {
        String m = service.unmetAttrRequirement(item, player);
        return m == null ? "Requirement not met" : m;
    }

    /** Armor is gated by wearing (PlayerArmorChangeEvent), not by holding. */
    private boolean isWorn(ItemStack item) {
        String m = item.getType().name();
        return m.endsWith("_HELMET") || m.endsWith("_CHESTPLATE")
                || m.endsWith("_LEGGINGS") || m.endsWith("_BOOTS") || m.equals("ELYTRA");
    }

    /** First empty slot in main storage (9-35), then hotbar (0-8) skipping the
     *  held slot, so an evicted main-hand item doesn't bounce back into it. */
    private int firstFreeStorageSlot(PlayerInventory inv, int excludeSlot) {
        for (int i = 9; i <= 35; i++) if (isEmpty(inv.getItem(i))) return i;
        for (int i = 0; i <= 8; i++) if (i != excludeSlot && isEmpty(inv.getItem(i))) return i;
        return -1;
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
