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
 * Per-party settings keyed on AlessioDP {@code Party.getId()}. Cross-server via
 * shared MariaDB; short-lived local cache invalidated on create/delete/join.
 */
public final class PartySettingsStore {

    private final CustomInventoryPlugin plugin;
    private final Database database;
    private final Map<UUID, LootMode> cache = new ConcurrentHashMap<>();

    public PartySettingsStore(CustomInventoryPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public LootMode getLootMode(UUID partyId) {
        if (partyId == null) return LootMode.ROUND_ROBIN;
        LootMode cached = cache.get(partyId);
        if (cached != null) return cached;
        LootMode loaded = load(partyId);
        cache.put(partyId, loaded);
        return loaded;
    }

    public void setLootMode(UUID partyId, LootMode mode) {
        if (partyId == null || mode == null) return;
        cache.put(partyId, mode);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> upsert(partyId, mode));
    }

    public void ensureDefault(UUID partyId) {
        if (partyId == null) return;
        cache.putIfAbsent(partyId, LootMode.ROUND_ROBIN);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> upsert(partyId, LootMode.ROUND_ROBIN));
    }

    public void delete(UUID partyId) {
        if (partyId == null) return;
        cache.remove(partyId);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = database.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM cip_party_settings WHERE party_id = ?")) {
                ps.setString(1, partyId.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("cip_party_settings delete failed: " + e.getMessage());
            }
        });
    }

    public void invalidate(UUID partyId) {
        if (partyId != null) cache.remove(partyId);
    }

    private LootMode load(UUID partyId) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT loot_mode FROM cip_party_settings WHERE party_id = ?")) {
            ps.setString(1, partyId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return LootMode.fromString(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("cip_party_settings load failed: " + e.getMessage());
        }
        return LootMode.ROUND_ROBIN;
    }

    private void upsert(UUID partyId, LootMode mode) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO cip_party_settings (party_id, loot_mode) VALUES (?, ?) "
                             + "ON DUPLICATE KEY UPDATE loot_mode = VALUES(loot_mode)")) {
            ps.setString(1, partyId.toString());
            ps.setString(2, mode.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("cip_party_settings upsert failed: " + e.getMessage());
        }
    }
}
