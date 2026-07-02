package com.example.custominventoryplugin.groupdrop;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * WYSIWYG editor window for a group drop. Slots 0..44 are the option grid (drop
 * an item to create an option at that slot; shift-click an option to edit its
 * bundle). Slots 45..53 are the control bar.
 *
 * The editor reads/writes a {@link GroupDropEditSession#draft}; the listener
 * persists on a clean close.
 */
public class GroupDropEditor implements InventoryHolder {

    public static final int SIZE             = 54;
    public static final int OPTION_AREA_MAX  = 44;   // slots 0..44 hold options

    public static final int SLOT_PICKS       = 45;
    public static final int SLOT_DISTINCT    = 46;
    public static final int SLOT_CLAIM       = 47;
    public static final int SLOT_TOKEN       = 48;
    public static final int SLOT_TOKEN_ICON  = 49;   // placeable
    public static final int SLOT_SAVE        = 52;
    public static final int SLOT_CANCEL      = 53;

    private final Player player;
    private final GroupDropEditSession session;
    private final Inventory inventory;

    public GroupDropEditor(Player player, GroupDropEditSession session) {
        this.player = player;
        this.session = session;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Text.title("&8Edit: &b" + session.draft.getId()));
        render();
    }

    public void render() {
        inventory.clear();
        GroupDrop g = session.draft;

        // Render CLEAN icons (no injected lore) so save/reload round-trips don't
        // accumulate hint text. Bundle/command info is surfaced in the sub-editor
        // and via the opening chat hint instead.
        for (GroupDropOption opt : g.getOptions().values()) {
            if (opt.getSlot() < 0 || opt.getSlot() > OPTION_AREA_MAX) continue;
            ItemStack icon = (opt.getIcon() != null && !opt.getIcon().getType().isAir())
                    ? opt.getIcon().clone() : new ItemStack(Material.CHEST);
            inventory.setItem(opt.getSlot(), icon);
        }

        inventory.setItem(SLOT_PICKS, control(Material.COMPARATOR,
                "&ePicks: &f" + g.getPicks(),
                "&7Left-click &a+1&7, right-click &c-1",
                "&7(effective: " + g.effectivePicks() + " of " + g.optionCount() + ")"));

        inventory.setItem(SLOT_DISTINCT, control(g.isDistinct() ? Material.LIME_DYE : Material.GRAY_DYE,
                "&eDistinct picks: " + (g.isDistinct() ? "&aON" : "&cOFF"),
                "&7Click to toggle.",
                "&7ON = can't pick the same option twice."));

        inventory.setItem(SLOT_CLAIM, control(Material.WRITABLE_BOOK,
                "&eClaim mode: &f" + g.getClaimMode().name().toLowerCase(),
                "&7Click to toggle.",
                "&7once = one claim per player ever",
                "&7repeatable = re-openable any time"));

        inventory.setItem(SLOT_TOKEN, control(g.isTokenEnabled() ? Material.SUNFLOWER : Material.GRAY_DYE,
                "&eToken: " + (g.isTokenEnabled() ? "&aON" : "&cOFF"),
                "&7Click to toggle redeemable tokens.",
                "&7Mint with &f/groupdrop token give " + g.getId()));

        if (g.isTokenEnabled()) {
            ItemStack tok = g.getTokenIcon();
            if (tok != null && !tok.getType().isAir()) {
                inventory.setItem(SLOT_TOKEN_ICON, tok.clone());
            } else {
                inventory.setItem(SLOT_TOKEN_ICON, control(Material.NAME_TAG,
                        "&eToken icon",
                        "&7Place an item here to use it",
                        "&7as the token's appearance."));
            }
        } else {
            inventory.setItem(SLOT_TOKEN_ICON, filler());
        }

        inventory.setItem(50, filler());
        inventory.setItem(51, filler());

        inventory.setItem(SLOT_SAVE, control(Material.EMERALD_BLOCK,
                "&aSAVE & CLOSE", "&7Persist this group drop."));
        inventory.setItem(SLOT_CANCEL, control(Material.BARRIER,
                "&cCANCEL", "&7Discard changes since open."));
    }

    private ItemStack control(Material mat, String name, String... loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.c(name));
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(Text.c(l));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack filler() {
        ItemStack it = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.c(" "));
            it.setItemMeta(meta);
        }
        return it;
    }

    /**
     * Rebuild draft.options from the current option-grid contents, preserving
     * bundles/commands/labels for slots that still hold an item. Also captures
     * the token icon from its placeable slot.
     */
    public void syncFromInventory() {
        GroupDrop g = session.draft;
        java.util.Map<Integer, GroupDropOption> rebuilt = new java.util.LinkedHashMap<>();
        for (int slot = 0; slot <= OPTION_AREA_MAX; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            GroupDropOption existing = g.getOption(slot);
            GroupDropOption opt = (existing != null) ? existing : new GroupDropOption(slot, item.clone());
            opt.setSlot(slot);
            opt.setIcon(item.clone());
            rebuilt.put(slot, opt);
        }
        g.getOptions().clear();
        g.getOptions().putAll(rebuilt);

        if (g.isTokenEnabled()) {
            ItemStack tok = inventory.getItem(SLOT_TOKEN_ICON);
            if (tok != null && !tok.getType().isAir() && tok.getType() != Material.NAME_TAG) {
                g.setTokenIcon(tok.clone());
            }
        }
    }

    public GroupDropEditSession getSession() { return session; }
    public Player getPlayer()                { return player; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }
}
