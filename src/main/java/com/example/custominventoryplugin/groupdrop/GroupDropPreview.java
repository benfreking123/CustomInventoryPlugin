package com.example.custominventoryplugin.groupdrop;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Read-only "look inside" window for a bundle option. Shows the items the option
 * grants. Closing it returns the player to the chooser they came from.
 */
public class GroupDropPreview implements InventoryHolder {

    private final GroupDropChooser parent;
    private final Inventory inventory;

    public GroupDropPreview(Player player, GroupDropOption option, GroupDropChooser parent) {
        this.parent = parent;
        List<ItemStack> grants = option.effectiveGrants();
        int rows = Math.max(1, Math.min(6, (grants.size() + 8) / 9));
        this.inventory = Bukkit.createInventory(this, rows * 9, Text.title("&8Contents"));
        for (int i = 0; i < grants.size() && i < inventory.getSize(); i++) {
            ItemStack it = grants.get(i);
            if (it != null) inventory.setItem(i, it.clone());
        }
    }

    public GroupDropChooser getParent() { return parent; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }
}
