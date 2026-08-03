package com.example.custominventoryplugin.compendium;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.Locale;

/** Icon specs shared by the Compendium GUIs: a Material name or {@code nexo:<id>}. */
public final class Icons {

    private Icons() { }

    /** Resolve a spec to a stack, falling back to {@code fallback} when unknown. */
    public static ItemStack build(String spec, Material fallback) {
        if (spec == null || spec.isBlank()) return new ItemStack(fallback);
        String s = spec.trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("nexo:")) {
            ItemStack nx = nexo(s.substring(5).trim());
            return nx != null ? nx : new ItemStack(fallback);
        }
        try {
            return new ItemStack(Material.valueOf(s.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return new ItemStack(fallback);
        }
    }

    /** Nexo item by id via reflection (no hard dependency), or null if absent. */
    public static ItemStack nexo(String id) {
        try {
            Class<?> nexoItems = Class.forName("com.nexomc.nexo.api.NexoItems");
            Method itemFromId = nexoItems.getMethod("itemFromId", String.class);
            Object builder = itemFromId.invoke(null, id);
            if (builder == null) return null;
            Object built = builder.getClass().getMethod("build").invoke(builder);
            if (built instanceof ItemStack is) return is.clone();
        } catch (Throwable ignored) {
            // Nexo missing or id unknown → caller falls back
        }
        return null;
    }
}
