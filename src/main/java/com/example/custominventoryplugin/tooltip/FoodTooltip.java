package com.example.custominventoryplugin.tooltip;

import com.example.custominventoryplugin.food.FoodRegenConfig;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The regeneration line on a food item.
 *
 * Food is the one thing CIP styles that is identified by {@link Material}
 * rather than by a Divinity item id, because these are ordinary vanilla stacks.
 * That also makes this the one styled item with no rarity, no tier frame and no
 * second page — so it deliberately skips the glyph/bar/pill machinery the gear
 * and catalyst tooltips use and writes two plain lines.
 *
 * Since the gamerule that made eating heal you is off everywhere, this line is
 * the only place a player can find out what a loaf of bread is actually for.
 *
 * Rebuilds lore wholesale rather than appending, which is what keeps it
 * idempotent under the 5-tick hand sweep. Safe only because the tracked
 * materials are plain vanilla food carrying no other lore; a Divinity
 * consumable must never be listed in {@code food-regen.foods}.
 */
final class FoodTooltip {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();
    private static final Key FONT_BODY = Key.key("tower", "tooltip");

    private final FoodRegenConfig config;
    /** Last-written "regen/seconds", so a re-stamp is a no-op until it changes. */
    private final NamespacedKey stampKey;

    FoodTooltip(JavaPlugin plugin, FoodRegenConfig config) {
        this.config = config;
        this.stampKey = new NamespacedKey(plugin, "tt_food_regen");
    }

    boolean isTracked(Material material) {
        return config.forMaterial(material) != null;
    }

    void reflow(ItemStack stack) {
        FoodRegenConfig.Food food = config.forMaterial(stack.getType());
        if (food == null) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;

        String stamp = food.regen() + "/" + food.seconds();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String existing = pdc.get(stampKey, PersistentDataType.STRING);
        if (stamp.equals(existing) && meta.hasLore()) return;

        List<Component> lore = new ArrayList<>();
        lore.add(line("&7Grants &a+" + food.regen() + " Health Regen"));
        lore.add(line("&7for &f" + food.seconds() + " seconds&7 when eaten."));

        meta.lore(lore);
        pdc.set(stampKey, PersistentDataType.STRING, stamp);
        stack.setItemMeta(meta);
    }

    private static Component line(String legacy) {
        return LEGACY.deserialize(legacy).font(FONT_BODY)
                .style(s -> s.shadowColor(ShadowColor.none()));
    }
}
