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

import java.util.*;

public class InventoryListener implements Listener {
    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;
    private final Map<UUID, PermissionAttachment> permissionAttachments;
    private final SlotHandler slotHandler;
    private final ArmorHandler armorHandler;
    private final SkillHandler skillHandler;
    private final AttributeHandler attributeHandler;
    
    public InventoryListener(ConfigManager configManager, CustomInventoryPlugin plugin) {
        this.configManager = configManager;
        this.plugin = plugin;
        this.permissionAttachments = new HashMap<>();
        this.slotHandler = new SlotHandler(configManager, plugin);
        this.armorHandler = new ArmorHandler(configManager);
        this.skillHandler = new SkillHandler(configManager, plugin, permissionAttachments);
        this.attributeHandler = new AttributeHandler(configManager);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GearInventory)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        
        if (slot < 0) {
            return;
        }

        // Handle shift-click from player inventory
        if (event.isShiftClick() && event.getClickedInventory() == event.getView().getBottomInventory()) {
            handleShiftClickFromPlayer(event, player);
            return;
        }

        // Handle shift-click from GUI to player inventory
        if (event.isShiftClick() && event.getClickedInventory() == event.getView().getTopInventory()) {
            handleShiftClickFromGUI(event, player);
            return;
        }

        // Handle normal clicks in GUI
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            handleNormalClick(event, player);
        }
    }

    private void handleShiftClickFromPlayer(InventoryClickEvent event, Player player) {
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType().isAir()) {
            return;
        }

        event.setCancelled(true);

        // Try armor slots first
        if (armorHandler.handleShiftClick(event, player, clickedItem)) {
            return;
        }

        // Try custom slots
        if (slotHandler.handleShiftClick(event, player, clickedItem)) {
            return;
        }
    }

    private void handleShiftClickFromGUI(InventoryClickEvent event, Player player) {
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType().isAir()) {
            return;
        }

        event.setCancelled(true);
        slotHandler.handleShiftClickFromGUI(event, player, clickedItem);
    }

    private void handleNormalClick(InventoryClickEvent event, Player player) {
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

                event.setCancelled(true);
                slotHandler.handleNormalClick(event, player, customSlot, slotId);
                return;
            }
        }

        // Handle armor slots
        Map<String, Integer> armorSlots = configManager.getArmorSlots();
        for (Map.Entry<String, Integer> entry : armorSlots.entrySet()) {
            if (entry.getValue() == event.getRawSlot()) {
                armorHandler.handleNormalClick(event, player, entry.getKey());
                return;
            }
        }

        // Cancel if clicking on barrier or glass panes
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem != null && 
            (clickedItem.getType() == Material.BARRIER || 
             clickedItem.getType() == Material.GRAY_STAINED_GLASS_PANE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof GearInventory)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        slotHandler.handleDrag(event, player);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof GearInventory)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        
        // Remove existing gear attributes
        attributeHandler.removeGearAttributes(player);
        
        // Remove existing permissions
        skillHandler.removeAllPermissions(player);
        
        // Apply new gear attributes and handle skills
        Map<String, ConfigManager.CustomSlot> customSlots = configManager.getCustomSlots();
        for (Map.Entry<String, ConfigManager.CustomSlot> entry : customSlots.entrySet()) {
            String slotId = entry.getKey();
            ConfigManager.CustomSlot slot = entry.getValue();
            if (!slot.isEnabled()) continue;

            ItemStack gear = PlayerGearData.getPlayerGear(playerUUID, slotId);
            if (gear != null && !gear.getType().isAir()) {
                if ("skill".equalsIgnoreCase(slot.getSlotType())) {
                    skillHandler.handleSkillSlot(player, gear, slotId);
                } else if ("attribute".equalsIgnoreCase(slot.getSlotType())) {
                    attributeHandler.applyGearAttributes(player, gear, slot);
                }
            }
        }
    }
}

// SlotHandler.java
class SlotHandler {
    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;
    private final SkillHandler skillHandler;
    private final AttributeHandler attributeHandler;

    public SlotHandler(ConfigManager configManager, CustomInventoryPlugin plugin) {
        this.configManager = configManager;
        this.plugin = plugin;
        this.skillHandler = new SkillHandler(configManager, plugin, new HashMap<>());
        this.attributeHandler = new AttributeHandler(configManager);
    }

