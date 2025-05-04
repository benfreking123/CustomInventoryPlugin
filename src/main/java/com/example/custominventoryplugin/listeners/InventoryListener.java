package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerGearData;
import com.example.custominventoryplugin.inventory.GearInventory;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.permissions.PermissionAttachment;
import studio.magemonkey.fabled.api.player.PlayerData;
import studio.magemonkey.fabled.Fabled;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.ArrayList;

public class InventoryListener implements Listener {
    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;
    private final Map<UUID, PermissionAttachment> permissionAttachments;
    
    public InventoryListener(ConfigManager configManager, CustomInventoryPlugin plugin) {
        this.configManager = configManager;
        this.plugin = plugin;
        this.permissionAttachments = new HashMap<>();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GearInventory)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        
        // Cancel if clicking outside the inventory
        if (slot < 0) {
            return;
        }

        // Handle shift-click from player inventory
        if (event.isShiftClick() && event.getClickedInventory() == event.getView().getBottomInventory()) {
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType().isAir()) {
                return;
            }

            // Cancel vanilla shift-click behavior
            event.setCancelled(true);

            // Try to place in correct armor slot
            for (Map.Entry<String, Integer> entry : configManager.getArmorSlots().entrySet()) {
                String armorType = entry.getKey();
                int guiSlot = entry.getValue();
                if (isValidArmorForSlot(clickedItem, armorType)) {
                    ItemStack slotItem = event.getInventory().getItem(guiSlot);
                    if (slotItem == null || slotItem.getType().isAir()) {
                        // Place item in GUI
                        event.getInventory().setItem(guiSlot, clickedItem.clone());
                        
                        // Update player's actual armor
                        PlayerInventory playerInv = player.getInventory();
                        switch (armorType) {
                            case "helmet" -> playerInv.setHelmet(clickedItem.clone());
                            case "chestplate" -> playerInv.setChestplate(clickedItem.clone());
                            case "leggings" -> playerInv.setLeggings(clickedItem.clone());
                            case "boots" -> playerInv.setBoots(clickedItem.clone());
                        }
                        
                        // Update player data
                        PlayerGearData.setPlayerGear(player.getUniqueId(), armorType, clickedItem.clone());
                        
                        // Remove from player's inventory
                        clickedItem.setAmount(clickedItem.getAmount() - 1);
                        if (clickedItem.getAmount() <= 0) {
                            event.setCurrentItem(null);
                        }
                        
                        // Update inventory display
                        if (event.getInventory().getHolder() instanceof GearInventory gearInventory) {
                            gearInventory.updateInventory();
                        }
                        return;
                    }
                }
            }

            // Try to place in correct custom slot
            for (Map.Entry<String, ConfigManager.CustomSlot> entry : configManager.getCustomSlots().entrySet()) {
                String slotId = entry.getKey();
                ConfigManager.CustomSlot customSlot = entry.getValue();
                int guiSlot = customSlot.getPosition();
                if (customSlot.isEnabled() && isValidItemForSlot(clickedItem, customSlot)) {
                    ItemStack slotItem = event.getInventory().getItem(guiSlot);
                    if (slotItem == null || slotItem.getType().isAir()) {
                        // Place item in GUI
                        event.getInventory().setItem(guiSlot, clickedItem.clone());
                        
                        // Update player data
                        configManager.debug("Shift-clicking item into custom slot " + slotId + ": " + clickedItem.getType());
                        PlayerGearData.setPlayerGear(player.getUniqueId(), slotId, clickedItem.clone());
                        
                        // If this is a skill slot, handle permissions
                        if ("skill".equalsIgnoreCase(customSlot.getSlotType())) {
                            handleSkillSlotPermissions(player, clickedItem, slotId);
                        }
                        
                        // Remove from player's inventory
                        clickedItem.setAmount(clickedItem.getAmount() - 1);
                        if (clickedItem.getAmount() <= 0) {
                            event.setCurrentItem(null);
                        }
                        
                        // Update inventory display
                        if (event.getInventory().getHolder() instanceof GearInventory gearInventory) {
                            gearInventory.updateInventory();
                        }
                        return;
                    }
                }
            }

