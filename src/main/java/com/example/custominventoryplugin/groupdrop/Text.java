package com.example.custominventoryplugin.groupdrop;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Small text helpers for legacy-ampersand colored components (italic off for lore). */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** Nexo fonts (see Nexo-shared/settings.yml): default = glyphs, shift = spacing. */
    private static final Key NEXO_DEFAULT = Key.key("nexo", "default");
    private static final Key NEXO_SHIFT = Key.key("nexo", "shift");

    /** Nexo shift-font negative-space chars (from assets/nexo/font/shift.json). */
    private static final char SHIFT_NEG_1 = '\uE101';   // -1
    private static final char SHIFT_NEG_2 = '\uE102';   // -2
    private static final char SHIFT_NEG_4 = '\uE103';   // -4
    private static final char SHIFT_NEG_8 = '\uE104';   // -8
    private static final char SHIFT_NEG_16 = '\uE105';  // -16
    private static final char SHIFT_NEG_32 = '\uE106';  // -32
    private static final char SHIFT_NEG_64 = '\uE107';  // -64
    private static final char SHIFT_NEG_128 = '\uE108'; // -128
    private static final char SHIFT_NEG_256 = '\uE109'; // -256

    private Text() {}

    /** Colored component with default-italic disabled (clean item names/lore). */
    public static Component c(String s) {
        return LEGACY.deserialize(s == null ? "" : s).decoration(TextDecoration.ITALIC, false);
    }

    /** Title component (keeps whatever formatting the string specifies). */
    public static Component title(String s) {
        return LEGACY.deserialize(s == null ? "" : s);
    }

    /**
     * Chest-GUI title that renders a Nexo background glyph. Emits
     * {@code <shift:shiftLeft><glyph>} using the nexo:shift + nexo:default
     * fonts (what Nexo's own inventory titles do internally). Tune the
     * vertical position with the glyph's {@code ascent} in interface.yml and
     * the horizontal position with {@code shiftLeft} here.
     */
    public static Component nexoBackground(char glyphChar, int shiftLeft) {
        // WHITE is essential: container titles default to dark-gray (#404040),
        // and bitmap glyphs are multiplied by the text colour — without this the
        // background renders at ~25% brightness (charcoal instead of the art).
        Component out = Component.empty();
        if (shiftLeft != 0) {
            out = out.append(Component.text(negativeSpace(shiftLeft))
                    .font(NEXO_SHIFT).color(NamedTextColor.WHITE));
        }
        out = out.append(Component.text(String.valueOf(glyphChar))
                .font(NEXO_DEFAULT).color(NamedTextColor.WHITE));
        return out;
    }

    /** Build a string of nexo:shift negative-space chars summing to -pixels. */
    private static String negativeSpace(int pixels) {
        int n = Math.abs(pixels);
        StringBuilder sb = new StringBuilder();
        while (n >= 256) { sb.append(SHIFT_NEG_256); n -= 256; }
        if (n >= 128) { sb.append(SHIFT_NEG_128); n -= 128; }
        if (n >= 64) { sb.append(SHIFT_NEG_64); n -= 64; }
        if (n >= 32) { sb.append(SHIFT_NEG_32); n -= 32; }
        if (n >= 16) { sb.append(SHIFT_NEG_16); n -= 16; }
        if (n >= 8) { sb.append(SHIFT_NEG_8); n -= 8; }
        if (n >= 4) { sb.append(SHIFT_NEG_4); n -= 4; }
        if (n >= 2) { sb.append(SHIFT_NEG_2); n -= 2; }
        if (n >= 1) { sb.append(SHIFT_NEG_1); n -= 1; }
        return sb.toString();
    }
}
