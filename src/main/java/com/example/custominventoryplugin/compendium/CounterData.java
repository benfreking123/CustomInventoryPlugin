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
 * Generic cross-server counters (cip_counters). One row per (player, key).
 * Keys are dot-namespaced, e.g. {@code crystal.floor2}, {@code dungeon.floor3_dungeon},
 * {@code checkpoint.floor3_1}. Fed by the {@code /cipcount} console command
 * (Skript / MythicDungeons hooks) and read by the Compendium.
 */
public class CounterData {

    private final CustomInventoryPlugin plugin;
    private final Database database;

    public CounterData(CustomInventoryPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Atomically add {@code amount} to a counter; returns the new value (0 on error). */
    public int increment(UUID uuid, String key, int amount) {
        if (uuid == null || key == null || key.isBlank()) return 0;
        try (Connection c = database.getConnection();
             PreparedStatement ups = c.prepareStatement(
                     "INSERT INTO cip_counters (player_uuid, counter_key, value) VALUES (?,?,?) "
                             + "ON DUPLICATE KEY UPDATE value = value + VALUES(value)");
             PreparedStatement sel = c.prepareStatement(
                     "SELECT value FROM cip_counters WHERE player_uuid=? AND counter_key=?")) {
            ups.setString(1, uuid.toString());
            ups.setString(2, key);
            ups.setInt(3, amount);
            ups.executeUpdate();
            sel.setString(1, uuid.toString());
            sel.setString(2, key);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "counter increment failed for " + uuid + "/" + key, e);
        }
        return 0;
    }

    /** All counters for a player: key → value. Empty map on error. */
    public Map<String, Integer> loadAll(UUID uuid) {
        Map<String, Integer> out = new HashMap<>();
        if (uuid == null) return out;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT counter_key, value FROM cip_counters WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "counter loadAll failed for " + uuid, e);
        }
        return out;
    }

    /** Delete every counter row for a player (full account reset). */
    public void clearAllForPlayer(UUID uuid) {
        if (uuid == null) return;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM cip_counters WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "counter clearAll failed for " + uuid, e);
        }
    }

    /** Sum of all values whose key starts with {@code prefix} (e.g. "crystal."). */
    public static int sumPrefix(Map<String, Integer> counters, String prefix) {
        int n = 0;
        for (Map.Entry<String, Integer> e : counters.entrySet()) {
            if (e.getKey().startsWith(prefix)) n += e.getValue();
        }
        return n;
    }

    /** Number of distinct keys with {@code prefix} and value &gt; 0 (discovery flags). */
    public static int countPrefix(Map<String, Integer> counters, String prefix) {
        int n = 0;
        for (Map.Entry<String, Integer> e : counters.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue() > 0) n++;
        }
        return n;
    }
}
