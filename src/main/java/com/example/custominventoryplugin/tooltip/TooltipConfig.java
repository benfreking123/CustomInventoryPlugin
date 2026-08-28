package com.example.custominventoryplugin.tooltip;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tooltip frame + detail-page config. See Docs/deisgn/tooltips.md.
 */
public final class TooltipConfig {

    private final JavaPlugin plugin;
    private boolean enabled = true;
    private String namespace = "tower";
    /** Also stamp the style path into custom_model_data strings[0] (slot glow). */
    private boolean glowEnabled = true;
    private final Map<String, String> tierToStyle = new HashMap<>();
    private final Map<String, String> itemIdOverrides = new HashMap<>();
    private final Map<String, String> materialOverrides = new HashMap<>();
    private final Map<String, String> sources = new HashMap<>();
    private final Map<String, Catalyst> catalysts = new HashMap<>();
    private final Map<String, Lootbox> lootboxes = new HashMap<>();

    // Derived attribute requirements (Docs/deisgn/tooltips.md). The value is
    // scaled from the item's Divinity level/tier; the attribute is chosen from
    // the item's dominant rolled Fabled stat via attrReqStatMap.
    private boolean attrReqEnabled = true;
    private int attrReqMin = 1;
    private int attrReqMax = 40;
    private double attrReqScaleBase = 0.0;
    private double attrReqScalePerLevel = 1.0;
    private final Set<String> attrReqTiers = new HashSet<>();
    private final Set<String> attrReqTypes = new HashSet<>();
    private final Map<String, String> attrReqAttributes = new LinkedHashMap<>();
    private final Map<String, Double> attrReqTierMult = new HashMap<>();
    /** Rolled Fabled stat key -> governing base attribute key. */
    private final Map<String, String> attrReqStatMap = new LinkedHashMap<>();

    // Skill-gem tooltip reflow (GemTooltip): badge row + Fabled skill stats.
    private boolean gemTooltipEnabled = true;
    private final List<String> gemIdPrefixes = new java.util.ArrayList<>();
    /** Lowercased non-element skill type -> legacy color code for its text tag. */
    private final Map<String, String> gemTypeColors = new HashMap<>();

    public TooltipConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        tierToStyle.clear();
        itemIdOverrides.clear();
        materialOverrides.clear();
        sources.clear();
        catalysts.clear();
        lootboxes.clear();
        attrReqTiers.clear();
        attrReqTypes.clear();
        attrReqAttributes.clear();
        attrReqTierMult.clear();
        attrReqStatMap.clear();
        gemIdPrefixes.clear();
        gemTypeColors.clear();

        File settingsFile = new File(plugin.getDataFolder(), "settings.yml");
        FileConfiguration settings = YamlConfiguration.loadConfiguration(settingsFile);
        ConfigurationSection root = settings.getConfigurationSection("tooltip");
        if (root == null) {
            enabled = true;
            namespace = "tower";
            glowEnabled = true;
            putDefaults();
            gemIdPrefixes.addAll(List.of("gem_", "passive_"));
            putDefaultGemTypeColors();
            return;
        }

        enabled = root.getBoolean("enabled", true);
        namespace = root.getString("namespace", "tower");
        glowEnabled = root.getBoolean("glow", true);

        ConfigurationSection tiers = root.getConfigurationSection("tiers");
        if (tiers != null) {
            for (String key : tiers.getKeys(false)) {
                tierToStyle.put(key.toLowerCase(Locale.ROOT), tiers.getString(key, key));
            }
        }
        if (tierToStyle.isEmpty()) putDefaults();

        ConfigurationSection ids = root.getConfigurationSection("item-ids");
        if (ids != null) {
            for (String key : ids.getKeys(false)) {
                itemIdOverrides.put(key.toLowerCase(Locale.ROOT), ids.getString(key, ""));
            }
        }

        ConfigurationSection mats = root.getConfigurationSection("materials");
        if (mats != null) {
            for (String key : mats.getKeys(false)) {
                materialOverrides.put(key.toUpperCase(Locale.ROOT), mats.getString(key, ""));
            }
        }

