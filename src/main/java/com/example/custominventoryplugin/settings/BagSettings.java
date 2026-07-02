package com.example.custominventoryplugin.settings;

import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.config.PickupMode;
import org.bukkit.Material;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-(player, backpack) pickup settings. Persisted in
 * {@code cip_player_backpack_settings}. An absent row falls back to the
 * backpack's configured defaults (see {@link #defaultsFor}).
 */
public final class BagSettings {

    private PickupMode mode;
    private final Set<Material> filter;   // empty = "everything"
    private boolean grabEverything;       // bag-specific ignore-filter
    private int tier;

    public BagSettings(PickupMode mode, Set<Material> filter, boolean grabEverything, int tier) {
        this.mode = mode == null ? PickupMode.OFF : mode;
        this.filter = filter == null ? new LinkedHashSet<>() : new LinkedHashSet<>(filter);
        this.grabEverything = grabEverything;
        this.tier = Math.max(0, tier);
    }

    public static BagSettings defaultsFor(BackpackDef def) {
        return new BagSettings(def.getDefaultMode(), def.getDefaultFilter(), false, 0);
    }

    public PickupMode getMode()        { return mode; }
    public Set<Material> getFilter()   { return filter; }
    public boolean isGrabEverything()  { return grabEverything; }
    public int getTier()               { return tier; }

    public void setMode(PickupMode m)          { this.mode = m == null ? PickupMode.OFF : m; }
    public void setGrabEverything(boolean v)   { this.grabEverything = v; }
    public void setTier(int t)                 { this.tier = Math.max(0, t); }

    public boolean accepts(Material m, boolean playerGrabEverything) {
        if (playerGrabEverything || grabEverything) return true;
        return filter.isEmpty() || filter.contains(m);
    }
}
