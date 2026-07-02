package com.example.custominventoryplugin.groupdrop;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * In-memory registry of {@link GroupDrop}s, loaded from the DB on startup (DB is
 * the source of truth, so definitions are cross-server). Also handles optional
 * YAML export/import via {@code groupdrops.yml} for backup / hand-editing.
 */
public class GroupDropConfig {

    private static final String YAML_FILE = "groupdrops.yml";

    private final CustomInventoryPlugin plugin;
    private final GroupDropData data;
    private final Map<String, GroupDrop> groups = new LinkedHashMap<>();

    public GroupDropConfig(CustomInventoryPlugin plugin, GroupDropData data) {
        this.plugin = plugin;
        this.data = data;
        loadAll();
    }

    public void loadAll() {
        groups.clear();
        groups.putAll(data.loadAll());
        plugin.getLogger().info("Loaded " + groups.size() + " group drop(s): " + groups.keySet());
    }

    public void reload() { loadAll(); }

    public GroupDrop get(String id) {
        return id == null ? null : groups.get(id.toLowerCase());
    }

    public Collection<GroupDrop> all() { return groups.values(); }

    public boolean exists(String id) { return id != null && groups.containsKey(id.toLowerCase()); }

    /** Persist a group to the DB and refresh the in-memory copy. */
    public void save(GroupDrop g) {
        data.saveGroup(g);
        groups.put(g.getId(), g);
    }

    public void delete(String id) {
        data.deleteGroup(id);
        groups.remove(id.toLowerCase());
    }

    // ─── YAML export / import ─────────────────────────────────────────────

    /** Export one group ("all" for everything) to groupdrops.yml. Returns count. */
    public int exportYaml(String idOrAll) throws IOException {
        File f = new File(plugin.getDataFolder(), YAML_FILE);
        YamlConfiguration yml = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();

        Collection<GroupDrop> target;
        if (idOrAll == null || idOrAll.equalsIgnoreCase("all")) {
            target = all();
        } else {
            GroupDrop g = get(idOrAll);
            if (g == null) return 0;
            target = List.of(g);
        }

        for (GroupDrop g : target) {
            ConfigurationSection s = yml.createSection("groups." + g.getId());
            s.set("title", g.getRawTitle());
            s.set("picks", g.getPicks());
            s.set("distinct", g.isDistinct());
            s.set("claim", g.getClaimMode().name().toLowerCase());
            s.set("permission", g.getPermission());
            s.set("token-enabled", g.isTokenEnabled());
            s.set("token-icon", ItemCodec.encode(g.getTokenIcon()));
            for (GroupDropOption opt : g.getOptions().values()) {
                ConfigurationSection os = s.createSection("options." + opt.getSlot());
                os.set("icon", ItemCodec.encode(opt.getIcon()));
                os.set("label", opt.getLabel());
                os.set("commands", new ArrayList<>(opt.getCommands()));
                List<String> grants = new ArrayList<>();
                for (ItemStack it : opt.getGrants()) {
                    String enc = ItemCodec.encode(it);
                    if (enc != null) grants.add(enc);
                }
                os.set("grants", grants);
            }
        }
        yml.save(f);
        return target.size();
    }

    /** Import one group ("all") from groupdrops.yml into the DB + cache. Returns count. */
    public int importYaml(String idOrAll) {
        File f = new File(plugin.getDataFolder(), YAML_FILE);
        if (!f.exists()) return 0;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = yml.getConfigurationSection("groups");
        if (root == null) return 0;

        int count = 0;
        for (String gid : root.getKeys(false)) {
            if (idOrAll != null && !idOrAll.equalsIgnoreCase("all") && !gid.equalsIgnoreCase(idOrAll)) continue;
            try {
                ConfigurationSection s = root.getConfigurationSection(gid);
                if (s == null) continue;
                GroupDrop g = new GroupDrop(gid);
                g.setTitle(s.getString("title", ""));
                g.setPicks(s.getInt("picks", 1));
                g.setDistinct(s.getBoolean("distinct", true));
                g.setClaimMode(GroupDrop.ClaimMode.from(s.getString("claim", "once")));
                g.setPermission(s.getString("permission", ""));
                g.setTokenEnabled(s.getBoolean("token-enabled", false));
                g.setTokenIcon(ItemCodec.decode(s.getString("token-icon")));

                ConfigurationSection opts = s.getConfigurationSection("options");
                if (opts != null) {
                    for (String slotKey : opts.getKeys(false)) {
                        int slot;
                        try { slot = Integer.parseInt(slotKey); } catch (NumberFormatException e) { continue; }
                        ConfigurationSection os = opts.getConfigurationSection(slotKey);
                        if (os == null) continue;
                        GroupDropOption opt = new GroupDropOption(slot, ItemCodec.decode(os.getString("icon")));
                        opt.setLabel(os.getString("label"));
                        opt.getCommands().addAll(os.getStringList("commands"));
                        for (String enc : os.getStringList("grants")) {
                            ItemStack it = ItemCodec.decode(enc);
                            if (it != null) opt.getGrants().add(it);
                        }
                        g.getOptions().put(slot, opt);
                    }
                }
                save(g);
                count++;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to import group drop '" + gid + "'", e);
            }
        }
        return count;
    }
}