            // If no valid slot found, do nothing (item stays in player inventory)
            return;
        }

        // Handle normal clicks and drags
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            // Check if the slot is a custom slot
            Map<String, ConfigManager.CustomSlot> customSlots = configManager.getCustomSlots();
            for (Map.Entry<String, ConfigManager.CustomSlot> entry : customSlots.entrySet()) {
                String slotId = entry.getKey();
                ConfigManager.CustomSlot customSlot = entry.getValue();
                if (customSlot.getPosition() == event.getRawSlot()) {
                    if (!customSlot.isEnabled()) {
                        event.setCancelled(true);
                        return;
                    }
                    
                    // Handle item placement
                    if (event.getCursor() != null && !event.getCursor().getType().isAir()) {
                        if (!isValidItemForSlot(event.getCursor(), customSlot)) {
                            event.setCancelled(true);
                            return;
                        }
                        
                        // Store the item in PlayerGearData
                        configManager.debug("Placing item in custom slot " + slotId + ": " + event.getCursor().getType());
                        PlayerGearData.setPlayerGear(player.getUniqueId(), slotId, event.getCursor().clone());
                        
                        // If this is a skill slot, handle permissions
                        if ("skill".equalsIgnoreCase(customSlot.getSlotType())) {
                            handleSkillSlotPermissions(player, event.getCursor(), slotId);
                        }
                    }
                    
                    // Handle item removal
                    if (event.getCurrentItem() != null) {
                        if (event.isShiftClick() || event.getCursor() == null || event.getCursor().getType().isAir()) {
                            // Remove the item from PlayerGearData when:
                            // 1. Shift-clicking
                            // 2. Clicking with empty cursor (picking up)
                            configManager.debug("Removing item from custom slot " + slotId + " (shift/empty)");
                            PlayerGearData.removePlayerGear(player.getUniqueId(), slotId);
                            
                            // Remove attributes or permissions based on slot type
                            if ("skill".equalsIgnoreCase(customSlot.getSlotType())) {
                                removeSkillSlotPermissions(player, slotId);
                            } else if ("attribute".equalsIgnoreCase(customSlot.getSlotType())) {
                                removeSlotAttributes(player, slotId);
                            }
                        } else if (event.getCursor() != null && !event.getCursor().getType().isAir()) {
                            // If swapping items, remove the old one and store the new one
                            configManager.debug("Swapping items in custom slot " + slotId);
                            configManager.debug("Removing old item: " + event.getCurrentItem().getType());
                            configManager.debug("Storing new item: " + event.getCursor().getType());
                            
                            // Remove old permissions/attributes
                            if ("skill".equalsIgnoreCase(customSlot.getSlotType())) {
                                removeSkillSlotPermissions(player, slotId);
                            } else if ("attribute".equalsIgnoreCase(customSlot.getSlotType())) {
                                removeSlotAttributes(player, slotId);
                            }
                            
                            // Store new item
                            PlayerGearData.setPlayerGear(player.getUniqueId(), slotId, event.getCursor().clone());
                            
                            // Handle new permissions if it's a skill slot
                            if ("skill".equalsIgnoreCase(customSlot.getSlotType())) {
                                handleSkillSlotPermissions(player, event.getCursor(), slotId);
                            }
                        }
                    }
                    return;
                }
            }
            
            // Handle armor slots
            Map<String, Integer> armorSlots = configManager.getArmorSlots();
            for (Map.Entry<String, Integer> entry : armorSlots.entrySet()) {
                if (entry.getValue() == event.getRawSlot()) {
                    // Validate armor type
                    ItemStack item = event.getCursor();
                    if (item != null && !item.getType().isAir()) {
                        if (!isValidArmorForSlot(item, entry.getKey())) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                    
                    // Sync with player's armor
                    PlayerInventory playerInv = player.getInventory();
                    switch (entry.getKey()) {
                        case "helmet" -> playerInv.setHelmet(event.getCursor());
                        case "chestplate" -> playerInv.setChestplate(event.getCursor());
                        case "leggings" -> playerInv.setLeggings(event.getCursor());
                        case "boots" -> playerInv.setBoots(event.getCursor());
                    }
                    return;
                }
            }
            
            // Cancel if clicking on barrier or glass panes
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem != null && 
                (clickedItem.getType() == Material.BARRIER || 
                 clickedItem.getType() == Material.GRAY_STAINED_GLASS_PANE)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof GearInventory)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        
        // Check if any dragged slots are custom slots
        for (int slot : event.getRawSlots()) {
            Map<String, ConfigManager.CustomSlot> customSlots = configManager.getCustomSlots();
            for (Map.Entry<String, ConfigManager.CustomSlot> entry : customSlots.entrySet()) {
                String slotId = entry.getKey();
                ConfigManager.CustomSlot customSlot = entry.getValue();
                if (customSlot.getPosition() == slot) {
                    if (!customSlot.isEnabled()) {
                        event.setCancelled(true);
                        return;
                    }
                    
                    // Validate the dragged item
                    ItemStack draggedItem = event.getOldCursor();
                    if (!isValidItemForSlot(draggedItem, customSlot)) {
                        event.setCancelled(true);
                        return;
                    }
                    
                    // Store the item in PlayerGearData
                    PlayerGearData.setPlayerGear(player.getUniqueId(), slotId, draggedItem);
                    return;
                }
            }
            
            // Check if any dragged slots are armor slots
            Map<String, Integer> armorSlots = configManager.getArmorSlots();
            for (Map.Entry<String, Integer> entry : armorSlots.entrySet()) {
                if (entry.getValue() == slot) {
                    // Validate armor type
                    ItemStack draggedItem = event.getOldCursor();
                    if (!isValidArmorForSlot(draggedItem, entry.getKey())) {
                        event.setCancelled(true);
                        return;
                    }
                    
                    // Sync with player's armor
                    PlayerInventory playerInv = player.getInventory();
                    switch (entry.getKey()) {
                        case "helmet" -> playerInv.setHelmet(draggedItem);
                        case "chestplate" -> playerInv.setChestplate(draggedItem);
                        case "leggings" -> playerInv.setLeggings(draggedItem);
                        case "boots" -> playerInv.setBoots(draggedItem);
                    }
                    return;
                }
            }
            
            // Cancel if dragging to barrier or glass panes
            ItemStack targetItem = event.getInventory().getItem(slot);
            if (targetItem != null && 
                (targetItem.getType() == Material.BARRIER || 
                 targetItem.getType() == Material.GRAY_STAINED_GLASS_PANE)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof GearInventory)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        
        // Remove existing gear attributes
        removeGearAttributes(player);
        
        // Remove existing permissions
        PermissionAttachment attachment = permissionAttachments.remove(playerUUID);
        if (attachment != null) {
            attachment.remove();
        }
        PlayerGearData.clearPlayerPermissions(playerUUID);
        
        // Apply new gear attributes and handle skills
        Map<String, ConfigManager.CustomSlot> customSlots = configManager.getCustomSlots();
        for (Map.Entry<String, ConfigManager.CustomSlot> entry : customSlots.entrySet()) {
            String slotId = entry.getKey();
            ConfigManager.CustomSlot slot = entry.getValue();
            if (!slot.isEnabled()) continue;

            ItemStack gear = PlayerGearData.getPlayerGear(playerUUID, slotId);
            if (gear != null && !gear.getType().isAir()) {
                if ("skill".equalsIgnoreCase(slot.getSlotType())) {
                    // Handle skill slot
                    if (gear.getItemMeta() != null) {
                        String displayName = gear.getItemMeta().getDisplayName();
                        if (displayName != null && displayName.endsWith(" Gem")) {
                            String skillName = displayName.substring(0, displayName.length() - 4).trim()
                                .toLowerCase().replace(' ', '-');
                            String permission = "fabled.skill." + skillName;
                            
                            // Add permission and track it
                            PermissionAttachment newAttachment = player.addAttachment(plugin);
                            newAttachment.setPermission(permission, true);
                            permissionAttachments.put(playerUUID, newAttachment);
                            PlayerGearData.addPlayerPermission(playerUUID, permission);
                            configManager.debug("Granted permission: " + permission + " to " + player.getName());
                        }
                    }
                } else if ("attribute".equalsIgnoreCase(slot.getSlotType())) {
                    // Handle attribute slot (existing logic)
                    applyGearAttributes(player, gear, slot);
                }
            } else {
                // If slot is empty, remove any associated permissions for skill slots
                if ("skill".equalsIgnoreCase(slot.getSlotType())) {
                    configManager.debug("No skill item in slot " + slotId + " for " + player.getName() + ", no permission granted.");
                }
            }
        }
    }

    private boolean isValidItemForSlot(ItemStack item, ConfigManager.CustomSlot slot) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        List<String> lore = meta.getLore();
        if (lore == null) return false;

        String loreMatch = slot.getLoreMatch();
        if (loreMatch == null || loreMatch.isEmpty()) {
            // If no lore match is specified, use the old form/type matching
            String form = extractLoreValue(lore, slot.getForm());
            String type = extractLoreValue(lore, slot.getType());
            return form != null && type != null && 
                   form.equalsIgnoreCase(slot.getForm()) && 
                   type.equalsIgnoreCase(slot.getType());
        }

        // Clean up the lore for matching
        List<String> cleanLore = new ArrayList<>();
        for (String line : lore) {
            // Handle JSON component format
            if (line.contains("\"extra\"")) {
                // Extract text from JSON components
                String cleanLine = line.replaceAll("\\{\"extra\":\\[.*?\"text\":\"([^\"]*)\".*?\\]}", "$1")
                                     .replaceAll("\\{\"text\":\"([^\"]*)\"\\}", "$1")
                                     .replaceAll("§.", "")
                                     .replaceAll("\"", "")
                                     .trim();
                if (!cleanLine.isEmpty()) {
                    cleanLore.add(cleanLine);
                }
            } else {
                // Handle regular lore format
                String cleanLine = line.replaceAll("§.", "")
                                     .replaceAll("\\{\"text\":\"", "")
                                     .replaceAll("\"\\}", "")
                                     .replaceAll("\"", "")
                                     .trim();
                if (!cleanLine.isEmpty()) {
                    cleanLore.add(cleanLine);
                }
            }
        }

        // Debug output for lore matching
        configManager.debug("Checking lore match for item: " + item.getType());
        configManager.debug("Lore match pattern: " + loreMatch);
        configManager.debug("Cleaned lore lines: " + cleanLore);

        // Check for exact match
        for (String line : cleanLore) {
            if (line.equalsIgnoreCase(loreMatch)) {
                configManager.debug("Found exact match: " + line);
                return true;
            }
        }

        // Handle multi-line matches
        if (loreMatch.contains(":")) {
            String[] parts = loreMatch.split(":", 2);
            String key = parts[0].trim().toLowerCase();
            String value = parts[1].trim().toLowerCase();
            
            // Look for the key part
            for (int i = 0; i < cleanLore.size(); i++) {
                String line = cleanLore.get(i).toLowerCase();
                
                // Case 1: "Form:" on one line, "Active" on next
                if (line.equals(key + ":")) {
                    if (i + 1 < cleanLore.size()) {
                        String nextLine = cleanLore.get(i + 1).toLowerCase();
                        if (nextLine.equals(value)) {
                            configManager.debug("Found split match: " + line + " + " + nextLine);
                            return true;
                        }
                    }
                }
                
                // Case 2: "Form" on one line, ":" on next, "Active" on next
                if (line.equals(key)) {
                    if (i + 2 < cleanLore.size()) {
                        String colonLine = cleanLore.get(i + 1).toLowerCase();
                        String valueLine = cleanLore.get(i + 2).toLowerCase();
                        if (colonLine.equals(":") && valueLine.equals(value)) {
                            configManager.debug("Found triple split match: " + line + " + " + colonLine + " + " + valueLine);
                            return true;
                        }
                    }
                }
                
                // Case 3: "Form: Active" on one line
                if (line.startsWith(key + ":")) {
                    String afterColon = line.substring((key + ":").length()).trim();
                    if (afterColon.equals(value)) {
                        configManager.debug("Found inline match: " + line);
                        return true;
                    }
                }
            }
        }

        configManager.debug("No matching lore found for item");
        return false;
    }

    private String extractLoreValue(List<String> lore, String key) {
        if (lore == null || key == null) return null;
        
        for (int i = 0; i < lore.size(); i++) {
            String line = lore.get(i).replaceAll("§.", ""); // Remove color codes
            String searchPattern = key + ":";
            int keyIndex = line.toLowerCase().indexOf(searchPattern.toLowerCase());
            if (keyIndex >= 0) {
                // Check if value is on same line
                String afterColon = line.substring(keyIndex + searchPattern.length()).trim();
                if (!afterColon.isEmpty()) {
                    return afterColon.replaceAll("[^A-Za-z0-9 _-]", "").trim();
                }
                // Check next line for value
                if (i + 1 < lore.size()) {
                    String nextLine = lore.get(i + 1).replaceAll("§.", "").trim();
                    if (!nextLine.isEmpty()) {
                        return nextLine.replaceAll("[^A-Za-z0-9 _-]", "").trim();
                    }
                }
            }
        }
        return null;
    }

    private boolean isValidArmorForSlot(ItemStack item, String slotType) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        Material type = item.getType();
        switch (slotType.toLowerCase()) {
            case "helmet":
                return type.name().endsWith("_HELMET") || 
                       type == Material.CARVED_PUMPKIN || 
                       type == Material.TURTLE_HELMET;
            case "chestplate":
                return type.name().endsWith("_CHESTPLATE") || 
                       type == Material.ELYTRA;
            case "leggings":
                return type.name().endsWith("_LEGGINGS");
            case "boots":
                return type.name().endsWith("_BOOTS");
            default:
                return false;
        }
    }

    private void removeGearAttributes(Player player) {
        PlayerData playerData = Fabled.getData(player);
        if (playerData == null) {
            configManager.debug("Could not get Fabled player data for " + player.getName());
            return;
        }

        UUID playerUUID = player.getUniqueId();
        Map<String, Map<String, Integer>> slotMap = PlayerGearData.playerSlotAttributes.get(playerUUID);
        if (slotMap != null) {
            for (Map.Entry<String, Map<String, Integer>> slotEntry : slotMap.entrySet()) {
                for (Map.Entry<String, Integer> attrEntry : slotEntry.getValue().entrySet()) {
                    String attribute = attrEntry.getKey();
                    int value = attrEntry.getValue();
                    playerData.giveAttribute(attribute, -value);
                    configManager.debug("Removed attribute " + attribute + " with value " + value + " from " + player.getName());
                }
            }
            PlayerGearData.clearPlayerSlotAttributes(playerUUID);
        }
        playerData.updatePlayerStat(player);
    }

    private void applyGearAttributes(Player player, ItemStack gear, ConfigManager.CustomSlot slot) {
        PlayerData playerData = Fabled.getData(player);
        if (playerData == null) {
            configManager.debug("Could not get Fabled player data for " + player.getName());
            return;
        }

        ItemMeta meta = gear.getItemMeta();
        if (meta == null) {
            configManager.debug("Item has no meta data");
            return;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (container == null) {
            configManager.debug("Item has no NBT data");
            return;
        }

        UUID playerUUID = player.getUniqueId();
        String slotId = slot.getType();
        Map<String, Integer> addedAttributes = new HashMap<>();

        for (org.bukkit.NamespacedKey key : container.getKeys()) {
            String keyStr = key.getKey();
            if (keyStr.startsWith("item_fabled_attr_")) {
                try {
                    Integer value = container.get(key, PersistentDataType.INTEGER);
                    if (value != null && value > 0) {
                        String attributeName = keyStr.substring("item_fabled_attr_".length());
                        playerData.giveAttribute(attributeName, value);
                        addedAttributes.put(attributeName, value);
                        configManager.debug("Applied attribute " + attributeName + " with value " + value + " to " + player.getName());
                    }
                } catch (Exception e) {
                    configManager.debug("Error applying attribute " + keyStr + ": " + e.getMessage());
                }
            }
        }

        // Save what we added for this slot
        PlayerGearData.setPlayerSlotAttributes(playerUUID, slotId, addedAttributes);
        playerData.updatePlayerStat(player);
    }

    private void removeSlotAttributes(Player player, String slotId) {
        PlayerData playerData = Fabled.getData(player);
        if (playerData == null) return;

        UUID playerUUID = player.getUniqueId();
        Map<String, Integer> attrs = PlayerGearData.getPlayerSlotAttributes(playerUUID, slotId);
        if (!attrs.isEmpty()) {
            for (Map.Entry<String, Integer> entry : attrs.entrySet()) {
                playerData.giveAttribute(entry.getKey(), -entry.getValue());
                configManager.debug("Removed attribute " + entry.getKey() + " with value " + entry.getValue() + " from " + player.getName());
            }
            PlayerGearData.removePlayerSlotAttributes(playerUUID, slotId);
        }
        playerData.updatePlayerStat(player);
    }

    private void handleSkillSlotPermissions(Player player, ItemStack item, String slotId) {
        if (item.getItemMeta() != null) {
            String displayName = item.getItemMeta().getDisplayName();
            if (displayName != null) {
                // Extract the actual name regardless of format
                String skillName = displayName;
                
                // Remove any JSON formatting if present
                if (displayName.contains("\"text\"")) {
                    int start = displayName.indexOf("\"text\":\"") + 8;
                    int end = displayName.indexOf("\"}", start);
                    if (end > start) {
                        skillName = displayName.substring(start, end);
                    }
                }
                
                // Clean up the name
                skillName = skillName.replaceAll("\"", "").trim();
                
                if (skillName.endsWith(" Gem")) {
                    skillName = skillName.substring(0, skillName.length() - 4).trim()
                        .toLowerCase().replace(' ', '-');
                    String permission = "fabled.skill." + skillName;
                    
                    // Add permission and track it
                    PermissionAttachment newAttachment = player.addAttachment(plugin);
                    newAttachment.setPermission(permission, true);
                    permissionAttachments.put(player.getUniqueId(), newAttachment);
                    PlayerGearData.addPlayerPermission(player.getUniqueId(), permission);
                    configManager.debug("Granted permission: " + permission + " to " + player.getName());
                }
            }
        }
    }

    private void removeSkillSlotPermissions(Player player, String slotId) {
        UUID playerUUID = player.getUniqueId();
        
        // Remove permission attachment
        PermissionAttachment attachment = permissionAttachments.remove(playerUUID);
        if (attachment != null) {
            attachment.remove();
        }
        
        // Clear stored permissions
        PlayerGearData.clearPlayerPermissions(playerUUID);
        configManager.debug("Removed all permissions for " + player.getName() + " from slot " + slotId);
    }
} 