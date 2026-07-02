package com.example.custominventoryplugin.inventory;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.BackpackConfig;
import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.data.BackpackData;
import com.example.custominventoryplugin.settings.BagSettings;
import com.example.custominventoryplugin.settings.PlayerPickupSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GUI shown by {@code /bp} and {@code /bp list}. Backpack icons open the
 * targeted bag; the bottom row carries the two player-level master toggles
 * (Pickup On/Off, Pickup Everything). Everything else is cancelled.
 */
public class BackpackListInventory implements InventoryHolder {

    public static final String MASTER_KEY = "cip_master";
    public static final String MASTER_ENABLED = "ENABLED";
    public static final String MASTER_GRAB = "GRAB";

    private final Player player;
    private final Inventory inventory;
    /** raw slot index → backpack id (only for slots with an icon) */
    private final Map<Integer, String> slotToBackpackId = new HashMap<>();

    public BackpackListInventory(CustomInventoryPlugin plugin, Player player, BackpackConfig config,
                                 BackpackData data, NamespacedKey iconKey) {
        this.player = player;

        List<BackpackDef> accessible = config.accessible(player);
        int n = accessible.size();
        int iconRows = Math.max(1, Math.min(5, (n + 8) / 9));  // cap at 5 so row 6 is the toggle bar
        int size = (iconRows + 1) * 9;
        int toggleRow = size - 9;

        Component title = Component.text("Your Backpacks").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true);
        this.inventory = Bukkit.createInventory(this, size, title);

        UUID uuid = player.getUniqueId();
        Map<String, Integer> usedById = data.countAllUsed(uuid);

        int iconCap = iconRows * 9;
        for (int i = 0; i < n && i < iconCap; i++) {
            BackpackDef def = accessible.get(i);
            int used = usedById.getOrDefault(def.getId(), 0);
            BagSettings bs = plugin.getSettingsCache().bag(uuid, def);
            int capacity = BackpackInventory.storageSize(def.windowSize(bs.getTier()));
            ItemStack icon = BackpackIconBuilder.build(def, used, capacity, bs.getMode(),
                    bs.getTier(), def.maxTier(), iconKey);
            this.inventory.setItem(i, icon);
            slotToBackpackId.put(i, def.getId());
        }

        // Toggle bar.
        PlayerPickupSettings pp = plugin.getSettingsCache().player(uuid);
        NamespacedKey masterKey = new NamespacedKey(plugin, MASTER_KEY);
        ItemStack filler = makeFiller();
        for (int i = toggleRow; i < size; i++) this.inventory.setItem(i, filler);
        this.inventory.setItem(toggleRow, masterEnabledButton(masterKey, pp));
        this.inventory.setItem(toggleRow + 1, masterGrabButton(masterKey, pp));

        // Fill the icon area's leftover slots.
        for (int i = n; i < iconCap; i++) {
            if (this.inventory.getItem(i) == null) this.inventory.setItem(i, filler);
        }
    }

    private ItemStack masterEnabledButton(NamespacedKey key, PlayerPickupSettings pp) {
        boolean on = pp.isMasterEnabled();
        return masterButton(key, MASTER_ENABLED,
                on ? Material.LIME_DYE : Material.GRAY_DYE,
                "&ePickup: " + (on ? "&aOn" : "&cOff"),
                List.of("&7Master switch for backpack auto-pickup", "&7(mob drops, mining, fishing, ground).",
                        "", "&eClick to toggle."));
    }

    private ItemStack masterGrabButton(NamespacedKey key, PlayerPickupSettings pp) {
        boolean on = pp.isGrabEverything();
        return masterButton(key, MASTER_GRAB,
                on ? Material.HOPPER : Material.HOPPER_MINECART,
                "&ePickup Everything: " + (on ? "&aOn" : "&cOff"),
                List.of("&7Ignore every bag's filter and", "&7grab all (non-forbidden) items.",
                        "", "&eClick to toggle."));
    }

    private ItemStack masterButton(NamespacedKey key, String value, Material mat, String name, List<String> loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(legacy(l));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack makeFiller() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            pane.setItemMeta(meta);
        }
        return pane;
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Returns the backpack id at this slot, or null if it's empty/filler/toggle. */
    public String getBackpackIdAtSlot(int slot) {
        return slotToBackpackId.get(slot);
    }

    public Player getPlayer() { return player; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }
}
