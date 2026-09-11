package com.example.custominventoryplugin.compendium;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code collections.yml} — the Compendium's trophy tracker.
 *
 * Two kinds of collectable share one counter namespace:
 * <ul>
 *   <li>{@link CollectionEntry} — a Divinity custom item, keyed by its item id
 *       ({@code collect.<category>.<id>}).</li>
 *   <li>{@link SetEntry} — an armour set, tracked one element at a time
 *       ({@code collect.sets.<setId>.<element>}) because set pieces are rolled
 *       by the item generator and share no id.</li>
 * </ul>
 *
 * Discovery is permanent, so a counter value of 1 is all that matters; the
 * stored value doubles as "times seen" for flavour.
 */
public class CollectionsConfig {

    public static final String DEFAULT_PREFIX = "collect.";
    public static final String DEFAULT_UNKNOWN_ICON = "nexo:collection_unknown_icon";
    public static final char DEFAULT_GLYPH = '\uA41C';   // ꐜ codex_bg, shared with the bestiary
    public static final int DEFAULT_SHIFT = 16;

    /** The set category is also the counter namespace for set pieces. */
    public static final String CAT_SETS = "sets";

    /** A tab in the Collections GUI. */
    public static final class Category {
        public final String id;
        public final String display;
        public final String icon;

        Category(String id, String display, String icon) {
            this.id = id;
            this.display = display;
            this.icon = icon;
        }
    }

    /** An armour set. Completion is per element, matched via SetManager. */
    public static final class SetEntry {
        public final String id;
        public final String display;
        public final String icon;
        public final List<String> elements;

        SetEntry(String id, String display, String icon, List<String> elements) {
            this.id = id;
            this.display = display;
            this.icon = icon;
            this.elements = elements;
        }
    }

    private final Plugin plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<String, CollectionEntry> entries = new LinkedHashMap<>();
    private final Map<String, SetEntry> sets = new LinkedHashMap<>();
    private final Set<String> exclude = new HashSet<>();
    private String counterPrefix = DEFAULT_PREFIX;
    private String unknownIcon = DEFAULT_UNKNOWN_ICON;
    private char backgroundGlyph = DEFAULT_GLYPH;
    private int backgroundShift = DEFAULT_SHIFT;

