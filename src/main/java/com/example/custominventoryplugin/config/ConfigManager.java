package com.example.custominventoryplugin.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class ConfigManager {
    private final Plugin plugin;
    private FileConfiguration config;
    private boolean debugMode;
    private boolean overrideEKey;
    private final Map<String, Integer> armorSlots;
    private final Map<String, CustomSlot> customSlots;
    private final NamespacedKey formKey;
    private final NamespacedKey typeKey;
    // Gear-menu filler material (non-interactive background) and the pane shown
    // for permission-locked skill-gem slots. Both configurable via settings.yml.
    private Material fillPane;
    private Material lockedPane;
    // How many Active / Passive skill-gem slots are usable with no permission.
    // Slots beyond these counts require their per-slot `permission` node.
    private int defaultActiveSlots;
    private int defaultPassiveSlots;
    /** Ticks before AutoPick vacuums owned ground drops (visual delay). */
    private int autopickupGroundDelayTicks;

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        this.armorSlots = new HashMap<String, Integer>();
        this.customSlots = new HashMap<String, CustomSlot>();
        this.formKey = new NamespacedKey(plugin, "form");
        this.typeKey = new NamespacedKey(plugin, "type");
        this.loadConfig();
    }

    public void loadConfig() {
        try {
            this.plugin.saveResource("settings.yml", false);
            File configFile = new File(this.plugin.getDataFolder(), "settings.yml");
            this.config = YamlConfiguration.loadConfiguration((File)configFile);
            this.debugMode = this.config.getBoolean("debug.enabled", false);
            this.overrideEKey = this.config.getBoolean("general.override-e-key", true);
            this.fillPane = this.parseMaterial(this.config.getString("general.fill-pane", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
            this.lockedPane = this.parseMaterial(this.config.getString("general.locked-pane", "RED_STAINED_GLASS_PANE"), Material.RED_STAINED_GLASS_PANE);
            this.defaultActiveSlots = Math.max(0, this.config.getInt("skill-slots.default-active", 3));
            this.defaultPassiveSlots = Math.max(0, this.config.getInt("skill-slots.default-passive", 2));
            this.autopickupGroundDelayTicks = Math.max(1, this.config.getInt("autopickup.ground-delay-ticks", 15));
            this.armorSlots.clear();
            this.armorSlots.put("helmet", this.config.getInt("armor-slots.helmet", 0));
            this.armorSlots.put("chestplate", this.config.getInt("armor-slots.chestplate", 9));
            this.armorSlots.put("leggings", this.config.getInt("armor-slots.leggings", 18));
            this.armorSlots.put("boots", this.config.getInt("armor-slots.boots", 27));
            for (Map.Entry<String, Integer> entry : this.armorSlots.entrySet()) {
                if (entry.getValue() >= 0 && entry.getValue() < 54) continue;
                this.plugin.getLogger().warning("Invalid armor slot position for " + entry.getKey() + ": " + String.valueOf(entry.getValue()));
                switch (entry.getKey()) {
                    case "helmet": {
                        this.armorSlots.put("helmet", 0);
                        break;
                    }
                    case "chestplate": {
                        this.armorSlots.put("chestplate", 9);
                        break;
                    }
                    case "leggings": {
                        this.armorSlots.put("leggings", 18);
                        break;
                    }
                    case "boots": {
                        this.armorSlots.put("boots", 27);
                    }
                }
            }
            this.customSlots.clear();
            if (this.config.getConfigurationSection("custom-slots.slots") != null) {
                for (String key : this.config.getConfigurationSection("custom-slots.slots").getKeys(false)) {
                    String path = "custom-slots.slots." + key;
                    int position = this.config.getInt(path + ".position", 0);
                    if (position < 0 || position >= 54) {
                        this.plugin.getLogger().warning("Invalid position for custom slot " + key + ": " + position);
                        position = 0;
                    }
                    CustomSlot slot = new CustomSlot(this.config.getBoolean(path + ".enabled", true), this.config.getString(path + ".form", "ring"), this.config.getString(path + ".type", "accessory"), position, this.config.getString(path + ".slot-type", "skill"), this.config.getString(path + ".lore_match", ""), this.config.getString(path + ".id_match", ""), this.config.getString(path + ".permission", ""));
                    this.customSlots.put(key, slot);
                }
            }
            this.saveConfig();
        }
        catch (Exception e) {
            this.plugin.getLogger().log(Level.SEVERE, "Error loading configuration", e);
        }
    }

    public boolean isDebugEnabled() {
        return this.debugMode;
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugMode = enabled;
        this.config.set("debug.enabled", (Object)enabled);
        this.saveConfig();
    }

    public void reloadConfig() {
        this.loadConfig();
    }

    public int getArmorSlot(String type) {
        return this.armorSlots.getOrDefault(type, 0);
    }

    public Map<String, Integer> getArmorSlots() {
        return new HashMap<String, Integer>(this.armorSlots);
    }

    public Map<String, CustomSlot> getCustomSlots() {
        return new HashMap<String, CustomSlot>(this.customSlots);
    }

    public Material getFillPane() {
        return this.fillPane;
    }

    public Material getLockedPane() {
        return this.lockedPane;
    }

    public int getDefaultActiveSlots() {
        return this.defaultActiveSlots;
    }

    public int getDefaultPassiveSlots() {
        return this.defaultPassiveSlots;
    }

    public int getAutopickupGroundDelayTicks() {
        return this.autopickupGroundDelayTicks;
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null || name.trim().isEmpty()) {
            return fallback;
        }
        Material material = Material.matchMaterial(name.trim().toUpperCase());
        if (material == null) {
            this.plugin.getLogger().warning("Invalid material '" + name + "' in settings.yml; using " + fallback.name());
            return fallback;
        }
        return material;
    }

    /**
     * Whether the given skill-gem slot is usable by this player. A slot is unlocked
     * when its position falls within the configured default-open count for its type
     * (Active/Passive), OR the player holds the slot's configured permission node.
     * Non-skill (attribute) slots are never permission-gated and always return true.
     */
    public boolean isSkillSlotUnlocked(CustomSlot slot, Player player) {
        if (slot == null) {
            return false;
        }
        if (!"skill".equalsIgnoreCase(slot.getSlotType())) {
            return true;
        }
        String type = slot.getType();
        int defaultOpen = "Passive".equalsIgnoreCase(type) ? this.defaultPassiveSlots : this.defaultActiveSlots;
        List<Integer> positions = new ArrayList<Integer>();
        for (CustomSlot other : this.customSlots.values()) {
            if (!"skill".equalsIgnoreCase(other.getSlotType())) continue;
            if (type == null || !type.equalsIgnoreCase(other.getType())) continue;
            positions.add(other.getPosition());
        }
        Collections.sort(positions);
        int index = positions.indexOf(slot.getPosition());
        if (index >= 0 && index < defaultOpen) {
            return true;
        }
        String permission = slot.getPermission();
        if (permission == null || permission.isEmpty()) {
            return false;
        }
        return player.hasPermission(permission);
    }

    public NamespacedKey getFormKey() {
        return this.formKey;
    }

    public NamespacedKey getTypeKey() {
        return this.typeKey;
    }

    public void debug(String message) {
        if (this.debugMode) {
            this.plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    private void saveConfig() {
        try {
            this.config.set("general.override-e-key", (Object)this.overrideEKey);
            this.config.set("general.fill-pane", (Object)this.fillPane.name());
            this.config.set("general.locked-pane", (Object)this.lockedPane.name());
            this.config.set("skill-slots.default-active", (Object)this.defaultActiveSlots);
            this.config.set("skill-slots.default-passive", (Object)this.defaultPassiveSlots);
            for (Map.Entry<String, Integer> entry : this.armorSlots.entrySet()) {
                this.config.set("armor-slots." + entry.getKey(), (Object)entry.getValue());
            }
            for (Map.Entry<String, CustomSlot> entry : this.customSlots.entrySet()) {
                String path = "custom-slots.slots." + entry.getKey();
                CustomSlot slot = entry.getValue();
                this.config.set(path + ".enabled", (Object)slot.isEnabled());
                this.config.set(path + ".form", (Object)slot.getForm());
                this.config.set(path + ".type", (Object)slot.getType());
                this.config.set(path + ".position", (Object)slot.getPosition());
                this.config.set(path + ".slot-type", (Object)slot.getSlotType());
                this.config.set(path + ".lore_match", (Object)slot.getLoreMatch());
                this.config.set(path + ".id_match", (Object)slot.getIdMatch());
                this.config.set(path + ".permission", (Object)slot.getPermission());
            }
            this.config.set("debug.enabled", (Object)this.debugMode);
            this.config.save(new File(this.plugin.getDataFolder(), "settings.yml"));
        }
        catch (Exception e) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not save config to settings.yml", e);
        }
    }

    public boolean isOverrideEKey() {
        return this.overrideEKey;
    }

    public void setOverrideEKey(boolean overrideEKey) {
        this.overrideEKey = overrideEKey;
        this.config.set("general.override-e-key", (Object)overrideEKey);
        this.saveConfig();
    }

    public static class CustomSlot {
        private final boolean enabled;
        private final String form;
        private final String type;
        private final int position;
        private final String slotType;
        private final String loreMatch;
        private final String idMatch;
        private final String permission;

        public CustomSlot(boolean enabled, String form, String type, int position, String slotType, String loreMatch, String idMatch, String permission) {
            this.enabled = enabled;
            this.form = form;
            this.type = type;
            this.position = position;
            this.slotType = slotType;
            this.loreMatch = loreMatch;
            this.idMatch = idMatch == null ? "" : idMatch;
            this.permission = permission == null ? "" : permission;
        }

        public boolean isEnabled() {
            return this.enabled;
        }

        public String getForm() {
            return this.form;
        }

        public String getType() {
            return this.type;
        }

        public int getPosition() {
            return this.position;
        }

        public String getSlotType() {
            return this.slotType;
        }

        public String getLoreMatch() {
            return this.loreMatch;
        }

        /** Comma-separated substrings matched against the Divinity item id (e.g. "ring"). */
        public String getIdMatch() {
            return this.idMatch;
        }

        public String getPermission() {
            return this.permission;
        }
    }
}

