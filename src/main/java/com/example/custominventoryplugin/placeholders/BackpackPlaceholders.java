package com.example.custominventoryplugin.placeholders;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.combat.CritCalculator;
import com.example.custominventoryplugin.compendium.CompendiumStats;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.compendium.QuestProgress;
import com.example.custominventoryplugin.config.BackpackConfig;
import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.data.BackpackData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import studio.magemonkey.fabled.Fabled;
import studio.magemonkey.fabled.api.player.PlayerData;

import java.text.DecimalFormat;
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
 *   %customip_breach_completed% %customip_breach_highest%
 *   %customip_crates_opened% %customip_crate_mimics%
 *   %customip_quests_done% / _total / _pct / _bar
 *   %customip_quests_floor1_done% / _total / _pct / _bar   (any floor key)
 *
 * Stats panel:
 *   %customip_stats_tab%                 — offense | defense | utility | off
 *   %customip_crit_base_<cat>%           — flat crit % this player adds
 *   %customip_crit_inc_<cat>%            — increased crit chance %
 *   %customip_crit_mult_<cat>%           — crit damage multiplier
 *     for <cat> of attack | projectile | spell. These are the layers, true for
 *     every skill and basic attack in that category.
 *   %customip_crit_chance_attack% / _projectile%
 *                                        — whole basic-attack crit %, which
 *     only exists where there is a configured baseline to add the layer to
 *
 * Falls back to "0" / "-" for unknown ids so HUDs don't break.
 */
public class BackpackPlaceholders extends PlaceholderExpansion {

    private final CustomInventoryPlugin plugin;
    private final BackpackConfig config;
    private final BackpackData data;

    /** Locale-fixed so the HUD never renders a comma decimal separator. */
    private static final DecimalFormat ONE_DP =
            new DecimalFormat("0.#", java.text.DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final DecimalFormat TWO_DP =
            new DecimalFormat("0.##", java.text.DecimalFormatSymbols.getInstance(Locale.ROOT));

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

        if (params.regionMatches(true, 0, "crit_", 0, 5)) {
            String crit = critValue(player, params.toLowerCase(Locale.ROOT));
            if (crit != null) return crit;
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

    // ── crit placeholders ───────────────────────────────────────────────────

    /**
     * Effective crit for the stats panel, or null when {@code params} isn't a
     * crit placeholder.
     *
     * <p>Deliberately routed through {@link CritCalculator}, the same class
     * {@code BasicAttackCritListener} rolls against, so the panel reports the
     * rate the game actually uses rather than a second copy of the formula
     * that can quietly fall out of step.
     *
     * <p>Only melee and bow expose a <em>chance</em>: those have a known flat
     * baseline from config. A spell's base chance is written into each skill
     * individually, so there is no single honest number to print — spells get
     * a multiplier only.
     */
    private String critValue(OfflinePlayer player, String params) {
        String kind;
        if (params.startsWith("crit_chance_")) kind = "chance";
        else if (params.startsWith("crit_mult_")) kind = "mult";
        else if (params.startsWith("crit_base_")) kind = "base";
        else if (params.startsWith("crit_inc_")) kind = "inc";
        else return null;

        String category = params.substring(params.lastIndexOf('_') + 1);
        boolean real = category.equals(CritCalculator.MELEE)
                || category.equals(CritCalculator.RANGED)
                || category.equals(CritCalculator.SPELL);
        // Only melee and bow have a basic attack to state a whole chance for.
        if (!real || (kind.equals("chance") && category.equals(CritCalculator.SPELL))) {
            return null;
        }

        ConfigManager cfg = plugin.getConfigManager();
        if (cfg == null || !cfg.isCritEnabled()) return "0";

        PlayerData data;
        try {
            data = Fabled.getData(player);
        } catch (Throwable ignored) {
            return "0";
        }

        switch (kind) {
            case "base":
                return ONE_DP.format(CritCalculator.baseLayer(data, category));
            case "inc":
                return ONE_DP.format(CritCalculator.increasedLayer(data, category));
            case "mult":
                return TWO_DP.format(CritCalculator.multiplier(
                        data, category, cfg.getCritBaseMultiplier()));
            default:
                return ONE_DP.format(CritCalculator.chance(
                        data, category, cfg.getCritBaseChance()));
        }
    }

    // ── compendium placeholders ─────────────────────────────────────────────

    /** Value for a compendium placeholder, or null when {@code params} isn't one. */
    private String compendiumValue(OfflinePlayer op, String params) {
        boolean known = params.equals("score")
                || params.startsWith("bestiary_")
                || params.equals("camps") || params.equals("crystals") || params.equals("dungeon_runs")
                || params.equals("crates_opened") || params.equals("crate_mimics")
                || params.equals("breach_completed") || params.equals("breach_highest")
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
            case "crates_opened":  return Integer.toString(st.cratesOpened);
            case "crate_mimics":   return Integer.toString(st.crateMimics);
            case "breach_completed": return Integer.toString(st.breachCompleted);
            case "breach_highest": return Integer.toString(st.breachHighest);
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
