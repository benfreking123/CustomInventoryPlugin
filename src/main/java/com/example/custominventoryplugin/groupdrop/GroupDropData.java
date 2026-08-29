package com.example.custominventoryplugin.groupdrop;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.data.Database;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * MariaDB DAO for group drops. Definitions live in three tables
 * (def / option / grant) and per-player progress in a claim table:
 *
 *   cip_groupdrop_def    — one row per group (settings + token icon)
 *   cip_groupdrop_option — one row per option (icon + label + commands)
 *   cip_groupdrop_grant  — bundle items per option (ordered)
 *   cip_groupdrop_claim  — picks_used + chosen-CSV per (uuid, group)
 *
 * Definitions are cross-server because the DB is shared; claims are global too,
 * so a ONCE group can only be claimed once across the whole network.
 */
public class GroupDropData {

    private final CustomInventoryPlugin plugin;
    private final Database database;

    public GroupDropData(CustomInventoryPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    // ─── definitions ──────────────────────────────────────────────────────

    public Map<String, GroupDrop> loadAll() {
        Map<String, GroupDrop> out = new LinkedHashMap<>();
        try (Connection c = database.getConnection()) {

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT group_id,title,picks,distinct_pick,claim_mode,permission,token_enabled,token_icon " +
                    "FROM cip_groupdrop_def ORDER BY group_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GroupDrop g = new GroupDrop(rs.getString("group_id"));
                    g.setTitle(rs.getString("title"));
                    g.setPicks(rs.getInt("picks"));
                    g.setDistinct(rs.getInt("distinct_pick") != 0);
                    g.setClaimMode(GroupDrop.ClaimMode.from(rs.getString("claim_mode")));
                    g.setPermission(rs.getString("permission"));
                    g.setTokenEnabled(rs.getInt("token_enabled") != 0);
                    g.setTokenIcon(ItemCodec.decode(rs.getString("token_icon")));
                    out.put(g.getId(), g);
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT group_id,option_index,icon_data,label,commands FROM cip_groupdrop_option");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GroupDrop g = out.get(rs.getString("group_id"));
                    if (g == null) continue;
                    int slot = rs.getInt("option_index");
                    GroupDropOption opt = new GroupDropOption(slot, ItemCodec.decode(rs.getString("icon_data")));
                    opt.setLabel(rs.getString("label"));
                    String cmds = rs.getString("commands");
                    if (cmds != null && !cmds.isEmpty()) {
                        for (String line : cmds.split("\n")) {
                            if (!line.trim().isEmpty()) opt.getCommands().add(line);
                        }
                    }
                    g.getOptions().put(slot, opt);
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT group_id,option_index,slot_index,item_data FROM cip_groupdrop_grant ORDER BY slot_index");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GroupDrop g = out.get(rs.getString("group_id"));
                    if (g == null) continue;
                    GroupDropOption opt = g.getOption(rs.getInt("option_index"));
                    if (opt == null) continue;
                    ItemStack it = ItemCodec.decode(rs.getString("item_data"));
                    if (it != null) opt.getGrants().add(it);
                }
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "GroupDrop loadAll failed", e);
        }
        return out;
    }

    public void saveGroup(GroupDrop g) {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cip_groupdrop_def " +
                        "(group_id,title,picks,distinct_pick,claim_mode,permission,token_enabled,token_icon) " +
                        "VALUES (?,?,?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE title=VALUES(title),picks=VALUES(picks)," +
                        "distinct_pick=VALUES(distinct_pick),claim_mode=VALUES(claim_mode)," +
                        "permission=VALUES(permission),token_enabled=VALUES(token_enabled),token_icon=VALUES(token_icon)")) {
                    ps.setString(1, g.getId());
                    ps.setString(2, g.getRawTitle());
                    ps.setInt(3, g.getPicks());
                    ps.setInt(4, g.isDistinct() ? 1 : 0);
                    ps.setString(5, g.getClaimMode().name());
                    ps.setString(6, g.getPermission());
                    ps.setInt(7, g.isTokenEnabled() ? 1 : 0);
                    ps.setString(8, ItemCodec.encode(g.getTokenIcon()));
                    ps.executeUpdate();
                }

                try (PreparedStatement del = c.prepareStatement("DELETE FROM cip_groupdrop_option WHERE group_id=?")) {
                    del.setString(1, g.getId()); del.executeUpdate();
                }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM cip_groupdrop_grant WHERE group_id=?")) {
                    del.setString(1, g.getId()); del.executeUpdate();
                }

                try (PreparedStatement optIns = c.prepareStatement(
                        "INSERT INTO cip_groupdrop_option (group_id,option_index,icon_data,label,commands) VALUES (?,?,?,?,?)");
                     PreparedStatement grIns = c.prepareStatement(
                        "INSERT INTO cip_groupdrop_grant (group_id,option_index,slot_index,item_data) VALUES (?,?,?,?)")) {
                    for (GroupDropOption opt : g.getOptions().values()) {
                        optIns.setString(1, g.getId());
                        optIns.setInt(2, opt.getSlot());
                        optIns.setString(3, ItemCodec.encode(opt.getIcon()));
                        optIns.setString(4, opt.getLabel());
                        optIns.setString(5, String.join("\n", opt.getCommands()));
                        optIns.addBatch();

                        int gi = 0;
                        for (ItemStack it : opt.getGrants()) {
                            String enc = ItemCodec.encode(it);
                            if (enc == null) continue;
                            grIns.setString(1, g.getId());
                            grIns.setInt(2, opt.getSlot());
                            grIns.setInt(3, gi++);
                            grIns.setString(4, enc);
                            grIns.addBatch();
                        }
                    }
                    optIns.executeBatch();
                    grIns.executeBatch();
                }

                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "GroupDrop saveGroup failed: " + g.getId(), e);
        }
    }

    public void deleteGroup(String id) {
        String gid = id.toLowerCase();
        try (Connection c = database.getConnection()) {
            for (String table : new String[]{
                    "cip_groupdrop_def", "cip_groupdrop_option",
                    "cip_groupdrop_grant", "cip_groupdrop_claim"}) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE group_id=?")) {
                    ps.setString(1, gid);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "GroupDrop deleteGroup failed: " + gid, e);
        }
    }

    // ─── claims ───────────────────────────────────────────────────────────

    public int getPicksUsed(UUID uuid, String groupId) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT picks_used FROM cip_groupdrop_claim WHERE player_uuid=? AND group_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, groupId.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "GroupDrop getPicksUsed failed", e);
        }
        return 0;
    }

    public Set<Integer> getChosen(UUID uuid, String groupId) {
        Set<Integer> out = new HashSet<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT chosen FROM cip_groupdrop_claim WHERE player_uuid=? AND group_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, groupId.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String csv = rs.getString(1);
                    if (csv != null && !csv.isEmpty()) {
                        for (String tok : csv.split(",")) {
                            try { out.add(Integer.parseInt(tok.trim())); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "GroupDrop getChosen failed", e);
        }
        return out;
    }

    /** Records one pick: bumps picks_used and appends the option index to the chosen CSV. */
    public void recordPick(UUID uuid, String groupId, int optionIndex) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO cip_groupdrop_claim (player_uuid,group_id,picks_used,chosen) " +
                     "VALUES (?,?,1,?) " +
                     "ON DUPLICATE KEY UPDATE picks_used=picks_used+1, " +
                     "chosen=CONCAT(IF(chosen IS NULL OR chosen='', '', CONCAT(chosen, ',')), VALUES(chosen))")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, groupId.toLowerCase());
            ps.setString(3, Integer.toString(optionIndex));
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "GroupDrop recordPick failed", e);
        }
    }

    public void resetClaim(UUID uuid, String groupId) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM cip_groupdrop_claim WHERE player_uuid=? AND group_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, groupId.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "GroupDrop resetClaim failed", e);
        }
    }

    /**
     * Clear every claim a player holds, across all groups. A player wipe has to
     * work without naming the groups: the group list lives in this table and
     * grows, so any caller enumerating ids by hand silently stops covering new
     * ones the day someone adds a group. Returns the number of rows removed.
     */
    public int resetAllClaims(UUID uuid) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM cip_groupdrop_claim WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "GroupDrop resetAllClaims failed", e);
            return 0;
        }
    }
}
