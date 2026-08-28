package com.example.custominventoryplugin.placeholders;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.compendium.CompendiumStats;
import com.example.custominventoryplugin.compendium.QuestProgress;
import com.example.custominventoryplugin.config.BackpackConfig;
import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.data.BackpackData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PlaceholderAPI expansion for CIP. Registers under the prefix
 * {@code customip}.
 *
 * Backpacks:
 *   %customip_backpack_pouch_used%       — slots currently used in 'pouch'
 *   %customip_backpack_pouch_size%       — total capacity of 'pouch'
 *   %customip_backpack_pouch_free%       — pouch.size - pouch.used
 *   %customip_backpack_pouch_pct%        — round(100 * used/size)
 *   %customip_backpack_total_used%       — sum of used across accessible bags
 *
 * Compendium (cached ~5s per player; online players only):
 *   %customip_score%                     — weighted account progress 0-100
 *   %customip_bestiary_found% / _total / _pct
 *   %customip_camps% %customip_crystals% %customip_dungeon_runs%
 *   %customip_quests_done% / _total / _pct / _bar
 *   %customip_quests_floor1_done% / _total / _pct / _bar   (any floor key)
 *
 * Stats panel:
 *   %customip_stats_tab%                 — offense | defense | utility | off
 *
 * Falls back to "0" / "-" for unknown ids so HUDs don't break.
 */
public class BackpackPlaceholders extends PlaceholderExpansion {

    private final CustomInventoryPlugin plugin;
    private final BackpackConfig config;
    private final BackpackData data;

    /** Short-lived stats cache: HUDs poll every tick, MariaDB shouldn't. */
    private static final long STATS_TTL_MS = 5000L;
    private final Map<UUID, CachedStats> statsCache = new ConcurrentHashMap<>();

    private record CachedStats(long expires, CompendiumStats stats) { }

    public BackpackPlaceholders(CustomInventoryPlugin plugin, BackpackConfig config, BackpackData data) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
    }

    @Override public @NotNull String getIdentifier() { return "customip"; }
    @Override public @NotNull String getAuthor()     { return "TheTower"; }
    @Override public @NotNull String getVersion()    { return plugin.getDescription().getVersion(); }
    @Override public boolean persist()               { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        // Which stats-panel tab is open. Every stats HUD gates itself on this in
        // a HUD-level `conditions:` block, so it is read once per player per
        // tick — keep it allocation-free and ahead of the heavier lookups.
        if (params.equalsIgnoreCase("stats_tab")) {
            return com.example.custominventoryplugin.inventory.StatsPanel.tab(player.getUniqueId());
        }

        String compendium = compendiumValue(player, params.toLowerCase(Locale.ROOT));
        if (compendium != null) return compendium;

        // Strip optional "backpack_" prefix to keep the placeholder names short
        // and consistent.
        if (params.startsWith("backpack_")) params = params.substring("backpack_".length());

        if (params.equalsIgnoreCase("total_used")) {
            int total = 0;
            for (BackpackDef def : config.all().values()) {
                if (player.isOnline() && def.canAccess(player.getPlayer())) {
                    total += data.countUsed(player.getUniqueId(), def.getId());
                }
            }
            return Integer.toString(total);
        }

        // Pattern: <id>_<field>
        int sep = params.lastIndexOf('_');
        if (sep <= 0 || sep == params.length() - 1) return "";

        String id = params.substring(0, sep).toLowerCase();
        String field = params.substring(sep + 1).toLowerCase();

        BackpackDef def = config.get(id);
        if (def == null) return "-";

        int used = data.countUsed(player.getUniqueId(), def.getId());
        int size = def.getSize();
        return switch (field) {
            case "used" -> Integer.toString(used);
            case "size" -> Integer.toString(size);
            case "free" -> Integer.toString(Math.max(0, size - used));
            case "pct"  -> Integer.toString(size == 0 ? 0 : (int) Math.round(100.0 * used / size));
            default     -> "";
        };
    }

    // ── compendium placeholders ─────────────────────────────────────────────

    /** Value for a compendium placeholder, or null when {@code params} isn't one. */
    private String compendiumValue(OfflinePlayer op, String params) {
        boolean known = params.equals("score")
                || params.startsWith("bestiary_")
                || params.equals("camps") || params.equals("crystals") || params.equals("dungeon_runs")
                || params.startsWith("quests_");
        if (!known) return null;

        Player player = op.getPlayer();
        if (player == null) return "";
        CompendiumStats st = stats(player);

        switch (params) {
            case "score":          return Integer.toString(st.score);
            case "camps":          return Integer.toString(st.checkpoints);
            case "crystals":       return Integer.toString(st.crystals);
            case "dungeon_runs":   return Integer.toString(st.dungeonRuns);
            case "bestiary_found": return Integer.toString(st.bestiaryFound);
            case "bestiary_total": return Integer.toString(st.bestiaryTotal);
            case "bestiary_pct":   return pct(st.bestiaryFound, st.bestiaryTotal);
            case "quests_done":    return Integer.toString(st.questsDone);
            case "quests_total":   return Integer.toString(st.questsTotal);
            case "quests_pct":     return pct(st.questsDone, st.questsTotal);
            case "quests_bar":     return bar(st.questsDone, st.questsTotal);
            default: break;
        }

        // quests_<floorKey>_<done|total|pct|bar>
        if (params.startsWith("quests_")) {
            String rest = params.substring("quests_".length());
            int sep = rest.lastIndexOf('_');
            if (sep <= 0 || sep == rest.length() - 1) return "";
            String key = rest.substring(0, sep);
            String field = rest.substring(sep + 1);
            for (QuestProgress.FloorQuests fq : st.questRows) {
                if (!fq.key.equalsIgnoreCase(key)) continue;
                return switch (field) {
                    case "done"  -> Integer.toString(fq.done);
                    case "total" -> Integer.toString(fq.total);
                    case "pct"   -> pct(fq.done, fq.total);
                    case "bar"   -> bar(fq.done, fq.total);
                    default      -> "";
                };
            }
            return "-";
        }
        return "";
    }

    private CompendiumStats stats(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        CachedStats cached = statsCache.get(uuid);
        if (cached != null && cached.expires > now) return cached.stats;
        CompendiumStats st = CompendiumStats.compute(plugin, player);
        statsCache.put(uuid, new CachedStats(now + STATS_TTL_MS, st));
        return st;
    }

    private static String pct(int done, int total) {
        return Integer.toString(total == 0 ? 0 : (int) Math.round(100.0 * done / total));
    }

    /** 10-segment legacy-color progress bar, e.g. {@code &a██&8████████}. */
    private static String bar(int done, int total) {
        int filled = total == 0 ? 0 : (int) Math.round(10.0 * done / total);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(i < filled ? "&a\u2588" : "&8\u2588");
        return b.toString();
    }
}
