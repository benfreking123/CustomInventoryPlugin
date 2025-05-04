package com.example.custominventoryplugin.data;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.Set;
import java.util.HashSet;

public class PlayerGearData {
    private static final Map<UUID, Map<String, ItemStack>> playerGear = new HashMap<>();
    public static final Map<UUID, Map<String, Map<String, Integer>>> playerSlotAttributes = new HashMap<>();
    private static final Map<UUID, Set<String>> playerPermissions = new HashMap<>();
    private static Plugin plugin;

    public static void initialize(Plugin plugin) {
        PlayerGearData.plugin = plugin;
        loadData();
    }

    public static void saveData() {
        File dataFile = new File(plugin.getDataFolder(), "player_gear.yml");
        FileConfiguration config = new YamlConfiguration();

        for (Map.Entry<UUID, Map<String, ItemStack>> playerEntry : playerGear.entrySet()) {
            String playerKey = playerEntry.getKey().toString();
            for (Map.Entry<String, ItemStack> slotEntry : playerEntry.getValue().entrySet()) {
                String slotKey = playerKey + "." + slotEntry.getKey();
                config.set(slotKey, slotEntry.getValue());
            }
        }

        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save player gear data", e);
        }
    }

    private static void loadData() {
        File dataFile = new File(plugin.getDataFolder(), "player_gear.yml");
        if (!dataFile.exists()) {
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        playerGear.clear();

        for (String playerKey : config.getKeys(false)) {
            try {
                UUID playerUUID = UUID.fromString(playerKey);
                Map<String, ItemStack> playerSlots = new HashMap<>();
                
                for (String slotKey : config.getConfigurationSection(playerKey).getKeys(false)) {
                    ItemStack item = config.getItemStack(playerKey + "." + slotKey);
                    if (item != null) {
                        playerSlots.put(slotKey, item);
                    }
                }
                
                if (!playerSlots.isEmpty()) {
                    playerGear.put(playerUUID, playerSlots);
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID in player gear data: " + playerKey);
            }
        }
    }

    public static void setPlayerGear(UUID playerUUID, String slotId, ItemStack item) {
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().info("[DEBUG] Setting gear for player " + playerUUID + " in slot " + slotId + 
                ": " + (item != null ? item.getType() : "null"));
        }
        playerGear.computeIfAbsent(playerUUID, k -> new HashMap<>()).put(slotId, item);
        saveData(); // Save after each change
    }

    public static ItemStack getPlayerGear(UUID playerUUID, String slotId) {
        Map<String, ItemStack> playerSlots = playerGear.get(playerUUID);
        ItemStack item = playerSlots != null ? playerSlots.get(slotId) : null;
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().info("[DEBUG] Getting gear for player " + playerUUID + " in slot " + slotId + 
                ": " + (item != null ? item.getType() : "null"));
        }
        return item;
    }

    public static void removePlayerGear(UUID playerUUID, String slotId) {
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().info("[DEBUG] Removing gear for player " + playerUUID + " in slot " + slotId);
        }
        Map<String, ItemStack> playerSlots = playerGear.get(playerUUID);
        if (playerSlots != null) {
            playerSlots.remove(slotId);
            if (playerSlots.isEmpty()) {
                playerGear.remove(playerUUID);
            }
        }
        saveData(); // Save after each change
    }

    public static void clearPlayerData(UUID playerUUID) {
        playerGear.remove(playerUUID);
        saveData(); // Save after each change
    }

    // Legacy methods for backward compatibility
    @Deprecated
    public static void setPlayerRing(UUID playerUUID, ItemStack ring) {
        setPlayerGear(playerUUID, "ring", ring);
    }

    @Deprecated
    public static ItemStack getPlayerRing(UUID playerUUID) {
        return getPlayerGear(playerUUID, "ring");
    }

    @Deprecated
    public static void removePlayerRing(UUID playerUUID) {
        removePlayerGear(playerUUID, "ring");
    }

    public static void setPlayerSlotAttributes(UUID playerUUID, String slotId, Map<String, Integer> attributes) {
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().info("[DEBUG] Setting attributes for player " + playerUUID + " in slot " + slotId + 
                ": " + attributes);
        }
        playerSlotAttributes
            .computeIfAbsent(playerUUID, k -> new HashMap<>())
            .put(slotId, new HashMap<>(attributes));
    }

    public static Map<String, Integer> getPlayerSlotAttributes(UUID playerUUID, String slotId) {
        Map<String, Map<String, Integer>> slotMap = playerSlotAttributes.get(playerUUID);
        return slotMap != null ? slotMap.getOrDefault(slotId, new HashMap<>()) : new HashMap<>();
    }

    public static void removePlayerSlotAttributes(UUID playerUUID, String slotId) {
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().info("[DEBUG] Removing attributes for player " + playerUUID + " in slot " + slotId);
        }
        Map<String, Map<String, Integer>> slotMap = playerSlotAttributes.get(playerUUID);
        if (slotMap != null) {
            slotMap.remove(slotId);
            if (slotMap.isEmpty()) {
                playerSlotAttributes.remove(playerUUID);
            }
        }
    }

    public static void clearPlayerSlotAttributes(UUID playerUUID) {
        playerSlotAttributes.remove(playerUUID);
    }

    public static void addPlayerPermission(UUID playerUUID, String permission) {
        playerPermissions.computeIfAbsent(playerUUID, k -> new HashSet<>()).add(permission);
    }

    public static void removePlayerPermission(UUID playerUUID, String permission) {
        Set<String> permissions = playerPermissions.get(playerUUID);
        if (permissions != null) {
            permissions.remove(permission);
            if (permissions.isEmpty()) {
                playerPermissions.remove(playerUUID);
            }
        }
    }

    public static Set<String> getPlayerPermissions(UUID playerUUID) {
        return playerPermissions.getOrDefault(playerUUID, new HashSet<>());
    }

    public static void clearPlayerPermissions(UUID playerUUID) {
        playerPermissions.remove(playerUUID);
    }
} 