        ConfigurationSection src = root.getConfigurationSection("sources");
        if (src != null) {
            for (String key : src.getKeys(false)) {
                String line = src.getString(key);
                if (line != null && !line.isBlank()) {
                    sources.put(key.toLowerCase(Locale.ROOT), line);
                }
            }
        }

        ConfigurationSection cats = root.getConfigurationSection("catalysts");
        if (cats != null) {
            for (String key : cats.getKeys(false)) {
                ConfigurationSection c = cats.getConfigurationSection(key);
                if (c == null) continue;
                catalysts.put(key.toLowerCase(Locale.ROOT), new Catalyst(
                        c.getString("rarity", "common"),
                        c.getStringList("actions"),
                        c.getString("headline", ""),
                        c.getString("accepts", ""),
                        c.getStringList("body")));
            }
        }

        ConfigurationSection boxes = root.getConfigurationSection("lootboxes");
        if (boxes != null) {
            for (String key : boxes.getKeys(false)) {
                ConfigurationSection b = boxes.getConfigurationSection(key);
                if (b == null) continue;
                lootboxes.put(key.toLowerCase(Locale.ROOT), new Lootbox(
                        b.getString("rarity", "common"),
                        b.getString("headline", ""),
                        b.getStringList("contents"),
                        b.getString("use", "Right-click to open")));
            }
        }

