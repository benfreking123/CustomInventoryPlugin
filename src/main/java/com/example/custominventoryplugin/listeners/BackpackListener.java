package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.config.PickupMode;
import com.example.custominventoryplugin.data.BackpackData;
import com.example.custominventoryplugin.inventory.BackpackControlBar;
import com.example.custominventoryplugin.inventory.BackpackFilterInventory;
import com.example.custominventoryplugin.inventory.BackpackInventory;
import com.example.custominventoryplugin.inventory.BackpackListInventory;
import com.example.custominventoryplugin.settings.BagSettings;
import com.example.custominventoryplugin.settings.PlayerPickupSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Owns click/drag/close handling for backpack windows, the {@code /bp} list
 * GUI (master toggles), and the filter editor.
 *
 * Persistence is storage-bounded: only slots {@code [0, storageSize)} are
 * saved, so the control-bar buttons in the bottom row never leak into storage.
 */
public class BackpackListener implements Listener {

    private static final Set<Material> FORBIDDEN_MATERIALS = Set.of(
            Material.SHULKER_BOX,
            Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX, Material.MAGENTA_SHULKER_BOX,
            Material.LIGHT_BLUE_SHULKER_BOX, Material.YELLOW_SHULKER_BOX, Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX, Material.LIGHT_GRAY_SHULKER_BOX,
            Material.CYAN_SHULKER_BOX, Material.PURPLE_SHULKER_BOX, Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX, Material.RED_SHULKER_BOX,
            Material.BLACK_SHULKER_BOX,
            Material.BUNDLE
    );

    public static final String MARKER_KEY = "backpack_item";

    private final CustomInventoryPlugin plugin;
    private final BackpackData data;
    private final NamespacedKey markerKey;

    public BackpackListener(CustomInventoryPlugin plugin, BackpackData data) {
        this.plugin = plugin;
        this.data = data;
        this.markerKey = new NamespacedKey(plugin, MARKER_KEY);
    }

    public static boolean isForbidden(ItemStack item, NamespacedKey markerKey) {
        if (item == null || item.getType().isAir()) return false;
        if (FORBIDDEN_MATERIALS.contains(item.getType())) return true;
        if (InfiniteArrowsListener.isDummy(item)) return true;
        if (item.hasItemMeta() && item.getItemMeta() != null) {
            return item.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
        }
        return false;
    }

    /**
     * Whether this specific bag refuses the stack.
     *
     * Companion to {@link #isForbidden}, which is global — no bag holds a
     * shulker. This one is per-bag: only the currency pouch holds currency.
     * Every path that can put an item into a bag (click, drag, sweep,
     * auto-pickup) checks both, because a restriction enforced on some paths is
     * not a restriction.
     *
     * Item identity comes from Divinity via the tooltip service, so a renamed
     * vanilla item is distinguished from the plain one it is built on.
     */
    public static boolean isRejectedBy(CustomInventoryPlugin plugin, BackpackDef def, ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (def == null || !def.isRestricted()) return false;
        return !def.acceptsId(plugin.getTooltipStyleService().resolveItemId(item));
    }

    // ─── backpack window clicks ───────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackInventory holder)) return;
        Player player = (Player) event.getWhoClicked();
        int storageSize = holder.getStorageSize();
        Inventory inv = holder.getInventory();

        // Control-bar clicks: cancel and dispatch the button's action.
        if (holder.hasControlBar()
                && event.getClickedInventory() == inv
                && event.getRawSlot() >= storageSize) {
            event.setCancelled(true);
            BackpackControlBar.Action action = BackpackControlBar.actionOf(plugin, event.getCurrentItem());
            if (action != null) handleControlAction(player, holder, action);
            return;
        }

        ClickType ct = event.getClick();
        if (ct == ClickType.NUMBER_KEY || ct == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
        }

        if (isDepositIntoBackpack(event, inv)) {
            ItemStack incoming = ct.isShiftClick() ? event.getCurrentItem() : event.getCursor();
            if (isForbidden(incoming, markerKey)) {
                event.setCancelled(true);
                player.sendActionBar(Component.text("\u00a7cThis item can't go in a backpack."));
                return;
            }
            if (isRejectedBy(plugin, holder.getDef(), incoming)) {
                event.setCancelled(true);
                player.sendActionBar(Component.text("\u00a7cThis bag won't hold that."));
                return;
            }
        }

