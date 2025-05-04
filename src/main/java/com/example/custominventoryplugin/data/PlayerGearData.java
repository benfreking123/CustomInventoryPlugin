package com.example.custominventoryplugin.data;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.Set;
import java.util.HashSet;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;

public class PlayerGearData {
    private static final Map<UUID, Map<String, ItemStack>> playerGear = new HashMap<>();
    public static final Map<UUID, Map<String, Map<String, Integer>>> playerSlotAttributes = new HashMap<>();
    private static final Map<UUID, Set<String>> playerPermissions = new HashMap<>();
    private static Plugin plugin;
    private static File playerDataFolder;
    private static final int AUTO_SAVE_INTERVAL = 300; // 5 minutes in seconds
    private static final Map<UUID, Long> lastSaveTime = new HashMap<>();
    private static ConfigManager configManager;
    private static final Set<UUID> loadedPlayers = new HashSet<>();

    public static void initialize(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }
        PlayerGearData.plugin = plugin;
        configManager = ((CustomInventoryPlugin) plugin).getConfigManager();
        playerDataFolder = new File(plugin.getDataFolder(), "playerData");
        if (!playerDataFolder.exists()) {
            playerDataFolder.mkdirs();
        }
        
        // Start auto-save task
        new BukkitRunnable() {
            @Override
            public void run() {
                autoSave();
            }
        }.runTaskTimer(plugin, AUTO_SAVE_INTERVAL * 20L, AUTO_SAVE_INTERVAL * 20L);
    }

    public static void loadPlayerData(UUID playerUUID) {
        if (playerUUID == null) {
            logWarning("Attempted to load data for null player UUID");
            return;
        }

        if (loadedPlayers.contains(playerUUID)) {
            return; // Already loaded
        }

        File playerFile = getPlayerFile(playerUUID);
        if (!playerFile.exists()) {
            loadedPlayers.add(playerUUID); // Mark as loaded even if no file exists
            return;
        }

        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(playerFile);

            // Load gear data with decompression
            if (config.contains("gear")) {
                Map<String, ItemStack> gear = new HashMap<>();
                for (String key : config.getConfigurationSection("gear").getKeys(false)) {
                    String compressed = config.getString("gear." + key);
                    ItemStack item = decompressItemStack(compressed);
                    if (item != null) {
                        gear.put(key, item);
                    }
                }
                if (!gear.isEmpty()) {
                    playerGear.put(playerUUID, gear);
                }
            }

            // Load slot attributes
            if (config.contains("attributes")) {
                Map<String, Map<String, Integer>> attributes = new HashMap<>();
                for (String key : config.getConfigurationSection("attributes").getKeys(false)) {
                    Map<String, Integer> slotAttrs = new HashMap<>();
                    for (String attrKey : config.getConfigurationSection("attributes." + key).getKeys(false)) {
                        slotAttrs.put(attrKey, config.getInt("attributes." + key + "." + attrKey));
                    }
                    attributes.put(key, slotAttrs);
                }
                if (!attributes.isEmpty()) {
                    playerSlotAttributes.put(playerUUID, attributes);
                }
            }

            // Load permissions
            if (config.contains("permissions")) {
                Set<String> permissions = new HashSet<>(config.getStringList("permissions"));
                if (!permissions.isEmpty()) {
                    playerPermissions.put(playerUUID, permissions);
                }
            }

            loadedPlayers.add(playerUUID);
            lastSaveTime.put(playerUUID, System.currentTimeMillis());
            logDebug("Loaded data for player: " + playerUUID);
        } catch (Exception e) {
            logWarning("Failed to load data for player: " + playerUUID, e);
        }
    }

    public static void unloadPlayerData(UUID playerUUID) {
        if (playerUUID == null) {
            logWarning("Attempted to unload data for null player UUID");
            return;
        }

        if (!loadedPlayers.contains(playerUUID)) {
            return; // Not loaded
        }

        // Save data before unloading
        savePlayerData(playerUUID);

        // Remove from memory
        playerGear.remove(playerUUID);
        playerSlotAttributes.remove(playerUUID);
        playerPermissions.remove(playerUUID);
        lastSaveTime.remove(playerUUID);
        loadedPlayers.remove(playerUUID);

        logDebug("Unloaded data for player: " + playerUUID);
    }

    public static void setPlayerGear(UUID playerUUID, String slotId, ItemStack item) {
        if (playerUUID == null) {
            logWarning("Attempted to set gear for null player UUID");
            return;
        }

        // Ensure player data is loaded
        if (!loadedPlayers.contains(playerUUID)) {
            loadPlayerData(playerUUID);
        }

        logDebug("Setting gear for player " + playerUUID + " in slot " + slotId + 
            ": " + (item != null ? item.getType() : "null"));
        
        playerGear.computeIfAbsent(playerUUID, k -> new HashMap<>()).put(slotId, item);
        savePlayerData(playerUUID);
    }

    public static ItemStack getPlayerGear(UUID playerUUID, String slotId) {
        if (playerUUID == null) {
            logWarning("Attempted to get gear for null player UUID");
            return null;
        }

        // Ensure player data is loaded
        if (!loadedPlayers.contains(playerUUID)) {
            loadPlayerData(playerUUID);
        }

        Map<String, ItemStack> playerSlots = playerGear.get(playerUUID);
        ItemStack item = playerSlots != null ? playerSlots.get(slotId) : null;
        logDebug("Getting gear for player " + playerUUID + " in slot " + slotId + 
            ": " + (item != null ? item.getType() : "null"));
        return item;
    }

    private static void autoSave() {
        if (plugin == null || !plugin.isEnabled()) return;
        
        long currentTime = System.currentTimeMillis();
        for (UUID playerUUID : playerGear.keySet()) {
            Long lastSave = lastSaveTime.get(playerUUID);
            if (lastSave == null || (currentTime - lastSave) > (AUTO_SAVE_INTERVAL * 1000L)) {
                savePlayerData(playerUUID);
                lastSaveTime.put(playerUUID, currentTime);
            }
        }
    }

    private static File getPlayerFile(UUID playerUUID) {
        if (playerUUID == null) {
            throw new IllegalArgumentException("Player UUID cannot be null");
        }
        return new File(playerDataFolder, playerUUID.toString() + ".yml");
    }

    private static File getBackupFile(UUID playerUUID) {
        if (playerUUID == null) {
            throw new IllegalArgumentException("Player UUID cannot be null");
        }
        return new File(playerDataFolder, playerUUID.toString() + ".backup.yml");
    }

    private static String compressItemStack(ItemStack item) {
        if (item == null) return null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            byte[] data = item.serializeAsBytes();
            deflater.setInput(data);
            deflater.finish();
            
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                baos.write(buffer, 0, count);
            }
            deflater.end();
            
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            logWarning("Failed to compress item stack", e);
            return null;
        }
    }

    private static ItemStack decompressItemStack(String compressed) {
        if (compressed == null) return null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] data = Base64.getDecoder().decode(compressed);
            Inflater inflater = new Inflater();
            inflater.setInput(data);
            
            byte[] buffer = new byte[1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                baos.write(buffer, 0, count);
            }
            inflater.end();
            
            return ItemStack.deserializeBytes(baos.toByteArray());
        } catch (Exception e) {
            logWarning("Failed to decompress item stack", e);
            return null;
        }
    }

    private static void savePlayerData(UUID playerUUID) {
        if (playerUUID == null) {
            logWarning("Attempted to save data for null player UUID");
            return;
        }

        File playerFile = getPlayerFile(playerUUID);
        File backupFile = getBackupFile(playerUUID);
        
        // Create backup of existing file
        if (playerFile.exists()) {
            try {
                if (backupFile.exists()) {
                    backupFile.delete();
                }
                playerFile.renameTo(backupFile);
            } catch (Exception e) {
                logWarning("Failed to create backup for " + playerUUID, e);
            }
        }

        FileConfiguration config = new YamlConfiguration();

        // Save gear data with compression
        Map<String, ItemStack> gear = playerGear.get(playerUUID);
        if (gear != null) {
            for (Map.Entry<String, ItemStack> entry : gear.entrySet()) {
                String compressed = compressItemStack(entry.getValue());
                if (compressed != null) {
                    config.set("gear." + entry.getKey(), compressed);
                }
            }
        }

        // Save slot attributes
        Map<String, Map<String, Integer>> attributes = playerSlotAttributes.get(playerUUID);
        if (attributes != null) {
            for (Map.Entry<String, Map<String, Integer>> entry : attributes.entrySet()) {
                config.set("attributes." + entry.getKey(), entry.getValue());
            }
        }

        // Save permissions
        Set<String> permissions = playerPermissions.get(playerUUID);
        if (permissions != null) {
            config.set("permissions", new HashSet<>(permissions));
        }

        try {
            config.save(playerFile);
            lastSaveTime.put(playerUUID, System.currentTimeMillis());
            
            // Clean up backup if save was successful
            if (backupFile.exists()) {
                backupFile.delete();
            }
        } catch (IOException e) {
            logSevere("Could not save player data for " + playerUUID, e);
            
            // Restore from backup if save failed
            if (backupFile.exists()) {
                try {
                    if (playerFile.exists()) {
                        playerFile.delete();
                    }
                    backupFile.renameTo(playerFile);
                } catch (Exception ex) {
                    logSevere("Failed to restore backup for " + playerUUID, ex);
                }
            }
        }
    }

    private static void logDebug(String message) {
        if (plugin != null && plugin.getLogger() != null && configManager != null && configManager.isDebugEnabled()) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    private static void logWarning(String message) {
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().warning(message);
        }
    }

    private static void logWarning(String message, Throwable e) {
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().log(Level.WARNING, message, e);
        }
    }

    private static void logSevere(String message, Throwable e) {
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().log(Level.SEVERE, message, e);
        }
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
            savePlayerData(playerUUID);
        }
    }

    public static void clearPlayerData(UUID playerUUID) {
        playerGear.remove(playerUUID);
        playerSlotAttributes.remove(playerUUID);
        playerPermissions.remove(playerUUID);
        File playerFile = getPlayerFile(playerUUID);
        if (playerFile.exists()) {
            playerFile.delete();
        }
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
        savePlayerData(playerUUID);
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
            savePlayerData(playerUUID);
        }
    }

    public static void clearPlayerSlotAttributes(UUID playerUUID) {
        playerSlotAttributes.remove(playerUUID);
        savePlayerData(playerUUID);
    }

    public static void addPlayerPermission(UUID playerUUID, String permission) {
        playerPermissions.computeIfAbsent(playerUUID, k -> new HashSet<>()).add(permission);
        savePlayerData(playerUUID);
    }

    public static void removePlayerPermission(UUID playerUUID, String permission) {
        Set<String> permissions = playerPermissions.get(playerUUID);
        if (permissions != null) {
            permissions.remove(permission);
            if (permissions.isEmpty()) {
                playerPermissions.remove(playerUUID);
            }
            savePlayerData(playerUUID);
        }
    }

    public static Set<String> getPlayerPermissions(UUID playerUUID) {
        return playerPermissions.getOrDefault(playerUUID, new HashSet<>());
    }

    public static void clearPlayerPermissions(UUID playerUUID) {
        playerPermissions.remove(playerUUID);
        savePlayerData(playerUUID);
    }
} 