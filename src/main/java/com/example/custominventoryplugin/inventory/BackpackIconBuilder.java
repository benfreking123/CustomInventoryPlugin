package com.example.custominventoryplugin.inventory;

import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.config.PickupMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for backpack icon rendering. Used by both the
 * /ci button injection ({@link GearInventory}) and the /bp list GUI
 * ({@link BackpackListInventory}) so both show the same tooltip layout.
 *
 * Tooltip layout:
 *   <bold display name>
 *   (blank)
 *   Capacity: 12/27 slots
 *   ███████░░░  44%
 *   (blank)
 *   Permission: customip.backpack.orebag      // or "Free for everyone"
 *   Smart pickup: Enabled                     // omitted if false
 *   (blank)
 *   Click to open
 */
public final class BackpackIconBuilder {

    /** Length of the capacity bar in characters. */
    private static final int BAR_LENGTH = 10;
    private static final char BAR_FILLED = '\u2588';   // █
    private static final char BAR_EMPTY  = '\u2591';   // ░

    private BackpackIconBuilder() {}

    public static ItemStack build(BackpackDef def, int used, NamespacedKey backpackButtonKey) {
        return build(def, used, BackpackInventory.storageSize(def.windowSize(0)),
                def.getDefaultMode(), 0, def.maxTier(), backpackButtonKey);
    }

    public static ItemStack build(BackpackDef def, int used, int capacity, PickupMode mode,
                                  int tier, int maxTier, NamespacedKey backpackButtonKey) {
        ItemStack icon = new ItemStack(def.getCiIcon());
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;

        // Title: parse legacy color codes from the configured display name,
        // strip italics so it doesn't render in the typical "renamed item" italic.
        Component title = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(def.getDisplayName())
                .decoration(TextDecoration.ITALIC, false);
        meta.displayName(title);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());

        int size = capacity;
        int safeUsed = Math.max(0, Math.min(used, size));
        int pct = size == 0 ? 0 : (int) Math.round(100.0 * safeUsed / size);
        int filled = size == 0 ? 0 : (int) Math.round((double) safeUsed / size * BAR_LENGTH);
        filled = Math.max(0, Math.min(BAR_LENGTH, filled));

        lore.add(plain("Capacity: ", NamedTextColor.GRAY)
                .append(plain(safeUsed + "/" + size, NamedTextColor.YELLOW))
                .append(plain(" slots", NamedTextColor.GRAY)));

        StringBuilder bar = new StringBuilder(BAR_LENGTH);
        for (int i = 0; i < filled; i++) bar.append(BAR_FILLED);
        StringBuilder rest = new StringBuilder(BAR_LENGTH - filled);
        for (int i = 0; i < BAR_LENGTH - filled; i++) rest.append(BAR_EMPTY);

        lore.add(plain(bar.toString(), barColor(pct))
                .append(plain(rest.toString(), NamedTextColor.DARK_GRAY))
                .append(plain("  " + pct + "%", NamedTextColor.GRAY)));

        lore.add(Component.empty());

        if (def.isFree()) {
            lore.add(plain("Permission: ", NamedTextColor.GRAY)
                    .append(plain("Free for everyone", NamedTextColor.GREEN)));
        } else {
            lore.add(plain("Permission: ", NamedTextColor.GRAY)
                    .append(plain(def.getPermission(), NamedTextColor.WHITE)));
        }
        lore.add(plain("Pickup: ", NamedTextColor.GRAY)
                .append(plain(modeLabel(mode), modeColor(mode))));
        if (maxTier > 0) {
            lore.add(plain("Tier: ", NamedTextColor.GRAY)
                    .append(plain(tier + "/" + maxTier, NamedTextColor.AQUA)));
        }
        lore.add(Component.empty());

        lore.add(plain("Click to open", NamedTextColor.AQUA));

        meta.lore(lore);

        // PDC marker so the click handler can resolve back to the backpack id.
        if (backpackButtonKey != null) {
            meta.getPersistentDataContainer().set(backpackButtonKey, PersistentDataType.STRING, def.getId());
        }

        icon.setItemMeta(meta);
        return icon;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }

    private static String modeLabel(PickupMode mode) {
        return switch (mode) {
            case OFF -> "Off";
            case MATCH -> "Match existing";
            case OVERFLOW -> "Overflow";
            case BAG_FIRST -> "Bag first";
        };
    }

    private static NamedTextColor modeColor(PickupMode mode) {
        return mode == PickupMode.OFF ? NamedTextColor.RED : NamedTextColor.GREEN;
    }

    /** Bar color: green at low fill, yellow medium, red when nearly full. */
    private static NamedTextColor barColor(int pct) {
        if (pct >= 90) return NamedTextColor.RED;
        if (pct >= 70) return NamedTextColor.GOLD;
        if (pct >= 40) return NamedTextColor.YELLOW;
        return NamedTextColor.GREEN;
    }
}
