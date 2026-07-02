package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.BackpackConfig;
import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.BackpackData;
import com.example.custominventoryplugin.inventory.BackpackInventory;
import com.example.custominventoryplugin.inventory.GearInventory;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

public class InventoryListener
implements Listener {
    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;
    private final SlotHandler slotHandler;
    private final ArmorHandler armorHandler;
    private final SkillHandler skillHandler;
    private final AttributeHandler attributeHandler;

    public InventoryListener(ConfigManager configManager, CustomInventoryPlugin plugin) {
        this.configManager = configManager;
        this.plugin = plugin;
        this.slotHandler = new SlotHandler(configManager, plugin);
        this.armorHandler = new ArmorHandler(configManager);
        this.skillHandler = this.slotHandler.getSkillHandler();
        this.attributeHandler = new AttributeHandler(configManager);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GearInventory)) {
            return;
        }
        if (event.getClick().isRightClick() && event.getClick().isShiftClick() || event.getClick() == ClickType.NUMBER_KEY || event.getClick() == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
        }
        Player player = (Player)event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot < 0) {
            return;
        }
        if (event.isShiftClick() && event.getClickedInventory() == event.getView().getBottomInventory()) {
            this.handleShiftClickFromPlayer(event, player);
            return;
        }
        if (event.isShiftClick() && event.getClickedInventory() == event.getView().getTopInventory()) {
            this.handleShiftClickFromGUI(event, player);
            return;
        }
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            this.handleNormalClick(event, player);
        }
    }

    private void handleShiftClickFromPlayer(InventoryClickEvent event, Player player) {
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType().isAir()) {
            return;
        }
        event.setCancelled(true);
        if (this.armorHandler.handleShiftClick(event, player, clickedItem)) {
            return;
        }
        if (this.slotHandler.handleShiftClick(event, player, clickedItem)) {
            return;
        }
    }

    private void handleShiftClickFromGUI(InventoryClickEvent event, Player player) {
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType().isAir()) {
            return;
        }
        event.setCancelled(true);
        this.slotHandler.handleShiftClickFromGUI(event, player, clickedItem);
    }

    private void handleNormalClick(InventoryClickEvent event, Player player) {
        // Backpack icon button? Open the backpack.
        if (event.getInventory().getHolder() instanceof GearInventory gear) {
            String bpId = gear.getBackpackIdAtSlot(event.getRawSlot());
            if (bpId != null) {
                event.setCancelled(true);
                openBackpack(player, bpId);
                return;
            }
        }

        Map<String, ConfigManager.CustomSlot> customSlots = this.configManager.getCustomSlots();
        for (Map.Entry<String, ConfigManager.CustomSlot> entry : customSlots.entrySet()) {
            String string = entry.getKey();
            ConfigManager.CustomSlot customSlot = entry.getValue();
            if (customSlot.getPosition() != event.getRawSlot()) continue;
            if (!customSlot.isEnabled()) {
                event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
            if (!this.configManager.isSkillSlotUnlocked(customSlot, player)) {
                // Locked slot: allow taking out a previously-stored gem (so nothing
                // gets trapped if a perm/default count changes), but block adding.
                ItemStack stored = com.example.custominventoryplugin.data.PlayerGearData.getPlayerGear(player.getUniqueId(), string);
                boolean hasStored = stored != null && !stored.getType().isAir();
                boolean cursorEmpty = event.getCursor() == null || event.getCursor().getType().isAir();
                if (hasStored && cursorEmpty) {
                    this.slotHandler.handleNormalClick(event, player, customSlot, string);
                }
                return;
            }
            this.slotHandler.handleNormalClick(event, player, customSlot, string);
            return;
        }
        Map<String, Integer> armorSlots = this.configManager.getArmorSlots();
        for (Map.Entry<String, Integer> entry : armorSlots.entrySet()) {
            if (entry.getValue().intValue() != event.getRawSlot()) continue;
            this.armorHandler.handleNormalClick(event, player, entry.getKey());
            return;
        }
        ItemStack itemStack = event.getCurrentItem();
        if (itemStack != null && (itemStack.getType() == Material.BARRIER || itemStack.getType() == this.configManager.getFillPane() || itemStack.getType() == this.configManager.getLockedPane())) {
            event.setCancelled(true);
        }
    }

    /** Close /ci, then on the next tick open the requested backpack. */
    private void openBackpack(Player player, String backpackId) {
        BackpackConfig bpCfg = this.plugin.getBackpackConfig();
        BackpackData   bpData = this.plugin.getBackpackData();
        if (bpCfg == null || bpData == null) return;

        BackpackDef def = bpCfg.get(backpackId);
        if (def == null || !def.canAccess(player)) {
            player.sendMessage("\u00a7cYou don't have access to that backpack.");
            return;
        }

        player.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> BackpackInventory.open(plugin, player, def));
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof GearInventory)) {
            return;
        }
        Player player = (Player)event.getWhoClicked();
        this.slotHandler.handleDrag(event, player);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof GearInventory)) {
            return;
        }
        Player player = (Player)event.getPlayer();
        this.skillHandler.removeAllPermissions(player);
    }
}

