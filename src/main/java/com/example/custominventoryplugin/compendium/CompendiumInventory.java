package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.compendium.CompendiumConfig.MenuTile;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
/**
 * Compendium hub. Layout (tiles, slots, icons, names, lore, actions) is fully
 * data-driven from {@code compendium.yml → menu}; this class only computes the
 * dynamic values and substitutes them into the configured tiles via
 * {@code {token}} placeholders (scalars) and block-tokens (multi-line).
 */
public class CompendiumInventory implements InventoryHolder {

    public static final String ACTION_KEY = "cmp_action";
    public static final String COMMAND_KEY = "cmp_command";
    public static final String COMMAND_RIGHT_KEY = "cmp_command_right";
    public static final String ACT_QUESTS = "quests";
    public static final String ACT_BESTIARY = "bestiary";
    public static final String ACT_COLLECTIONS = "collections";
    public static final String ACT_HARVEST = "harvest";
    public static final String ACT_CLOSE = "close";
    public static final String ACT_COMMAND = "command";
    /**
     * Runs the tile's command from the console instead of the player, with
     * {@code {player}} substituted. Needed for anything the player must not be
     * able to type themselves — Genesis shops in particular, since Genesis has
     * no per-shop permission and {@code climber} holds no Genesis.open.* node.
     */
    public static final String ACT_CONSOLE = "console";

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault());

    private final Player player;
    private final Inventory inventory;
    private final CompendiumConfig cfg;

    public CompendiumInventory(CustomInventoryPlugin plugin, Player player) {
        this.player = player;
        this.cfg = plugin.getCompendiumConfig();
        int size = cfg.menuRows() * 9;
        this.inventory = Bukkit.createInventory(this, size, Text.title(cfg.menuTitle()));

        NamespacedKey actionKey = new NamespacedKey(plugin, ACTION_KEY);
        NamespacedKey commandKey = new NamespacedKey(plugin, COMMAND_KEY);
        NamespacedKey commandRightKey = new NamespacedKey(plugin, COMMAND_RIGHT_KEY);

        if (cfg.fillerEnabled()) {
            ItemStack filler = filler(cfg.fillerIcon(), cfg.fillerName());
            for (int i = 0; i < size; i++) inventory.setItem(i, filler);
        }

        // ── compute every dynamic value once (shared with the PAPI expansion) ──
        CompendiumStats st = CompendiumStats.compute(plugin, player);

        // ── scalar placeholders ────────────────────────────────────────────
        Map<String, String> scalars = new HashMap<>();
        scalars.put("player", player.getName());
        scalars.put("score", Integer.toString(st.score));
        scalars.put("playtime", formatHours(st.hours));
        scalars.put("deaths", Integer.toString(st.deaths));
        scalars.put("first_joined", firstJoined());
        scalars.put("quests_done", Integer.toString(st.questsDone));
        scalars.put("quests_total", Integer.toString(st.questsTotal));
        scalars.put("camps", Integer.toString(st.checkpoints));
        scalars.put("camps_total", Integer.toString(cfg.camps().size()));
        scalars.put("recipes", Integer.toString(knownRecipes()));
        scalars.put("recipes_total", Integer.toString(cfg.recipes().size()));
        scalars.put("crystals", Integer.toString(st.crystals));
        scalars.put("dungeon_runs", Integer.toString(st.dungeonRuns));
        scalars.put("crates_opened", Integer.toString(st.cratesOpened));
        scalars.put("crate_mimics", Integer.toString(st.crateMimics));
        scalars.put("breach_completed", Integer.toString(st.breachCompleted));
        scalars.put("breach_highest", st.breachHighest > 0 ? "Tier " + st.breachHighest : "none");
        scalars.put("bestiary_found", Integer.toString(st.bestiaryFound));
        scalars.put("bestiary_total", Integer.toString(st.bestiaryTotal));
        scalars.put("floor_count", Integer.toString(st.floorCount));
        scalars.put("highest", Integer.toString(st.highest));
        scalars.put("climb_line", st.highest > 0
                ? "&7Climbing from &fFloor " + st.highest
                : "&7The climb begins on &fFloor 1");

        // ── block placeholders (expand a whole lore line to many) ───────────
        Map<String, List<String>> blocks = new HashMap<>();
        blocks.put("bars", List.of(
                bar("Floors", st.floorsPct, cfg.weightFloors()),
                bar("Quests", st.questsPct, cfg.weightQuests()),
                bar("Bestiary", st.bestiaryPct, cfg.weightBestiary()),
                bar("Collections", st.collectionsPct, cfg.weightCollections()),
                bar("Misc", st.miscPct, cfg.weightMisc())));
        blocks.put("quest_summary", questSummary(st.questRows, st.questsDone, st.questsTotal));
        blocks.put("floors_summary", floorsSummary(st.floorsDone, st.highest, st.floorCount));
        blocks.put("haven_status", havenStatus());
        blocks.put("camps_summary", campsSummary(st.counters));
        blocks.put("recipes_summary", recipesSummary());
        blocks.put("crates_summary", cratesSummary(st.counters, st.cratesOpened));

        // ── render tiles from config ────────────────────────────────────────
        for (MenuTile tile : cfg.menuTiles()) {
            if (!tile.enabled || tile.slot < 0 || tile.slot >= size) continue;
            ItemStack icon = buildIcon(tile.icon);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(Text.c(sub(tile.name, scalars)));
                meta.lore(renderLore(tile.lore, scalars, blocks));
                if (tile.action != null && !tile.action.equalsIgnoreCase("none")) {
                    meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING,
                            tile.action.toLowerCase(Locale.ROOT));
                }
                if (tile.command != null && !tile.command.isEmpty()) {
                    meta.getPersistentDataContainer().set(commandKey, PersistentDataType.STRING,
                            tile.command);
                }
                if (tile.commandRight != null && !tile.commandRight.isEmpty()) {
                    meta.getPersistentDataContainer().set(commandRightKey, PersistentDataType.STRING,
                            tile.commandRight);
                }
                icon.setItemMeta(meta);
            }
            inventory.setItem(tile.slot, icon);
        }
    }

    private List<Component> renderLore(List<String> template,
                                       Map<String, String> scalars,
                                       Map<String, List<String>> blocks) {
        List<Component> out = new ArrayList<>();
        if (template == null) return out;
        for (String line : template) {
            String block = blockTokenOf(line, blocks);
            if (block != null) {
                for (String bl : blocks.get(block)) out.add(Text.c(sub(bl, scalars)));
            } else {
                out.add(Text.c(sub(line, scalars)));
            }
        }
        return out;
    }

    /** If the whole line is exactly a known block token like {@code {bars}}. */
    private String blockTokenOf(String line, Map<String, List<String>> blocks) {
        String t = line == null ? "" : line.trim();
        if (t.length() < 3 || t.charAt(0) != '{' || t.charAt(t.length() - 1) != '}') return null;
        String key = t.substring(1, t.length() - 1);
        return blocks.containsKey(key) ? key : null;
    }

    private String sub(String s, Map<String, String> scalars) {
        if (s == null || s.indexOf('{') < 0) return s;
        String out = s;
        for (Map.Entry<String, String> e : scalars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    // ── icon resolution ────────────────────────────────────────────────────

    /** Vanilla Material, {@code player_head}, or {@code nexo:<id>}. */
    private ItemStack buildIcon(String spec) {
        if (spec == null || spec.isBlank()) return new ItemStack(Material.PAPER);
        String s = spec.trim();
        if (s.equalsIgnoreCase("player_head")) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta skull) {
                skull.setOwningPlayer(player);
                head.setItemMeta(skull);
            }
            return head;
        }
        if (s.toLowerCase(Locale.ROOT).startsWith("nexo:")) {
            ItemStack nx = nexoItem(s.substring(5).trim());
            return nx != null ? nx : new ItemStack(Material.PAPER);
        }
        try {
            return new ItemStack(Material.valueOf(s.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return new ItemStack(Material.PAPER);
        }
    }

    /** Nexo item by id via reflection (no hard dependency), or null if absent. */
    private ItemStack nexoItem(String id) {
        try {
            Class<?> nexoItems = Class.forName("com.nexomc.nexo.api.NexoItems");
            Method itemFromId = nexoItems.getMethod("itemFromId", String.class);
            Object builder = itemFromId.invoke(null, id);
            if (builder == null) return null;
            Method build = builder.getClass().getMethod("build");
            Object built = build.invoke(builder);
            if (built instanceof ItemStack is) return is.clone();
        } catch (Throwable ignored) {
            // Nexo missing or id unknown → caller falls back
        }
        return null;
    }

    // ── dynamic block builders ──────────────────────────────────────────────

    private List<String> questSummary(List<QuestProgress.FloorQuests> rows, int done, int total) {
        List<String> lore = new ArrayList<>();
        if (total > 0) {
            lore.add("&aCompleted: &f" + done + "&7/&f" + total);
            for (QuestProgress.FloorQuests fq : rows) {
                String col = fq.done >= fq.total ? "&a" : (fq.done > 0 ? "&e" : "&8");
                lore.add("&7" + fq.label + ": " + col + fq.done + "&7/&f" + fq.total);
            }
        } else {
            lore.add("&8Quest tracking not configured.");
        }
        return lore;
    }

    private List<String> floorsSummary(List<Integer> floors, int highest, int floorCount) {
        List<String> lore = new ArrayList<>();
        lore.add("&f" + floorCount + " &7floor" + (floorCount == 1 ? "" : "s") + " cleared");
        if (highest > 0) {
            lore.add("&7Highest: &fFloor " + highest);
            int next = highest + 1;
            if (next <= cfg.totalFloors()) lore.add("&7Next gate: &eFloor " + next);
        } else {
            lore.add("&8No floors cleared yet.");
            lore.add("&7Start on &fFloor 1&7.");
        }
        lore.add("");
        int known = 4; // floors with content today
        int clearedKnown = 0;
        for (int n = 1; n <= known; n++) if (floors.contains(n)) clearedKnown++;
        lore.add("&7Zone progress: &f" + clearedKnown + "&7/&f" + known);
        lore.add(miniBar(clearedKnown, known));
        if (!floors.isEmpty()) {
            StringBuilder sb = new StringBuilder("&7Cleared: &f");
            for (int i = 0; i < floors.size(); i++) {
                if (i > 0) sb.append("&7, &f");
                sb.append(floors.get(i));
            }
            lore.add(sb.toString());
        }
        return lore;
    }

    /** Configured camps: discovered names vs "???" rows, grouped as listed. */
    private List<String> campsSummary(Map<String, Integer> counters) {
        List<String> lore = new ArrayList<>();
        List<CompendiumConfig.Camp> camps = cfg.camps();
        if (camps.isEmpty()) {
            lore.add("&8No camps registered yet.");
            return lore;
        }
        for (CompendiumConfig.Camp c : camps) {
            boolean found = counters.getOrDefault(cfg.checkpointPrefix() + c.id, 0) > 0;
            String where = c.floor > 0 ? " &8\u00b7 Floor " + c.floor : "";
            lore.add(found
                    ? "&a\u2714 &f" + c.label + where
                    : "&8\u2718 &7???" + where);
        }
        return lore;
    }

    /**
     * Crate openings grouped by where the crate stood, one row per tier.
     *
     * <p>TowerCrates writes {@code crate.<source>.<tier>} per opening and
     * appends {@code .mimic} when the crate bit instead, so a tier's total is
     * the two rows added and the mimic count is a share of it rather than an
     * extra. Anything counted whose source or tier isn't configured here is
     * rolled into a trailing line, so the rows always reconcile with the tile's
     * headline total instead of quietly falling short of it.
     */
    private List<String> cratesSummary(Map<String, Integer> counters, int allOpened) {
        List<String> lore = new ArrayList<>();
        List<CompendiumConfig.CrateLabel> sources = cfg.crateSources();
        List<CompendiumConfig.CrateLabel> tiers = cfg.crateTiers();
        if (sources.isEmpty() || tiers.isEmpty()) {
            lore.add("&8Crate tracking not configured.");
            return lore;
        }
        int listed = 0;
        for (CompendiumConfig.CrateLabel source : sources) {
            lore.add("&7" + source.label);
            for (CompendiumConfig.CrateLabel tier : tiers) {
                String key = cfg.cratesPrefix() + source.id + "." + tier.id;
                int mimics = counters.getOrDefault(key + ".mimic", 0);
                int opened = counters.getOrDefault(key, 0) + mimics;
                listed += opened;
                if (opened <= 0) {
                    lore.add("&8\u2718 " + tier.label + ": 0");
                    continue;
                }
                lore.add("&a\u2714 &f" + tier.label + "&7: &f" + opened
                        + (mimics > 0 ? " &c(" + mimics + " mimic" + (mimics == 1 ? "" : "s") + ")" : ""));
            }
        }
        if (allOpened > listed) {
            lore.add("&8Other: &7" + (allOpened - listed));
        }
        return lore;
    }

    /**
     * Configured recipes grouped by bench, with locked ones shown as "???" so
     * the tile hints at what is still out there. Recipes with an empty node
     * are available to everyone and always read as known.
     */
    private List<String> recipesSummary() {
        List<String> lore = new ArrayList<>();
        List<CompendiumConfig.Recipe> recipes = cfg.recipes();
        if (recipes.isEmpty()) {
            lore.add("&8No recipes registered yet.");
            return lore;
        }
        Map<String, List<CompendiumConfig.Recipe>> byType = new LinkedHashMap<>();
        for (CompendiumConfig.Recipe r : recipes) {
            byType.computeIfAbsent(r.type, k -> new ArrayList<>()).add(r);
        }
        boolean first = true;
        for (Map.Entry<String, List<CompendiumConfig.Recipe>> e : byType.entrySet()) {
            if (!first) lore.add("");
            first = false;
            lore.add("&6" + capitalise(e.getKey()));
            for (CompendiumConfig.Recipe r : e.getValue()) {
                lore.add(knowsRecipe(r)
                        ? "&a\u2714 &f" + r.label
                        : "&8\u2718 &7???");
            }
        }
        return lore;
    }

    private boolean knowsRecipe(CompendiumConfig.Recipe r) {
        return r.node.isEmpty() || hasGranted(r.node);
    }

    private int knownRecipes() {
        int n = 0;
        for (CompendiumConfig.Recipe r : cfg.recipes()) {
            if (knowsRecipe(r)) n++;
        }
        return n;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private List<String> havenStatus() {
        boolean unlocked = hasGranted(cfg.havenUnlockedNode());
        boolean created = hasGranted(cfg.havenCreatedNode());
        List<String> lore = new ArrayList<>();
        if (created) {
            lore.add("&aStatus: &fCreated");
            lore.add("&7Visit with &f/haven&7.");
        } else if (unlocked) {
            lore.add("&eStatus: &fUnlocked \u2014 not yet created");
            lore.add("&7Use &f/haven &7to found it.");
        } else {
            lore.add("&8Status: Locked");
            lore.add("&7Unlocks through the Floor 4 story.");
        }
        return lore;
    }

    private static String miniBar(int filled, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < max; i++) b.append(i < filled ? "&a\u2588" : "&8\u2588");
        return b.toString();
    }

    private boolean hasGranted(String node) {
        return CompendiumStats.hasGranted(player, node);
    }

    private String firstJoined() {
        long first = player.getFirstPlayed();
        if (first <= 0) return "\u2014";
        return DATE.format(Instant.ofEpochMilli(first));
    }

    private static String formatHours(double hours) {
        if (hours < 1.0) return Math.round(hours * 60.0) + "m";
        long h = (long) hours;
        long m = Math.round((hours - h) * 60.0);
        return m > 0 ? (h + "h " + m + "m") : (h + "h");
    }

    private static String bar(String label, double pct, int weight) {
        int filled = (int) Math.round(pct * 10);
        StringBuilder b = new StringBuilder("&7" + label + ": ");
        for (int i = 0; i < 10; i++) b.append(i < filled ? "&a\u2588" : "&8\u2588");
        b.append(" &7(").append(Math.round(pct * 100)).append("% of ").append(weight).append(")");
        return b.toString();
    }

    private ItemStack filler(String iconSpec, String name) {
        ItemStack it = buildIcon(iconSpec);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Text.c(name == null ? " " : name));
            it.setItemMeta(m);
        }
        return it;
    }

    public Player getPlayer() { return player; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }
}
