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
import java.util.Map;

/**
 * Bestiary browser, reskinned over the "codex" background glyph (Nexo
 * {@code codex_bg}). Left column = category tabs (Mobs / Bosses; two locked
 * slots reserved for Dungeons + a future tab). Right 5x4 grid = entries for
 * the current category + floor page. Green arrows page through floors.
 *
 * Decorative regions (tabs, arrows, close, info) are AIR so the painted
 * background shows through; clicks are routed by raw slot via {@link #actionAt}.
 * Only entry icons are real items (they sit on the painted grid cells).
 */
public class BestiaryInventory implements InventoryHolder {

    public static final String ACTION_KEY = "cmp_bestiary";
    public static final String ACT_FLOOR = "floor:";     // legacy (unused by new UI)
    public static final String ACT_ENTRY = "entry:";
    public static final String ACT_BACK = "back";
    public static final String ACT_ACCOUNT = "account";
    public static final String ACT_CAT = "cat:";         // cat:mobs / cat:bosses
    public static final String ACT_PREV = "prev";
    public static final String ACT_NEXT = "next";
    public static final String ACT_CLOSE = "close";

    public static final String CAT_MOBS = "mobs";
    public static final String CAT_BOSSES = "bosses";
    public static final String CAT_DUNGEONS = "dungeons";

    /** Registered node (plugin.yml). Actual node checked is {@code config.revealPermission()}. */
    public static final String PERM_REVEAL = BestiaryConfig.DEFAULT_REVEAL_PERM;

    // Codex slot map (54-slot chest). Tabs span 3 slots each (left column).
    private static final int[] TAB_MOBS = {0, 1, 2};
    private static final int[] TAB_BOSSES = {9, 10, 11};
    private static final int[] TAB_DUNGEONS = {18, 19, 20};
    private static final int[] TAB_LOCK2 = {27, 28, 29};   // reserved (soon)
    private static final int[] GRID = {
            4, 5, 6, 7, 8,
            13, 14, 15, 16, 17,
            22, 23, 24, 25, 26,
            31, 32, 33, 34, 35};
    private static final int SLOT_PREV = 40;
    private static final int SLOT_PAGE = 42;   // floor/page indicator between arrows
    private static final int SLOT_NEXT = 44;
    private static final int SLOT_CLOSE = 45;

    private final Player player;
    private final Inventory inventory;
    private final BestiaryConfig config;
    private final Map<String, Integer> kills;
    /** cip_counters snapshot (dungeon run counts). */
    private final Map<String, Integer> counters;
    private final NamespacedKey key;
    private final boolean reveal;
    private String category;
    private int floor;
    private final Map<Integer, String> slotAction = new HashMap<>();

    public BestiaryInventory(CustomInventoryPlugin plugin, Player player, String category, int floor,
                             BestiaryConfig config, Map<String, Integer> kills,
                             Map<String, Integer> counters) {
        this.player = player;
        this.config = config;
        this.kills = kills;
        this.counters = counters == null ? Map.of() : counters;
        this.reveal = player.hasPermission(config.revealPermission());
        this.key = new NamespacedKey(plugin, ACTION_KEY);
        this.inventory = Bukkit.createInventory(this, 54,
                Text.nexoBackground(config.backgroundGlyph(), config.backgroundShift()));
        render(category, floor);
    }

    /**
     * (Re)paint the whole window for a category + floor. Reused for in-place
     * navigation so tab/arrow clicks never reopen the container (no flicker,
     * no cursor recenter). The title is identical across pages, so mutating
     * the open inventory is safe.
     *
     * @param floor may be {@code null} to snap to the category's first floor.
     */
    public void render(String category, Integer floor) {
        this.category = normalizeCat(category);
        List<Integer> floors = floorsForCategory(config, this.category);
        int want = floor == null ? Integer.MIN_VALUE : floor;
        this.floor = floors.isEmpty() ? Math.max(0, want)
                : (floors.contains(want) ? want : floors.get(0));

        inventory.clear();
        slotAction.clear();

        // ── Category tabs (air + slot actions; labels are painted) ─────────
        mapAll(TAB_MOBS, ACT_CAT + CAT_MOBS);
        mapAll(TAB_BOSSES, ACT_CAT + CAT_BOSSES);
        mapAll(TAB_DUNGEONS, ACT_CAT + CAT_DUNGEONS);
        // Remaining locked tab: no action (painted gray plate).

        // ── Entry grid for current category + floor ───────────────────────
        if (CAT_DUNGEONS.equals(this.category)) {
            renderDungeonGrid();
        } else {
            BestiaryConfig.Thresholds th = config.thresholds();
            List<BestiaryEntry> entries = entriesFor(config, this.category, this.floor);
            for (int i = 0; i < GRID.length; i++) {
                int slot = GRID[i];
                if (i >= entries.size()) continue;          // leave empty cell painted
                BestiaryEntry e = entries.get(i);
                int total = BestiaryData.sumForEntry(kills, e);
                int elites = BestiaryData.eliteKills(kills, e);
                boolean known = reveal || total >= th.discovered;
                if (known) {
                    inventory.setItem(slot, knownIcon(key, e, total, elites, th));
                    slotAction.put(slot, ACT_ENTRY + e.getId());
                }
                // else: leave the cell AIR so the painted "?" background shows through.
            }
        }

        // ── Page arrows (only when there is somewhere to go) ───────────────
        int idx = floors.indexOf(this.floor);
        if (idx > 0) slotAction.put(SLOT_PREV, ACT_PREV);
        if (idx >= 0 && idx < floors.size() - 1) slotAction.put(SLOT_NEXT, ACT_NEXT);

        // ── Floor / page indicator (between the arrows) ────────────────────
        int page = idx < 0 ? 1 : idx + 1;
        inventory.setItem(SLOT_PAGE, pageIndicator(this.floor, page, floors.size()));

        // ── Close (painted X) ──────────────────────────────────────────────
        slotAction.put(SLOT_CLOSE, ACT_CLOSE);

        // Push slot updates to the open view (harmless before it is shown).
        player.updateInventory();
    }

