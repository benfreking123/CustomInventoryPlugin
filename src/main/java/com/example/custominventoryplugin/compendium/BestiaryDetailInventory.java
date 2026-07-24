package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.groupdrop.Text;
import net.kyori.adventure.text.Component;
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
import java.util.List;
import java.util.Map;

/**
 * Detail pane for one bestiary entry. Unlock order:
 * discovered → locations @1 · lore @10 · weaknesses @50 · drops @100.
 * (Combat "stats" removed — drops replace that slot.)
 */
public class BestiaryDetailInventory implements InventoryHolder {

    public static final String ACTION_KEY = BestiaryInventory.ACTION_KEY;
    public static final String ACT_BACK = BestiaryInventory.ACT_BACK;

    private final Player player;
    private final Inventory inventory;
    private final int returnFloor;
    private final String returnCategory;

    public BestiaryDetailInventory(CustomInventoryPlugin plugin, Player player, BestiaryEntry entry,
                                   BestiaryConfig.Thresholds th, Map<String, Integer> kills,
                                   String returnCategory, int returnFloor, String revealPermission) {
        this.player = player;
        this.returnFloor = returnFloor;
        this.returnCategory = returnCategory;
        this.inventory = Bukkit.createInventory(this, 27,
                Text.title("&e&l" + entry.getDisplay()));

        NamespacedKey key = new NamespacedKey(plugin, ACTION_KEY);
        ItemStack filler = filler();
        for (int i = 0; i < 27; i++) inventory.setItem(i, filler);

        boolean reveal = player.hasPermission(revealPermission);
        int total = BestiaryData.sumForEntry(kills, entry);
        int elites = BestiaryData.eliteKills(kills, entry);

        // Portrait
        List<String> head = new ArrayList<>();
        if (entry.isBoss()) head.add("&c&lBoss");
        if (!entry.getFamily().isBlank()) head.add("&7Family: &f" + entry.getFamily());
        if (!entry.getRole().isBlank()) head.add("&7Role: &f" + entry.getRole());
        head.add("&7Kills: &f" + total);
        if (elites > 0) head.add("&7Elite kills: &f" + elites);
        inventory.setItem(4, build(entry.getIcon(),
                (entry.isBoss() ? "&c&l" : "&a&l") + entry.getDisplay(), head));

        // Locations — available on discovery
        {
            List<String> loc = new ArrayList<>();
            if (entry.getLocations().isEmpty()) loc.add("&8Unknown.");
            else for (String l : entry.getLocations()) loc.add("&f" + l);
            inventory.setItem(11, build(Material.COMPASS, "&b&lKnown Locations", loc));
        }

        // Lore @10
        if (reveal || total >= th.lore) {
            List<String> lore = new ArrayList<>();
            if (entry.getLore().isEmpty()) lore.add("&8No notes yet.");
            else for (String l : entry.getLore()) lore.add("&7" + l);
            inventory.setItem(12, build(Material.WRITABLE_BOOK, "&d&lLore", lore));
        } else {
            inventory.setItem(12, locked("Lore", total, th.lore));
        }

        // Weaknesses / resists @50
        if (reveal || total >= th.weaknesses) {
            List<String> wr = new ArrayList<>();
            wr.add("&aResists:");
            if (entry.getResists().isEmpty()) wr.add("  &8None listed.");
            else for (String r : entry.getResists()) wr.add("  &f" + r);
            wr.add("");
            wr.add("&cWeaknesses:");
            if (entry.getWeaknesses().isEmpty()) wr.add("  &8None listed.");
            else for (String w : entry.getWeaknesses()) wr.add("  &f" + w);
            inventory.setItem(14, build(Material.SHIELD, "&6&lResists & Weaknesses", wr));
        } else {
            inventory.setItem(14, locked("Weaknesses", total, th.weaknesses));
        }

        // Drops @100 (replaces old stats slot)
        if (reveal || total >= th.drops) {
            List<String> drops = new ArrayList<>();
            if (entry.getDrops().isEmpty()) drops.add("&8No drop notes yet.");
            else for (String d : entry.getDrops()) drops.add("&f" + d);
            inventory.setItem(15, build(Material.CHEST, "&6&lDrops", drops));
        } else {
            inventory.setItem(15, locked("Drops", total, th.drops));
        }

        inventory.setItem(22, button(key, ACT_BACK, Material.ARROW, "&e&lBack",
                List.of("&7Return to Floor " + returnFloor + ".")));
    }

    private ItemStack locked(String label, int total, int need) {
        return build(Material.GRAY_DYE, "&8" + label + " (locked)",
                List.of("&7Need &f" + need + " &7kills.", "&7Progress: &f" + total + "/" + need));
    }

    private ItemStack button(NamespacedKey key, String action, Material mat, String name, List<String> lore) {
        ItemStack it = build(mat, name, lore);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.getPersistentDataContainer().set(key, PersistentDataType.STRING, action);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack build(Material mat, String name, List<String> loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Text.c(name));
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(Text.c(l));
            m.lore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack filler() {
        ItemStack it = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Component.text(" "));
            it.setItemMeta(m);
        }
        return it;
    }

    public int getReturnFloor() { return returnFloor; }
    public String getReturnCategory() { return returnCategory; }
    public Player getPlayer() { return player; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }
}
