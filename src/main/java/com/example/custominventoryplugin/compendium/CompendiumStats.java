package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One snapshot of every number the Compendium tracks for a player. Computed
 * on demand from LuckPerms nodes, Bukkit statistics, BetonQuest tags and the
 * CIP MariaDB tables. Shared by the hub GUI and the PAPI expansion so both
 * always agree.
 */
public final class CompendiumStats {

    public final double hours;
    public final int deaths;
    public final List<Integer> floorsDone;
    public final int floorCount;
    public final int highest;
    public final int bestiaryFound;
    public final int bestiaryTotal;
    public final List<QuestProgress.FloorQuests> questRows;
    public final int questsDone;
    public final int questsTotal;
    public final Map<String, Integer> counters;
    public final int crystals;
    public final int dungeonRuns;
    public final int checkpoints;
    public final int cratesOpened;
    public final int crateMimics;
    /** Breaches cleared (Breach plugin, {@code breach.completed}). */
    public final int breachCompleted;
    /** Highest breach tier with a clear ({@code breach.tier.<n>} > 0), 0 if none. */
    public final int breachHighest;
    public final double floorsPct;
    public final double questsPct;
    public final double bestiaryPct;
    public final double collectionsPct;
    public final double miscPct;
    public final int score;

    private CompendiumStats(double hours, int deaths, List<Integer> floorsDone, int highest,
                            int bestiaryFound, int bestiaryTotal,
                            List<QuestProgress.FloorQuests> questRows, int questsDone, int questsTotal,
                            Map<String, Integer> counters, int crystals, int dungeonRuns, int checkpoints,
                            int cratesOpened, int crateMimics,
                            int breachCompleted, int breachHighest,
                            double floorsPct, double questsPct, double bestiaryPct,
                            double collectionsPct, double miscPct, int score) {
        this.hours = hours;
        this.deaths = deaths;
        this.floorsDone = floorsDone;
        this.floorCount = floorsDone.size();
        this.highest = highest;
        this.bestiaryFound = bestiaryFound;
        this.bestiaryTotal = bestiaryTotal;
        this.questRows = questRows;
        this.questsDone = questsDone;
        this.questsTotal = questsTotal;
        this.counters = counters;
        this.crystals = crystals;
        this.dungeonRuns = dungeonRuns;
        this.checkpoints = checkpoints;
        this.cratesOpened = cratesOpened;
        this.crateMimics = crateMimics;
        this.breachCompleted = breachCompleted;
        this.breachHighest = breachHighest;
        this.floorsPct = floorsPct;
        this.questsPct = questsPct;
        this.bestiaryPct = bestiaryPct;
        this.collectionsPct = collectionsPct;
        this.miscPct = miscPct;
        this.score = score;
    }

