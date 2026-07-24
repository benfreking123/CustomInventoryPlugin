package com.example.custominventoryplugin.inventory;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerGearData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GearInventory implements InventoryHolder {

    /** PDC key tagging /ci button icons with the target backpack id. */
    public static final String BACKPACK_BUTTON_KEY = "ci_backpack_button";

    /** Nexo ci_background glyph (interface.yml, char ꐟ / U+A41F) + horizontal pull. */
    private static final char GEAR_BG_GLYPH = '\uA41F';
    private static final int GEAR_BG_SHIFT = 16;

    private final Inventory inventory;
    private final Player player;
    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;
    private final NamespacedKey backpackButtonKey;

    /** slot position → backpack id, for quick click lookup */
    private final Map<Integer, String> backpackButtonSlots = new HashMap<>();

    public GearInventory(Player player, ConfigManager configManager, CustomInventoryPlugin plugin) {
        this.player = player;
        this.configManager = configManager;
        this.plugin = plugin;
        this.backpackButtonKey = new NamespacedKey(plugin, BACKPACK_BUTTON_KEY);
        this.inventory = Bukkit.createInventory(this, 54,
                com.example.custominventoryplugin.groupdrop.Text.nexoBackground(GEAR_BG_GLYPH, GEAR_BG_SHIFT));
        this.updateInventory();
    }

    public void updateInventory() {
        this.inventory.clear();
        this.backpackButtonSlots.clear();

        // 1. Armor column
        PlayerInventory playerInv = this.player.getInventory();
        Map<String, Integer> armorSlots = this.configManager.getArmorSlots();
        this.inventory.setItem(armorSlots.get("helmet").intValue(),     playerInv.getHelmet());
        this.inventory.setItem(armorSlots.get("chestplate").intValue(), playerInv.getChestplate());
        this.inventory.setItem(armorSlots.get("leggings").intValue(),   playerInv.getLeggings());
        this.inventory.setItem(armorSlots.get("boots").intValue(),      playerInv.getBoots());

        // 2. Custom (attribute + skill) slots
        Map<String, ConfigManager.CustomSlot> customSlots = this.configManager.getCustomSlots();
        for (Map.Entry<String, ConfigManager.CustomSlot> entry : customSlots.entrySet()) {
            String slotId = entry.getKey();
            ConfigManager.CustomSlot slot = entry.getValue();
            if (!slot.isEnabled()) {
                this.inventory.setItem(slot.getPosition(), new ItemStack(Material.BARRIER));
                continue;
            }
            ItemStack storedItem = PlayerGearData.getPlayerGear(this.player.getUniqueId(), slotId);
            boolean hasStored = storedItem != null && !storedItem.getType().isAir();
            boolean unlocked = this.configManager.isSkillSlotUnlocked(slot, this.player);
            if (hasStored) {
                // Always show a stored gem (even if the slot became locked later) so
                // it can be retrieved — locked slots only block *adding* new gems.
                this.inventory.setItem(slot.getPosition(), storedItem);
            } else if (!unlocked) {
                this.inventory.setItem(slot.getPosition(), this.buildLockedPane());
            } else {
                this.inventory.setItem(slot.getPosition(), null);
            }
        }

        // 3. Decorative slots stay EMPTY so the painted ci_background frame shows
        // through. (Clicks on these are cancelled in InventoryListener.) The old
        // glass-pane fill would cover the art, so it's intentionally not placed.
        List<BackpackDef> backpacks = plugin.getBackpackConfig() != null
                ? plugin.getBackpackConfig().accessible(player)
                : List.of();

        // 4. Backpack icon buttons — placed last so they override anything.
        // Use one batched query for fill counts → avoid N round-trips.
        Map<String, Integer> usedById = plugin.getBackpackData() != null
                ? plugin.getBackpackData().countAllUsed(player.getUniqueId())
                : Map.of();
        for (BackpackDef def : backpacks) {
            if (!def.hasCiButton()) continue;
            int used = usedById.getOrDefault(def.getId(), 0);
            this.inventory.setItem(def.getCiSlot(),
                    BackpackIconBuilder.build(def, used, backpackButtonKey));
            this.backpackButtonSlots.put(def.getCiSlot(), def.getId());
        }
    }

    /** Builds the placeholder shown in a permission-locked skill-gem slot. */
    private ItemStack buildLockedPane() {
        ItemStack pane = new ItemStack(this.configManager.getLockedPane());
        org.bukkit.inventory.meta.ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("\u00a7c\u00a7lLocked Slot");
            meta.setLore(java.util.List.of("\u00a77Unlock this slot by progressing."));
            pane.setItemMeta(meta);
        }
        return pane;
    }

    /** Returns the backpack id at this slot, or null if it's not a button slot. */
    public String getBackpackIdAtSlot(int slot) {
        return backpackButtonSlots.get(slot);
    }

    @Override @NotNull
    public Inventory getInventory() { return this.inventory; }

    public Player getPlayer() { return this.player; }

    public NamespacedKey getBackpackButtonKey() { return backpackButtonKey; }
}
