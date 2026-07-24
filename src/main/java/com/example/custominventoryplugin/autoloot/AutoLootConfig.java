package com.example.custominventoryplugin.autoloot;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Config for CIP AutoLoot (the server's authoritative drop manager). Read from
 * the {@code autoloot} section of settings.yml.
 *
 * <ul>
 *   <li>{@code force-all} — auto-loot is on for everyone, ignoring the per-player
 *       toggle and the {@code custominventory.backpack.autopickup} permission.</li>
 *   <li>{@code hide-toggle} — hide the "Pickup On/Off" button in {@code /bp}.</li>
 *   <li>{@code blacklist} — spawn reasons / entity types that never auto-loot
 *       (ported from Divinity's old loot module so mob-farm behaviour is kept).</li>
 *   <li>{@code effects} — per-tier glow colour + which tiers also get a particle
 *       burst. Tier ids come from Divinity ({@link com.example.custominventoryplugin.tooltip.TooltipStyleService}).</li>
 * </ul>
 */
public final class AutoLootConfig {

    private final JavaPlugin plugin;

    private boolean forceAll = true;
    private boolean hideToggle = true;
    private int groundLingerTicks = 40;

    private final Set<String> blacklistSpawnReasons = new HashSet<>();
    private final Set<String> blacklistEntityTypes = new HashSet<>();

    private boolean effectsEnabled = true;
    private boolean allGroundItems = true;
    private final Set<String> burstTiers = new HashSet<>();
    private final Map<String, ChatColor> tierGlow = new HashMap<>();
    private final Map<String, Color> tierColor = new HashMap<>();
    private final Map<String, String> aliases = new HashMap<>();

    public AutoLootConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        blacklistSpawnReasons.clear();
        blacklistEntityTypes.clear();
        burstTiers.clear();
        tierGlow.clear();
        tierColor.clear();
        aliases.clear();

        File settingsFile = new File(plugin.getDataFolder(), "settings.yml");
        FileConfiguration settings = YamlConfiguration.loadConfiguration(settingsFile);
        ConfigurationSection root = settings.getConfigurationSection("autoloot");
        if (root == null) {
            putDefaults();
            return;
        }

        forceAll = root.getBoolean("force-all", true);
        hideToggle = root.getBoolean("hide-toggle", true);
        groundLingerTicks = Math.max(1, root.getInt("ground-linger-ticks", 40));

        ConfigurationSection bl = root.getConfigurationSection("blacklist");
        if (bl != null) {
            for (String s : bl.getStringList("spawn-reasons")) blacklistSpawnReasons.add(s.toUpperCase(Locale.ROOT));
            for (String s : bl.getStringList("entity-types")) blacklistEntityTypes.add(s.toUpperCase(Locale.ROOT));
        }

        ConfigurationSection fx = root.getConfigurationSection("effects");
        if (fx != null) {
            effectsEnabled = fx.getBoolean("enabled", true);
            allGroundItems = fx.getBoolean("all-ground-items", true);
            for (String s : fx.getStringList("burst-tiers")) burstTiers.add(s.toLowerCase(Locale.ROOT));
            ConfigurationSection tiers = fx.getConfigurationSection("tiers");
            if (tiers != null) {
                for (String key : tiers.getKeys(false)) {
                    ConfigurationSection t = tiers.getConfigurationSection(key);
                    if (t == null) continue;
                    String low = key.toLowerCase(Locale.ROOT);
                    ChatColor glow = parseChatColor(t.getString("glow", "WHITE"));
                    if (glow != null) tierGlow.put(low, glow);
                    Color col = parseHex(t.getString("color", "#FFFFFF"));
                    if (col != null) tierColor.put(low, col);
                }
            }
            ConfigurationSection al = fx.getConfigurationSection("aliases");
            if (al != null) {
                for (String key : al.getKeys(false)) {
                    aliases.put(key.toLowerCase(Locale.ROOT), al.getString(key, key).toLowerCase(Locale.ROOT));
                }
            }
        }

        if (tierGlow.isEmpty()) putDefaults();
    }

    private void putDefaults() {
        forceAll = true;
        hideToggle = true;
        blacklistSpawnReasons.add("SPAWNER");
        blacklistEntityTypes.add("ARMOR_STAND");
        effectsEnabled = true;
        burstTiers.add("mythic");
        burstTiers.add("legendary");
        def("common", ChatColor.GRAY, 0xAAAAAA);
        def("uncommon", ChatColor.GREEN, 0x55FF55);
        def("rare", ChatColor.YELLOW, 0xFFFF55);
        def("mythic", ChatColor.LIGHT_PURPLE, 0xFF55FF);
        def("legendary", ChatColor.RED, 0xFF5555);
        def("tool", ChatColor.GOLD, 0xFFAA00);
        def("currency", ChatColor.YELLOW, 0xFFD966);
        def("resource", ChatColor.AQUA, 0x55FFFF);
        aliases.put("default", "common");
        aliases.put("superior", "mythic");
        aliases.put("fabled", "legendary");
        aliases.put("eternal", "legendary");
        aliases.put("cursed", "common");
        aliases.put("archieve", "common");
    }

    private void def(String tier, ChatColor glow, int rgb) {
        tierGlow.put(tier, glow);
        tierColor.put(tier, Color.fromRGB(rgb));
    }

    /** Resolve tier aliases (e.g. superior -> mythic). Null-safe. */
    public String normalize(String tier) {
        if (tier == null) return null;
        String low = tier.toLowerCase(Locale.ROOT);
        return aliases.getOrDefault(low, low);
    }

    public boolean isForceAll()   { return forceAll; }
    public boolean isHideToggle() { return hideToggle; }
    public int getGroundLingerTicks() { return groundLingerTicks; }
    public boolean effectsEnabled() { return effectsEnabled; }

    /** Rarity-glow every ground item entity, not just AutoLoot pop-outs. */
    public boolean isAllGroundItems() { return effectsEnabled && allGroundItems; }

    public Set<String> getBlacklistSpawnReasons() { return blacklistSpawnReasons; }
    public Set<String> getBlacklistEntityTypes()  { return blacklistEntityTypes; }

    public ChatColor glowFor(String tier) {
        String n = normalize(tier);
        return n == null ? null : tierGlow.get(n);
    }

    public Color burstColorFor(String tier) {
        String n = normalize(tier);
        return n == null ? null : tierColor.get(n);
    }

    public boolean isBurst(String tier) {
        String n = normalize(tier);
        return n != null && burstTiers.contains(n);
    }

    private static ChatColor parseChatColor(String name) {
        if (name == null) return null;
        try {
            return ChatColor.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ChatColor.WHITE;
        }
    }

    private static Color parseHex(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        try {
            return Color.fromRGB(Integer.parseInt(h, 16));
        } catch (NumberFormatException e) {
            return Color.WHITE;
        }
    }
}
