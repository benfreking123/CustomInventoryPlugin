package com.example.custominventoryplugin.data;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Player gear/attribute store, backed by MariaDB through {@link Database}.
 *
 * Public API is identical to the v1.0 YAML-backed implementation so existing
 * callers (ArmorHandler, AttributeHandler, GearInventory) need no changes.
 *
 * Per-(uuid, slot) state lives in three tables:
 *   cip_player_gear        — equipped ItemStack
 *   cip_player_slot_attrs  — Fabled attribute deltas (slot → {attr → value})
 *   cip_player_slot_perms  — slot → permission node (for skill gem revoke)
 *
 * In-memory caches are populated on PlayerJoinEvent and write-through on
 * every mutation. Permissions themselves persist via LuckPerms (cross-server);
 * cip_player_slot_perms only tracks WHICH slot granted what so we can revoke.
 */
public class PlayerGearData {

    private static final Map<UUID, Map<String, ItemStack>> playerGear = new HashMap<>();
    public  static final Map<UUID, Map<String, Map<String, Integer>>> playerSlotAttributes = new HashMap<>();
    private static final Map<UUID, Map<String, String>> playerSlotPerms = new HashMap<>();
    /** Slots whose gem skill was force-levelled 0 -> 1 for free (see Database free_level). */
    private static final Map<UUID, Set<String>> playerSlotFreeLevel = new HashMap<>();
    private static final Set<UUID> loadedPlayers = new HashSet<>();

    private static Plugin plugin;
    private static ConfigManager configManager;
    private static Database database;

