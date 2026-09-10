package com.example.custominventoryplugin.compendium;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads the Account/meta tuning knobs from {@code compendium.yml} (the same
 * file {@link QuestProgress} reads for quest tags). Everything here is a
 * game-design value the Account book uses to compute the progress score and
 * the "journey" stats — kept in YAML so it can be tuned without a rebuild.
 *
 * <p>Sections (all optional; sensible defaults if omitted):
 * <pre>
 * progress:
 *   total-floors: 100
 *   misc-full-hours: 200
 *   weights: { floors: 40, quests: 20, bestiary: 15, collections: 15, misc: 10 }
 * counters:
 *   crystals-prefix: "crystal."
 *   dungeon-prefix: "dungeon."
 *   checkpoint-prefix: "checkpoint."
 *   crates-prefix: "crate."
 *   breach-prefix: "breach."
 * permissions:
 *   floor-done: "tower.floor{n}.done"   # {n} → floor number
 *   haven-unlocked: "tower.haven.unlocked"
 *   haven-created: "tower.haven.created"
 * </pre>
 */
public class CompendiumConfig {

    private final Plugin plugin;

    private int totalFloors = 100;
    private double miscFullHours = 200.0;
    private int wFloors = 40;
    private int wQuests = 20;
    private int wBestiary = 15;
    private int wCollections = 15;
    private int wMisc = 10;

    private String crystalsPrefix = "crystal.";
    private String dungeonPrefix = "dungeon.";
    private String checkpointPrefix = "checkpoint.";
    private String cratesPrefix = "crate.";
    private String breachPrefix = "breach.";

    private String floorDoneNode = "tower.floor{n}.done";
    private String havenUnlockedNode = "tower.haven.unlocked";
    private String havenCreatedNode = "tower.haven.created";

    /** A registered camp/checkpoint: discovery = checkpoint counter &gt; 0. */
    public static final class Camp {
        public final String id;
        public final String label;
        public final int floor;

        Camp(String id, String label, int floor) {
            this.id = id;
            this.label = label;
            this.floor = floor;
        }
    }

    private final List<Camp> camps = new ArrayList<>();

    /**
     * A workbench recipe. {@code type} is the bench it sits on (cooking /
     * crafting) and drives the grouping in the Workbenches tile lore.
     * An empty {@code node} means the recipe is available to everyone, so
     * starter recipes still count toward the total.
     *
     * <p>Must be kept in step with the Genesis shop that actually sells it:
     * this list only drives Compendium display, the real gate is the shop
     * entry's {@code ExtraPermission}.
     */
    public static final class Recipe {
        public final String id;
        public final String label;
        public final String type;
        public final String node;

