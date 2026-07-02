package com.example.custominventoryplugin.data;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/**
 * Manages the MariaDB connection pool used for cross-server gear/attribute storage.
 *
 * Schema:
 *   cip_player_gear        — equipped ItemStack per (uuid, slot)
 *   cip_player_slot_attrs  — Fabled attribute deltas per (uuid, slot, attr)
 *   cip_player_slot_perms  — slot → permission node mapping (for skill gem revoke)
 *   cip_player_backpack    — backpack contents per (uuid, backpack_id, slot_index)
 */
public class Database {

    private final CustomInventoryPlugin plugin;
    private HikariDataSource pool;

    public Database(CustomInventoryPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() throws SQLException {
        FileConfiguration cfg = plugin.getConfig();
        String host = cfg.getString("database.host", "localhost");
        int port = cfg.getInt("database.port", 3306);
        String name = cfg.getString("database.name", "thetower");
        String user = cfg.getString("database.user", "thetower");
        String pass = cfg.getString("database.password", "");

        String jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + name
                + "?useSSL=false&autoReconnect=true&characterEncoding=utf8";

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setDriverClassName("org.mariadb.jdbc.Driver");
        hc.setPoolName("CIP-Pool");
        hc.setMaximumPoolSize(6);
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(5000);
        hc.setMaxLifetime(30 * 60 * 1000L);
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "64");

        // Suppress chatty Hikari logs
        java.util.logging.Logger.getLogger("com.example.custominventoryplugin.shaded.hikari").setLevel(Level.WARNING);

        this.pool = new HikariDataSource(hc);
        plugin.getLogger().info("Database pool initialized → " + host + ":" + port + "/" + name);

        ensureSchema();
    }

    public void stop() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            plugin.getLogger().info("Database pool closed.");
        }
    }

    public Connection getConnection() throws SQLException {
        if (pool == null) throw new SQLException("Database pool is not initialized");
        return pool.getConnection();
    }

    private void ensureSchema() throws SQLException {
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cip_player_gear (" +
                "  player_uuid CHAR(36) NOT NULL," +
                "  slot_id     VARCHAR(16) NOT NULL," +
                "  item_data   MEDIUMTEXT NOT NULL," +
                "  updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (player_uuid, slot_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cip_player_slot_attrs (" +
                "  player_uuid CHAR(36) NOT NULL," +
                "  slot_id     VARCHAR(16) NOT NULL," +
                "  attr_name   VARCHAR(48) NOT NULL," +
                "  attr_value  INT NOT NULL," +
                "  PRIMARY KEY (player_uuid, slot_id, attr_name)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cip_player_slot_perms (" +
                "  player_uuid CHAR(36) NOT NULL," +
                "  slot_id     VARCHAR(16) NOT NULL," +
                "  permission  VARCHAR(128) NOT NULL," +
                "  PRIMARY KEY (player_uuid, slot_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cip_player_backpack (" +
                "  player_uuid CHAR(36) NOT NULL," +
                "  backpack_id VARCHAR(32) NOT NULL," +
                "  slot_index  INT NOT NULL," +
                "  item_data   MEDIUMTEXT NOT NULL," +
                "  updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (player_uuid, backpack_id, slot_index)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            // ─── pickup settings (v1.5.0) ─────────────────────────────────
            // Player-level master toggles surfaced in the /bp list GUI.
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cip_player_pickup_settings (" +
                "  player_uuid     CHAR(36) NOT NULL," +
                "  master_enabled  TINYINT NOT NULL DEFAULT 0," +
                "  grab_everything TINYINT NOT NULL DEFAULT 0," +
                "  updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (player_uuid)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            // Per-(player, backpack) pickup mode / filter / grab / tier.
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cip_player_backpack_settings (" +
                "  player_uuid     CHAR(36) NOT NULL," +
                "  backpack_id     VARCHAR(32) NOT NULL," +
                "  pickup_mode     VARCHAR(16) NOT NULL DEFAULT 'OFF'," +
                "  filter_csv      MEDIUMTEXT NULL," +
                "  grab_everything TINYINT NOT NULL DEFAULT 0," +
                "  tier            INT NOT NULL DEFAULT 0," +
                "  updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (player_uuid, backpack_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            // ─── group drops (choose-your-reward) ─────────────────────────
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cip_groupdrop_def (" +
                "  group_id      VARCHAR(48) NOT NULL," +
                "  title         VARCHAR(128) NOT NULL DEFAULT ''," +
                "  picks         INT NOT NULL DEFAULT 1," +
                "  distinct_pick TINYINT NOT NULL DEFAULT 1," +
                "  claim_mode    VARCHAR(16) NOT NULL DEFAULT 'ONCE'," +
                "  permission    VARCHAR(128) NOT NULL DEFAULT ''," +
                "  token_enabled TINYINT NOT NULL DEFAULT 0," +
                "  token_icon    MEDIUMTEXT NULL," +
                "  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (group_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cip_groupdrop_option (" +
                "  group_id     VARCHAR(48) NOT NULL," +
                "  option_index INT NOT NULL," +
                "  icon_data    MEDIUMTEXT NULL," +
                "  label        VARCHAR(128) NULL," +
                "  commands     MEDIUMTEXT NULL," +
                "  PRIMARY KEY (group_id, option_index)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cip_groupdrop_grant (" +
                "  group_id     VARCHAR(48) NOT NULL," +
                "  option_index INT NOT NULL," +
                "  slot_index   INT NOT NULL," +
                "  item_data    MEDIUMTEXT NOT NULL," +
                "  PRIMARY KEY (group_id, option_index, slot_index)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cip_groupdrop_claim (" +
                "  player_uuid CHAR(36) NOT NULL," +
                "  group_id    VARCHAR(48) NOT NULL," +
                "  picks_used  INT NOT NULL DEFAULT 0," +
                "  chosen      VARCHAR(255) NULL," +
                "  updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (player_uuid, group_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            plugin.getLogger().info("Schema verified: cip_player_gear, cip_player_slot_attrs, cip_player_slot_perms, "
                    + "cip_player_backpack, cip_player_pickup_settings, cip_player_backpack_settings, "
                    + "cip_groupdrop_def, cip_groupdrop_option, cip_groupdrop_grant, cip_groupdrop_claim");
        }
    }
}
