package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.groupdrop.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collections browser, sharing the bestiary's slot map so the two pages feel
 * like one book (over its own frame — see {@code collections_bg}). Left column
 * = category tabs, right 5x4 grid = entries for the current tab and page.
 *
 * Unlike the bestiary, nothing is hidden: an entry the player has not obtained
 * still occupies its cell, drawn with a uniform "?" placeholder icon. A
 * collection is a checklist, so its value is showing what is still out there —
 * and with most gems sharing the NETHER_STAR icon, swapping the icon rather
 * than only a lore line is the only way the two states read apart at a glance.
 *
 * Sets are one tile per set showing element progress rather than one tile per
 * piece — four near-identical armour icons read as noise.
 */
public class CollectionsInventory implements InventoryHolder {

    public static final String ACTION_KEY = "cmp_collections";
    public static final String ACT_ENTRY = "entry:";
    public static final String ACT_CAT = "cat:";
    public static final String ACT_PREV = "prev";
    public static final String ACT_NEXT = "next";
    public static final String ACT_CLOSE = "close";
    public static final String ACT_ACCOUNT = "account";

    private static final int[][] TABS = {
            {0, 1, 2}, {9, 10, 11}, {18, 19, 20}, {27, 28, 29}};
    private static final int[] GRID = {
            4, 5, 6, 7, 8,
            13, 14, 15, 16, 17,
            22, 23, 24, 25, 26,
            31, 32, 33, 34, 35};
    private static final int SLOT_PREV = 40;
    private static final int SLOT_PAGE = 42;
    private static final int SLOT_NEXT = 44;
    private static final int SLOT_CLOSE = 45;

    private final Player player;
    private final Inventory inventory;
    private final CollectionsConfig config;
    private final CollectionsService service;
    private final Map<String, Integer> counters;
    private final NamespacedKey key;
    private String category;
    private int page;
    private final Map<Integer, String> slotAction = new HashMap<>();

    public CollectionsInventory(CustomInventoryPlugin plugin, Player player, String category,
                                int page, CollectionsConfig config, CollectionsService service,
                                Map<String, Integer> counters) {
        this.player = player;
        this.config = config;
        this.service = service;
        this.counters = counters == null ? Map.of() : counters;
        this.key = new NamespacedKey(plugin, ACTION_KEY);
        this.inventory = Bukkit.createInventory(this, 54,
                Text.nexoBackground(config.backgroundGlyph(), config.backgroundShift()));
        render(category, page);
    }

    /** Repaint in place so tab and arrow clicks never reopen the container. */
    public void render(String category, Integer page) {
        List<String> cats = config.tabOrder();
        this.category = (category != null && cats.contains(category))
                ? category : (cats.isEmpty() ? "" : cats.get(0));

        int size = countFor(this.category);
        int pages = Math.max(1, (int) Math.ceil(size / (double) GRID.length));
        int want = page == null ? 0 : page;
        this.page = Math.max(0, Math.min(want, pages - 1));

        inventory.clear();
        slotAction.clear();

        for (int i = 0; i < TABS.length && i < cats.size(); i++) {
            for (int s : TABS[i]) slotAction.put(s, ACT_CAT + cats.get(i));
        }

        if (CollectionsConfig.CAT_SETS.equals(this.category)) {
            renderSets();
        } else {
            renderItems();
        }

        if (this.page > 0) slotAction.put(SLOT_PREV, ACT_PREV);
        if (this.page < pages - 1) slotAction.put(SLOT_NEXT, ACT_NEXT);
        inventory.setItem(SLOT_PAGE, pageIndicator(pages));
        slotAction.put(SLOT_CLOSE, ACT_CLOSE);

        player.updateInventory();
    }

    private int countFor(String cat) {
        return CollectionsConfig.CAT_SETS.equals(cat)
                ? config.sets().size() : config.byCategory(cat).size();
    }

