package com.example.custominventoryplugin.compendium;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One bestiary entry. Kills for every id in {@link #mythicIds} and
 * {@link #eliteIds} are stored separately in MariaDB and summed for unlocks
 * / progress; elite kills are also shown as their own sub-stat.
 */
public final class BestiaryEntry {

    private final String id;
    private final int floor;
    private final String family;
    private final String display;
    private final Material icon;
    private final List<String> mythicIds;
    private final List<String> eliteIds;
    private final String role;
    private final List<String> resists;
    private final List<String> weaknesses;
    private final List<String> locations;
    private final List<String> lore;
    private final List<String> drops;
    private final boolean boss;

    public BestiaryEntry(String id, int floor, String family, String display, Material icon,
                         List<String> mythicIds, List<String> eliteIds, String role,
                         List<String> resists, List<String> weaknesses,
                         List<String> locations, List<String> lore, List<String> drops, boolean boss) {
        this.id = id;
        this.floor = floor;
        this.family = family == null ? "" : family;
        this.display = display;
        this.icon = icon == null ? Material.PAPER : icon;
        this.mythicIds = List.copyOf(mythicIds == null ? List.of() : mythicIds);
        this.eliteIds = List.copyOf(eliteIds == null ? List.of() : eliteIds);
        this.role = role == null ? "" : role;
        this.resists = List.copyOf(resists == null ? List.of() : resists);
        this.weaknesses = List.copyOf(weaknesses == null ? List.of() : weaknesses);
        this.locations = List.copyOf(locations == null ? List.of() : locations);
        this.lore = List.copyOf(lore == null ? List.of() : lore);
        this.drops = List.copyOf(drops == null ? List.of() : drops);
        this.boss = boss;
    }

    public String getId() { return id; }
    public int getFloor() { return floor; }
    public String getFamily() { return family; }
    public String getDisplay() { return display; }
    public Material getIcon() { return icon; }
    public List<String> getMythicIds() { return mythicIds; }
    public List<String> getEliteIds() { return eliteIds; }
    public String getRole() { return role; }
    public List<String> getResists() { return resists; }
    public List<String> getWeaknesses() { return weaknesses; }
    public List<String> getLocations() { return locations; }
    public List<String> getLore() { return lore; }
    public List<String> getDrops() { return drops; }
    public boolean isBoss() { return boss; }

    /** Every mythic id that feeds this entry (base + elite). */
    public List<String> allTrackedIds() {
        List<String> all = new ArrayList<>(mythicIds.size() + eliteIds.size());
        all.addAll(mythicIds);
        all.addAll(eliteIds);
        return Collections.unmodifiableList(all);
    }
}