        UUID uuid = player.getUniqueId();
        String backpackId = holder.getDef().getId();
        plugin.getServer().getScheduler().runTask(plugin,
                () -> data.saveAll(uuid, backpackId, inv.getContents(), storageSize));
    }

    private boolean isDepositIntoBackpack(InventoryClickEvent event, Inventory backpackInv) {
        InventoryAction action = event.getAction();
        boolean clickedTop = event.getClickedInventory() == backpackInv;
        boolean shift = event.getClick().isShiftClick();
        if (shift && !clickedTop) return true;
        if (clickedTop && !shift) {
            return action == InventoryAction.PLACE_ALL
                || action == InventoryAction.PLACE_ONE
                || action == InventoryAction.PLACE_SOME
                || action == InventoryAction.SWAP_WITH_CURSOR
                || action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.HOTBAR_MOVE_AND_READD;
        }
        return false;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackInventory holder)) return;
        Player player = (Player) event.getWhoClicked();
        int storageSize = holder.getStorageSize();
        Inventory inv = holder.getInventory();

        // Block drags that touch the control bar, or that carry an item this bag
        // won't hold — forbidden anywhere, or outside this bag's allow-list.
        boolean touchesControl = holder.hasControlBar() && event.getRawSlots().stream()
                .anyMatch(slot -> slot >= storageSize && slot < inv.getSize());
        boolean touchesStorage = event.getRawSlots().stream().anyMatch(slot -> slot < storageSize);
        ItemStack dragged = event.getOldCursor();
        boolean refused = touchesStorage
                && (isForbidden(dragged, markerKey) || isRejectedBy(plugin, holder.getDef(), dragged));
        if (touchesControl || refused) {
            event.setCancelled(true);
            return;
        }

        UUID uuid = player.getUniqueId();
        String backpackId = holder.getDef().getId();
        plugin.getServer().getScheduler().runTask(plugin,
                () -> data.saveAll(uuid, backpackId, inv.getContents(), storageSize));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackInventory holder)) return;
        Player player = (Player) event.getPlayer();
        data.saveAll(player.getUniqueId(), holder.getDef().getId(),
                holder.getInventory().getContents(), holder.getStorageSize());
    }

    // ─── control-bar actions ──────────────────────────────────────────────

    private void handleControlAction(Player player, BackpackInventory holder, BackpackControlBar.Action action) {
        UUID uuid = player.getUniqueId();
        BackpackDef def = holder.getDef();
        BagSettings bs = plugin.getSettingsCache().bag(uuid, def);
        Inventory inv = holder.getInventory();

        switch (action) {
            case MODE -> {
                bs.setMode(bs.getMode().next());
                plugin.getSettingsCache().saveBag(uuid, def.getId());
                BackpackControlBar.render(plugin, inv, def, bs);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
            }
            case GRAB -> {
                bs.setGrabEverything(!bs.isGrabEverything());
                plugin.getSettingsCache().saveBag(uuid, def.getId());
                BackpackControlBar.render(plugin, inv, def, bs);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
            }
            case FILTER -> {
                if (!def.isFilterable()) return;
                player.closeInventory();
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.openInventory(new BackpackFilterInventory(plugin, player, def).getInventory()));
            }
            case DEPOSIT -> {
                int moved = quickDeposit(player, holder, bs);
                if (moved > 0) {
                    data.saveAll(uuid, def.getId(), inv.getContents(), holder.getStorageSize());
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.1f);
                } else {
                    player.sendActionBar(Component.text("\u00a77Nothing to deposit."));
                }
            }
            case SORT -> {
                sortStorage(inv, holder.getStorageSize());
                data.saveAll(uuid, def.getId(), inv.getContents(), holder.getStorageSize());
                player.playSound(player.getLocation(), Sound.BLOCK_BARREL_CLOSE, 0.6f, 1.4f);
            }
            case TIER -> attemptUpgrade(player, holder, bs);
            case FILLER -> { /* no-op */ }
        }
    }

    /** Moves filter-matching items from the player's inventory into the bag storage. */
    private int quickDeposit(Player player, BackpackInventory holder, BagSettings bs) {
        Inventory inv = holder.getInventory();
        int storageSize = holder.getStorageSize();
        boolean playerGrab = plugin.getSettingsCache().player(player.getUniqueId()).isGrabEverything();
        int moved = 0;

        ItemStack[] playerContents = player.getInventory().getStorageContents();
        for (int s = 0; s < playerContents.length; s++) {
            ItemStack it = playerContents[s];
            if (it == null || it.getType().isAir()) continue;
            if (isForbidden(it, markerKey)) continue;
            // A restricted bag's allow-list outranks every convenience toggle
            // below, including "grab everything".
            if (isRejectedBy(plugin, holder.getDef(), it)) continue;

            boolean accept = playerGrab || bs.isGrabEverything()
                    || (!bs.getFilter().isEmpty() && bs.getFilter().contains(it.getType()))
                    || hasMatchInStorage(inv, storageSize, it)
                    // A restricted bag has already said yes by accepting the id;
                    // requiring a material filter too would mean it collected
                    // nothing until the player hand-built a filter it cannot edit.
                    || holder.getDef().isRestricted();
            if (!accept) continue;

            int remaining = it.getAmount();
            remaining = depositIntoStorage(inv, storageSize, it, remaining);
            int placed = it.getAmount() - remaining;
            if (placed > 0) {
                moved += placed;
                if (remaining <= 0) player.getInventory().setItem(s, null);
                else { it.setAmount(remaining); player.getInventory().setItem(s, it); }
            }
        }
        return moved;
    }

    private boolean hasMatchInStorage(Inventory inv, int storageSize, ItemStack stack) {
        for (int i = 0; i < storageSize; i++) {
            ItemStack ex = inv.getItem(i);
            if (ex != null && ex.isSimilar(stack)) return true;
        }
        return false;
    }

    /** Returns the leftover amount that did not fit. */
    private int depositIntoStorage(Inventory inv, int storageSize, ItemStack stack, int remaining) {
        int max = stack.getMaxStackSize();
        for (int i = 0; i < storageSize && remaining > 0; i++) {
            ItemStack ex = inv.getItem(i);
            if (ex == null || ex.getType().isAir() || !ex.isSimilar(stack)) continue;
            int free = max - ex.getAmount();
            if (free <= 0) continue;
            int take = Math.min(free, remaining);
            ex.setAmount(ex.getAmount() + take);
            inv.setItem(i, ex);
            remaining -= take;
        }
        for (int i = 0; i < storageSize && remaining > 0; i++) {
            ItemStack ex = inv.getItem(i);
            if (ex != null && !ex.getType().isAir()) continue;
            int take = Math.min(max, remaining);
            ItemStack copy = stack.clone();
            copy.setAmount(take);
            inv.setItem(i, copy);
            remaining -= take;
        }
        return remaining;
    }

    /** Merge same-type stacks and sort the storage region by material, in place. */
    private void sortStorage(Inventory inv, int storageSize) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < storageSize; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) items.add(it.clone());
            inv.setItem(i, null);
        }
        // Merge stacks of the same item.
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack it : items) {
            boolean done = false;
            for (ItemStack m : merged) {
                if (m.isSimilar(it) && m.getAmount() < m.getMaxStackSize()) {
                    int free = m.getMaxStackSize() - m.getAmount();
                    int take = Math.min(free, it.getAmount());
                    m.setAmount(m.getAmount() + take);
                    it.setAmount(it.getAmount() - take);
                    if (it.getAmount() <= 0) { done = true; break; }
                }
            }
            if (!done && it.getAmount() > 0) merged.add(it);
        }
        merged.sort(Comparator
                .comparing((ItemStack it) -> it.getType().name())
                .thenComparing(it -> -it.getAmount()));
        for (int i = 0; i < merged.size() && i < storageSize; i++) {
            inv.setItem(i, merged.get(i));
        }
    }

    private void attemptUpgrade(Player player, BackpackInventory holder, BagSettings bs) {
        UUID uuid = player.getUniqueId();
        BackpackDef def = holder.getDef();
        int maxTier = def.maxTier();
        if (bs.getTier() >= maxTier) {
            player.sendActionBar(Component.text("\u00a7eThis backpack is already at max tier."));
            return;
        }
        if (!player.hasPermission("custominventory.backpack.upgrade")) {
            player.sendActionBar(Component.text("\u00a7cYou don't have permission to upgrade backpacks."));
            return;
        }
        double cost = def.getUpgradeCost() * (bs.getTier() + 1);
        if (cost > 0) {
            if (!plugin.getEconomyHook().isAvailable()) {
                player.sendActionBar(Component.text("\u00a7cEconomy unavailable — can't purchase upgrades."));
                return;
            }
            if (!plugin.getEconomyHook().has(player, cost)) {
                player.sendActionBar(Component.text("\u00a7cYou need \u00a7a$" + (long) cost + "\u00a7c to upgrade."));
                return;
            }
            if (!plugin.getEconomyHook().withdraw(player, cost)) {
                player.sendActionBar(Component.text("\u00a7cPayment failed."));
                return;
            }
        }
        bs.setTier(bs.getTier() + 1);
        plugin.getSettingsCache().saveBag(uuid, def.getId());
        // Persist current storage first, then reopen at the new (larger) window size.
        data.saveAll(uuid, def.getId(), holder.getInventory().getContents(), holder.getStorageSize());
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        player.sendMessage("\u00a7aUpgraded \u00a7f" + def.getId() + "\u00a7a to tier \u00a7f" + bs.getTier() + "\u00a7a.");
        player.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> BackpackInventory.open(plugin, player, def));
    }

    // ─── /bp list (icons + master toggles) ────────────────────────────────

    @EventHandler
    public void onListClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackListInventory list)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != list.getInventory()) return;

        // Master toggle buttons.
        ItemStack clicked = event.getCurrentItem();
        if (clicked != null && clicked.hasItemMeta()) {
            String master = clicked.getItemMeta().getPersistentDataContainer()
                    .get(new NamespacedKey(plugin, BackpackListInventory.MASTER_KEY), PersistentDataType.STRING);
            if (master != null) {
                handleMasterToggle(player, list, master);
                return;
            }
        }

        String backpackId = list.getBackpackIdAtSlot(event.getRawSlot());
        if (backpackId == null) return;
        player.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> openBackpack(player, backpackId));
    }

    private void handleMasterToggle(Player player, BackpackListInventory list, String master) {
        UUID uuid = player.getUniqueId();
        PlayerPickupSettings pp = plugin.getSettingsCache().player(uuid);
        if (BackpackListInventory.MASTER_ENABLED.equals(master)) {
            pp.setMasterEnabled(!pp.isMasterEnabled());
        } else if (BackpackListInventory.MASTER_GRAB.equals(master)) {
            pp.setGrabEverything(!pp.isGrabEverything());
        } else {
            return;
        }
        plugin.getSettingsCache().savePlayer(uuid);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        // Re-open the list to reflect the new toggle state.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            NamespacedKey iconKey = new NamespacedKey(plugin,
                    com.example.custominventoryplugin.inventory.GearInventory.BACKPACK_BUTTON_KEY);
            player.openInventory(new BackpackListInventory(
                    plugin, player, plugin.getBackpackConfig(), data, iconKey).getInventory());
        });
    }

    @EventHandler
    public void onListDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackListInventory)) return;
        event.setCancelled(true);
    }

    private void openBackpack(Player player, String backpackId) {
        var cfg = plugin.getBackpackConfig();
        if (cfg == null) return;
        BackpackDef def = cfg.get(backpackId);
        if (def == null || !def.canAccess(player)) {
            player.sendMessage("\u00a7cYou don't have access to that backpack.");
            return;
        }
        BackpackInventory.open(plugin, player, def);
    }

    // ─── filter editor ────────────────────────────────────────────────────

    @EventHandler
    public void onFilterClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackFilterInventory holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        UUID uuid = player.getUniqueId();
        BackpackDef def = holder.getDef();
        BagSettings bs = plugin.getSettingsCache().bag(uuid, def);

        if (event.getClickedInventory() == holder.getInventory()) {
            // Top: clicking a control button or a filter icon.
            BackpackFilterInventory.Control ctl = holder.controlAt(event.getRawSlot());
            if (ctl == BackpackFilterInventory.Control.DONE) {
                player.closeInventory();
                plugin.getServer().getScheduler().runTask(plugin, () -> BackpackInventory.open(plugin, player, def));
                return;
            }
            if (ctl == BackpackFilterInventory.Control.CLEAR) {
                bs.getFilter().clear();
                plugin.getSettingsCache().saveBag(uuid, def.getId());
                holder.render(bs);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                return;
            }
            Material mat = holder.materialAt(event.getRawSlot());
            if (mat != null) {
                bs.getFilter().remove(mat);
                plugin.getSettingsCache().saveBag(uuid, def.getId());
                holder.render(bs);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.9f);
            }
            return;
        }

        // Bottom (player inventory): toggle the clicked item's material.
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        if (isForbidden(clicked, markerKey)) return;
        Material mat = clicked.getType();
        if (bs.getFilter().contains(mat)) bs.getFilter().remove(mat);
        else bs.getFilter().add(mat);
        plugin.getSettingsCache().saveBag(uuid, def.getId());
        holder.render(bs);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    @EventHandler
    public void onFilterDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackFilterInventory)) return;
        event.setCancelled(true);
    }

    public NamespacedKey getMarkerKey() { return markerKey; }
}