    public boolean handleShiftClick(InventoryClickEvent event, Player player, ItemStack clickedItem) {
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
                    
                    // Handle slot-specific logic
                    handleSlotTypeLogic(player, clickedItem, customSlot, slotId);
                    
                    // Remove from player's inventory
                    clickedItem.setAmount(clickedItem.getAmount() - 1);
                    if (clickedItem.getAmount() <= 0) {
                        event.setCurrentItem(null);
                    }
                    
                    updateInventory(event);
                    return true;
                }
            }
        }
        return false;
    }

    public void handleShiftClickFromGUI(InventoryClickEvent event, Player player, ItemStack clickedItem) {
        Map<String, ConfigManager.CustomSlot> customSlots = configManager.getCustomSlots();
        for (Map.Entry<String, ConfigManager.CustomSlot> entry : customSlots.entrySet()) {
            String slotId = entry.getKey();
            ConfigManager.CustomSlot customSlot = entry.getValue();
            if (customSlot.getPosition() == event.getRawSlot()) {
                PlayerInventory playerInv = player.getInventory();
                if (playerInv.firstEmpty() != -1) {
                    // Remove from custom slot
                    event.setCurrentItem(null);
                    PlayerGearData.removePlayerGear(player.getUniqueId(), slotId);
                    
                    // Remove slot-specific effects
                    removeSlotEffects(player, customSlot, slotId);
                    
                    // Add to player inventory
                    playerInv.addItem(clickedItem);
                    
                    updateInventory(event);
                }
                return;
            }
        }
    }

    public void handleNormalClick(InventoryClickEvent event, Player player, ConfigManager.CustomSlot customSlot, String slotId) {
        // Always cancel the event to handle it ourselves
        event.setCancelled(true);

        // Handle item swapping (both cursor and slot have items)
        if (event.getCursor() != null && !event.getCursor().getType().isAir() &&
            event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()) {
            
            ItemStack cursorItem = event.getCursor().clone();
            ItemStack slotItem = event.getCurrentItem().clone();

            // Validate both items for the slot
            if (isValidItemForSlot(cursorItem, customSlot) && isValidItemForSlot(slotItem, customSlot)) {
                // Remove effects of the old slot item
                removeSlotEffects(player, customSlot, slotId);
                PlayerGearData.removePlayerGear(player.getUniqueId(), slotId);

                // Place cursor item in slot
                PlayerGearData.setPlayerGear(player.getUniqueId(), slotId, cursorItem);
                event.getInventory().setItem(event.getRawSlot(), cursorItem);
                handleSlotTypeLogic(player, cursorItem, customSlot, slotId);

                // Put old slot item on cursor
                event.setCursor(slotItem);
                updateInventory(event);
                return;
            }
            return; // Invalid items, don't proceed
        }
        
        // Handle item placement (only cursor has item)
        if (event.getCursor() != null && !event.getCursor().getType().isAir()) {
            if (!isValidItemForSlot(event.getCursor(), customSlot)) {
                return;
            }
            
            // Store the item in PlayerGearData
            configManager.debug("Placing item in custom slot " + slotId + ": " + event.getCursor().getType());
            ItemStack newItem = event.getCursor().clone();
            PlayerGearData.setPlayerGear(player.getUniqueId(), slotId, newItem);
            
            // Update the slot in the GUI
            event.getInventory().setItem(event.getRawSlot(), newItem);
            
            // Handle slot-specific logic
            handleSlotTypeLogic(player, newItem, customSlot, slotId);
            
            // Clear the cursor
            event.setCursor(null);
            updateInventory(event);
        }
        // Handle item removal (only slot has item)
        else if (event.getCurrentItem() != null) {
            // Picking up an item
            ItemStack currentItem = event.getCurrentItem().clone();
            
            // Remove the item from PlayerGearData
            configManager.debug("Removing item from custom slot " + slotId + " (pickup)");
            PlayerGearData.removePlayerGear(player.getUniqueId(), slotId);
            
            // Remove slot-specific effects
            removeSlotEffects(player, customSlot, slotId);
            
            // Clear the slot and set the cursor
            event.setCurrentItem(null);
            event.setCursor(currentItem);
            
            updateInventory(event);
        }
    }

    public void handleDrag(InventoryDragEvent event, Player player) {
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
                    
                    ItemStack draggedItem = event.getOldCursor();
                    if (!isValidItemForSlot(draggedItem, customSlot)) {
                        event.setCancelled(true);
                        return;
                    }
                    
                    PlayerGearData.setPlayerGear(player.getUniqueId(), slotId, draggedItem);
                    return;
                }
            }
        }
    }

    private void handleSlotTypeLogic(Player player, ItemStack item, ConfigManager.CustomSlot slot, String slotId) {
        if ("skill".equalsIgnoreCase(slot.getSlotType())) {
            skillHandler.handleSkillSlot(player, item, slotId);
        } else if ("attribute".equalsIgnoreCase(slot.getSlotType())) {
            attributeHandler.applyGearAttributes(player, item, slot);
        }
    }

    private void removeSlotEffects(Player player, ConfigManager.CustomSlot slot, String slotId) {
        if ("skill".equalsIgnoreCase(slot.getSlotType())) {
            skillHandler.removeSkillSlotPermissions(player, slotId);
        } else if ("attribute".equalsIgnoreCase(slot.getSlotType())) {
            attributeHandler.removeSlotAttributes(player, slotId);
        }
    }

    private void updateInventory(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof GearInventory gearInventory) {
            gearInventory.updateInventory();
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
            String form = extractLoreValue(lore, slot.getForm());
            String type = extractLoreValue(lore, slot.getType());
            return form != null && type != null && 
                   form.equalsIgnoreCase(slot.getForm()) && 
                   type.equalsIgnoreCase(slot.getType());
        }

        List<String> cleanLore = cleanLoreLines(lore);
        return checkLoreMatch(cleanLore, loreMatch);
    }

    private List<String> cleanLoreLines(List<String> lore) {
        List<String> cleanLore = new ArrayList<>();
        for (String line : lore) {
            if (line.contains("\"extra\"")) {
                String cleanLine = line.replaceAll("\\{\"extra\":\\[.*?\"text\":\"([^\"]*)\".*?\\]}", "$1")
                                     .replaceAll("\\{\"text\":\"([^\"]*)\"\\}", "$1")
                                     .replaceAll("§.", "")
                                     .replaceAll("\"", "")
                                     .trim();
                if (!cleanLine.isEmpty()) {
                    cleanLore.add(cleanLine);
                }
            } else {
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
        return cleanLore;
    }

    private boolean checkLoreMatch(List<String> cleanLore, String loreMatch) {
        // Check for exact match
        for (String line : cleanLore) {
            if (line.equalsIgnoreCase(loreMatch)) {
                return true;
            }
        }

        // Handle multi-line matches
        if (loreMatch.contains(":")) {
            String[] parts = loreMatch.split(":", 2);
            String key = parts[0].trim().toLowerCase();
            String value = parts[1].trim().toLowerCase();
            
            for (int i = 0; i < cleanLore.size(); i++) {
                String line = cleanLore.get(i).toLowerCase();
                
                if (line.equals(key + ":")) {
                    if (i + 1 < cleanLore.size()) {
                        String nextLine = cleanLore.get(i + 1).toLowerCase();
                        if (nextLine.equals(value)) {
                            return true;
                        }
                    }
                }
                
                if (line.equals(key)) {
                    if (i + 2 < cleanLore.size()) {
                        String colonLine = cleanLore.get(i + 1).toLowerCase();
                        String valueLine = cleanLore.get(i + 2).toLowerCase();
                        if (colonLine.equals(":") && valueLine.equals(value)) {
                            return true;
                        }
                    }
                }
                
                if (line.startsWith(key + ":")) {
                    String afterColon = line.substring((key + ":").length()).trim();
                    if (afterColon.equals(value)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private String extractLoreValue(List<String> lore, String key) {
        if (lore == null || key == null) return null;
        
        for (int i = 0; i < lore.size(); i++) {
            String line = lore.get(i).replaceAll("§.", "");
            String searchPattern = key + ":";
            int keyIndex = line.toLowerCase().indexOf(searchPattern.toLowerCase());
            if (keyIndex >= 0) {
                String afterColon = line.substring(keyIndex + searchPattern.length()).trim();
                if (!afterColon.isEmpty()) {
                    return afterColon.replaceAll("[^A-Za-z0-9 _-]", "").trim();
                }
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
}

// ArmorHandler.java
class ArmorHandler {
    private final ConfigManager configManager;

    public ArmorHandler(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public boolean handleShiftClick(InventoryClickEvent event, Player player, ItemStack clickedItem) {
        for (Map.Entry<String, Integer> entry : configManager.getArmorSlots().entrySet()) {
            String armorType = entry.getKey();
            int guiSlot = entry.getValue();
            if (isValidArmorForSlot(clickedItem, armorType)) {
                ItemStack slotItem = event.getInventory().getItem(guiSlot);
                if (slotItem == null || slotItem.getType().isAir()) {
                    event.getInventory().setItem(guiSlot, clickedItem.clone());
                    
                    PlayerInventory playerInv = player.getInventory();
                    switch (armorType) {
                        case "helmet" -> playerInv.setHelmet(clickedItem.clone());
                        case "chestplate" -> playerInv.setChestplate(clickedItem.clone());
                        case "leggings" -> playerInv.setLeggings(clickedItem.clone());
                        case "boots" -> playerInv.setBoots(clickedItem.clone());
                    }
                    
                    PlayerGearData.setPlayerGear(player.getUniqueId(), armorType, clickedItem.clone());
                    
                    clickedItem.setAmount(clickedItem.getAmount() - 1);
                    if (clickedItem.getAmount() <= 0) {
                        event.setCurrentItem(null);
                    }
                    
                    if (event.getInventory().getHolder() instanceof GearInventory gearInventory) {
                        gearInventory.updateInventory();
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public void handleNormalClick(InventoryClickEvent event, Player player, String armorType) {
        ItemStack item = event.getCursor();
        if (item != null && !item.getType().isAir()) {
            if (!isValidArmorForSlot(item, armorType)) {
                event.setCancelled(true);
                return;
            }
        }
        
        PlayerInventory playerInv = player.getInventory();
        switch (armorType) {
            case "helmet" -> playerInv.setHelmet(event.getCursor());
            case "chestplate" -> playerInv.setChestplate(event.getCursor());
            case "leggings" -> playerInv.setLeggings(event.getCursor());
            case "boots" -> playerInv.setBoots(event.getCursor());
        }
    }

    private boolean isValidArmorForSlot(ItemStack item, String slotType) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        Material type = item.getType();
        return switch (slotType.toLowerCase()) {
            case "helmet" -> type.name().endsWith("_HELMET") || 
                           type == Material.CARVED_PUMPKIN || 
                           type == Material.TURTLE_HELMET;
            case "chestplate" -> type.name().endsWith("_CHESTPLATE") || 
                               type == Material.ELYTRA;
            case "leggings" -> type.name().endsWith("_LEGGINGS");
            case "boots" -> type.name().endsWith("_BOOTS");
            default -> false;
        };
    }
}

// SkillHandler.java
class SkillHandler {
    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;
    private final Map<UUID, PermissionAttachment> permissionAttachments;

    public SkillHandler(ConfigManager configManager, CustomInventoryPlugin plugin, Map<UUID, PermissionAttachment> permissionAttachments) {
        this.configManager = configManager;
        this.plugin = plugin;
        this.permissionAttachments = permissionAttachments;
    }

    public void handleSkillSlot(Player player, ItemStack gear, String slotId) {
        if (gear.getItemMeta() != null) {
            String displayName = gear.getItemMeta().getDisplayName();
            if (displayName != null && displayName.endsWith(" Gem")) {
                String skillName = displayName.substring(0, displayName.length() - 4).trim()
                    .toLowerCase().replace(' ', '-');
                String permission = "fabled.skill." + skillName;
                
                PermissionAttachment newAttachment = player.addAttachment(plugin);
                newAttachment.setPermission(permission, true);
                permissionAttachments.put(player.getUniqueId(), newAttachment);
                PlayerGearData.addPlayerPermission(player.getUniqueId(), permission);
                configManager.debug("Granted permission: " + permission + " to " + player.getName());
            }
        }
    }

    public void removeSkillSlotPermissions(Player player, String slotId) {
        UUID playerUUID = player.getUniqueId();
        
        PermissionAttachment attachment = permissionAttachments.remove(playerUUID);
        if (attachment != null) {
            attachment.remove();
        }
        
        PlayerGearData.clearPlayerPermissions(playerUUID);
        configManager.debug("Removed all permissions for " + player.getName() + " from slot " + slotId);
    }

    public void removeAllPermissions(Player player) {
        UUID playerUUID = player.getUniqueId();
        
        PermissionAttachment attachment = permissionAttachments.remove(playerUUID);
        if (attachment != null) {
            attachment.remove();
        }
        PlayerGearData.clearPlayerPermissions(playerUUID);
    }
}

// AttributeHandler.java
class AttributeHandler {
    private final ConfigManager configManager;

    public AttributeHandler(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void removeGearAttributes(Player player) {
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

    public void applyGearAttributes(Player player, ItemStack gear, ConfigManager.CustomSlot slot) {
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

        PlayerGearData.setPlayerSlotAttributes(playerUUID, slotId, addedAttributes);
        playerData.updatePlayerStat(player);
    }

    public void removeSlotAttributes(Player player, String slotId) {
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
} 