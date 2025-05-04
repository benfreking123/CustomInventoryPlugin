package com.example.custominventoryplugin.inventory;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerGearData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class GearInventory implements InventoryHolder {
    private final Inventory inventory;
    private final Player player;
    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;

    public GearInventory(Player player, ConfigManager configManager, CustomInventoryPlugin plugin) {
        this.player = player;
        this.configManager = configManager;
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, 54, "Gear Menu");
        updateInventory();
    }

    public void updateInventory() {
        // Clear the inventory first
        inventory.clear();
        
        PlayerInventory playerInv = player.getInventory();
        
        // Mirror armor slots
        Map<String, Integer> armorSlots = configManager.getArmorSlots();
        inventory.setItem(armorSlots.get("helmet"), playerInv.getHelmet());
        inventory.setItem(armorSlots.get("chestplate"), playerInv.getChestplate());
        inventory.setItem(armorSlots.get("leggings"), playerInv.getLeggings());
        inventory.setItem(armorSlots.get("boots"), playerInv.getBoots());
        
        // Load custom slots
        Map<String, ConfigManager.CustomSlot> customSlots = configManager.getCustomSlots();
        for (Map.Entry<String, ConfigManager.CustomSlot> entry : customSlots.entrySet()) {
            String slotId = entry.getKey();
            ConfigManager.CustomSlot slot = entry.getValue();
            
            if (slot.isEnabled()) {
                // Get the stored item for this slot
                ItemStack storedItem = PlayerGearData.getPlayerGear(player.getUniqueId(), slotId);
                if (storedItem != null && !storedItem.getType().isAir()) {
                    inventory.setItem(slot.getPosition(), storedItem);
                } else {
                    // If no item is stored, leave the slot empty
                    inventory.setItem(slot.getPosition(), null);
                }
            } else {
                // For disabled slots, use barrier blocks
                inventory.setItem(slot.getPosition(), new ItemStack(Material.BARRIER));
            }
        }
        
        // Fill non-slot positions with glass panes
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < 54; slot++) {
            final int currentSlot = slot;
            // Only fill with glass if:
            // 1. The slot is not an armor slot
            // 2. The slot is not a custom slot (enabled or disabled)
            boolean isArmorSlot = armorSlots.containsValue(currentSlot);
            boolean isCustomSlot = customSlots.values().stream()
                .anyMatch(s -> s.getPosition() == currentSlot);
            
            if (!isArmorSlot && !isCustomSlot) {
                inventory.setItem(currentSlot, glass);
            }
        }
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public Player getPlayer() {
        return player;
    }
} 