    public static void initialize(Plugin plugin, Database database) {
        if (plugin == null) throw new IllegalArgumentException("Plugin cannot be null");
        if (database == null) throw new IllegalArgumentException("Database cannot be null");
        PlayerGearData.plugin = plugin;
        PlayerGearData.database = database;
        PlayerGearData.configManager = ((CustomInventoryPlugin) plugin).getConfigManager();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Player session lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    public static void loadPlayerData(UUID uuid) {
        if (uuid == null) return;
        if (loadedPlayers.contains(uuid)) return;

        try (Connection c = database.getConnection()) {
            // Gear
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT slot_id, item_data FROM cip_player_gear WHERE player_uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, ItemStack> gear = new HashMap<>();
                    while (rs.next()) {
                        ItemStack stack = decodeItem(rs.getString("item_data"));
                        if (stack != null) gear.put(rs.getString("slot_id"), stack);
                    }
                    if (!gear.isEmpty()) playerGear.put(uuid, gear);
                }
            }

            // Slot attribute deltas
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT slot_id, attr_name, attr_value FROM cip_player_slot_attrs WHERE player_uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, Map<String, Integer>> attrs = new HashMap<>();
                    while (rs.next()) {
                        attrs.computeIfAbsent(rs.getString("slot_id"), k -> new HashMap<>())
                             .put(rs.getString("attr_name"), rs.getInt("attr_value"));
                    }
                    if (!attrs.isEmpty()) playerSlotAttributes.put(uuid, attrs);
                }
            }

            // Slot → permission map
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT slot_id, permission, free_level FROM cip_player_slot_perms WHERE player_uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, String> perms = new HashMap<>();
                    Set<String> free = new HashSet<>();
                    while (rs.next()) {
                        perms.put(rs.getString("slot_id"), rs.getString("permission"));
                        if (rs.getInt("free_level") != 0) free.add(rs.getString("slot_id"));
                    }
                    if (!perms.isEmpty()) playerSlotPerms.put(uuid, perms);
                    if (!free.isEmpty()) playerSlotFreeLevel.put(uuid, free);
                }
            }

            loadedPlayers.add(uuid);
            logDebug("Loaded data for " + uuid);
        } catch (SQLException e) {
            logWarning("Failed to load gear data for " + uuid, e);
        }
    }

    /**
     * Whether this player's slot rows are in the cache. Callers that hand out a
     * revocable grant (Fabled attribute points) must check this first: the row
     * that records the grant is the only way to take it back, so granting while
     * the ledger is missing makes the grant permanent.
     */
    public static boolean isLoaded(UUID uuid) {
        return uuid != null && loadedPlayers.contains(uuid);
    }

    public static void unloadPlayerData(UUID uuid) {
        if (uuid == null || !loadedPlayers.contains(uuid)) return;
        playerGear.remove(uuid);
        playerSlotAttributes.remove(uuid);
        playerSlotPerms.remove(uuid);
        playerSlotFreeLevel.remove(uuid);
        loadedPlayers.remove(uuid);
        logDebug("Unloaded data for " + uuid);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gear (ItemStack per slot)
    // ─────────────────────────────────────────────────────────────────────────

    public static void setPlayerGear(UUID uuid, String slotId, ItemStack item) {
        if (uuid == null) return;
        if (!loadedPlayers.contains(uuid)) loadPlayerData(uuid);

        playerGear.computeIfAbsent(uuid, k -> new HashMap<>()).put(slotId, item);

        String encoded = encodeItem(item);
        if (encoded == null) {
            removePlayerGear(uuid, slotId);
            return;
        }
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO cip_player_gear (player_uuid, slot_id, item_data) VALUES (?,?,?) " +
                     "ON DUPLICATE KEY UPDATE item_data=VALUES(item_data)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, slotId);
            ps.setString(3, encoded);
            ps.executeUpdate();
        } catch (SQLException e) {
            logWarning("setPlayerGear failed for " + uuid + ":" + slotId, e);
        }
    }

    public static ItemStack getPlayerGear(UUID uuid, String slotId) {
        if (uuid == null) return null;
        if (!loadedPlayers.contains(uuid)) loadPlayerData(uuid);
        Map<String, ItemStack> slots = playerGear.get(uuid);
        return slots != null ? slots.get(slotId) : null;
    }

    public static void removePlayerGear(UUID uuid, String slotId) {
        if (uuid == null) return;
        Map<String, ItemStack> slots = playerGear.get(uuid);
        if (slots != null) {
            slots.remove(slotId);
            if (slots.isEmpty()) playerGear.remove(uuid);
        }
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM cip_player_gear WHERE player_uuid=? AND slot_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, slotId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logWarning("removePlayerGear failed for " + uuid + ":" + slotId, e);
        }
    }

    public static void clearPlayerData(UUID uuid) {
        if (uuid == null) return;
        playerGear.remove(uuid);
        playerSlotAttributes.remove(uuid);
        playerSlotPerms.remove(uuid);
        try (Connection c = database.getConnection()) {
            for (String table : new String[]{"cip_player_gear", "cip_player_slot_attrs", "cip_player_slot_perms"}) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE player_uuid=?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            logWarning("clearPlayerData failed for " + uuid, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Slot attribute deltas (Fabled bookkeeping)
    // ─────────────────────────────────────────────────────────────────────────

    public static void setPlayerSlotAttributes(UUID uuid, String slotId, Map<String, Integer> attrs) {
        if (uuid == null || attrs == null) return;
        playerSlotAttributes.computeIfAbsent(uuid, k -> new HashMap<>())
                            .put(slotId, new HashMap<>(attrs));
        try (Connection c = database.getConnection()) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM cip_player_slot_attrs WHERE player_uuid=? AND slot_id=?")) {
                del.setString(1, uuid.toString()); del.setString(2, slotId);
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO cip_player_slot_attrs (player_uuid, slot_id, attr_name, attr_value) VALUES (?,?,?,?)")) {
                for (Map.Entry<String, Integer> e : attrs.entrySet()) {
                    ins.setString(1, uuid.toString());
                    ins.setString(2, slotId);
                    ins.setString(3, e.getKey());
                    ins.setInt(4, e.getValue());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        } catch (SQLException e) {
            logWarning("setPlayerSlotAttributes failed for " + uuid + ":" + slotId, e);
        }
    }

    /**
     * Every slot id this player currently has a ledger row for. The audit walks
     * this rather than the configured slot list, because the rows that matter
     * are exactly the ones nothing owns any more — a slot that was renamed or
     * removed from settings.yml still holds live Fabled points.
     */
    public static Set<String> getLedgerSlotIds(UUID uuid) {
        Map<String, Map<String, Integer>> slotMap = playerSlotAttributes.get(uuid);
        return slotMap == null ? new HashSet<>() : new HashSet<>(slotMap.keySet());
    }

    /** Slot ids holding a stored gear item (the gear-menu slots), for the audit. */
    public static Set<String> getGearSlotIds(UUID uuid) {
        Map<String, ItemStack> gear = playerGear.get(uuid);
        return gear == null ? new HashSet<>() : new HashSet<>(gear.keySet());
    }

    public static Map<String, Integer> getPlayerSlotAttributes(UUID uuid, String slotId) {
        Map<String, Map<String, Integer>> slotMap = playerSlotAttributes.get(uuid);
        return slotMap != null ? slotMap.getOrDefault(slotId, new HashMap<>()) : new HashMap<>();
    }

    public static void removePlayerSlotAttributes(UUID uuid, String slotId) {
        if (uuid == null) return;
        Map<String, Map<String, Integer>> slotMap = playerSlotAttributes.get(uuid);
        if (slotMap != null) {
            slotMap.remove(slotId);
            if (slotMap.isEmpty()) playerSlotAttributes.remove(uuid);
        }
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM cip_player_slot_attrs WHERE player_uuid=? AND slot_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, slotId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logWarning("removePlayerSlotAttributes failed for " + uuid + ":" + slotId, e);
        }
    }

    public static void clearPlayerSlotAttributes(UUID uuid) {
        if (uuid == null) return;
        playerSlotAttributes.remove(uuid);
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM cip_player_slot_attrs WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logWarning("clearPlayerSlotAttributes failed for " + uuid, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Slot → permission tracking (used by SkillHandler with LuckPerms)
    // ─────────────────────────────────────────────────────────────────────────

    public static void setSlotPermission(UUID uuid, String slotId, String permission) {
        if (uuid == null || permission == null) return;
        playerSlotPerms.computeIfAbsent(uuid, k -> new HashMap<>()).put(slotId, permission);
        // A (re)socket always starts as "paid" — the free level is flagged
        // separately by setSlotFreeLevel once Fabled has actually been upped.
        Set<String> free = playerSlotFreeLevel.get(uuid);
        if (free != null) free.remove(slotId);
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO cip_player_slot_perms (player_uuid, slot_id, permission, free_level) VALUES (?,?,?,0) " +
                     "ON DUPLICATE KEY UPDATE permission=VALUES(permission), free_level=0")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, slotId);
            ps.setString(3, permission);
            ps.executeUpdate();
        } catch (SQLException e) {
            logWarning("setSlotPermission failed for " + uuid + ":" + slotId, e);
        }
    }

    /** Mark whether the gem in {@code slotId} owes its level 1 to a free force-up. */
    public static void setSlotFreeLevel(UUID uuid, String slotId, boolean free) {
        if (uuid == null || slotId == null) return;
        if (free) {
            playerSlotFreeLevel.computeIfAbsent(uuid, k -> new HashSet<>()).add(slotId);
        } else {
            Set<String> set = playerSlotFreeLevel.get(uuid);
            if (set != null) {
                set.remove(slotId);
                if (set.isEmpty()) playerSlotFreeLevel.remove(uuid);
            }
        }
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE cip_player_slot_perms SET free_level=? WHERE player_uuid=? AND slot_id=?")) {
            ps.setInt(1, free ? 1 : 0);
            ps.setString(2, uuid.toString());
            ps.setString(3, slotId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logWarning("setSlotFreeLevel failed for " + uuid + ":" + slotId, e);
        }
    }

    public static boolean isSlotFreeLevel(UUID uuid, String slotId) {
        Set<String> set = playerSlotFreeLevel.get(uuid);
        return set != null && set.contains(slotId);
    }

    public static String getSlotPermission(UUID uuid, String slotId) {
        Map<String, String> map = playerSlotPerms.get(uuid);
        return map != null ? map.get(slotId) : null;
    }

    /** Snapshot of slot→permission for admin wipe / revoke. Never null. */
    public static Map<String, String> getPlayerSlotPerms(UUID uuid) {
        Map<String, String> map = playerSlotPerms.get(uuid);
        if (map == null || map.isEmpty()) return Map.of();
        return Map.copyOf(map);
    }

    public static void removeSlotPermission(UUID uuid, String slotId) {
        if (uuid == null) return;
        Map<String, String> map = playerSlotPerms.get(uuid);
        if (map != null) {
            map.remove(slotId);
            if (map.isEmpty()) playerSlotPerms.remove(uuid);
        }
        Set<String> free = playerSlotFreeLevel.get(uuid);
        if (free != null) {
            free.remove(slotId);
            if (free.isEmpty()) playerSlotFreeLevel.remove(uuid);
        }
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM cip_player_slot_perms WHERE player_uuid=? AND slot_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, slotId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logWarning("removeSlotPermission failed for " + uuid + ":" + slotId, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Legacy permission-set API (used by older code paths in the JAR).
    // No-ops now: actual permissions are tracked via cip_player_slot_perms +
    // LuckPerms. Kept so existing callers compile/load.
    // ─────────────────────────────────────────────────────────────────────────

    public static void addPlayerPermission(UUID uuid, String permission) { /* no-op */ }
    public static void removePlayerPermission(UUID uuid, String permission) { /* no-op */ }
    public static Set<String> getPlayerPermissions(UUID uuid) { return new HashSet<>(); }
    public static void clearPlayerPermissions(UUID uuid) { /* no-op */ }

    // ─────────────────────────────────────────────────────────────────────────
    // Deprecated single-ring helpers (kept for binary compatibility)
    // ─────────────────────────────────────────────────────────────────────────

    @Deprecated public static void      setPlayerRing(UUID u, ItemStack ring) { setPlayerGear(u, "ring", ring); }
    @Deprecated public static ItemStack getPlayerRing(UUID u)                  { return getPlayerGear(u, "ring"); }
    @Deprecated public static void      removePlayerRing(UUID u)               { removePlayerGear(u, "ring"); }

    // ─────────────────────────────────────────────────────────────────────────
    // ItemStack serialization (Bukkit/Paper NBT bytes → Base64 string)
    // ─────────────────────────────────────────────────────────────────────────

    private static String encodeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try {
            byte[] bytes = item.serializeAsBytes();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            logWarning("encodeItem failed", e);
            return null;
        }
    }

    private static ItemStack decodeItem(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return ItemStack.deserializeBytes(bytes);
        } catch (Exception e) {
            logWarning("decodeItem failed", e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Logging helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void logDebug(String msg) {
        if (plugin != null && configManager != null && configManager.isDebugEnabled()) {
            plugin.getLogger().info("[DEBUG] " + msg);
        }
    }
    private static void logWarning(String msg, Throwable e) {
        if (plugin != null) plugin.getLogger().log(Level.WARNING, msg, e);
    }
}
