package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerGearData;
import com.example.custominventoryplugin.inventory.GearInventory;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

class ArmorHandler {
    private final ConfigManager configManager;

    public ArmorHandler(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public boolean handleShiftClick(InventoryClickEvent event, Player player, ItemStack clickedItem) {
        for (Map.Entry<String, Integer> entry : this.configManager.getArmorSlots().entrySet()) {
            InventoryHolder inventoryHolder;
            ItemStack slotItem;
            String armorType = entry.getKey();
            int guiSlot = entry.getValue();
            if (!this.isValidArmorForSlot(clickedItem, armorType) || (slotItem = event.getInventory().getItem(guiSlot)) != null && !slotItem.getType().isAir()) continue;
            event.getInventory().setItem(guiSlot, clickedItem.clone());
            PlayerInventory playerInv = player.getInventory();
            switch (armorType) {
                case "helmet": {
                    playerInv.setHelmet(clickedItem.clone());
                    break;
                }
                case "chestplate": {
                    playerInv.setChestplate(clickedItem.clone());
                    break;
                }
                case "leggings": {
                    playerInv.setLeggings(clickedItem.clone());
                    break;
                }
                case "boots": {
                    playerInv.setBoots(clickedItem.clone());
                }
            }
            PlayerGearData.setPlayerGear(player.getUniqueId(), armorType, clickedItem.clone());
            clickedItem.setAmount(clickedItem.getAmount() - 1);
            if (clickedItem.getAmount() <= 0) {
                event.setCurrentItem(null);
            }
            if ((inventoryHolder = event.getInventory().getHolder()) instanceof GearInventory) {
                GearInventory gearInventory = (GearInventory)inventoryHolder;
                gearInventory.updateInventory();
            }
            return true;
        }
        return false;
    }

    public void handleShiftClickFromGUI(InventoryClickEvent event, Player player) {
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType().isAir()) {
            return;
        }
        event.setCancelled(true);
        for (Map.Entry<String, Integer> entry : this.configManager.getArmorSlots().entrySet()) {
            String armorType = entry.getKey();
            if (entry.getValue().intValue() != event.getRawSlot()) continue;
            PlayerInventory playerInv = player.getInventory();
            if (playerInv.firstEmpty() != -1) {
                switch (armorType) {
                    case "helmet": {
                        playerInv.setHelmet(null);
                        break;
                    }
                    case "chestplate": {
                        playerInv.setChestplate(null);
                        break;
                    }
                    case "leggings": {
                        playerInv.setLeggings(null);
                        break;
                    }
                    case "boots": {
                        playerInv.setBoots(null);
                    }
                }
                PlayerGearData.removePlayerGear(player.getUniqueId(), armorType);
                event.setCurrentItem(null);
                playerInv.addItem(new ItemStack[]{clickedItem});
                InventoryHolder inventoryHolder = event.getInventory().getHolder();
                if (inventoryHolder instanceof GearInventory) {
                    GearInventory gearInventory = (GearInventory)inventoryHolder;
                    gearInventory.updateInventory();
                }
            }
            return;
        }
    }

    public void handleNormalClick(InventoryClickEvent event, Player player, String armorType) {
        ItemStack item = event.getCursor();
        if (item != null && !item.getType().isAir() && !this.isValidArmorForSlot(item, armorType)) {
            event.setCancelled(true);
            return;
        }
        PlayerInventory playerInv = player.getInventory();
        switch (armorType) {
            case "helmet": {
                playerInv.setHelmet(event.getCursor());
                break;
            }
            case "chestplate": {
                playerInv.setChestplate(event.getCursor());
                break;
            }
            case "leggings": {
                playerInv.setLeggings(event.getCursor());
                break;
            }
            case "boots": {
                playerInv.setBoots(event.getCursor());
            }
        }
    }

    private boolean isValidArmorForSlot(ItemStack item, String slotType) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        Material type = item.getType();
        return switch (slotType.toLowerCase()) {
            case "helmet" -> {
                if (type.name().endsWith("_HELMET") || type == Material.CARVED_PUMPKIN || type == Material.TURTLE_HELMET) {
                    yield true;
                }
                yield false;
            }
            case "chestplate" -> {
                if (type.name().endsWith("_CHESTPLATE") || type == Material.ELYTRA) {
                    yield true;
                }
                yield false;
            }
            case "leggings" -> type.name().endsWith("_LEGGINGS");
            case "boots" -> type.name().endsWith("_BOOTS");
            default -> false;
        };
    }
}

