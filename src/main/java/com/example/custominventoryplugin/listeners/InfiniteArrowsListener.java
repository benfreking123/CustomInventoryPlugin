package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Vanilla bows will not draw with an empty quiver (except creative). Dart
 * already fires a Particle Projectile and does not use arrows; this covers
 * the remaining right-click bow / crossbow shots.
 *
 * Keep one tagged dummy arrow in the inventory when the player has no other
 * ammo, and never consume the shot. The dummy is locked to the player
 * inventory so it cannot be dropped, stored, or looted.
 */
public final class InfiniteArrowsListener implements Listener {

    static final String KEY_NAME = "infinite_arrow";

    private final CustomInventoryPlugin plugin;
    private final NamespacedKey key;

    public InfiniteArrowsListener(CustomInventoryPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, KEY_NAME);
    }

    public static boolean isDummy(ItemStack item) {
        if (item == null || item.getType() != Material.ARROW || !item.hasItemMeta()) {
            return false;
        }
        for (NamespacedKey k : item.getItemMeta().getPersistentDataContainer().getKeys()) {
            if (KEY_NAME.equals(k.getKey())) return true;
        }
        return false;
    }

    private boolean isAmmo(ItemStack item) {
        if (item == null) return false;
        Material type = item.getType();
        return type == Material.ARROW
                || type == Material.SPECTRAL_ARROW
                || type == Material.TIPPED_ARROW;
    }

    private boolean isRangedWeapon(ItemStack item) {
        if (item == null) return false;
        Material type = item.getType();
        return type == Material.BOW || type == Material.CROSSBOW;
    }

    private ItemStack dummyArrow() {
        ItemStack stack = new ItemStack(Material.ARROW, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        meta.displayName(Component.text("Arrow")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                Component.text("Does not consume.")
                        .color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        stack.setItemMeta(meta);
        return stack;
    }

    void ensureArrow(Player player) {
        if (player == null || !player.isOnline()) return;
        PlayerInventory inv = player.getInventory();
        boolean hasReal = false;
        java.util.List<Integer> dummySlots = new java.util.ArrayList<>();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (!isAmmo(stack)) continue;
            if (isDummy(stack)) dummySlots.add(i);
            else hasReal = true;
        }
        ItemStack off = inv.getItemInOffHand();
        if (isAmmo(off) && !isDummy(off)) hasReal = true;

        if (hasReal) {
            for (int slot : dummySlots) inv.setItem(slot, null);
            if (isDummy(off)) inv.setItemInOffHand(null);
            return;
        }
        if (!dummySlots.isEmpty() || isDummy(off)) return;
        ItemStack dummy = dummyArrow();
        java.util.HashMap<Integer, ItemStack> leftover = inv.addItem(dummy);
        if (!leftover.isEmpty() && (off == null || off.getType().isAir())) {
            inv.setItemInOffHand(dummy);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> ensureArrow(event.getPlayer()), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin,
                () -> ensureArrow(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        ensureArrow(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        ItemStack next = event.getPlayer().getInventory().getItem(event.getNewSlot());
        if (isRangedWeapon(next)) ensureArrow(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isRangedWeapon(event.getMainHandItem()) || isRangedWeapon(event.getOffHandItem())) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> ensureArrow(event.getPlayer()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        event.setConsumeItem(false);
        plugin.getServer().getScheduler().runTask(plugin, () -> ensureArrow(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isDummy(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean dummy = isDummy(current) || isDummy(cursor);
        if (!dummy) return;
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == InventoryAction.DROP_ALL_SLOT
                || event.getAction() == InventoryAction.DROP_ONE_SLOT
                || event.getAction() == InventoryAction.DROP_ALL_CURSOR
                || event.getAction() == InventoryAction.DROP_ONE_CURSOR) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() != null
                && event.getClickedInventory() != player.getInventory()
                && (isDummy(cursor) || isDummy(current))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!isDummy(event.getOldCursor()) && !isDummy(event.getCursor())) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(InfiniteArrowsListener::isDummy);
        PlayerInventory inv = event.getEntity().getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isDummy(contents[i])) inv.setItem(i, null);
        }
    }
}
