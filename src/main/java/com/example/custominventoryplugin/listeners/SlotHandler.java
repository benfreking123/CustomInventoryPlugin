package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerGearData;
import com.example.custominventoryplugin.inventory.GearInventory;
import com.example.custominventoryplugin.listeners.AttributeHandler;
import com.example.custominventoryplugin.listeners.SkillHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

class SlotHandler {
    private static final org.bukkit.NamespacedKey DIVINITY_ITEM_ID =
            new org.bukkit.NamespacedKey("divinity", "item_id");

    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;
    private final SkillHandler skillHandler;
    private final AttributeHandler attributeHandler;

    public SlotHandler(ConfigManager configManager, CustomInventoryPlugin plugin) {
        this.configManager = configManager;
        this.plugin = plugin;
        this.skillHandler = new SkillHandler(configManager, plugin);
        this.attributeHandler = new AttributeHandler(configManager);
    }

    public SkillHandler getSkillHandler() {
        return this.skillHandler;
    }

    /**
     * True (and the player is told) when {@code item} is a gem for a skill
     * that is already socketed in a different skill slot. One gem per skill:
     * both copies would share one permission node and one Fabled skill, so
     * the second grants nothing and unsocketing either revokes both.
     */
    private boolean rejectDuplicateGem(Player player, ItemStack item, ConfigManager.CustomSlot slot, String slotId) {
        if (!"skill".equalsIgnoreCase(slot.getSlotType())) return false;
        String other = this.skillHandler.duplicateGemSlot(player, item, slotId);
        if (other == null) return false;
        String name = item.getItemMeta() != null && item.getItemMeta().hasDisplayName()
                ? item.getItemMeta().getDisplayName().replaceAll("\u00a7.", "").trim()
                : "that gem";
        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                "&8[&dSkill&8] &cYou already have a " + name + " socketed. One gem per skill."));
        this.configManager.debug("Refused duplicate gem " + name + " for " + player.getName()
                + " (already in slot " + other + ")");
        return true;
    }

    private void handleSlotChange(Player player, String slotId, ConfigManager.CustomSlot slot, ItemStack newItem) {
        this.configManager.debug("Handling slot change for slot " + slotId + " for player " + player.getName());
        this.updateSlotAttributes(player, slotId, newItem, slot);
        InventoryHolder inventoryHolder = player.getOpenInventory().getTopInventory().getHolder();
        if (inventoryHolder instanceof GearInventory) {
            GearInventory gearInventory = (GearInventory)inventoryHolder;
            gearInventory.updateInventory();
        }
    }

    private void updateSlotAttributes(Player player, String slotId, ItemStack newItem, ConfigManager.CustomSlot slot) {
        this.configManager.debug("Updating attributes for slot " + slotId + " for player " + player.getName());
        this.configManager.debug("Slot type: " + slot.getSlotType());
        boolean isAttribute = "attribute".equalsIgnoreCase(slot.getSlotType());
        boolean isSkill     = "skill".equalsIgnoreCase(slot.getSlotType());

        // Always revoke the previous slot's contribution before re-applying
        if (isAttribute) {
            this.configManager.debug("Removing attributes from slot " + slotId + " for " + player.getName());
            this.attributeHandler.removeSlotAttributes(player, slotId);
        }
        if (isSkill) {
            this.configManager.debug("Revoking previous skill permission for slot " + slotId);
            this.skillHandler.removeSkillSlotPermission(player, slotId);
        }

        if (newItem != null && !newItem.getType().isAir()) {
            this.configManager.debug("Setting new item in slot " + slotId + ": " + String.valueOf(newItem.getType()));
            PlayerGearData.setPlayerGear(player.getUniqueId(), slotId, newItem);
            if (isAttribute) {
                this.configManager.debug("Applying new attributes for slot " + slotId);
                this.attributeHandler.applyGearAttributes(player, newItem, slot, slotId);
            }
            if (isSkill) {
                this.configManager.debug("Granting skill permission for slot " + slotId);
                this.skillHandler.handleSkillSlot(player, newItem, slotId);
            }
        } else {
            this.configManager.debug("Removing item from slot " + slotId);
            PlayerGearData.removePlayerGear(player.getUniqueId(), slotId);
            // (skill perm + attribute deltas were already revoked above)
        }
    }

    public boolean handleShiftClick(InventoryClickEvent event, Player player, ItemStack clickedItem) {
        for (Map.Entry<String, ConfigManager.CustomSlot> entry : this.configManager.getCustomSlots().entrySet()) {
            ItemStack slotItem;
            String slotId = entry.getKey();
            ConfigManager.CustomSlot customSlot = entry.getValue();
            int guiSlot = customSlot.getPosition();
            if (!customSlot.isEnabled() || !this.configManager.isSkillSlotUnlocked(customSlot, player) || !this.isValidItemForSlot(clickedItem, customSlot) || (slotItem = event.getInventory().getItem(guiSlot)) != null && !slotItem.getType().isAir()) continue;
            if (this.rejectDuplicateGem(player, clickedItem, customSlot, slotId)) return true;
            ItemStack singleItem = clickedItem.clone();
            singleItem.setAmount(1);
            this.handleSlotChange(player, slotId, customSlot, singleItem);
            event.getInventory().setItem(guiSlot, singleItem);
            clickedItem.setAmount(clickedItem.getAmount() - 1);
            if (clickedItem.getAmount() <= 0) {
                event.setCurrentItem(null);
            }
            return true;
        }
        return false;
    }

    public void handleShiftClickFromGUI(InventoryClickEvent event, Player player, ItemStack clickedItem) {
        Map<String, ConfigManager.CustomSlot> customSlots = this.configManager.getCustomSlots();
        for (Map.Entry<String, ConfigManager.CustomSlot> entry : customSlots.entrySet()) {
            String slotId = entry.getKey();
            ConfigManager.CustomSlot customSlot = entry.getValue();
            if (customSlot.getPosition() != event.getRawSlot()) continue;
            // Only allow shift-pulling a real gem out (this also blocks shift-clicking
            // the locked/filler panes, which aren't valid items for the slot).
            if (!this.isValidItemForSlot(clickedItem, customSlot)) {
                return;
            }
            PlayerInventory playerInv = player.getInventory();
            if (playerInv.firstEmpty() != -1) {
                this.handleSlotChange(player, slotId, customSlot, null);
                event.setCurrentItem(null);
                playerInv.addItem(new ItemStack[]{clickedItem});
                return;
            }
            return;
        }
    }

    public void handleNormalClick(InventoryClickEvent event, Player player, ConfigManager.CustomSlot customSlot, String slotId) {
        event.setCancelled(true);
        if (event.getCursor() != null && !event.getCursor().getType().isAir() && event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()) {
            ItemStack cursorItem = event.getCursor().clone();
            ItemStack slotItem = event.getCurrentItem().clone();
            if (this.isValidItemForSlot(cursorItem, customSlot) && this.isValidItemForSlot(slotItem, customSlot)) {
                if (this.rejectDuplicateGem(player, cursorItem, customSlot, slotId)) return;
                cursorItem.setAmount(1);
                this.handleSlotChange(player, slotId, customSlot, cursorItem);
                event.getInventory().setItem(event.getRawSlot(), cursorItem);
                event.setCursor(slotItem);
                return;
            }
            return;
        }
        if (event.getCursor() != null && !event.getCursor().getType().isAir()) {
            if (!this.isValidItemForSlot(event.getCursor(), customSlot)) {
                return;
            }
            if (this.rejectDuplicateGem(player, event.getCursor(), customSlot, slotId)) return;
            ItemStack newItem = event.getCursor().clone();
            newItem.setAmount(1);
            this.handleSlotChange(player, slotId, customSlot, newItem);
            event.getInventory().setItem(event.getRawSlot(), newItem);
            ItemStack cursorItem = event.getCursor();
            cursorItem.setAmount(cursorItem.getAmount() - 1);
            if (cursorItem.getAmount() <= 0) {
                event.setCursor(null);
            } else {
                event.setCursor(cursorItem);
            }
        } else if (event.getCurrentItem() != null) {
            ItemStack currentItem = event.getCurrentItem().clone();
            this.handleSlotChange(player, slotId, customSlot, null);
            event.setCurrentItem(null);
            event.setCursor(currentItem);
        }
    }

    public void handleDrag(InventoryDragEvent event, Player player) {
        Iterator iterator = event.getRawSlots().iterator();
        while (iterator.hasNext()) {
            int slot = (Integer)iterator.next();
            Map<String, ConfigManager.CustomSlot> customSlots = this.configManager.getCustomSlots();
            for (Map.Entry<String, ConfigManager.CustomSlot> entry : customSlots.entrySet()) {
                String slotId = entry.getKey();
                ConfigManager.CustomSlot customSlot = entry.getValue();
                if (customSlot.getPosition() != slot) continue;
                if (!customSlot.isEnabled() || !this.configManager.isSkillSlotUnlocked(customSlot, player)) {
                    event.setCancelled(true);
                    return;
                }
                ItemStack draggedItem = event.getOldCursor();
                if (!this.isValidItemForSlot(draggedItem, customSlot)) {
                    event.setCancelled(true);
                    return;
                }
                if (this.rejectDuplicateGem(player, draggedItem, customSlot, slotId)) {
                    event.setCancelled(true);
                    return;
                }
                ItemStack newItem = draggedItem.clone();
                newItem.setAmount(1);
                this.handleSlotChange(player, slotId, customSlot, newItem);
                return;
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
        // Skill gems reflowed by the tooltip system no longer carry a
        // "Form: Active" lore line; their original Form lives in the tt_gem
        // PDC snapshot (version\nform\ntype\npercent\nrarity).
        String gemForm = this.gemForm(meta);
        if (gemForm != null) {
            return "Form".equalsIgnoreCase(slot.getForm()) && gemForm.equalsIgnoreCase(slot.getType());
        }
        // Prefer matching on the Divinity item id (survives lore reflows by the
        // tooltip system). Falls back to legacy lore matching for items without
        // a Divinity id or slots without id_match configured.
        String idMatch = slot.getIdMatch();
        if (idMatch != null && !idMatch.isEmpty()) {
            String itemId = meta.getPersistentDataContainer().get(
                    DIVINITY_ITEM_ID, org.bukkit.persistence.PersistentDataType.STRING);
            if (itemId != null && !itemId.isBlank()) {
                String low = itemId.toLowerCase(java.util.Locale.ROOT);
                for (String token : idMatch.toLowerCase(java.util.Locale.ROOT).split(",")) {
                    if (!token.isBlank() && low.contains(token.trim())) {
                        return true;
                    }
                }
                return false;
            }
        }
        List lore = meta.getLore();
        if (lore == null) {
            return false;
        }
        String loreMatch = slot.getLoreMatch();
        if (loreMatch == null || loreMatch.isEmpty()) {
            String form = this.extractLoreValue(lore, slot.getForm());
            String type = this.extractLoreValue(lore, slot.getType());
            return form != null && type != null && form.equalsIgnoreCase(slot.getForm()) && type.equalsIgnoreCase(slot.getType());
        }
        List<String> cleanLore = this.cleanLoreLines(lore);
        return this.checkLoreMatch(cleanLore, loreMatch);
    }

    /** Form ("Active"/"Passive") from the gem tooltip's PDC snapshot, or null. */
    private String gemForm(ItemMeta meta) {
        String raw = meta.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(this.plugin, "tt_gem"),
                org.bukkit.persistence.PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("\n", -1);
        if (parts.length < 2 || parts[1].isBlank()) {
            return null;
        }
        return parts[1].trim();
    }

    private List<String> cleanLoreLines(List<String> lore) {
        ArrayList<String> cleanLore = new ArrayList<String>();
        for (String line : lore) {
            String cleanLine;
            if (line.contains("\"extra\"")) {
                cleanLine = line.replaceAll("\\{\"extra\":\\[.*?\"text\":\"([^\"]*)\".*?\\]}", "$1").replaceAll("\\{\"text\":\"([^\"]*)\"\\}", "$1").replaceAll("\u00a7.", "").replaceAll("\"", "").trim();
                if (cleanLine.isEmpty()) continue;
                cleanLore.add(cleanLine);
                continue;
            }
            cleanLine = line.replaceAll("\u00a7.", "").replaceAll("\\{\"text\":\"", "").replaceAll("\"\\}", "").replaceAll("\"", "").trim();
            if (cleanLine.isEmpty()) continue;
            cleanLore.add(cleanLine);
        }
        return cleanLore;
    }

    private boolean checkLoreMatch(List<String> cleanLore, String loreMatch) {
        for (String line : cleanLore) {
            if (!line.equalsIgnoreCase(loreMatch)) continue;
            return true;
        }
        if (loreMatch.contains(":")) {
            String[] parts = loreMatch.split(":", 2);
            String key = parts[0].trim().toLowerCase();
            String value = parts[1].trim().toLowerCase();
            for (int i = 0; i < cleanLore.size(); ++i) {
                String afterColon;
                String nextLine;
                String line = cleanLore.get(i).toLowerCase();
                if (line.equals(key + ":") && i + 1 < cleanLore.size() && (nextLine = cleanLore.get(i + 1).toLowerCase()).equals(value)) {
                    return true;
                }
                if (line.equals(key) && i + 2 < cleanLore.size()) {
                    String colonLine = cleanLore.get(i + 1).toLowerCase();
                    String valueLine = cleanLore.get(i + 2).toLowerCase();
                    if (colonLine.equals(":") && valueLine.equals(value)) {
                        return true;
                    }
                }
                if (!line.startsWith(key + ":") || !(afterColon = line.substring((key + ":").length()).trim()).equals(value)) continue;
                return true;
            }
        }
        return false;
    }

    private String extractLoreValue(List<String> lore, String key) {
        if (lore == null || key == null) {
            return null;
        }
        for (int i = 0; i < lore.size(); ++i) {
            String nextLine;
            String line = lore.get(i).replaceAll("\u00a7.", "");
            String searchPattern = key + ":";
            int keyIndex = line.toLowerCase().indexOf(searchPattern.toLowerCase());
            if (keyIndex < 0) continue;
            String afterColon = line.substring(keyIndex + searchPattern.length()).trim();
            if (!afterColon.isEmpty()) {
                return afterColon.replaceAll("[^A-Za-z0-9 _-]", "").trim();
            }
            if (i + 1 >= lore.size() || (nextLine = lore.get(i + 1).replaceAll("\u00a7.", "").trim()).isEmpty()) continue;
            return nextLine.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        }
        return null;
    }
}

