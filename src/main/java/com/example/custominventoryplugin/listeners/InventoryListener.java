package com.example.custominventoryplugin.listeners;

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
import studio.magemonkey.fabled.api.player.PlayerData;
import studio.magemonkey.fabled.Fabled;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

public class InventoryListener implements Listener {
    private final ConfigManager configManager;
    
    public InventoryListener(ConfigManager configManager) {
        this.configManager = configManager;
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
                    }
                    
                    // Handle item removal
                    if (event.getCurrentItem() != null) {
                        if (event.isShiftClick() || event.getCursor() == null || event.getCursor().getType().isAir()) {
                            // Remove the item from PlayerGearData when:
                            // 1. Shift-clicking
                            // 2. Clicking with empty cursor (picking up)
                            configManager.debug("Removing item from custom slot " + slotId + " (shift/empty)");
                            PlayerGearData.removePlayerGear(player.getUniqueId(), slotId);
                            // Remove attributes for this slot
                            removeSlotAttributes(player, slotId);
                        } else if (event.getCursor() != null && !event.getCursor().getType().isAir()) {
                            // If swapping items, remove the old one and store the new one
                            configManager.debug("Swapping items in custom slot " + slotId);
                            configManager.debug("Removing old item: " + event.getCurrentItem().getType());
                            configManager.debug("Storing new item: " + event.getCursor().getType());
                            // Remove attributes for the old item
                            removeSlotAttributes(player, slotId);
                            PlayerGearData.setPlayerGear(player.getUniqueId(), slotId, event.getCursor().clone());
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
        
        // Apply new gear attributes
        Map<String, ConfigManager.CustomSlot> customSlots = configManager.getCustomSlots();
        for (Map.Entry<String, ConfigManager.CustomSlot> entry : customSlots.entrySet()) {
            String slotId = entry.getKey();
            ItemStack gear = PlayerGearData.getPlayerGear(playerUUID, slotId);
            if (gear != null && !gear.getType().isAir()) {
                applyGearAttributes(player, gear, entry.getValue());
            }
        }
    }

    private boolean isValidItemForSlot(ItemStack item, ConfigManager.CustomSlot slot) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        // Check lore for slot validation
        List<String> lore = meta.getLore();
        if (lore != null) {
            String formTag = configManager.getItemForm() + ": " + slot.getType(); // "Type: Ring"
            for (String line : lore) {
                if (line.equalsIgnoreCase(formTag)) {
                    return true;
                }
            }
        }
        return false;
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
        String slotId = slot.getId();
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
} 