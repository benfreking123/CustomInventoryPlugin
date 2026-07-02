package com.example.custominventoryplugin.groupdrop;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Free-edit container for one option's bundle. Whatever items the admin leaves
 * in here become the option's granted items. An empty bundle falls back to the
 * option's display icon (handled by {@link GroupDropOption#effectiveGrants()}).
 *
 * Closing returns to the main {@link GroupDropEditor}.
 */
public class GroupDropBundleEditor implements InventoryHolder {

    public static final int SIZE = 54;

    private final Player player;
    private final GroupDropEditSession session;
    private final int optionSlot;
    private final Inventory inventory;

    public GroupDropBundleEditor(Player player, GroupDropEditSession session, int optionSlot) {
        this.player = player;
        this.session = session;
        this.optionSlot = optionSlot;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Text.title("&8Bundle: option @" + optionSlot));

        GroupDropOption opt = session.draft.getOption(optionSlot);
        if (opt != null && !opt.getGrants().isEmpty()) {
            List<ItemStack> grants = opt.getGrants();
            for (int i = 0; i < grants.size() && i < SIZE; i++) {
                ItemStack it = grants.get(i);
                if (it != null) inventory.setItem(i, it.clone());
            }
        }
    }

    /** Persist the current container contents back into the option's grant list. */
    public void syncToOption() {
        GroupDropOption opt = session.draft.getOption(optionSlot);
        if (opt == null) return;
        opt.getGrants().clear();
        for (ItemStack it : inventory.getContents()) {
            if (it != null && !it.getType().isAir()) opt.getGrants().add(it.clone());
        }
    }

    public GroupDropEditSession getSession() { return session; }
    public Player getPlayer()                { return player; }
    public int getOptionSlot()               { return optionSlot; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }
}
