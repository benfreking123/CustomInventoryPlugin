package com.example.custominventoryplugin.inventory;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.settings.BagSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Filter editor for a single backpack. The top inventory shows the bag's
 * current allow-list as material icons; clicking one removes it. Clicking an
 * item in the player's own inventory (bottom) toggles that material in/out of
 * the filter — no items are ever moved or consumed.
 */
public class BackpackFilterInventory implements InventoryHolder {

    public enum Control { DONE, CLEAR }

    private static final int SIZE = 54;
    private static final int SLOT_CLEAR = 52;
    private static final int SLOT_DONE = 53;

    private final CustomInventoryPlugin plugin;
    private final Player player;
    private final BackpackDef def;
    private final Inventory inventory;
    private final Map<Integer, Material> slotToMaterial = new HashMap<>();

    public BackpackFilterInventory(CustomInventoryPlugin plugin, Player player, BackpackDef def) {
        this.plugin = plugin;
        this.player = player;
        this.def = def;
        Component title = legacy("&8Filter: " + def.getDisplayName());
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        render(plugin.getSettingsCache().bag(player.getUniqueId(), def));
    }

    public void render(BagSettings bs) {
        inventory.clear();
        slotToMaterial.clear();

        int slot = 0;
        for (Material m : bs.getFilter()) {
            if (slot >= SLOT_CLEAR) break;
            ItemStack icon = new ItemStack(m);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(legacy("&f" + m.name().toLowerCase()));
                meta.lore(List.of(legacy("&7Click to remove from filter.")));
                icon.setItemMeta(meta);
            }
            inventory.setItem(slot, icon);
            slotToMaterial.put(slot, m);
            slot++;
        }

        inventory.setItem(SLOT_CLEAR, simple(Material.BARRIER, "&cClear filter",
                List.of("&7Remove all materials.", "&7An empty filter grabs everything.")));
        inventory.setItem(SLOT_DONE, simple(Material.EMERALD_BLOCK, "&aDone",
                List.of("&7Click an item in your inventory", "&7to add/remove it. Back to bag.")));
    }

    public Control controlAt(int rawSlot) {
        if (rawSlot == SLOT_DONE) return Control.DONE;
        if (rawSlot == SLOT_CLEAR) return Control.CLEAR;
        return null;
    }

    public Material materialAt(int rawSlot) {
        return slotToMaterial.get(rawSlot);
    }

    private ItemStack simple(Material mat, String name, List<String> loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(legacy(l));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    public BackpackDef getDef() { return def; }
    public Player getPlayer() { return player; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }
}
