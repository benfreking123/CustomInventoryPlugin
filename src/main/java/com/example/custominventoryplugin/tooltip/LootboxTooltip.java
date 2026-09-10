package com.example.custominventoryplugin.tooltip;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Page 1 of a lootbox (the {@code lootcrate_*} custom items).
 *
 * The only question a player has when hovering a box is what is inside it, and
 * that was the one thing the old lore could not be trusted on: contents were
 * hand-written prose while the payout lives in
 * Skript-shared/scripts/lootboxes/lootcrates.sk, so the two drifted apart. The
 * copy now sits in the {@code lootboxes:} block of settings.yml with the payout
 * spelled out under a CONTENTS bar, and tt-check compares it against the script.
 *
 * Odds are deliberately omitted — Ben's call: name what can drop, keep the roll
 * a surprise.
 */
final class LootboxTooltip {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private static final Map<String, Character> RARITY_PILL = Map.of(
            "common", '\uE100', "uncommon", '\uE101', "rare", '\uE102',
            "mythic", '\uE103', "legendary", '\uE104');
    private static final char RAIL_CONTENTS = '\uE168';

    private static final int MIN_WIDTH = 163;
    private static final int PILL_GAP = 3;
    private static final int RAIL_GAP = 3;

    private static final Key FONT_BODY = Key.key("tower", "tooltip");
    private static final Key FONT_BIG = Key.key("tower", "big");

    private final TooltipConfig config;
    private final TooltipGlyphs glyphs;

    LootboxTooltip(JavaPlugin plugin, TooltipConfig config) {
        this.config = config;
        this.glyphs = new TooltipGlyphs(plugin);
    }

    boolean isLootbox(String itemId) {
        return config.lootboxForItemId(itemId) != null;
    }

    void reflow(ItemStack stack, String itemId, Component footer) {
        TooltipConfig.Lootbox box = config.lootboxForItemId(itemId);
        if (box == null) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        glyphs.refresh();
        meta.lore(layout(box, footer));
        stack.setItemMeta(meta);
    }

    private List<Component> layout(TooltipConfig.Lootbox box, Component footer) {
        List<Object> rows = new ArrayList<>();
        rows.add(badgeRow(box));
        if (!box.headline().isBlank()) {
            rows.add("");
            rows.add(new Big("&f" + box.headline()));
        }
        if (!box.contents().isEmpty()) {
            rows.add("");
            rows.add(TooltipGlyphs.Bar.CONTENTS);
            for (String line : box.contents()) {
                rows.add("&f" + RAIL_CONTENTS + TooltipGlyphs.pad(RAIL_GAP) + line);
            }
        }
        if (!box.use().isBlank()) {
            rows.add("");
            // Honour an authored colour in settings.yml (`use: '&e…'`). Bare
            // text stays yellow — dark-gray was invisible on the tooltip.
            String use = box.use();
            rows.add(use.startsWith("&") ? use : "&e" + use);
        }

        int width = MIN_WIDTH;
        for (Object row : rows) {
            if (row instanceof String s) {
                width = Math.max(width, glyphs.width(s));
            } else if (row instanceof Big b) {
                width = Math.max(width, glyphs.width(b.text(), TooltipGlyphs.BIG_CHAR));
            }
        }

        List<Component> lore = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Big b) {
                lore.add(plain(LEGACY.deserialize(b.text()).font(FONT_BIG)));
            } else if (row instanceof TooltipGlyphs.Bar bar) {
                lore.add(plain(LEGACY.deserialize("&f" + glyphs.bar(bar, width))
                        .font(FONT_BODY)));
            } else {
                String s = (String) row;
                lore.add(s.isEmpty() ? Component.empty()
                        : plain(LEGACY.deserialize(s).font(FONT_BODY)));
            }
        }
        lore.add(Component.empty());
        lore.add(footer);
        return lore;
    }

    private String badgeRow(TooltipConfig.Lootbox box) {
        Character rarity = RARITY_PILL.get(box.rarity() == null ? ""
                : box.rarity().toLowerCase(Locale.ROOT));
        return "&f" + (rarity == null ? RARITY_PILL.get("common") : rarity)
                + TooltipGlyphs.pad(PILL_GAP) + '\uE11E';   // type_lootbox
    }

    private record Big(String text) { }

    private static Component plain(Component c) {
        return c.style(s -> s.shadowColor(ShadowColor.none()));
    }
}
