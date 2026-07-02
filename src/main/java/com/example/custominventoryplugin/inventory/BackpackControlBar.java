package com.example.custominventoryplugin.inventory;

import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.config.PickupMode;
import com.example.custominventoryplugin.settings.BagSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders and identifies the bottom-row control bar of a backpack window.
 * Each button is PDC-tagged with {@link #CTL_KEY} → an {@link Action} name so
 * the click handler can dispatch without positional guessing.
 */
public final class BackpackControlBar {

    public static final String CTL_KEY = "cip_ctl";

    public enum Action { MODE, FILTER, GRAB, DEPOSIT, SORT, TIER, FILLER }

    private BackpackControlBar() {}

    public static NamespacedKey key(Plugin plugin) {
        return new NamespacedKey(plugin, CTL_KEY);
    }

    /** Returns the control Action for an item, or null if it isn't a control button. */
    public static Action actionOf(Plugin plugin, ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        String v = meta.getPersistentDataContainer().get(key(plugin), PersistentDataType.STRING);
        if (v == null) return null;
        try {
            return Action.valueOf(v);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static void render(Plugin plugin, Inventory inv, BackpackDef def, BagSettings settings) {
        int size = inv.getSize();
        if (size < 18) return;
        int base = size - 9;

        // Fill the whole bar with spacers first, then overlay the buttons.
        ItemStack filler = button(plugin, Action.FILLER, Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 9; i++) inv.setItem(base + i, filler);

        inv.setItem(base, modeButton(plugin, settings));
        if (def.isFilterable()) {
            inv.setItem(base + 1, filterButton(plugin, settings));
        }
        inv.setItem(base + 2, grabButton(plugin, settings));
        inv.setItem(base + 4, button(plugin, Action.DEPOSIT, Material.HOPPER,
                "&bQuick Deposit", List.of("&7Move matching items from your", "&7inventory into this bag.")));
        inv.setItem(base + 6, button(plugin, Action.SORT, Material.COMPARATOR,
                "&bSort / Compact", List.of("&7Merge stacks and sort this bag.")));
        inv.setItem(base + 8, tierButton(plugin, def, settings));
    }

    private static ItemStack modeButton(Plugin plugin, BagSettings s) {
        return button(plugin, Action.MODE, Material.LEVER, "&ePickup Mode: " + s.getMode().display(),
                List.of("&7Click to cycle:", "&7Off → Match → Overflow → Bag first"));
    }

    private static ItemStack filterButton(Plugin plugin, BagSettings s) {
        List<String> lore = new ArrayList<>();
        if (s.getFilter().isEmpty()) {
            lore.add("&7Currently: &fEverything");
        } else {
            lore.add("&7Currently &f" + s.getFilter().size() + " &7material(s):");
            int shown = 0;
            for (Material m : s.getFilter()) {
                if (shown++ >= 8) { lore.add("&8  …"); break; }
                lore.add("&8• &7" + m.name().toLowerCase());
            }
        }
        lore.add("");
        lore.add("&7Left-click to edit the allow-list.");
        return button(plugin, Action.FILTER, Material.HOPPER_MINECART, "&ePickup Filter", lore);
    }

    private static ItemStack grabButton(Plugin plugin, BagSettings s) {
        boolean on = s.isGrabEverything();
        return button(plugin, Action.GRAB, on ? Material.LIME_DYE : Material.GRAY_DYE,
                "&eGrab Everything: " + (on ? "&aOn" : "&cOff"),
                List.of("&7Ignore this bag's filter and", "&7accept every item. Click to toggle."));
    }

    private static ItemStack tierButton(Plugin plugin, BackpackDef def, BagSettings s) {
        int maxTier = def.maxTier();
        List<String> lore = new ArrayList<>();
        lore.add("&7Tier &f" + s.getTier() + "&7/&f" + maxTier);
        lore.add("&7Capacity &f" + BackpackInventory.storageSize(def.windowSize(s.getTier())) + " &7slots");
        if (s.getTier() >= maxTier) {
            lore.add("");
            lore.add("&aMax tier reached.");
        } else {
            double cost = def.getUpgradeCost() * (s.getTier() + 1);
            lore.add("");
            lore.add("&7Next tier: &a$" + (long) cost);
            lore.add("&eClick to upgrade.");
        }
        return button(plugin, Action.TIER, Material.ANVIL, "&eCapacity Upgrade", lore);
    }

    private static ItemStack button(Plugin plugin, Action action, Material mat, String name, List<String> loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name));
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(legacy(l));
            if (!lore.isEmpty()) meta.lore(lore);
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, action.name());
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }
}
