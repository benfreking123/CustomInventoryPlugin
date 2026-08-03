package com.example.custominventoryplugin.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Loader for {@code backpacks.yml}. One {@link BackpackDef} per declared
 * backpack, keyed by id. See the file header in {@code backpacks.yml} for
 * the schema.
 *
 * Sizes are clamped to multiples of 9 in [9, 54]. Invalid entries are
 * skipped with a warning so a typo in one entry doesn't break the others.
 */
public class BackpackConfig {

    private final Plugin plugin;
    /** Insertion-ordered so config order = smart-pickup priority order. */
    private final Map<String, BackpackDef> backpacks = new LinkedHashMap<>();

    public BackpackConfig(Plugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        try {
            plugin.saveResource("backpacks.yml", false);
        } catch (IllegalArgumentException ignored) {
            // jar has no default backpacks.yml — that's fine, admins write it
        }

        File f = new File(plugin.getDataFolder(), "backpacks.yml");
        if (!f.exists()) {
            plugin.getLogger().info("backpacks.yml missing — no backpacks configured.");
            backpacks.clear();
            return;
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        backpacks.clear();

        ConfigurationSection root = cfg.getConfigurationSection("backpacks");
        if (root == null) {
            plugin.getLogger().warning("backpacks.yml has no 'backpacks:' section.");
            return;
        }

        for (String id : root.getKeys(false)) {
            try {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) continue;

                String display = s.getString("display-name", id);
                int size = clampSize(s.getInt("size", 27), id);
                int maxSize = clampSize(s.getInt("max-size", size), id);
                if (maxSize < size) maxSize = size;
                int tierStep = s.getInt("tier-step", 9);
                if (tierStep <= 0 || tierStep % 9 != 0) tierStep = 9;
                String permission = s.getString("permission", "").trim();
                int ciSlot = s.getInt("ci-slot", -1);
                if (ciSlot < -1 || ciSlot >= 54) {
                    plugin.getLogger().warning("Backpack '" + id + "': ci-slot " + ciSlot + " out of range, treating as -1.");
                    ciSlot = -1;
                }
                Material icon = parseMaterial(s.getString("ci-icon", "CHEST"), id);

                // smart-pickup (legacy) seeds the default mode when default-mode isn't set.
                boolean smartPickup = s.getBoolean("smart-pickup", false);
                PickupMode defaultMode = PickupMode.fromString(
                        s.getString("default-mode", smartPickup ? "MATCH" : "OFF"),
                        smartPickup ? PickupMode.MATCH : PickupMode.OFF);

                boolean filterable = s.getBoolean("filterable", true);
                Set<Material> defaultFilter = parseMaterialSet(s.getStringList("default-filter"), id);
                double upgradeCost = s.getDouble("upgrade-cost", 0.0);
                Set<String> acceptIds = parseIdSet(s.getStringList("accepts-ids"));

                BackpackDef def = new BackpackDef(id, display, size, maxSize, tierStep, permission,
                        ciSlot, icon, defaultMode, filterable, defaultFilter, upgradeCost, acceptIds);
                backpacks.put(id, def);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load backpack '" + id + "'", e);
            }
        }

        plugin.getLogger().info("Loaded " + backpacks.size() + " backpack(s): " + backpacks.keySet());

        // A mistyped accepts-ids entry cannot be caught at load (Divinity may not
        // have registered its items yet), and the failure is silent and total —
        // the bag simply holds nothing. Print the list so it can be eyeballed.
        for (BackpackDef def : backpacks.values()) {
            if (def.isRestricted()) {
                plugin.getLogger().info("Backpack '" + def.getId() + "' accepts only "
                        + def.getAcceptIds().size() + " item id(s): " + def.getAcceptIds());
            }
        }
    }

    public void reload() { load(); }

    private int clampSize(int requested, String id) {
        if (requested < 9 || requested > 54 || requested % 9 != 0) {
            plugin.getLogger().warning("Backpack '" + id + "': size " + requested
                    + " is not a multiple of 9 in [9,54]. Defaulting to 27.");
            return 27;
        }
        return requested;
    }

    private Material parseMaterial(String name, String id) {
        if (name == null) return Material.CHEST;
        Material m = Material.matchMaterial(name.toUpperCase());
        if (m == null) {
            plugin.getLogger().warning("Backpack '" + id + "': unknown icon material '" + name + "', falling back to CHEST.");
            return Material.CHEST;
        }
        return m;
    }

