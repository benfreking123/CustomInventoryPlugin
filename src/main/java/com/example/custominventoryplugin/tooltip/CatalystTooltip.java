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
 * Page 1 of a Smithy catalyst (the {@code smithy_*} custom items).
 *
 * These nine items are named for what they are made of, not what they do —
 * Flux Dust, Ember, Purge Salt tell a player nothing — so the stone's effect is
 * promoted to a pill and a headline, and the prose drops to a DESCRIPTION
 * block. The copy lives in the {@code catalysts:} block of settings.yml rather
 * than in the Divinity item YAML, so it reloads with {@code /ci reload} and can
 * be wrapped to the measured tooltip width.
 *
 * Safe to rewrite wholesale: Smithy resolves a stone by its Divinity item id
 * ({@code custom-item:} in stones.yml), never by reading its lore, so unlike
 * gear — whose damage line is a machine interface for Fabled's Value Lore, see
 * Docs/how-to/skills.md — nothing here is load-bearing text.
 */
final class CatalystTooltip {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    /** Rarity pills U+E100..; the frame stays currency, this is the band only. */
    private static final Map<String, Character> RARITY_PILL = Map.of(
            "common", '\uE100', "uncommon", '\uE101', "rare", '\uE102',
            "mythic", '\uE103', "legendary", '\uE104');
    /** Type pill band: catalyst is the 14th of TYPES. */
    private static final char CATALYST_PILL = '\uE11D';
    /** Action pills U+E1C0.., in gen-tooltip-assets.py's ACTION_PILLS order. */
    private static final Map<String, Character> ACTION_PILL = Map.of(
            "reroll", '\uE1C0', "swap", '\uE1C1', "promote", '\uE1C2',
            "add", '\uE1C3', "lock", '\uE1C4', "purge", '\uE1C5');
    private static final char RAIL_DESCRIPTION = '\uE164';
    private static final char RAIL_REQUIREMENTS = '\uE161';

    private static final int MIN_WIDTH = 163;
    private static final int PILL_GAP = 3;
    private static final int RAIL_GAP = 3;

    private static final Key FONT_BODY = Key.key("tower", "tooltip");
    private static final Key FONT_BIG = Key.key("tower", "big");

    private final TooltipConfig config;
    private final TooltipGlyphs glyphs;

    CatalystTooltip(JavaPlugin plugin, TooltipConfig config) {
        this.config = config;
        this.glyphs = new TooltipGlyphs(plugin);
    }

    boolean isCatalyst(String itemId) {
        return config.catalystForItemId(itemId) != null;
    }

    /** Rebuild page 1. The footer comes from the service, which owns paging. */
    void reflow(ItemStack stack, String itemId, Component footer) {
        TooltipConfig.Catalyst entry = config.catalystForItemId(itemId);
        if (entry == null) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        glyphs.refresh();

        List<Component> lore = layout(entry, footer);
        meta.lore(lore);
        stack.setItemMeta(meta);
    }

    private List<Component> layout(TooltipConfig.Catalyst entry, Component footer) {
        List<String> body = entry.body().isEmpty() ? List.of() : entry.body();

        // Rows are laid out before they are sized: a bar has to span the widest
        // content line, which isn't known until every line exists.
        List<Object> rows = new ArrayList<>();
        rows.add(badgeRow(entry));
        if (!entry.headline().isBlank()) {
            rows.add("");
            rows.add(new Big("&f" + entry.headline()));
        }
        if (!body.isEmpty()) {
            rows.add("");
            rows.add(TooltipGlyphs.Bar.DESCRIPTION);
            for (String line : body) {
                rows.add(railed(RAIL_DESCRIPTION, line));
            }
        }
        if (!entry.accepts().isBlank()) {
            rows.add("");
            rows.add(TooltipGlyphs.Bar.REQUIREMENTS);
            rows.add(railed(RAIL_REQUIREMENTS,
                    "&f" + entry.accepts() + " &8· &7consumed on use"));
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

    /** Rarity, CATALYST, then one pill per thing the stone does to the gear. */
    private String badgeRow(TooltipConfig.Catalyst entry) {
        List<Character> pills = new ArrayList<>();
        Character rarity = RARITY_PILL.get(entry.rarity() == null ? ""
                : entry.rarity().toLowerCase(Locale.ROOT));
        pills.add(rarity == null ? RARITY_PILL.get("common") : rarity);
        pills.add(CATALYST_PILL);
        for (String action : entry.actions()) {
            Character pill = ACTION_PILL.get(action.toLowerCase(Locale.ROOT));
            if (pill != null) pills.add(pill);
        }

        StringBuilder sb = new StringBuilder("&f");
        for (int i = 0; i < pills.size(); i++) {
            if (i > 0) sb.append(TooltipGlyphs.pad(PILL_GAP));
            sb.append(pills.get(i));
        }
        return sb.toString();
    }

    private String railed(char rail, String line) {
        return "&f" + rail + TooltipGlyphs.pad(RAIL_GAP) + line;
    }

    /** Big-font headline; a marker so sizing can use the wider advance. */
    private record Big(String text) { }

    private static Component plain(Component c) {
        return c.style(s -> s.shadowColor(ShadowColor.none()));
    }
}
