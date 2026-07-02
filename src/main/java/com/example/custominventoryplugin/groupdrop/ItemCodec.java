package com.example.custominventoryplugin.groupdrop;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/**
 * Shared ItemStack ⇄ Base64 codec for group-drop persistence. Mirrors the
 * scheme used by {@code BackpackData}/{@code PlayerGearData}
 * ({@link ItemStack#serializeAsBytes()} wrapped in Base64) so blobs are
 * interchangeable across the plugin's storage.
 */
public final class ItemCodec {

    private ItemCodec() {}

    public static String encode(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try {
            return Base64.getEncoder().encodeToString(item.serializeAsBytes());
        } catch (Exception e) {
            return null;
        }
    }

    public static ItemStack decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (Exception e) {
            return null;
        }
    }
}