    public CollectionsConfig(Plugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        try {
            plugin.saveResource("collections.yml", false);
        } catch (IllegalArgumentException ignored) {
            // jar may ship without it during early builds
        }

        File f = new File(plugin.getDataFolder(), "collections.yml");
        categories.clear();
        entries.clear();
        sets.clear();
        exclude.clear();
        if (!f.exists()) {
            plugin.getLogger().info("collections.yml missing — collections empty.");
            return;
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        counterPrefix = cfg.getString("counter-prefix", DEFAULT_PREFIX);
        unknownIcon = cfg.getString("unknown-icon", DEFAULT_UNKNOWN_ICON);

        ConfigurationSection disp = cfg.getConfigurationSection("display");
        if (disp != null) {
            String glyph = disp.getString("glyph", "");
            if (glyph != null && !glyph.isEmpty()) backgroundGlyph = glyph.charAt(0);
            backgroundShift = disp.getInt("shift", DEFAULT_SHIFT);
        }

        for (String id : cfg.getStringList("exclude")) {
            exclude.add(id.toLowerCase(Locale.ROOT));
        }

        ConfigurationSection cats = cfg.getConfigurationSection("categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                ConfigurationSection s = cats.getConfigurationSection(id);
                if (s == null) continue;
                categories.put(id, new Category(id,
                        s.getString("display", id),
                        parseIcon(s.getString("icon", "PAPER"), id)));
            }
        }

        ConfigurationSection root = cfg.getConfigurationSection("entries");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) continue;
                String key = id.toLowerCase(Locale.ROOT);
                if (exclude.contains(key)) continue;
                String category = s.getString("category", "");
                if (category.isBlank() || !categories.containsKey(category)) {
                    plugin.getLogger().warning("collections: entry '" + id
                            + "' has unknown category '" + category + "' — skipped.");
                    continue;
                }
                entries.put(key, new CollectionEntry(key, category,
                        s.getString("display", id),
                        parseIcon(s.getString("icon", "PAPER"), id),
                        s.getString("rarity", "common")));
            }
        }

        ConfigurationSection sc = cfg.getConfigurationSection(CAT_SETS);
        if (sc != null) {
            for (String id : sc.getKeys(false)) {
                ConfigurationSection s = sc.getConfigurationSection(id);
                if (s == null) continue;
                String key = id.toLowerCase(Locale.ROOT);
                if (exclude.contains(key)) continue;
                List<String> elements = new ArrayList<>();
                for (String e : s.getStringList("elements")) {
                    elements.add(e.toLowerCase(Locale.ROOT));
                }
                if (elements.isEmpty()) continue;
                sets.put(key, new SetEntry(key,
                        s.getString("display", id),
                        parseIcon(s.getString("icon", "LEATHER_CHESTPLATE"), id),
                        elements));
            }
        }

        plugin.getLogger().info("Collections loaded: " + entries.size() + " item(s), "
                + sets.size() + " set(s) (" + setPieceCount() + " pieces), "
                + totalTrackable() + " trackable total.");
    }

    /**
     * Validate an icon spec. {@code nexo:<id>} is passed through untouched
     * (resolved at draw time by {@link Icons#build}); anything else must be a
     * Material name.
     */
    private String parseIcon(String name, String id) {
        if (name == null || name.isBlank()) return "PAPER";
        String s = name.trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("nexo:")) return s;
        try {
            return Material.valueOf(s.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("collections '" + id + "': bad icon '" + name + "', using PAPER.");
            return "PAPER";
        }
    }

    // ── counter keys ──────────────────────────────────────────────────────

    public String keyFor(CollectionEntry e) {
        return counterPrefix + e.getCategory() + "." + e.getId();
    }

    public String keyForSetPiece(String setId, String element) {
        return counterPrefix + CAT_SETS + "." + setId.toLowerCase(Locale.ROOT)
                + "." + element.toLowerCase(Locale.ROOT);
    }

    // ── lookups ───────────────────────────────────────────────────────────

    public String counterPrefix() { return counterPrefix; }
    public String unknownIcon() { return unknownIcon; }
    public char backgroundGlyph() { return backgroundGlyph; }
    public int backgroundShift() { return backgroundShift; }

    public Map<String, Category> categories() { return Collections.unmodifiableMap(categories); }
    public Map<String, CollectionEntry> entries() { return Collections.unmodifiableMap(entries); }
    public Map<String, SetEntry> sets() { return Collections.unmodifiableMap(sets); }

    public CollectionEntry get(String id) {
        return id == null ? null : entries.get(id.toLowerCase(Locale.ROOT));
    }

    public SetEntry getSet(String id) {
        return id == null ? null : sets.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean isExcluded(String id) {
        return id != null && exclude.contains(id.toLowerCase(Locale.ROOT));
    }

    public List<CollectionEntry> byCategory(String category) {
        List<CollectionEntry> out = new ArrayList<>();
        for (CollectionEntry e : entries.values()) {
            if (e.getCategory().equals(category)) out.add(e);
        }
        return out;
    }

    /**
     * Category ids in config order, one per painted tab plate.
     *
     * Every configured category is returned even when it is empty. The tab
     * labels are painted into the frame texture, so position is meaning: drop
     * an empty category here and every tab below it slides up under the wrong
     * word. An empty tab showing an empty grid is the lesser evil.
     */
    public List<String> tabOrder() {
        return new ArrayList<>(categories.keySet());
    }

    public int setPieceCount() {
        int n = 0;
        for (SetEntry s : sets.values()) n += s.elements.size();
        return n;
    }

    /** Denominator for the Collections pillar: every item plus every set piece. */
    public int totalTrackable() {
        return entries.size() + setPieceCount();
    }

    public boolean isEmpty() {
        return entries.isEmpty() && sets.isEmpty();
    }
}