    private void mapAll(int[] slots, String action) {
        for (int s : slots) slotAction.put(s, action);
    }

    /** Dungeon tiles for the current floor: discovered at 1 lifetime run. */
    private void renderDungeonGrid() {
        List<BestiaryConfig.DungeonEntry> list = config.dungeonsByFloor(this.floor);
        for (int i = 0; i < GRID.length && i < list.size(); i++) {
            BestiaryConfig.DungeonEntry d = list.get(i);
            int runs = counters.getOrDefault(d.counterKey, 0);
            boolean known = reveal || runs >= 1;
            if (!known) continue;   // painted "?" shows through
            List<String> lore = new ArrayList<>();
            lore.add("&5&lDungeon");
            if (!d.location.isBlank()) lore.add("&7Location: &f" + d.location);
            lore.add("&7Runs completed: &f" + runs);
            if (!d.lore.isEmpty()) {
                lore.add("");
                for (String l : d.lore) lore.add("&7" + l);
            }
            if (reveal && runs < 1) {
                lore.add("");
                lore.add("&8Revealed by permission.");
            }
            inventory.setItem(GRID[i], build(d.icon, "&d&l" + d.display, lore));
        }
    }

    private static String normalizeCat(String c) {
        if (CAT_BOSSES.equalsIgnoreCase(c)) return CAT_BOSSES;
        if (CAT_DUNGEONS.equalsIgnoreCase(c)) return CAT_DUNGEONS;
        return CAT_MOBS;
    }

    private static boolean matchesCategory(BestiaryEntry e, String cat) {
        return CAT_BOSSES.equals(cat) ? e.isBoss() : !e.isBoss();
    }

    /** Floors (ordered) that have at least one entry in this category. */
    static List<Integer> floorsForCategory(BestiaryConfig config, String cat) {
        if (CAT_DUNGEONS.equalsIgnoreCase(cat)) return config.dungeonFloors();
        List<Integer> out = new ArrayList<>();
        for (int f : config.floors()) {
            for (BestiaryEntry e : config.byFloor(f)) {
                if (matchesCategory(e, cat)) { out.add(f); break; }
            }
        }
        return out;
    }

    private static List<BestiaryEntry> entriesFor(BestiaryConfig config, String cat, int floor) {
        List<BestiaryEntry> out = new ArrayList<>();
        for (BestiaryEntry e : config.byFloor(floor)) {
            if (matchesCategory(e, cat)) out.add(e);
        }
        return out;
    }

    private ItemStack knownIcon(NamespacedKey key, BestiaryEntry e, int total, int elites,
                                BestiaryConfig.Thresholds th) {
        List<String> lore = new ArrayList<>();
        if (e.isBoss()) lore.add("&c&lBoss");
        if (!e.getFamily().isBlank()) lore.add("&7Family: &f" + e.getFamily());
        if (!e.getRole().isBlank()) lore.add("&7Role: &f" + e.getRole());
        lore.add("&7Kills: &f" + total + (elites > 0 ? " &8(elites: " + elites + ")" : ""));
        lore.add("");
        lore.add(unlockLine("Discovered", total, th.discovered));
        lore.add(unlockLine("Lore", total, th.lore));
        lore.add(unlockLine("Weaknesses", total, th.weaknesses));
        lore.add(unlockLine("Drops", total, th.drops));
        if (reveal && total < th.drops) {
            lore.add("");
            lore.add("&8Revealed by permission.");
        }
        lore.add("");
        lore.add("&eClick for details.");
        String name = (e.isBoss() ? "&c&l" : "&a&l") + e.getDisplay();
        return button(key, ACT_ENTRY + e.getId(), e.getIcon(), name, lore);
    }

    /** Floor/page marker: stack size = floor number (always-visible badge). */
    private ItemStack pageIndicator(int floor, int page, int totalFloors) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Page &f" + page + "&7/&f" + Math.max(1, totalFloors));
        lore.add("");
        lore.add("&8Use the arrows to change floor.");
        int amount = Math.max(1, Math.min(64, floor));
        ItemStack it = new ItemStack(Material.PAPER, amount);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Text.c("&e&lFloor " + floor));
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(Text.c(s));
            m.lore(l);
            it.setItemMeta(m);
        }
        return it;
    }

    private String unlockLine(String label, int total, int need) {
        return (reveal || total >= need)
                ? "&a\u2714 &7" + label
                : "&8\u2718 &7" + label + " &8(" + total + "/" + need + ")";
    }

    private ItemStack button(NamespacedKey key, String action, Material mat, String name, List<String> lore) {
        ItemStack it = build(mat, name, lore);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.getPersistentDataContainer().set(key, PersistentDataType.STRING, action);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack build(Material mat, String name, List<String> loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Text.c(name));
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(Text.c(l));
            m.lore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    /** Action for a raw slot (used for AIR decorative regions). */
    public String actionAt(int slot) { return slotAction.get(slot); }
    public String getCategory() { return category; }
    public int getFloor() { return floor; }
    public Player getPlayer() { return player; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }
}