    private void renderItems() {
        List<CollectionEntry> all = config.byCategory(this.category);
        int from = this.page * GRID.length;
        for (int i = 0; i < GRID.length; i++) {
            int idx = from + i;
            if (idx >= all.size()) break;
            CollectionEntry e = all.get(idx);
            boolean found = service.has(counters, e);

            List<String> lore = new ArrayList<>();
            lore.add("&7Rarity: " + rarityColor(e.getRarity()) + capitalize(e.getRarity()));
            lore.add("&7Category: &f" + categoryName(e.getCategory()));
            lore.add("");
            int times = counters.getOrDefault(config.keyFor(e), 0);
            if (found) {
                lore.add("&a\u2714 &7Collected" + (times > 1 ? " &8(seen " + times + "\u00d7)" : ""));
            } else {
                lore.add("&8\u2718 &7Not collected yet");
                lore.add("&8Obtain one to add it to your codex.");
            }
            inventory.setItem(GRID[i], entryIcon(found, e, lore));
        }
    }

    private void renderSets() {
        List<CollectionsConfig.SetEntry> all = new ArrayList<>(config.sets().values());
        int from = this.page * GRID.length;
        for (int i = 0; i < GRID.length; i++) {
            int idx = from + i;
            if (idx >= all.size()) break;
            CollectionsConfig.SetEntry s = all.get(idx);
            int found = service.setPiecesFound(counters, s);
            boolean any = found > 0;

            List<String> lore = new ArrayList<>();
            lore.add("&7Pieces: &f" + found + "&7/&f" + s.elements.size());
            lore.add("");
            for (String el : s.elements) {
                boolean have = counters.getOrDefault(config.keyForSetPiece(s.id, el), 0) > 0;
                lore.add((have ? "&a\u2714 &7" : "&8\u2718 &7") + capitalize(el));
            }
            if (found >= s.elements.size()) {
                lore.add("");
                lore.add("&6\u2726 Set complete!");
            }
            ItemStack icon = any ? new ItemStack(s.icon) : unknownIcon();
            inventory.setItem(GRID[i], stamp(icon, ACT_ENTRY + s.id,
                    (any ? "" : "&8") + s.display, lore));
        }
    }

    /**
     * Real icon once collected, uniform placeholder before. Most gems share the
     * NETHER_STAR material, so the icon swap — not the lore — is what makes the
     * two states distinguishable at a glance.
     */
    private ItemStack entryIcon(boolean found, CollectionEntry e, List<String> lore) {
        ItemStack icon = found ? new ItemStack(e.getIcon()) : unknownIcon();
        String name = (found ? "&f" : "&8") + stripColors(e.getDisplay());
        return stamp(icon, ACT_ENTRY + e.getId(), name, lore);
    }

    private ItemStack unknownIcon() {
        return Icons.build(config.unknownIcon(), Material.LIGHT_GRAY_STAINED_GLASS_PANE);
    }

    private ItemStack pageIndicator(int pages) {
        List<String> lore = new ArrayList<>();
        int found = service.discovered(counters);
        lore.add("&7Page &f" + (page + 1) + "&7/&f" + pages);
        lore.add("");
        lore.add("&7Collected: &f" + found + "&7/&f" + config.totalTrackable());
        lore.add("");
        lore.add("&8Use the arrows to turn the page.");
        ItemStack it = new ItemStack(Material.PAPER, Math.max(1, Math.min(64, page + 1)));
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Text.c("&e&l" + categoryName(category)));
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(Text.c(s));
            m.lore(l);
            it.setItemMeta(m);
        }
        return it;
    }

    private String categoryName(String id) {
        CollectionsConfig.Category c = config.categories().get(id);
        return c == null ? capitalize(id) : c.display;
    }

    private static String rarityColor(String rarity) {
        return switch (rarity == null ? "" : rarity.toLowerCase(Locale.ROOT)) {
            case "uncommon" -> "&a";
            case "rare" -> "&9";
            case "superior", "epic" -> "&5";
            case "fabled", "legendary" -> "&6";
            default -> "&f";
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String stripColors(String s) {
        return s == null ? "" : s.replaceAll("[&\u00a7][0-9a-fk-orA-FK-OR]", "");
    }

    /** Apply name, lore and the click action to an already-built icon. */
    private ItemStack stamp(ItemStack it, String action, String name, List<String> loreLines) {
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Text.c(name));
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(Text.c(l));
            m.lore(lore);
            m.getPersistentDataContainer().set(key, PersistentDataType.STRING, action);
            it.setItemMeta(m);
        }
        return it;
    }

    public String actionAt(int slot) { return slotAction.get(slot); }
    public String getCategory() { return category; }
    public int getPage() { return page; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }
}
