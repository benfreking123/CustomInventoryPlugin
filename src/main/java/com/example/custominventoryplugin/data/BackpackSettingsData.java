package com.example.custominventoryplugin.data;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.PickupMode;
import com.example.custominventoryplugin.settings.BagSettings;
import com.example.custominventoryplugin.settings.PlayerPickupSettings;
import org.bukkit.Material;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * DAO for the v1.5.0 pickup settings:
 *   cip_player_pickup_settings    — player-level master toggles
 *   cip_player_backpack_settings  — per-(player, bag) mode/filter/grab/tier
 *
 * Write-through; the in-memory cache lives in
 * {@link com.example.custominventoryplugin.settings.BackpackSettingsCache}.
 */
public class BackpackSettingsData {

    private final CustomInventoryPlugin plugin;
    private final Database database;

    public BackpackSettingsData(CustomInventoryPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    // ─── player-level master toggles ──────────────────────────────────────

    public PlayerPickupSettings loadPlayer(UUID uuid) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT master_enabled, grab_everything FROM cip_player_pickup_settings WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerPickupSettings(rs.getBoolean(1), rs.getBoolean(2));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "loadPlayer pickup settings failed for " + uuid, e);
        }
        return PlayerPickupSettings.defaults();
    }

    public void savePlayer(UUID uuid, PlayerPickupSettings s) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO cip_player_pickup_settings (player_uuid, master_enabled, grab_everything) " +
                     "VALUES (?,?,?) ON DUPLICATE KEY UPDATE master_enabled=VALUES(master_enabled), " +
                     "grab_everything=VALUES(grab_everything)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, s.isMasterEnabled() ? 1 : 0);
            ps.setInt(3, s.isGrabEverything() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "savePlayer pickup settings failed for " + uuid, e);
        }
    }

    // ─── per-bag settings ─────────────────────────────────────────────────

    public Map<String, BagSettings> loadBags(UUID uuid) {
        Map<String, BagSettings> out = new HashMap<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT backpack_id, pickup_mode, filter_csv, grab_everything, tier " +
                     "FROM cip_player_backpack_settings WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PickupMode mode = PickupMode.fromString(rs.getString("pickup_mode"), PickupMode.OFF);
                    Set<Material> filter = decodeFilter(rs.getString("filter_csv"));
                    boolean grab = rs.getBoolean("grab_everything");
                    int tier = rs.getInt("tier");
                    out.put(rs.getString("backpack_id"), new BagSettings(mode, filter, grab, tier));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "loadBags pickup settings failed for " + uuid, e);
        }
        return out;
    }

    public void saveBag(UUID uuid, String backpackId, BagSettings s) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO cip_player_backpack_settings " +
                     "(player_uuid, backpack_id, pickup_mode, filter_csv, grab_everything, tier) " +
                     "VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " +
                     "pickup_mode=VALUES(pickup_mode), filter_csv=VALUES(filter_csv), " +
                     "grab_everything=VALUES(grab_everything), tier=VALUES(tier)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, backpackId);
            ps.setString(3, s.getMode().name());
            ps.setString(4, encodeFilter(s.getFilter()));
            ps.setInt(5, s.isGrabEverything() ? 1 : 0);
            ps.setInt(6, s.getTier());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "saveBag pickup settings failed for " + uuid + "/" + backpackId, e);
        }
    }

    // ─── filter (de)serialization ─────────────────────────────────────────

    private String encodeFilter(Set<Material> filter) {
        if (filter == null || filter.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Material m : filter) {
            if (sb.length() > 0) sb.append(',');
            sb.append(m.name());
        }
        return sb.toString();
    }

    private Set<Material> decodeFilter(String csv) {
        Set<Material> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            Material m = Material.matchMaterial(part.trim().toUpperCase());
            if (m != null) out.add(m);
        }
        return out;
    }

    /** Wipe pickup + per-bag settings for a player (admin /ci reset). */
    public void clearAllForPlayer(UUID uuid) {
        if (uuid == null) return;
        try (Connection c = database.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM cip_player_pickup_settings WHERE player_uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM cip_player_backpack_settings WHERE player_uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Settings clearAllForPlayer failed for " + uuid, e);
        }
    }
}
