package com.example.custominventoryplugin.compendium;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads {@code bestiary.yml}. Entries are keyed by id; mythic-id → entry
 * reverse index is built so the kill listener can resolve a death in O(1).
 *
 * Unlock tiers (defaults): discovered@1 · lore@10 · weaknesses@50 · drops@100.
 */
public class BestiaryConfig {

    public static final class Thresholds {
        public final int discovered;
        public final int lore;
        public final int weaknesses;
        public final int drops;

        Thresholds(int discovered, int lore, int weaknesses, int drops) {
            this.discovered = Math.max(1, discovered);
            this.lore = Math.max(this.discovered, lore);
            this.weaknesses = Math.max(this.lore, weaknesses);
            this.drops = Math.max(this.weaknesses, drops);
        }
    }

    /** A dungeon tile for the Dungeons tab. Run counts come from cip_counters. */
    public static final class DungeonEntry {
        public final String id;
        public final String display;
        public final int floor;
        public final Material icon;
        /** cip_counters key holding lifetime runs (default {@code dungeon.<id>}). */
        public final String counterKey;
        public final String location;
        public final List<String> lore;

        DungeonEntry(String id, String display, int floor, Material icon,
                     String counterKey, String location, List<String> lore) {
            this.id = id;
            this.display = display;
            this.floor = floor;
            this.icon = icon;
            this.counterKey = counterKey;
            this.location = location;
            this.lore = lore;
        }
    }

    /** Fallback defaults if bestiary.yml omits the display/reveal keys. */
    public static final String DEFAULT_REVEAL_PERM = "custominventory.bestiary.reveal";
    public static final char DEFAULT_GLYPH = '\uA41C';   // ꐜ codex_bg in nexo:default
    public static final int DEFAULT_SHIFT = 16;

    private final Plugin plugin;
    private final Map<String, BestiaryEntry> entries = new LinkedHashMap<>();
    private final Map<String, DungeonEntry> dungeons = new LinkedHashMap<>();
    /** mythic_id → entry id (both base and elite ids point at the same entry). */
    private final Map<String, String> mythicToEntry = new LinkedHashMap<>();
    private Thresholds thresholds = new Thresholds(1, 10, 50, 100);
    private String revealPermission = DEFAULT_REVEAL_PERM;
    private char backgroundGlyph = DEFAULT_GLYPH;
    private int backgroundShift = DEFAULT_SHIFT;

