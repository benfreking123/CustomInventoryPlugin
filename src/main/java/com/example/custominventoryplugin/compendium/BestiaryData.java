package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * MariaDB kill counters for the bestiary. One row per (player, mythic_id).
 * Write-through on every kill; reads are one SELECT for the whole player.
 */
public class BestiaryData {

    private final CustomInventoryPlugin plugin;
    private final Database database;

    public BestiaryData(CustomInventoryPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Atomically increment kills for one mythic id; returns the new total. */
    public int increment(UUID uuid, String mythicId) {
        if (uuid == null || mythicId == null || mythicId.isBlank()) return 0;
        try (Connection c = database.getConnection();
             PreparedStatement ups = c.prepareStatement(
                     "INSERT INTO cip_bestiary_kills (player_uuid, mythic_id, kills) VALUES (?,?,1) "
                             + "ON DUPLICATE KEY UPDATE kills = kills + 1");
             PreparedStatement sel = c.prepareStatement(
                     "SELECT kills FROM cip_bestiary_kills WHERE player_uuid=? AND mythic_id=?")) {
            ups.setString(1, uuid.toString());
            ups.setString(2, mythicId);
            ups.executeUpdate();
            sel.setString(1, uuid.toString());
            sel.setString(2, mythicId);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "bestiary increment failed for " + uuid + "/" + mythicId, e);
        }
        return 0;
    }

    /** All kill rows for a player: mythic_id → kills. Empty map on error. */
    public Map<String, Integer> loadAll(UUID uuid) {
        Map<String, Integer> out = new HashMap<>();
        if (uuid == null) return out;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT mythic_id, kills FROM cip_bestiary_kills WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "bestiary loadAll failed for " + uuid, e);
        }
        return out;
    }

    /** Delete every kill row for a player (full account reset). */
    public void clearAllForPlayer(UUID uuid) {
        if (uuid == null) return;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM cip_bestiary_kills WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "bestiary clearAll failed for " + uuid, e);
        }
    }

    public int totalForEntry(UUID uuid, BestiaryEntry entry) {
        if (entry == null) return 0;
        Map<String, Integer> all = loadAll(uuid);
        return sumForEntry(all, entry);
    }

    public static int sumForEntry(Map<String, Integer> kills, BestiaryEntry entry) {
        if (kills == null || entry == null) return 0;
        int n = 0;
        for (String id : entry.allTrackedIds()) {
            n += kills.getOrDefault(id, 0);
        }
        return n;
    }

    public static int eliteKills(Map<String, Integer> kills, BestiaryEntry entry) {
        if (kills == null || entry == null) return 0;
        int n = 0;
        for (String id : entry.getEliteIds()) {
            n += kills.getOrDefault(id, 0);
        }
        return n;
    }
}