    public static CompendiumStats compute(CustomInventoryPlugin plugin, Player player) {
        CompendiumConfig cfg = plugin.getCompendiumConfig();

        long ticks = safeStat(player, Statistic.PLAY_ONE_MINUTE);
        double hours = ticks / 20.0 / 3600.0;
        int deaths = safeStat(player, Statistic.DEATHS);

        List<Integer> floorsDone = new ArrayList<>();
        for (int n = 1; n <= cfg.totalFloors(); n++) {
            if (hasGranted(player, cfg.floorDoneNode(n))) floorsDone.add(n);
        }
        int highest = floorsDone.isEmpty() ? 0 : floorsDone.get(floorsDone.size() - 1);

        int bestiaryFound = 0;
        int bestiaryTotal = 0;
        BestiaryConfig bc = plugin.getBestiaryConfig();
        BestiaryData bd = plugin.getBestiaryData();
        if (bc != null && bd != null && !bc.entries().isEmpty()) {
            Map<String, Integer> kills = bd.loadAll(player.getUniqueId());
            int need = bc.thresholds().discovered;
            for (BestiaryEntry e : bc.entries().values()) {
                if (BestiaryData.sumForEntry(kills, e) >= need) bestiaryFound++;
            }
            bestiaryTotal = bc.entries().size();
        }

        QuestProgress quests = plugin.getQuestProgress();
        Set<String> tags = quests != null && !quests.isEmpty()
                ? quests.playerTags(player.getUniqueId()) : Collections.emptySet();
        List<QuestProgress.FloorQuests> questRows = quests != null
                ? quests.breakdown(tags) : List.of();
        int questsDone = 0;
        int questsTotal = 0;
        for (QuestProgress.FloorQuests fq : questRows) {
            questsDone += fq.done;
            questsTotal += fq.total;
        }

        CounterData counterData = plugin.getCounterData();
        Map<String, Integer> counters = counterData != null
                ? counterData.loadAll(player.getUniqueId()) : Map.of();
        int crystals = CounterData.sumPrefix(counters, cfg.crystalsPrefix());
        int dungeonRuns = CounterData.sumPrefix(counters, cfg.dungeonPrefix());
        int checkpoints = CounterData.countPrefix(counters, cfg.checkpointPrefix());
        // TowerCrates writes exactly one key per opening, with ".mimic" appended
        // when the crate woke a mimic — so the prefix sum is every opening and
        // the mimics are a subset of it, not an addition to it.
        int cratesOpened = CounterData.sumPrefix(counters, cfg.cratesPrefix());
        int crateMimics = CounterData.sumPrefixSuffix(counters, cfg.cratesPrefix(), ".mimic");
        // Breach writes one additive key per clear and one per tier cleared, so
        // "highest tier beaten" is the largest tier key with a count — /cipcount
        // can only add, and this keeps the plugin free of a set-or-max command.
        int breachCompleted = CounterData.sumPrefix(counters, cfg.breachPrefix() + "completed");
        int breachHighest = CounterData.maxNumericSuffix(counters, cfg.breachPrefix() + "tier.");

        double floorsPct = clamp01((double) floorsDone.size() / cfg.totalFloors());
        double questsPct = questsTotal == 0 ? 0.0 : clamp01((double) questsDone / questsTotal);
        double bestiaryPct = bestiaryTotal == 0 ? 0.0 : clamp01((double) bestiaryFound / bestiaryTotal);
        CollectionsConfig collectionsCfg = plugin.getCollectionsConfig();
        CollectionsService collectionsSvc = plugin.getCollectionsService();
        double collectionsPct = 0.0;
        if (collectionsCfg != null && collectionsSvc != null && !collectionsCfg.isEmpty()) {
            int total = collectionsCfg.totalTrackable();
            if (total > 0) {
                collectionsPct = clamp01((double) collectionsSvc.discovered(counters) / total);
            }
        }
        double miscPct = clamp01(hours / cfg.miscFullHours());

        int score = (int) Math.round(
                cfg.weightFloors() * floorsPct + cfg.weightQuests() * questsPct
                        + cfg.weightBestiary() * bestiaryPct
                        + cfg.weightCollections() * collectionsPct + cfg.weightMisc() * miscPct);

        return new CompendiumStats(hours, deaths, floorsDone, highest,
                bestiaryFound, bestiaryTotal, questRows, questsDone, questsTotal,
                counters, crystals, dungeonRuns, checkpoints, cratesOpened, crateMimics,
                breachCompleted, breachHighest,
                floorsPct, questsPct, bestiaryPct, collectionsPct, miscPct, score);
    }

    private static int safeStat(Player player, Statistic stat) {
        try { return player.getStatistic(stat); }
        catch (Exception e) { return 0; }
    }

    /**
     * True only when a permission is <em>explicitly</em> granted (by LuckPerms),
     * not when it merely resolves true via Bukkit's op fallback for unregistered
     * nodes. Without this, opped players see every {@code tower.*} node as true.
     */
    public static boolean hasGranted(Player player, String node) {
        return player.isPermissionSet(node) && player.hasPermission(node);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
