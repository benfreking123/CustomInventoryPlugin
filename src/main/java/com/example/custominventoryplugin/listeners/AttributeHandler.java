package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerGearData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import studio.magemonkey.fabled.Fabled;
import studio.magemonkey.fabled.api.player.PlayerData;

public class AttributeHandler {
    private final ConfigManager configManager;

    public AttributeHandler(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * Hand back every attribute this player's ledger says we granted, from all
     * slots at once.
     *
     * For wipes. Deleting the ledger rows on their own is the single worst thing
     * that can happen to this bookkeeping: the rows are the only record that the
     * points were ever given, so once they are gone the points sit in Fabled's
     * invested pool with nothing left that knows to subtract them, and the
     * player is permanently inflated. Revoke first, then delete.
     */
    public void removeAllSlotAttributes(Player player) {
        for (String slotId : PlayerGearData.getLedgerSlotIds(player.getUniqueId())) {
            removeSlotAttributes(player, slotId);
        }
    }

    public void removeSlotAttributes(Player player, String slotId) {
        PlayerData playerData = Fabled.getData((OfflinePlayer)player);
        if (playerData == null) {
            this.configManager.debug("Could not get Fabled player data for " + player.getName());
            return;
        }
        UUID playerUUID = player.getUniqueId();
        Map<String, Integer> attrs = PlayerGearData.getPlayerSlotAttributes(playerUUID, slotId);
        this.configManager.debug("Found attributes for slot " + slotId + ": " + String.valueOf(attrs));
        if (!attrs.isEmpty()) {
            this.configManager.debug("Removing attributes from slot " + slotId + " for " + player.getName());
            for (Map.Entry<String, Integer> entry : attrs.entrySet()) {
                String attribute = entry.getKey();
                int value = entry.getValue();
                playerData.giveAttribute(attribute, -value);
                this.configManager.debug("Removed attribute " + attribute + " with value " + value + " from " + player.getName());
            }
            PlayerGearData.removePlayerSlotAttributes(playerUUID, slotId);
            this.configManager.debug("Cleared stored attributes for slot " + slotId);
        } else {
            this.configManager.debug("No attributes found to remove for slot " + slotId);
        }
        playerData.updatePlayerStat(player);
    }

    public void applyGearAttributes(Player player, ItemStack gear, ConfigManager.CustomSlot slot, String slotId) {
        PlayerData playerData = Fabled.getData((OfflinePlayer)player);
        if (playerData == null) {
            this.configManager.debug("Could not get Fabled player data for " + player.getName());
            return;
        }
        ItemMeta meta = gear.getItemMeta();
        if (meta == null) {
            this.configManager.debug("Item has no meta data");
            return;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (container == null) {
            this.configManager.debug("Item has no NBT data");
            return;
        }
        UUID playerUUID = player.getUniqueId();
        HashMap<String, Integer> addedAttributes = new HashMap<String, Integer>();
        this.configManager.debug("Applying attributes from item in slot " + slotId + " for " + player.getName());
        for (NamespacedKey key : container.getKeys()) {
            String keyStr = key.getKey();
            if (!keyStr.startsWith("item_fabled_attr_")) continue;
            try {
                Integer value = (Integer)container.get(key, PersistentDataType.INTEGER);
                if (value == null || value <= 0) continue;
                String attributeName = keyStr.substring("item_fabled_attr_".length());
                playerData.giveAttribute(attributeName, value.intValue());
                addedAttributes.put(attributeName, value);
                this.configManager.debug("Applied attribute " + attributeName + " with value " + value + " to " + player.getName());
            }
            catch (Exception e) {
                // WARNING, not debug. A grant that throws half way leaves the
                // ledger row and Fabled's point pool disagreeing, and nothing
                // downstream can tell that happened — the next reconcile just
                // sees a mismatch it will "fix" against whichever side is wrong.
                // This is the moment the drift is created, so it is the one
                // moment worth saying out loud without debug being switched on.
                Bukkit.getLogger().warning("[CustomInventoryPlugin] Failed to apply attribute "
                        + keyStr + " for " + player.getName() + " (slot " + slotId + "): " + e);
            }
        }
        if (!addedAttributes.isEmpty()) {
            PlayerGearData.setPlayerSlotAttributes(playerUUID, slotId, addedAttributes);
            this.configManager.debug("Stored attributes for slot " + slotId + ": " + String.valueOf(addedAttributes));
        } else {
            this.configManager.debug("No attributes to store for slot " + slotId);
        }
        playerData.updatePlayerStat(player);
    }
}

