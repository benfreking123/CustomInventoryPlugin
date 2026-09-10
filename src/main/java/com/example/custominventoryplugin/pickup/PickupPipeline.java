package com.example.custominventoryplugin.pickup;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.autoloot.AutoLootConfig;
import com.example.custominventoryplugin.config.BackpackConfig;
import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.config.PickupMode;
import com.example.custominventoryplugin.data.BackpackData;
import com.example.custominventoryplugin.inventory.BackpackInventory;
import com.example.custominventoryplugin.listeners.BackpackListener;
import com.example.custominventoryplugin.settings.BackpackSettingsCache;
import com.example.custominventoryplugin.settings.BagSettings;
import com.example.custominventoryplugin.settings.PlayerPickupSettings;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared back-end for every auto-pickup feed (mob death, block harvest,
 * fishing, ground pickup). A {@link Session} batches per-bag DB IO across a
 * burst of items (e.g. a mob's full drop list) and flushes once.
 *
 * Routing order per item (see design spec):
 *   1. forbidden items are never absorbed (returned to caller)
 *   2. BAG_FIRST / MATCH bags (priority order) take before the inventory
 *   3. the player inventory
 *   4. OVERFLOW bags catch whatever the inventory couldn't hold
 *
 * A bag with an {@code accepts-ids} allow-list is offered only the items on it,
 * regardless of the player's filter or "grab everything" — see
 * {@link Session#accepts}.
 */
public class PickupPipeline {

    private final CustomInventoryPlugin plugin;
    private final BackpackConfig config;
    private final BackpackData data;
    private final BackpackSettingsCache cache;
    private final NamespacedKey markerKey;
    private final AutoLootConfig autoLoot;

    public PickupPipeline(CustomInventoryPlugin plugin, BackpackConfig config, BackpackData data,
                          BackpackSettingsCache cache, NamespacedKey markerKey, AutoLootConfig autoLoot) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
        this.cache = cache;
        this.markerKey = markerKey;
        this.autoLoot = autoLoot;
    }

    /**
     * AutoLoot is the server's drop manager: {@code force-all} makes every feed
     * (mob death, block harvest, fishing, ground) auto-pickup for everyone,
     * overriding the per-player master toggle and the autopickup permission.
     * Player Q-drops stay exempt (see {@code BackpackPickupListener}).
     */
    public boolean masterEnabled(Player player) {
        if (autoLoot != null && autoLoot.isForceAll()) return true;
        if (!player.hasPermission("custominventory.backpack.autopickup")) return false;
        return cache.player(player.getUniqueId()).isMasterEnabled();
    }

    public Session begin(Player player) {
        return new Session(player, false);
    }

    /** Session that routes regardless of the player's master toggle/permission. */
    public Session beginForced(Player player) {
        return new Session(player, true);
    }

    /** Convenience for single-item feeds. Returns the amount absorbed. */
    public int routeSingle(Player player, ItemStack stack) {
        Session s = begin(player);
        int absorbed = s.route(stack);
        s.flush();
        return absorbed;
    }

    // ─────────────────────────────────────────────────────────────────────

    public final class Session {
        private final Player player;
        private final UUID uuid;
        private final PlayerPickupSettings pp;
        private final List<BagCtx> bags = new ArrayList<>();
        private final boolean active;
        private int bagDeposited;

        Session(Player player, boolean force) {
            this.player = player;
            this.uuid = player.getUniqueId();
            this.pp = cache.player(uuid);
            this.active = force || masterEnabled(player);
            if (active) {
                Inventory openTop = openBackpackInventory(player);
                String openId = openBackpackId(player);
                for (BackpackDef def : config.accessible(player)) {
                    BagSettings bs = cache.bag(uuid, def);
                    if (bs.getMode() == PickupMode.OFF) continue;
                    int windowSize = def.windowSize(bs.getTier());
                    int storage = BackpackInventory.storageSize(windowSize);
                    boolean isOpen = def.getId().equals(openId);
                    bags.add(new BagCtx(uuid, def, bs, storage, isOpen ? openTop : null));
                }
            }
        }

        /**
         * Routes one stack. Mutates {@code stack.setAmount(...)} to reflect the
         * leftover, and returns the amount absorbed into bags/inventory.
         */
        public int route(ItemStack stack) {
            if (!active) return 0;
            if (stack == null || stack.getType().isAir()) return 0;
            if (BackpackListener.isForbidden(stack, markerKey)) return 0;

            // Style before anything tries to merge. Both deposit() and
            // addItem() below decide by isSimilar(), which compares the whole
            // item, so a freshly dropped catalyst will not stack with a copy
            // the tooltip pass has already stamped -- that mismatch is why two
            // Chunks of Scrap sat in separate slots. Every pickup path funnels
            // through here, so this is the one place that guarantees both
            // copies are identical before they are compared. Deliberately the
            // viewer-less overload: per-viewer requirement marks would make
            // two players' copies differ again.
            plugin.getTooltipStyleService().stamp(stack);

            int remaining = stack.getAmount();
            int absorbed = 0;

            // Pass A — BAG_FIRST / MATCH bags (take before inventory).
            for (BagCtx bag : bags) {
                if (remaining <= 0) break;
                if (!accepts(bag, stack)) continue;
                PickupMode mode = bag.settings.getMode();
                int dep;
                if (mode == PickupMode.BAG_FIRST) dep = bag.deposit(stack, remaining, true);
                else if (mode == PickupMode.MATCH) dep = bag.deposit(stack, remaining, false);
                else continue;
                remaining -= dep;
                absorbed += dep;
                bagDeposited += dep;
            }

            // Pass B — player inventory. Unidentified gear goes into main
            // storage (slots 9-35) only — vanilla addItem() fills the hotbar
            // first, which swaps a weapon out mid-fight when a drop lands.
            if (remaining > 0) {
                ItemStack toAdd = stack.clone();
                toAdd.setAmount(remaining);
                int before = remaining;
                int left = isUnidentified(toAdd)
                        ? addPreferStorage(player, toAdd)
                        : leftoverCount(player.getInventory().addItem(toAdd));
                int placed = before - left;
                remaining -= placed;
                absorbed += placed;
            }

            // Pass C — OVERFLOW bags catch the remainder.
            for (BagCtx bag : bags) {
                if (remaining <= 0) break;
                if (bag.settings.getMode() != PickupMode.OVERFLOW) continue;
                if (!accepts(bag, stack)) continue;
                int dep = bag.deposit(stack, remaining, true);
                remaining -= dep;
                absorbed += dep;
                bagDeposited += dep;
            }

            stack.setAmount(Math.max(0, remaining));
            return absorbed;
        }

        /**
         * Whether this bag will take the stack during auto-pickup.
         *
         * A restricted bag answers from its own allow-list alone. The per-player
         * material filter and "grab everything" are conveniences the player owns
         * and must not be able to widen what the bag is for; and requiring a
         * material filter as well would mean a restricted bag collected nothing
         * until the player hand-built a filter it does not let them edit.
         */
        private boolean accepts(BagCtx bag, ItemStack stack) {
            if (bag.def.isRestricted()) {
                return !BackpackListener.isRejectedBy(plugin, bag.def, stack);
            }
            return bag.settings.accepts(stack.getType(), pp.isGrabEverything());
        }

        public int bagDeposited() { return bagDeposited; }

        public void flush() {
            for (BagCtx bag : bags) {
                if (bag.dirty) bag.save(uuid);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private final class BagCtx {
        private final UUID owner;
        private final BackpackDef def;
        private final BagSettings settings;
        private final int storageSize;
        private final Inventory live;   // non-null when the player has this bag open
        private ItemStack[] arr;        // lazily loaded when not open
        private boolean dirty;

        BagCtx(UUID owner, BackpackDef def, BagSettings settings, int storageSize, Inventory live) {
            this.owner = owner;
            this.def = def;
            this.settings = settings;
            this.storageSize = storageSize;
            this.live = live;
        }

        private void ensureLoaded() {
            if (live == null && arr == null) {
                arr = data.load(owner, def.getId(), storageSize);
            }
        }

        private ItemStack get(int i) {
            return live != null ? live.getItem(i) : arr[i];
        }

        private void set(int i, ItemStack it) {
            if (live != null) live.setItem(i, it);
            else arr[i] = it;
            dirty = true;
        }

        /**
         * Deposits up to {@code remaining} of {@code stack}. When
         * {@code fillEmpty} is false only existing matching stacks are topped
         * up (MATCH mode). Returns the amount deposited.
         */
        int deposit(ItemStack stack, int remaining, boolean fillEmpty) {
            ensureLoaded();
            int deposited = 0;
            int max = stack.getMaxStackSize();

            // Top up existing similar stacks.
            for (int i = 0; i < storageSize && remaining > 0; i++) {
                ItemStack ex = get(i);
                if (ex == null || ex.getType().isAir()) continue;
                if (!ex.isSimilar(stack)) continue;
                int free = max - ex.getAmount();
                if (free <= 0) continue;
                int take = Math.min(free, remaining);
                ex.setAmount(ex.getAmount() + take);
                set(i, ex);
                remaining -= take;
                deposited += take;
            }

            if (fillEmpty) {
                for (int i = 0; i < storageSize && remaining > 0; i++) {
                    ItemStack ex = get(i);
                    if (ex != null && !ex.getType().isAir()) continue;
                    int take = Math.min(max, remaining);
                    ItemStack copy = stack.clone();
                    copy.setAmount(take);
                    set(i, copy);
                    remaining -= take;
                    deposited += take;
                }
            }
            return deposited;
        }

        void save(UUID uuid) {
            if (live != null) {
                data.saveAll(uuid, def.getId(), live.getContents(), storageSize);
            } else if (arr != null) {
                data.saveAll(uuid, def.getId(), arr, storageSize);
            }
        }
    }

    /**
     * Divinity unidentified drops: obfuscated "Undentified Item" (the live
     * name is misspelled), lore "not known yet", or an item-id starting
     * {@code unid}.
     */
    public boolean isUnidentified(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        String id = plugin.getTooltipStyleService() != null
                ? plugin.getTooltipStyleService().resolveItemId(stack) : null;
        if (id != null && id.toLowerCase().startsWith("unid")) return true;
        if (!stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta.hasDisplayName()) {
            String name = ChatColor.stripColor(meta.getDisplayName());
            if (name != null && name.toLowerCase().contains("ndentified")) return true;
        }
        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                String plain = ChatColor.stripColor(line);
                if (plain != null && plain.toLowerCase().contains("not known yet")) return true;
            }
        }
        return false;
    }

    /**
     * Place into storage slots 9-35 (merge, then empty). Never the hotbar.
     * Returns the amount that did not fit.
     */
    public int addPreferStorage(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return 0;
        PlayerInventory inv = player.getInventory();
        int remaining = stack.getAmount();
        int max = stack.getMaxStackSize();

        for (int i = 9; i <= 35 && remaining > 0; i++) {
            ItemStack ex = inv.getItem(i);
            if (ex == null || ex.getType().isAir() || !ex.isSimilar(stack)) continue;
            int free = max - ex.getAmount();
            if (free <= 0) continue;
            int take = Math.min(free, remaining);
            ex.setAmount(ex.getAmount() + take);
            inv.setItem(i, ex);
            remaining -= take;
        }
        for (int i = 9; i <= 35 && remaining > 0; i++) {
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

    private static int leftoverCount(java.util.Map<Integer, ItemStack> leftover) {
        int left = 0;
        for (ItemStack ls : leftover.values()) left += ls.getAmount();
        return left;
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static Inventory openBackpackInventory(Player player) {
        if (player.getOpenInventory() == null) return null;
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top != null && top.getHolder() instanceof BackpackInventory) return top;
        return null;
    }

    private static String openBackpackId(Player player) {
        Inventory top = openBackpackInventory(player);
        if (top != null && top.getHolder() instanceof BackpackInventory bi) return bi.getDef().getId();
        return null;
    }
}