    public BestiaryConfig(Plugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        try {
            plugin.saveResource("bestiary.yml", false);
        } catch (IllegalArgumentException ignored) {
            // jar may ship without it during early builds
        }

        File f = new File(plugin.getDataFolder(), "bestiary.yml");
        entries.clear();
        dungeons.clear();
        mythicToEntry.clear();
        if (!f.exists()) {
            plugin.getLogger().info("bestiary.yml missing — bestiary empty.");
            return;
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection th = cfg.getConfigurationSection("thresholds");
        if (th != null) {
            thresholds = new Thresholds(
                    th.getInt("discovered", 1),
                    th.getInt("lore", 10),
                    th.getInt("weaknesses", 50),
                    th.getInt("drops", 100));
        }

        revealPermission = cfg.getString("reveal-permission", DEFAULT_REVEAL_PERM);
        ConfigurationSection disp = cfg.getConfigurationSection("display");
        if (disp != null) {
            String glyph = disp.getString("glyph", "");
            if (glyph != null && !glyph.isEmpty()) backgroundGlyph = glyph.charAt(0);
            backgroundShift = disp.getInt("shift", DEFAULT_SHIFT);
        }

        ConfigurationSection root = cfg.getConfigurationSection("entries");
        if (root == null) {
            plugin.getLogger().warning("bestiary.yml has no 'entries:' section.");
            return;
        }

        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                BestiaryEntry e = parse(id, s);
                entries.put(id, e);
                for (String mid : e.allTrackedIds()) {
                    String prev = mythicToEntry.put(mid.toLowerCase(), id);
                    if (prev != null && !prev.equals(id)) {
                        plugin.getLogger().warning("bestiary: mythic id '" + mid
                                + "' maps to both '" + prev + "' and '" + id + "' — last wins.");
                    }
                }
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "bestiary: skipped entry '" + id + "'", ex);
            }
        }
        ConfigurationSection dg = cfg.getConfigurationSection("dungeons");
        if (dg != null) {
            for (String id : dg.getKeys(false)) {
                ConfigurationSection s = dg.getConfigurationSection(id);
                if (s == null) continue;
                dungeons.put(id, new DungeonEntry(id,
                        s.getString("display", id),
                        s.getInt("floor", 0),
                        parseMaterial(s.getString("icon", "IRON_BARS"), id),
                        s.getString("counter-key", "dungeon." + id),
                        s.getString("location", ""),
                        s.getStringList("lore")));
            }
        }

        plugin.getLogger().info("Bestiary loaded: " + entries.size() + " entries, "
                + mythicToEntry.size() + " mythic ids tracked, "
                + dungeons.size() + " dungeon(s).");
    }

    private BestiaryEntry parse(String id, ConfigurationSection s) {
        String display = s.getString("display", id);
        int floor = s.getInt("floor", 0);
        String family = s.getString("family", "");
        Material icon = parseMaterial(s.getString("icon", "PAPER"), id);
        List<String> mythic = new ArrayList<>(s.getStringList("mythic"));
        List<String> elite = new ArrayList<>(s.getStringList("elite"));
        if (mythic.isEmpty()) {
            String single = s.getString("mythic", "");
            if (single != null && !single.isBlank()) mythic = List.of(single);
        }
        String role = s.getString("role", "");
        List<String> resists = s.getStringList("resists");
        List<String> weaknesses = s.getStringList("weaknesses");
        List<String> locations = s.getStringList("locations");
        List<String> lore = s.getStringList("lore");
        List<String> drops = new ArrayList<>(s.getStringList("drops"));
        // Back-compat: old `loot:` string → single drops line
        if (drops.isEmpty()) {
            String loot = s.getString("loot", "");
            if (loot != null && !loot.isBlank()) drops.add(loot);
        }
        boolean boss = s.getBoolean("boss", false);
        return new BestiaryEntry(id, floor, family, display, icon, mythic, elite, role,
                resists, weaknesses, locations, lore, drops, boss);
    }

    private Material parseMaterial(String name, String id) {
        if (name == null || name.isBlank()) return Material.PAPER;
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("bestiary '" + id + "': bad icon '" + name + "', using PAPER.");
            return Material.PAPER;
        }
    }

    public Thresholds thresholds() { return thresholds; }
    public String revealPermission() { return revealPermission; }
    public char backgroundGlyph() { return backgroundGlyph; }
    public int backgroundShift() { return backgroundShift; }
    public Map<String, BestiaryEntry> entries() { return Collections.unmodifiableMap(entries); }
    public BestiaryEntry get(String id) { return entries.get(id); }

    public BestiaryEntry byMythicId(String mythicId) {
        if (mythicId == null) return null;
        String entryId = mythicToEntry.get(mythicId.toLowerCase());
        return entryId == null ? null : entries.get(entryId);
    }

    public List<BestiaryEntry> byFloor(int floor) {
        List<BestiaryEntry> out = new ArrayList<>();
        for (BestiaryEntry e : entries.values()) {
            if (e.getFloor() == floor) out.add(e);
        }
        return out;
    }

    public List<Integer> floors() {
        List<Integer> out = new ArrayList<>();
        for (BestiaryEntry e : entries.values()) {
            if (!out.contains(e.getFloor())) out.add(e.getFloor());
        }
        Collections.sort(out);
        return out;
    }

    public Map<String, DungeonEntry> dungeons() { return Collections.unmodifiableMap(dungeons); }

    public List<DungeonEntry> dungeonsByFloor(int floor) {
        List<DungeonEntry> out = new ArrayList<>();
        for (DungeonEntry d : dungeons.values()) {
            if (d.floor == floor) out.add(d);
        }
        return out;
    }

    public List<Integer> dungeonFloors() {
        List<Integer> out = new ArrayList<>();
        for (DungeonEntry d : dungeons.values()) {
            if (!out.contains(d.floor)) out.add(d.floor);
        }
        Collections.sort(out);
        return out;
    }
}
