package com.example.custominventoryplugin.config;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class ConfigManager {
    private final Plugin plugin;
    private FileConfiguration config;
    private boolean debugMode;
    private final Map<String, Integer> armorSlots;
    private final Map<String, CustomSlot> customSlots;
    private final NamespacedKey formKey;
    private final NamespacedKey typeKey;
    private String itemForm;
    private String itemType;

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        this.armorSlots = new HashMap<>();
        this.customSlots = new HashMap<>();
        this.formKey = new NamespacedKey(plugin, "form");
        this.typeKey = new NamespacedKey(plugin, "type");
        loadConfig();
    }

    public void loadConfig() {
        try {
            // Save default settings.yml if it doesn't exist
            plugin.saveResource("settings.yml", false);
            
            // Load the config file
            File configFile = new File(plugin.getDataFolder(), "settings.yml");
            config = YamlConfiguration.loadConfiguration(configFile);
            
            // Load debug mode
            debugMode = config.getBoolean("debug.enabled", false);
            
            // Load armor slots
            armorSlots.clear();
            armorSlots.put("helmet", config.getInt("armor-slots.helmet", 0));
            armorSlots.put("chestplate", config.getInt("armor-slots.chestplate", 9));
            armorSlots.put("leggings", config.getInt("armor-slots.leggings", 18));
            armorSlots.put("boots", config.getInt("armor-slots.boots", 27));
            
            // Validate armor slots
            for (Map.Entry<String, Integer> entry : armorSlots.entrySet()) {
                if (entry.getValue() < 0 || entry.getValue() >= 36) {
                    plugin.getLogger().warning("Invalid armor slot position for " + entry.getKey() + ": " + entry.getValue());
                    // Reset to default position
                    switch (entry.getKey()) {
                        case "helmet" -> armorSlots.put("helmet", 0);
                        case "chestplate" -> armorSlots.put("chestplate", 9);
                        case "leggings" -> armorSlots.put("leggings", 18);
                        case "boots" -> armorSlots.put("boots", 27);
                    }
                }
            }
            
            // Load custom slots
            customSlots.clear();
            if (config.getConfigurationSection("custom-slots.slots") != null) {
                for (String key : config.getConfigurationSection("custom-slots.slots").getKeys(false)) {
                    String path = "custom-slots.slots." + key;
                    int position = config.getInt(path + ".position", 0);
                    
                    // Validate position
                    if (position < 0 || position >= 36) {
                        plugin.getLogger().warning("Invalid position for custom slot " + key + ": " + position);
                        position = 0; // Reset to default
                    }
                    
                    CustomSlot slot = new CustomSlot(
                        config.getBoolean(path + ".enabled", true),
                        config.getString(path + ".form", "ring"),
                        config.getString(path + ".type", "accessory"),
                        position,
                        config.getString(path + ".slot-type", "skill"),
                        config.getString(path + ".lore_match", "")
                    );
                    customSlots.put(key, slot);
                }
            }

            // Load item type settings
            itemForm = config.getString("item-types.form", "Type");
            itemType = config.getString("item-types.type", "Ring");
            
            // Save any corrected values
            saveConfig();
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading configuration", e);
        }
    }

    public boolean isDebugEnabled() {
        return debugMode;
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugMode = enabled;
        config.set("debug.enabled", enabled);
        saveConfig();
    }

    public void reloadConfig() {
        loadConfig();
    }

    public void setItemForm(String form) {
        this.itemForm = form;
        config.set("item-types.form", form);
        saveConfig();
    }

    public void setItemType(String type) {
        this.itemType = type;
        config.set("item-types.type", type);
        saveConfig();
    }

    public String getItemForm() {
        return itemForm;
    }

    public String getItemType() {
        return itemType;
    }

    public int getArmorSlot(String type) {
        return armorSlots.getOrDefault(type, 0);
    }

    public Map<String, Integer> getArmorSlots() {
        return new HashMap<>(armorSlots); // Return a copy to prevent external modification
    }

    public Map<String, CustomSlot> getCustomSlots() {
        return new HashMap<>(customSlots); // Return a copy to prevent external modification
    }

    public NamespacedKey getFormKey() {
        return formKey;
    }

    public NamespacedKey getTypeKey() {
        return typeKey;
    }

    public void debug(String message) {
        if (debugMode) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    private void saveConfig() {
        try {
            // Save armor slots
            for (Map.Entry<String, Integer> entry : armorSlots.entrySet()) {
                config.set("armor-slots." + entry.getKey(), entry.getValue());
            }
            
            // Save custom slots
            for (Map.Entry<String, CustomSlot> entry : customSlots.entrySet()) {
                String path = "custom-slots.slots." + entry.getKey();
                CustomSlot slot = entry.getValue();
                config.set(path + ".enabled", slot.isEnabled());
                config.set(path + ".form", slot.getForm());
                config.set(path + ".type", slot.getType());
                config.set(path + ".position", slot.getPosition());
                config.set(path + ".slot-type", slot.getSlotType());
                config.set(path + ".lore_match", slot.getLoreMatch());
            }
            
            // Save other settings
            config.set("debug.enabled", debugMode);
            config.set("item-types.form", itemForm);
            config.set("item-types.type", itemType);
            
            config.save(new File(plugin.getDataFolder(), "settings.yml"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save config to settings.yml", e);
        }
    }

    public static class CustomSlot {
        private final boolean enabled;
        private final String form;
        private final String type;
        private final int position;
        private final String slotType; // "skill" or "attribute"
        private final String loreMatch; // Custom lore matching pattern

        public CustomSlot(boolean enabled, String form, String type, int position, String slotType, String loreMatch) {
            this.enabled = enabled;
            this.form = form;
            this.type = type;
            this.position = position;
            this.slotType = slotType;
            this.loreMatch = loreMatch;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getForm() {
            return form;
        }

        public String getType() {
            return type;
        }

        public int getPosition() {
            return position;
        }

        public String getSlotType() {
            return slotType;
        }

        public String getLoreMatch() {
            return loreMatch;
        }
    }
} 