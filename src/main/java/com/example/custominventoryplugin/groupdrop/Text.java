package com.example.custominventoryplugin.groupdrop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Small text helpers for legacy-ampersand colored components (italic off for lore). */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {}

    /** Colored component with default-italic disabled (clean item names/lore). */
    public static Component c(String s) {
        return LEGACY.deserialize(s == null ? "" : s).decoration(TextDecoration.ITALIC, false);
    }

    /** Title component (keeps whatever formatting the string specifies). */
    public static Component title(String s) {
        return LEGACY.deserialize(s == null ? "" : s);
    }
}
