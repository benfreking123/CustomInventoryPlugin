package com.example.custominventoryplugin.party;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Party sidebar: leader, roster with who is on this backend, size against the
 * cap, and the loot mode. Redrawn on a timer rather than driven by Parties
 * events, because the interesting facts (who is online, who is on THIS server)
 * change without any party event firing.
 *
 * <p>Giving a player their own {@link Scoreboard} is the only way to show them
 * a personal sidebar, but it costs them anything the main scoreboard was
 * providing — here that is the loot glow tint, which is applied through
 * per-colour teams and read from the VIEWER's scoreboard. So every board this
 * class hands out mirrors those teams, and {@code LootEffectService} writes its
 * entries to all of them. Without that, turning the sidebar on would silently
 * grey out rarity glow.
 */
public final class PartyScoreboardService {

    /** Matches LootEffectService's naming so mirrored teams line up. */
    public static final String GLOW_TEAM_PREFIX = "cip_lt_";

    private static final int REFRESH_TICKS = 40;
    private static final int MAX_LINES = 15;

    private final CustomInventoryPlugin plugin;
    private final PartyService parties;
    private final PartyHudStore store;
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private BukkitTask task;

    public PartyScoreboardService(CustomInventoryPlugin plugin, PartyService parties, PartyHudStore store) {
        this.plugin = plugin;
        this.parties = parties;
        this.store = store;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, REFRESH_TICKS, REFRESH_TICKS);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : List.copyOf(boards.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) detach(p);
            boards.remove(id);
        }
    }

    /** Boards currently shown to players, for LootEffectService to mirror into. */
    public Collection<Scoreboard> activeBoards() {
        return boards.values();
    }

    public boolean isEnabled(Player player) {
        return store.isEnabled(player.getUniqueId());
    }

    /** Flip the preference and apply it immediately. Returns the new state. */
    public boolean toggle(Player player) {
        boolean next = !isEnabled(player);
        store.set(player.getUniqueId(), next);
        if (next) {
            refresh(player);
        } else {
            detach(player);
            boards.remove(player.getUniqueId());
        }
        return next;
    }

    public void handleQuit(Player player) {
        boards.remove(player.getUniqueId());
        store.forget(player.getUniqueId());
    }

    private void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isEnabled(player)) {
                refresh(player);
            } else if (boards.containsKey(player.getUniqueId())) {
                detach(player);
                boards.remove(player.getUniqueId());
            }
        }
    }

    /**
     * Hand the player back to the main scoreboard. Leaving them on a personal
     * board with no sidebar would keep them cut off from the glow teams.
     */
    private void detach(Player player) {
        try {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        } catch (Throwable t) {
            plugin.getLogger().warning("party sidebar detach failed for " + player.getName() + ": " + t.getMessage());
        }
    }

    private void refresh(Player player) {
        List<String> lines = buildLines(player);
        if (lines.isEmpty()) {
            // Not in a party and nothing worth a panel — don't hold the sidebar.
            if (boards.containsKey(player.getUniqueId())) {
                detach(player);
                boards.remove(player.getUniqueId());
            }
            return;
        }
        try {
            Scoreboard board = boards.get(player.getUniqueId());
            if (board == null || player.getScoreboard() != board) {
                board = Bukkit.getScoreboardManager().getNewScoreboard();
                mirrorGlowTeams(board);
                boards.put(player.getUniqueId(), board);
                player.setScoreboard(board);
            }
            draw(board, lines);
        } catch (Throwable t) {
            plugin.getLogger().warning("party sidebar failed for " + player.getName() + ": " + t.getMessage());
            boards.remove(player.getUniqueId());
        }
    }

    /** Copy the loot-glow teams so rarity tint survives the personal board. */
    private void mirrorGlowTeams(Scoreboard board) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team source : main.getTeams()) {
            if (!source.getName().startsWith(GLOW_TEAM_PREFIX)) continue;
            if (board.getTeam(source.getName()) != null) continue;
            Team copy = board.registerNewTeam(source.getName());
            copy.setColor(source.getColor());
            for (String entry : source.getEntries()) {
                copy.addEntry(entry);
            }
        }
    }

    private void draw(Scoreboard board, List<String> lines) {
        Objective obj = board.getObjective("cip_party");
        if (obj == null) {
            obj = board.registerNewObjective("cip_party", "dummy", "\u00a75\u00a7lParty");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        // Scores are the line ORDER, so clear stale entries or a shrinking
        // roster leaves ghost lines behind.
        for (String entry : List.copyOf(board.getEntries())) {
            board.resetScores(entry);
        }
        int score = lines.size();
        for (String line : lines) {
            obj.getScore(unique(line, score)).setScore(score);
            score--;
        }
    }

    /**
     * Sidebar entries are keyed by string, so two identical lines collapse into
     * one. Pad with colour codes, which are invisible and never collide.
     */
    private String unique(String line, int slot) {
        String text = line.length() > 40 ? line.substring(0, 40) : line;
        return text + "\u00a7r\u00a7" + Integer.toHexString(slot % 16);
    }

    private List<String> buildLines(Player viewer) {
        List<String> lines = new ArrayList<>();
        if (!parties.isAvailable()) return lines;

        Optional<UUID> partyId = parties.getPartyId(viewer.getUniqueId());
        if (partyId.isEmpty()) {
            // Nothing to show; the GUI already explains how to make a party.
            return lines;
        }

        UUID pid = partyId.get();
        List<UUID> members = parties.getMembers(pid);
        if (members.size() < 2) {
            // Parties auto-creates a party on invite and keeps it when the
            // last other member leaves, so most players sit in a party of one
            // for good. To them that is "not in a party" — no sidebar until
            // somebody else is actually in it.
            return lines;
        }
        UUID leader = parties.getLeader(pid).orElse(null);

        lines.add("\u00a78Members \u00a7f" + members.size() + "\u00a77/\u00a7f4");
        for (UUID id : members) {
            if (lines.size() >= MAX_LINES - 2) break;
            lines.add(memberLine(viewer, id, leader));
        }
        // Cached-only: this runs on a 2s main-thread timer, and the loot mode
        // cache is dropped on every party join. Omitted for the one draw after
        // a miss rather than blocking the tick loop on a query.
        parties.peekLootMode(pid)
                .ifPresent(mode -> lines.add("\u00a78Loot \u00a7f" + mode.displayName()));
        return lines;
    }

    /**
     * Crown marks the leader; colour marks reachability, which is the fact
     * players actually need — loot splitting, XP share and dungeon entry all
     * only reach members on the SAME backend, and Parties happily spans them.
     */
    private String memberLine(Player viewer, UUID id, UUID leader) {
        Player online = Bukkit.getPlayer(id);
        boolean here = online != null && online.isOnline();
        String name = here ? online.getName() : nameOf(id);
        String colour = here ? "\u00a7a" : "\u00a78";
        String mark = id.equals(leader) ? "\u00a76\u2726 " : "\u00a77- ";
        String self = id.equals(viewer.getUniqueId()) ? "\u00a77 (you)" : "";
        return mark + colour + name + self;
    }

    private String nameOf(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : id.toString().substring(0, 8);
    }
}
