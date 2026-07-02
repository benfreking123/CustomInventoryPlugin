package com.example.custominventoryplugin.groupdrop;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Player-facing read-only selection window. Options render at their configured
 * slot; left-click chooses, right-click previews a bundle. Tracks remaining
 * picks and the set of already-chosen option slots (for distinct enforcement
 * and greying).
 */
public class GroupDropChooser implements InventoryHolder {

    private final Player player;
    private final GroupDrop group;
    private final boolean tokenTriggered;
    private final Set<Integer> chosen;
    private final Inventory inventory;

    private int picksRemaining;
    private boolean tokenConsumed = false;
    /** Set true while we transition to a preview window so close handling is a no-op. */
    private boolean openingPreview = false;

    public GroupDropChooser(Player player, GroupDrop group, boolean tokenTriggered,
                            int picksRemaining, Set<Integer> chosen) {
        this.player = player;
        this.group = group;
        this.tokenTriggered = tokenTriggered;
        this.picksRemaining = picksRemaining;
        this.chosen = chosen;
        this.inventory = Bukkit.createInventory(this, group.chooserSize(), Text.title(group.getTitle()));
        render();
    }

    public void render() {
        inventory.clear();
        for (GroupDropOption opt : group.getOptions().values()) {
            if (opt.getSlot() < 0 || opt.getSlot() >= inventory.getSize()) continue;
            inventory.setItem(opt.getSlot(), buildIcon(opt));
        }
    }

    private ItemStack buildIcon(GroupDropOption opt) {
        ItemStack icon = (opt.getIcon() != null && !opt.getIcon().getType().isAir())
                ? opt.getIcon().clone() : new ItemStack(Material.CHEST);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;

        if (opt.getLabel() != null && !opt.getLabel().isEmpty()) {
            meta.displayName(Text.c(opt.getLabel()));
        }

        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());

        boolean blocked = group.isDistinct() && chosen.contains(opt.getSlot());
        if (blocked) {
            lore.add(Text.c("&8✔ Already chosen"));
        } else {
            lore.add(Text.c("&aLeft-click to choose"));
            if (opt.hasPreview()) lore.add(Text.c("&7Right-click to preview contents"));
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    public Player getPlayer()              { return player; }
    public GroupDrop getGroup()            { return group; }
    public boolean isTokenTriggered()      { return tokenTriggered; }
    public Set<Integer> getChosen()        { return chosen; }
    public int getPicksRemaining()         { return picksRemaining; }
    public void decrementPicks()           { this.picksRemaining--; }
    public boolean isTokenConsumed()       { return tokenConsumed; }
    public void markTokenConsumed()        { this.tokenConsumed = true; }
    public boolean isOpeningPreview()      { return openingPreview; }
    public void setOpeningPreview(boolean b) { this.openingPreview = b; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }
}
