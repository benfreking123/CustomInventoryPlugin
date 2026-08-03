package com.example.custominventoryplugin.compendium;

import org.bukkit.Material;

/**
 * One collectable Divinity custom item (a gem or a unique). Armour sets are
 * generated per-roll and carry no stable item id, so they are not entries —
 * they live in {@link CollectionsConfig.SetEntry} and are matched through
 * Divinity's SetManager instead.
 */
public final class CollectionEntry {

    private final String id;
    private final String category;
    private final String display;
    private final Material icon;
    private final String rarity;

    CollectionEntry(String id, String category, String display, Material icon, String rarity) {
        this.id = id;
        this.category = category;
        this.display = display;
        this.icon = icon;
        this.rarity = rarity;
    }

    public String getId() { return id; }
    public String getCategory() { return category; }
    public String getDisplay() { return display; }
    public Material getIcon() { return icon; }
    public String getRarity() { return rarity; }
}
