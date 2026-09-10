package com.example.custominventoryplugin.party;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a player wants the party sidebar. Per-player and shared across
 * backends, so the choice survives a walk from social to zone1.
 *
 * <p>The cache is authoritative once loaded: the sidebar is redrawn on a timer,
 * and a database read per player per tick-cycle is not worth it.
 */
public final class PartyHudStore {

    private final CustomInventoryPlugin plugin;
    private final Database database;
    private final Map<UUID, Boolean> cache = new ConcurrentHashMap<>();

    public PartyHudStore(CustomInventoryPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Default ON: a party member who never opens the GUI still sees the roster. */
    public boolean isEnabled(UUID playerId) {
        if (playerId == null) return false;
        Boolean cached = cache.get(playerId);
        return cached != null ? cached : true;
    }

    /** Warm the cache off-thread; call on join so the first draw is correct. */
    public void load(UUID playerId) {
        if (playerId == null) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = database.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT show_board FROM cip_party_hud WHERE player_uuid = ?")) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    cache.put(playerId, !rs.next() || rs.getBoolean(1));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("cip_party_hud load failed: " + e.getMessage());
            }
        });
    }

    public void set(UUID playerId, boolean enabled) {
        if (playerId == null) return;
        cache.put(playerId, enabled);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = database.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO cip_party_hud (player_uuid, show_board) VALUES (?, ?) "
                                 + "ON DUPLICATE KEY UPDATE show_board = VALUES(show_board)")) {
                ps.setString(1, playerId.toString());
                ps.setBoolean(2, enabled);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("cip_party_hud upsert failed: " + e.getMessage());
            }
        });
    }

    public void forget(UUID playerId) {
        if (playerId != null) cache.remove(playerId);
    }
}