        Recipe(String id, String label, String type, String node) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.node = node == null ? "" : node;
        }
    }

    private final List<Recipe> recipes = new ArrayList<>();

    /**
     * One crate source or tier, in the order the Crates tile lists it.
     * TowerCrates counts an opening as {@code crate.<source>.<tier>} (plus
     * {@code .mimic} when the crate woke a mimic), so these two lists are what
     * turns those keys into rows of lore.
     *
     * <p>Display only: a source or tier missing here is still counted, it just
     * lands in the tile's "Other" line instead of a row of its own.
     */
    public static final class CrateLabel {
        public final String id;
        public final String label;

        CrateLabel(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private final List<CrateLabel> crateSources = new ArrayList<>();
    private final List<CrateLabel> crateTiers = new ArrayList<>();

    // Hub GUI layout (custom-slots style). Fully data-driven so tiles can be
    // moved, re-skinned, relabelled or disabled without a rebuild.
    private String menuTitle = "&d&lCompendium";
    private int menuRows = 6;
    private boolean fillerEnabled = true;
    private String fillerIcon = "BLACK_STAINED_GLASS_PANE";
    private String fillerName = " ";
    private final List<MenuTile> menuTiles = new ArrayList<>();

    /**
     * One hub tile. {@code icon} is a vanilla Material name, {@code player_head},
     * or {@code nexo:<id>}. {@code name}/{@code lore} accept {@code {token}}
     * placeholders; a lore line that is exactly {@code {token}} for a known
     * block-token expands to several computed lines. {@code action} is one of
     * quests/bestiary/harvest/collections/close (or none).
     */
    public static final class MenuTile {
        public final String key;
        public final boolean enabled;
        public final int slot;
        public final String icon;
        public final String name;
        public final String action;
        /** Player command to run when action is "command" (no leading slash). */
        public final String command;
        /** Optional command for a right-click (falls back to {@link #command}). */
        public final String commandRight;
        public final List<String> lore;

        public MenuTile(String key, boolean enabled, int slot, String icon,
                        String name, String action, List<String> lore) {
            this(key, enabled, slot, icon, name, action, "", "", lore);
        }

        public MenuTile(String key, boolean enabled, int slot, String icon,
                        String name, String action, String command, List<String> lore) {
            this(key, enabled, slot, icon, name, action, command, "", lore);
        }

        public MenuTile(String key, boolean enabled, int slot, String icon,
                        String name, String action, String command, String commandRight,
                        List<String> lore) {
            this.key = key;
            this.enabled = enabled;
            this.slot = slot;
            this.icon = icon;
            this.name = name;
            this.action = action;
            this.command = command == null ? "" : command;
            this.commandRight = commandRight == null ? "" : commandRight;
            this.lore = lore;
        }
    }

    public CompendiumConfig(Plugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        try {
            plugin.saveResource("compendium.yml", false);
        } catch (IllegalArgumentException ignored) {
            // jar may ship without it during early builds
        }
        File f = new File(plugin.getDataFolder(), "compendium.yml");
        if (!f.exists()) return;

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);

        ConfigurationSection p = cfg.getConfigurationSection("progress");
        if (p != null) {
            totalFloors = Math.max(1, p.getInt("total-floors", totalFloors));
            miscFullHours = Math.max(0.01, p.getDouble("misc-full-hours", miscFullHours));
            ConfigurationSection w = p.getConfigurationSection("weights");
            if (w != null) {
                wFloors = w.getInt("floors", wFloors);
                wQuests = w.getInt("quests", wQuests);
                wBestiary = w.getInt("bestiary", wBestiary);
                wCollections = w.getInt("collections", wCollections);
                wMisc = w.getInt("misc", wMisc);
            }
        }

        ConfigurationSection c = cfg.getConfigurationSection("counters");
        if (c != null) {
            crystalsPrefix = c.getString("crystals-prefix", crystalsPrefix);
            dungeonPrefix = c.getString("dungeon-prefix", dungeonPrefix);
            checkpointPrefix = c.getString("checkpoint-prefix", checkpointPrefix);
            cratesPrefix = c.getString("crates-prefix", cratesPrefix);
            breachPrefix = c.getString("breach-prefix", breachPrefix);
        }

        ConfigurationSection perms = cfg.getConfigurationSection("permissions");
        if (perms != null) {
            floorDoneNode = perms.getString("floor-done", floorDoneNode);
            havenUnlockedNode = perms.getString("haven-unlocked", havenUnlockedNode);
            havenCreatedNode = perms.getString("haven-created", havenCreatedNode);
        }

        camps.clear();
        ConfigurationSection cs = cfg.getConfigurationSection("camps");
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                ConfigurationSection s = cs.getConfigurationSection(id);
                if (s == null) continue;
                camps.add(new Camp(id, s.getString("label", id), s.getInt("floor", 0)));
            }
        }

        recipes.clear();
        ConfigurationSection rs = cfg.getConfigurationSection("recipes");
        if (rs != null) {
            for (String id : rs.getKeys(false)) {
                ConfigurationSection s = rs.getConfigurationSection(id);
                if (s == null) continue;
                recipes.add(new Recipe(id,
                        s.getString("label", id),
                        s.getString("type", "crafting"),
                        s.getString("node", "")));
            }
        }

        crateSources.clear();
        crateTiers.clear();
        ConfigurationSection crates = cfg.getConfigurationSection("crates");
        if (crates != null) {
            readLabels(crates.getConfigurationSection("sources"), crateSources);
            readLabels(crates.getConfigurationSection("tiers"), crateTiers);
        }

        loadMenu(cfg.getConfigurationSection("menu"));
    }

    /** {@code id: "Label"} rows, also accepting the {@code id: {label: …}} form. */
    private static void readLabels(ConfigurationSection s, List<CrateLabel> into) {
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection nested = s.getConfigurationSection(id);
            into.add(new CrateLabel(id, nested != null
                    ? nested.getString("label", id)
                    : s.getString(id, id)));
        }
    }

    private void loadMenu(ConfigurationSection menu) {
        menuTiles.clear();
        if (menu != null) {
            menuTitle = menu.getString("title", menuTitle);
            menuRows = Math.max(1, Math.min(6, menu.getInt("rows", menuRows)));
            ConfigurationSection fill = menu.getConfigurationSection("filler");
            if (fill != null) {
                fillerEnabled = fill.getBoolean("enabled", fillerEnabled);
                fillerIcon = fill.getString("icon", fillerIcon);
                fillerName = fill.getString("name", fillerName);
            }
            ConfigurationSection tiles = menu.getConfigurationSection("tiles");
            if (tiles != null) {
                for (String key : tiles.getKeys(false)) {
                    ConfigurationSection t = tiles.getConfigurationSection(key);
                    if (t == null) continue;
                    List<String> lore = t.getStringList("lore");
                    menuTiles.add(new MenuTile(
                            key,
                            t.getBoolean("enabled", true),
                            t.getInt("slot", -1),
                            t.getString("icon", "PAPER"),
                            t.getString("name", "&f" + key),
                            t.getString("action", "none"),
                            t.getString("command", ""),
                            t.getString("command-right", ""),
                            lore));
                }
            }
        }
        if (menuTiles.isEmpty()) putDefaultMenu();
    }

    /** Fallback hub layout, mirrored in compendium.yml. */
    private void putDefaultMenu() {
        menuTiles.add(new MenuTile("account", true, 0, "WRITABLE_BOOK", "&d&lAccount", "none", List.of(
                "&7Player Account Info",
                "",
                "&dAccount Progress: &f{score}%",
                "&bPlaytime: &f{playtime}",
                "&8Deaths: &f{deaths}",
                "&aFirst joined: &f{first_joined}",
                "",
                "&7Journey so far",
                "&aQuests done: &f{quests_done}&7/&f{quests_total}",
                "&6Camps found: &f{camps}",
                "&5Crystals charged: &f{crystals}",
                "&cDungeon runs: &f{dungeon_runs}",
                "&dBreaches cleared: &f{breach_completed} &7(best: &f{breach_highest}&7)",
                "",
                "&7Progress breakdown",
                "{bars}")));
        menuTiles.add(new MenuTile("quests", true, 1, "KNOWLEDGE_BOOK", "&a&lMy Quests", "quests", List.of(
                "&7Quest log across the Tower",
                "",
                "{quest_summary}",
                "",
                "&eClick \u2192 &f/myquest")));
        menuTiles.add(new MenuTile("floors", true, 2, "LADDER", "&6&lFloors Completed", "none", List.of(
                "&7Tower climb so far",
                "",
                "{floors_summary}")));
        menuTiles.add(new MenuTile("haven", true, 3, "GRASS_BLOCK", "&2&lPersonal Space", "none", List.of(
                "&7Your Haven \u2014 a pocket of calm.",
                "",
                "{haven_status}")));
        menuTiles.add(new MenuTile("bestiary", true, 4, "SKELETON_SKULL", "&e&lBestiary", "bestiary", List.of(
                "&7Discovered: &f{bestiary_found}&7/&f{bestiary_total}",
                "",
                "&eClick to open.")));
        menuTiles.add(new MenuTile("harvest", true, 5, "GOLDEN_HOE", "&a&lHarvested & Collected", "harvest", List.of(
                "&7Lifetime farming and mining totals.",
                "",
                "&eClick to open.")));
        menuTiles.add(new MenuTile("collections", true, 6, "CHEST", "&e&lCollections &8(soon)", "none", List.of(
                "&7Tracking arrives in a future update.")));
        menuTiles.add(new MenuTile("crates", true, 12, "TRAPPED_CHEST", "&6&lTower Crates", "none", List.of(
                "&7Crates opened: &f{crates_opened}",
                "&cMimics woken: &f{crate_mimics}",
                "",
                "{crates_summary}")));
        menuTiles.add(new MenuTile("close", true, 8, "BARRIER", "&c&lClose", "close", List.of(
                "&7Close the compendium.")));
        menuTiles.add(new MenuTile("head", true, 13, "player_head", "&d&l{player}", "none", List.of(
                "&7Account Progress &d{score}%",
                "{climb_line}")));
    }

    public int totalFloors() { return totalFloors; }
    public double miscFullHours() { return miscFullHours; }
    public int weightFloors() { return wFloors; }
    public int weightQuests() { return wQuests; }
    public int weightBestiary() { return wBestiary; }
    public int weightCollections() { return wCollections; }
    public int weightMisc() { return wMisc; }

    public String crystalsPrefix() { return crystalsPrefix; }
    public String dungeonPrefix() { return dungeonPrefix; }
    public String checkpointPrefix() { return checkpointPrefix; }
    public String cratesPrefix() { return cratesPrefix; }
    public String breachPrefix() { return breachPrefix; }

    /** LuckPerms node for a floor's completion tag ({@code {n}} → floor number). */
    public String floorDoneNode(int floor) { return floorDoneNode.replace("{n}", Integer.toString(floor)); }
    public String havenUnlockedNode() { return havenUnlockedNode; }
    public String havenCreatedNode() { return havenCreatedNode; }

    public List<Camp> camps() { return Collections.unmodifiableList(camps); }
    public List<Recipe> recipes() { return Collections.unmodifiableList(recipes); }
    public List<CrateLabel> crateSources() { return Collections.unmodifiableList(crateSources); }
    public List<CrateLabel> crateTiers() { return Collections.unmodifiableList(crateTiers); }

    public String menuTitle() { return menuTitle; }
    public int menuRows() { return menuRows; }
    public boolean fillerEnabled() { return fillerEnabled; }
    public String fillerIcon() { return fillerIcon; }
    public String fillerName() { return fillerName; }
    public List<MenuTile> menuTiles() { return Collections.unmodifiableList(menuTiles); }
}
