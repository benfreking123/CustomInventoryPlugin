package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.data.Database;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Quest completion, read straight from BetonQuest's MariaDB tables (same
 * {@code thetower} database — no new tracking). {@code compendium.yml} lists
 * the done-tags per floor; a quest counts as complete when its tag exists in
 * {@code betonquest_tags} for the player's active profile.
 */
public class QuestProgress {

    /** Per-floor result: how many configured done-tags the player holds. */
    public static final class FloorQuests {
        /** Config key from compendium.yml quests section, e.g. "floor1". */
        public final String key;
        public final String label;
        public final int done;
        public final int total;

        FloorQuests(String key, String label, int done, int total) {
            this.key = key;
            this.label = label;
            this.done = done;
            this.total = total;
        }
    }

    private final CustomInventoryPlugin plugin;
    private final Database database;
    /** floor key (config order) → (label, done-tag list). */
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final Map<String, List<String>> floorTags = new LinkedHashMap<>();

    public QuestProgress(CustomInventoryPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        load();
    }

    public void load() {
        try {
            plugin.saveResource("compendium.yml", false);
        } catch (IllegalArgumentException ignored) {
        }

        labels.clear();
        floorTags.clear();
        File f = new File(plugin.getDataFolder(), "compendium.yml");
        if (!f.exists()) {
            plugin.getLogger().info("compendium.yml missing — quest tracking empty.");
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection quests = cfg.getConfigurationSection("quests");
        if (quests == null) return;
        for (String key : quests.getKeys(false)) {
            ConfigurationSection sec = quests.getConfigurationSection(key);
            if (sec == null) continue;
            List<String> tags = sec.getStringList("tags");
            if (tags.isEmpty()) continue;
            labels.put(key, sec.getString("label", key));
            floorTags.put(key, tags);
        }
        plugin.getLogger().info("Quest tracking: " + floorTags.size() + " floor(s), "
                + totalQuests() + " quest tags.");
    }

    public boolean isEmpty() { return floorTags.isEmpty(); }

    public int totalQuests() {
        int n = 0;
        for (List<String> t : floorTags.values()) n += t.size();
        return n;
    }

    /** All BetonQuest tags for the player's active profile. Empty set on error. */
    public Set<String> playerTags(UUID uuid) {
        Set<String> out = new HashSet<>();
        if (uuid == null) return out;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT t.tag FROM betonquest_tags t "
                             + "JOIN betonquest_player p ON t.profileID = p.active_profile "
                             + "WHERE p.playerID = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "betonquest tag read failed for " + uuid, e);
        }
        return out;
    }

    /** Per-floor breakdown, config order. */
    public List<FloorQuests> breakdown(Set<String> tags) {
        List<FloorQuests> out = new java.util.ArrayList<>();
        for (Map.Entry<String, List<String>> e : floorTags.entrySet()) {
            int done = 0;
            for (String tag : e.getValue()) {
                if (tags.contains(tag)) done++;
            }
            out.add(new FloorQuests(e.getKey(), labels.get(e.getKey()), done, e.getValue().size()));
        }
        return out;
    }
}