        ConfigurationSection ar = root.getConfigurationSection("attr-requirements");
        if (ar != null) {
            attrReqEnabled = ar.getBoolean("enabled", true);
            attrReqMin = ar.getInt("min", attrReqMin);
            attrReqMax = ar.getInt("max", attrReqMax);
            for (String t : ar.getStringList("tiers")) attrReqTiers.add(t.toLowerCase(Locale.ROOT));
            for (String t : ar.getStringList("types")) attrReqTypes.add(t.toLowerCase(Locale.ROOT));
            ConfigurationSection attrs = ar.getConfigurationSection("attributes");
            if (attrs != null) {
                for (String key : attrs.getKeys(false)) {
                    attrReqAttributes.put(key, attrs.getString(key, key));
                }
            }
            ConfigurationSection scale = ar.getConfigurationSection("scale");
            if (scale != null) {
                attrReqScaleBase = scale.getDouble("base", attrReqScaleBase);
                attrReqScalePerLevel = scale.getDouble("per-level", attrReqScalePerLevel);
                ConfigurationSection tm = scale.getConfigurationSection("tier-multiplier");
                if (tm != null) {
                    for (String key : tm.getKeys(false)) {
                        attrReqTierMult.put(key.toLowerCase(Locale.ROOT), tm.getDouble(key, 1.0));
                    }
                }
            }
            ConfigurationSection sm = ar.getConfigurationSection("stat-attribute-map");
            if (sm != null) {
                for (String key : sm.getKeys(false)) {
                    String attr = sm.getString(key);
                    if (attr != null && !attr.isBlank()) {
                        attrReqStatMap.put(key.toLowerCase(Locale.ROOT), attr.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        if (attrReqTiers.isEmpty()) attrReqTiers.addAll(List.of("common", "uncommon"));
        if (attrReqTypes.isEmpty()) attrReqTypes.addAll(List.of(
                "sword", "axe", "bow", "wand", "hammer",
                "helmet", "chestplate", "leggings", "boots", "cloak"));
        if (attrReqAttributes.isEmpty()) {
            attrReqAttributes.put("base_strength", "Strength");
            attrReqAttributes.put("base_dexterity", "Dexterity");
            attrReqAttributes.put("base_intelligence", "Intelligence");
            attrReqAttributes.put("base_vitality", "Vitality");
            attrReqAttributes.put("base_agility", "Agility");
            attrReqAttributes.put("base_wisdom", "Wisdom");
        }
        if (attrReqStatMap.isEmpty()) putDefaultStatMap();

        ConfigurationSection gems = root.getConfigurationSection("gems");
        if (gems != null) {
            gemTooltipEnabled = gems.getBoolean("enabled", true);
            for (String p : gems.getStringList("id-prefixes")) {
                if (!p.isBlank()) gemIdPrefixes.add(p.toLowerCase(Locale.ROOT));
            }
            ConfigurationSection colors = gems.getConfigurationSection("type-colors");
            if (colors != null) {
                for (String key : colors.getKeys(false)) {
                    String c = colors.getString(key);
                    if (c != null && !c.isBlank()) {
                        gemTypeColors.put(key.toLowerCase(Locale.ROOT), c);
                    }
                }
            }
        }
        if (gemIdPrefixes.isEmpty()) gemIdPrefixes.addAll(List.of("gem_", "passive_"));
        if (gemTypeColors.isEmpty()) putDefaultGemTypeColors();
    }

    /** Colors for damage elements and for skill types with no element pill. */
    private void putDefaultGemTypeColors() {
        // The five damage types, matched to their pills in gen-tooltip-assets.py.
        // Without these every element fell through to grey, so lightning and
        // fire damage read the same as physical.
        gemTypeColors.put("physical", "&f");
        gemTypeColors.put("fire", "&6");
        gemTypeColors.put("ice", "&b");
        gemTypeColors.put("lightning", "&e");
        gemTypeColors.put("chaotic", "&d");
        gemTypeColors.put("sigil", "&6");
        gemTypeColors.put("movement", "&b");
        gemTypeColors.put("melee", "&7");
        gemTypeColors.put("projectile", "&6");
        gemTypeColors.put("resource", "&a");
        gemTypeColors.put("dynamic", "&d");
        gemTypeColors.put("passive", "&d");
    }

    /** Fallback stat -> governing attribute map, mirrored in settings.yml. */
    private void putDefaultStatMap() {
        // Strength — melee / physical might
        for (String s : new String[]{"damage_physical", "damage_melee", "damage_attack",
                "stat_block_break", "stat_knockback_resist"}) attrReqStatMap.put(s, "base_strength");
        // Dexterity — ranged / finesse / attack & projectile crit
        for (String s : new String[]{"damage_projectile", "base_attack_speed", "stat_range",
                "stat_attack_range", "stat_crit_chance_attack", "stat_crit_damage_attack",
                "stat_crit_chance_projectile", "stat_crit_damage_projectile"}) attrReqStatMap.put(s, "base_dexterity");
        // Intelligence — spells / elements / mana / block chance
        for (String s : new String[]{"damage_spell", "damage_fire", "damage_ice", "damage_lightning",
                "damage_chaos", "damage_area", "damage_skill", "stat_mana", "stat_mana_mod",
                "stat_crit_chance_spell", "stat_crit_damage_spell", "block_chance"}) attrReqStatMap.put(s, "base_intelligence");
        // Vitality — health / defense / armor
        for (String s : new String[]{"stat_health", "stat_health_mod", "stat_armor", "stat_armor_mod",
                "stat_armor_toughness", "stat_absorption", "stat_heal_power", "defense_physical",
                "defense_fire", "defense_ice", "defense_lightning", "defense_chaos"}) attrReqStatMap.put(s, "base_vitality");
        // Agility — mobility / evasion
        for (String s : new String[]{"stat_move_speed", "stat_jump", "stat_mining_efficiency",
                "dodge_chance"}) attrReqStatMap.put(s, "base_agility");
        // Wisdom — regen / utility
        for (String s : new String[]{"stat_mana_regen", "stat_mana_regen_mod", "stat_exp",
                "stat_luck"}) attrReqStatMap.put(s, "base_wisdom");
    }

    private void putDefaults() {
        for (String t : new String[]{
                "common", "uncommon", "rare", "mythic", "legendary",
                "tool", "currency", "resource", "default"}) {
            tierToStyle.put(t, t);
        }
        tierToStyle.put("superior", "mythic");
        tierToStyle.put("fabled", "legendary");
        tierToStyle.put("eternal", "legendary");
        tierToStyle.put("cursed", "common");
        tierToStyle.put("archieve", "common");
    }

    public boolean isEnabled() { return enabled; }
    public String getNamespace() { return namespace; }
    public boolean isGlowEnabled() { return glowEnabled; }

    public String styleForTier(String tierId) {
        if (tierId == null || tierId.isBlank()) return null;
        return tierToStyle.get(tierId.toLowerCase(Locale.ROOT));
    }

    /**
     * The distinct style names, sorted. These are exactly the values stamped into
     * custom_model_data strings[0], so they are also the set of glow layers the
     * pack has to switch between. Sorted because the pack the glow ends up in has
     * to be byte-identical across backends.
     */
    public List<String> glowTiers() {
        return tierToStyle.values().stream().distinct().sorted().toList();
    }

    public String styleForItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        String s = itemIdOverrides.get(itemId.toLowerCase(Locale.ROOT));
        return (s == null || s.isBlank()) ? null : s;
    }

    public String styleForMaterial(String materialName) {
        if (materialName == null) return null;
        String s = materialOverrides.get(materialName.toUpperCase(Locale.ROOT));
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * A lootbox's authored copy; see the `lootboxes:` block.
     *
     * `contents` is what the box can actually pay out, kept in step with
     * Skript-shared/scripts/lootboxes/lootcrates.sk — the script is the real
     * source of truth, and tt-check compares the two.
     */
    public record Lootbox(String rarity, String headline, List<String> contents,
                          String use) { }

    public Lootbox lootboxForItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        return lootboxes.get(itemId.toLowerCase(Locale.ROOT));
    }

    /** A Smithy catalyst's authored copy; see the `catalysts:` block. */
    public record Catalyst(String rarity, List<String> actions, String headline,
                           String accepts, List<String> body) { }

    public Catalyst catalystForItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        return catalysts.get(itemId.toLowerCase(Locale.ROOT));
    }

    public String sourceForItemId(String itemId) {
        if (itemId == null) return null;
        return sources.get(itemId.toLowerCase(Locale.ROOT));
    }

    public Map<String, String> getSources() {
        return Collections.unmodifiableMap(sources);
    }

    public boolean isAttrReqEnabled() { return attrReqEnabled; }
    public int getAttrReqMin() { return attrReqMin; }
    public int getAttrReqMax() { return attrReqMax; }
    public double getAttrReqScaleBase() { return attrReqScaleBase; }
    public double getAttrReqScalePerLevel() { return attrReqScalePerLevel; }

    /** Per-tier multiplier applied to the scaled requirement value (default 1.0). */
    public double attrReqTierMultiplier(String tier) {
        if (tier == null) return 1.0;
        return attrReqTierMult.getOrDefault(tier.toLowerCase(Locale.ROOT), 1.0);
    }

    /**
     * Governing base attribute key for a rolled Fabled stat, or null if unmapped.
     * A {@code base_<attr>} stat maps to itself so gear that grants an attribute
     * also asks for it.
     */
    public String attributeForStat(String statKey) {
        if (statKey == null) return null;
        String k = statKey.toLowerCase(Locale.ROOT);
        if (attrReqAttributes.containsKey(k)) return k;
        return attrReqStatMap.get(k);
    }

    /** Attribute key (e.g. base_strength) -> display name (Strength), iteration order stable. */
    public Map<String, String> getAttrReqAttributes() {
        return Collections.unmodifiableMap(attrReqAttributes);
    }

    public boolean isGemTooltipEnabled() { return gemTooltipEnabled; }

    /** Item-id prefixes treated as skill gems (lowercase). */
    public List<String> getGemIdPrefixes() {
        return Collections.unmodifiableList(gemIdPrefixes);
    }

    /** Legacy color code(s) for a non-element skill type tag (default gray). */
    public String gemTypeColor(String type) {
        if (type == null) return "&7";
        return gemTypeColors.getOrDefault(type.toLowerCase(Locale.ROOT), "&7");
    }

    /** Should this Divinity tier + item id roll an attribute requirement? */
    public boolean attrReqApplies(String tier, String itemId) {
        if (!attrReqEnabled || tier == null || itemId == null) return false;
        if (!attrReqTiers.contains(tier.toLowerCase(Locale.ROOT))) return false;
        String low = itemId.toLowerCase(Locale.ROOT);
        for (String type : attrReqTypes) {
            if (low.contains(type)) return true;
        }
        return false;
    }
}
