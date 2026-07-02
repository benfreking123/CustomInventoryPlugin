package com.example.custominventoryplugin.data;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Level;

/**
 * MariaDB-backed backpack storage. Unlike {@link PlayerGearData}, this class
 * intentionally holds <strong>no in-memory cache</strong>:
 *
 *   • Load fresh contents on every {@code /bp} open
 *   • Write through on every click that mutates a slot
 *
 * This guarantees cross-server consistency without proxy-side messaging.
 * The DB is fast enough — a 54-slot backpack is one SELECT returning ≤54
 * rows, and each save is one INSERT…ON DUPLICATE KEY UPDATE.
 *
 * Table layout (created by {@link Database}):
 *   cip_player_backpack (player_uuid, backpack_id, slot_index) → item_data
 *
 * {@code item_data} is Paper's {@link ItemStack#serializeAsBytes()} output
 * wrapped in Base64 — same encoding {@link PlayerGearData} uses.
 */
public class BackpackData {

    private final CustomInventoryPlugin plugin;
    private final Database database;

    public BackpackData(CustomInventoryPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /**
     * Load a 0-indexed array of size {@code size}. Missing rows yield {@code null}.
     * Returns a fresh array on every call — callers should not cache it.
     */
    public ItemStack[] load(UUID uuid, String backpackId, int size) {
        ItemStack[] out = new ItemStack[size];
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT slot_index, item_data FROM cip_player_backpack " +
                     "WHERE player_uuid=? AND backpack_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, backpackId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int idx = rs.getInt("slot_index");
                    if (idx < 0 || idx >= size) continue;
                    out[idx] = decode(rs.getString("item_data"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Backpack load failed for " + uuid + "/" + backpackId, e);
        }
        return out;
    }

    /**
     * Persist a single slot. Null/AIR deletes the row.
     */
    public void saveSlot(UUID uuid, String backpackId, int slotIndex, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            deleteSlot(uuid, backpackId, slotIndex);
            return;
        }
        String encoded = encode(item);
        if (encoded == null) {
            deleteSlot(uuid, backpackId, slotIndex);
            return;
        }
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO cip_player_backpack (player_uuid, backpack_id, slot_index, item_data) " +
                     "VALUES (?,?,?,?) " +
                     "ON DUPLICATE KEY UPDATE item_data=VALUES(item_data)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, backpackId);
            ps.setInt(3, slotIndex);
            ps.setString(4, encoded);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Backpack saveSlot failed for " + uuid + "/" + backpackId + "#" + slotIndex, e);
        }
    }

    public void deleteSlot(UUID uuid, String backpackId, int slotIndex) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM cip_player_backpack WHERE player_uuid=? AND backpack_id=? AND slot_index=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, backpackId);
            ps.setInt(3, slotIndex);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Backpack deleteSlot failed for " + uuid + "/" + backpackId + "#" + slotIndex, e);
        }
    }

    /**
     * Replace every slot of one backpack in a single batch. Used by
     * smart-pickup which can mutate multiple slots in one event.
     */
    public void saveAll(UUID uuid, String backpackId, ItemStack[] contents) {
        saveAll(uuid, backpackId, contents, contents.length);
    }

    /**
     * Storage-bounded variant: only slots {@code [0, limit)} are persisted.
     * Slots {@code >= limit} (the GUI control bar) are ignored, and any stray
     * rows beyond {@code limit} are removed by the leading DELETE. This is the
     * fix for control-bar buttons leaking into storage.
     */
    public void saveAll(UUID uuid, String backpackId, ItemStack[] contents, int limit) {
        int bound = Math.min(limit, contents.length);
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM cip_player_backpack WHERE player_uuid=? AND backpack_id=?")) {
                del.setString(1, uuid.toString());
                del.setString(2, backpackId);
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO cip_player_backpack (player_uuid, backpack_id, slot_index, item_data) " +
                    "VALUES (?,?,?,?)")) {
                for (int i = 0; i < bound; i++) {
                    ItemStack item = contents[i];
                    if (item == null || item.getType().isAir()) continue;
                    String encoded = encode(item);
                    if (encoded == null) continue;
                    ins.setString(1, uuid.toString());
                    ins.setString(2, backpackId);
                    ins.setInt(3, i);
                    ins.setString(4, encoded);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Backpack saveAll failed for " + uuid + "/" + backpackId, e);
        }
    }

    /** Count non-null/non-air slots. Cheap — one SELECT COUNT. */
    public int countUsed(UUID uuid, String backpackId) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM cip_player_backpack WHERE player_uuid=? AND backpack_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, backpackId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Backpack countUsed failed for " + uuid + "/" + backpackId, e);
        }
        return 0;
    }

    /**
     * One-query batched fill counts for every backpack a player owns rows in.
     * Returned map's keys are backpack ids; missing keys mean zero rows in DB
     * (i.e. the bag is empty). Used by /bp list to render N icons without
     * issuing N SELECTs.
     */
    public java.util.Map<String, Integer> countAllUsed(UUID uuid) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT backpack_id, COUNT(*) FROM cip_player_backpack " +
                     "WHERE player_uuid=? GROUP BY backpack_id")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Backpack countAllUsed failed for " + uuid, e);
        }
        return out;
    }

    // ─── encoding (same scheme as PlayerGearData) ─────────────────────────

    private String encode(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try {
            return Base64.getEncoder().encodeToString(item.serializeAsBytes());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "encode failed", e);
            return null;
        }
    }

    private ItemStack decode(String encoded) {
        if (encoded == null) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "decode failed", e);
            return null;
        }
    }
}