    /**
     * Divinity {@code custom_items} ids for an {@code accepts-ids} allow-list.
     * Not validated against Divinity: this config loads before Divinity may have
     * registered its items, and a typo that silently emptied the list would turn
     * a restricted bag into an unrestricted one.
     */
    private Set<String> parseIdSet(List<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        if (ids == null) return out;
        for (String raw : ids) {
            if (raw == null || raw.isBlank()) continue;
            out.add(raw.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private Set<Material> parseMaterialSet(List<String> names, String id) {
        Set<Material> out = new LinkedHashSet<>();
        if (names == null) return out;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            Material m = Material.matchMaterial(name.trim().toUpperCase());
            if (m == null) {
                plugin.getLogger().warning("Backpack '" + id + "': unknown filter material '" + name + "', skipping.");
                continue;
            }
            out.add(m);
        }
        return out;
    }

    public BackpackDef get(String id) {
        return id == null ? null : backpacks.get(id.toLowerCase());
    }

    /** All backpacks regardless of perm. */
    public Map<String, BackpackDef> all() {
        return Collections.unmodifiableMap(backpacks);
    }

    /** Backpacks the given player can open (no perm gate, or holds perm). */
    public List<BackpackDef> accessible(Player player) {
        List<BackpackDef> out = new ArrayList<>();
        for (BackpackDef def : backpacks.values()) {
            if (def.canAccess(player)) out.add(def);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────

    public static final class BackpackDef {
        private final String id;
        private final String displayName;
        private final int size;          // window size at tier 0
        private final int maxSize;       // window size cap (>= size, <= 54)
        private final int tierStep;      // extra slots per tier (multiple of 9)
        private final String permission;
        private final int ciSlot;
        private final Material ciIcon;
        private final PickupMode defaultMode;
        private final boolean filterable;
        private final Set<Material> defaultFilter;
        private final double upgradeCost;
        /** Divinity item ids this bag will hold. Empty = holds anything. */
        private final Set<String> acceptIds;

        BackpackDef(String id, String displayName, int size, int maxSize, int tierStep, String permission,
                    int ciSlot, Material ciIcon, PickupMode defaultMode, boolean filterable,
                    Set<Material> defaultFilter, double upgradeCost, Set<String> acceptIds) {
            this.id = id.toLowerCase();
            this.displayName = displayName;
            this.size = size;
            this.maxSize = Math.max(maxSize, size);
            this.tierStep = tierStep <= 0 ? 9 : tierStep;
            this.permission = permission == null ? "" : permission.trim();
            this.ciSlot = ciSlot;
            this.ciIcon = ciIcon;
            this.defaultMode = defaultMode == null ? PickupMode.OFF : defaultMode;
            this.filterable = filterable;
            this.defaultFilter = defaultFilter == null ? Set.of() : defaultFilter;
            this.upgradeCost = Math.max(0.0, upgradeCost);
            this.acceptIds = acceptIds == null ? Set.of() : Set.copyOf(acceptIds);
        }

        public String getId()             { return id; }
        public String getDisplayName()    { return displayName; }
        public int    getSize()           { return size; }
        public int    getMaxSize()        { return maxSize; }
        public int    getTierStep()       { return tierStep; }
        public String getPermission()     { return permission; }
        public int    getCiSlot()         { return ciSlot; }
        public Material getCiIcon()       { return ciIcon; }
        public PickupMode getDefaultMode(){ return defaultMode; }
        public boolean isFilterable()     { return filterable; }
        public Set<Material> getDefaultFilter() { return defaultFilter; }
        public double getUpgradeCost()    { return upgradeCost; }
        public Set<String> getAcceptIds() { return acceptIds; }
        public boolean hasCiButton()      { return ciSlot >= 0; }
        public boolean isFree()           { return permission.isEmpty(); }

        /** True when this bag restricts what it will hold to specific items. */
        public boolean isRestricted()     { return !acceptIds.isEmpty(); }

        /**
         * Whether this bag will hold the item carrying the given Divinity id.
         *
         * An unrestricted bag takes anything. A restricted bag takes only listed
         * ids, so a vanilla item — which has no Divinity id and arrives here as
         * null — is refused. That is the point: a currency pouch must reject a
         * plain iron nugget while accepting the Chunk of Scrap that shares its
         * material, which a Material-based filter cannot do.
         *
         * Deliberately independent of the per-player pickup filter and of "grab
         * everything". Those are conveniences the player owns; this is what the
         * bag *is*, and no toggle should be able to widen it.
         */
        public boolean acceptsId(String divinityItemId) {
            if (acceptIds.isEmpty()) return true;
            return divinityItemId != null
                    && acceptIds.contains(divinityItemId.toLowerCase(Locale.ROOT));
        }

        /** Legacy accessor kept for callers: true when the default mode auto-collects. */
        public boolean isSmartPickup()    { return defaultMode != PickupMode.OFF; }

        /** Max tier index reachable (0 when no upgrades are possible). */
        public int maxTier() {
            if (maxSize <= size) return 0;
            return (maxSize - size) / tierStep;
        }

        /** Window size (Bukkit inventory size) for the given tier, clamped to [9,54] multiples of 9. */
        public int windowSize(int tier) {
            int t = Math.max(0, Math.min(tier, maxTier()));
            int w = size + t * tierStep;
            if (w > 54) w = 54;
            if (w < 9) w = 9;
            w -= (w % 9);
            return w;
        }

        public boolean canAccess(Player p) {
            if (p == null) return false;
            return isFree() || p.hasPermission(permission);
        }
    }
}
