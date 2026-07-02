package com.example.custominventoryplugin.groupdrop;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A "group drop": a choose-your-reward definition. Players are shown the
 * options and may pick {@code picks} of them (default 1).
 *
 * Options are keyed by their slot (0..44), which is also where they render in
 * the chooser. The chooser auto-sizes to the highest occupied slot.
 */
public class GroupDrop {

    public enum ClaimMode {
        /** Each player may claim up to {@code picks} total, ever (DB-tracked). */
        ONCE,
        /** No claim tracking — re-openable any time (gated by command perm / token). */
        REPEATABLE;

        public static ClaimMode from(String s) {
            if (s == null) return ONCE;
            try { return ClaimMode.valueOf(s.trim().toUpperCase()); }
            catch (IllegalArgumentException e) { return ONCE; }
        }
    }

    private final String id;
    private String title = "";
    private int picks = 1;
    private boolean distinct = true;
    private ClaimMode claimMode = ClaimMode.ONCE;
    private String permission = "";
    private boolean tokenEnabled = false;
    private ItemStack tokenIcon;
    /** slot (0..44) → option */
    private final Map<Integer, GroupDropOption> options = new LinkedHashMap<>();

    public GroupDrop(String id) {
        this.id = id.toLowerCase();
    }

    public String getId() { return id; }

    public String getTitle() {
        return (title == null || title.isEmpty()) ? ("&8Choose your reward") : title;
    }
    public String getRawTitle()                  { return title == null ? "" : title; }
    public void setTitle(String t)               { this.title = t == null ? "" : t; }

    public int getPicks()                        { return picks; }
    public void setPicks(int p)                  { this.picks = Math.max(1, p); }

    public boolean isDistinct()                  { return distinct; }
    public void setDistinct(boolean d)           { this.distinct = d; }

    public ClaimMode getClaimMode()              { return claimMode; }
    public void setClaimMode(ClaimMode c)        { this.claimMode = c == null ? ClaimMode.ONCE : c; }

    public String getPermission()                { return permission == null ? "" : permission; }
    public void setPermission(String p)          { this.permission = p == null ? "" : p.trim(); }

    public boolean isTokenEnabled()              { return tokenEnabled; }
    public void setTokenEnabled(boolean t)       { this.tokenEnabled = t; }

    public ItemStack getTokenIcon()              { return tokenIcon; }
    public void setTokenIcon(ItemStack i)        { this.tokenIcon = i; }

    public Map<Integer, GroupDropOption> getOptions() { return options; }
    public GroupDropOption getOption(int slot)   { return options.get(slot); }
    public int optionCount()                     { return options.size(); }

    /** Clamp configured picks to the number of options so it's always satisfiable. */
    public int effectivePicks() {
        return Math.min(picks, Math.max(1, options.size()));
    }

    /** Chooser inventory size: smallest multiple of 9 covering the top option slot (max 5 rows). */
    public int chooserSize() {
        int max = 0;
        for (int slot : options.keySet()) max = Math.max(max, slot);
        int rows = (max / 9) + 1;
        if (rows < 1) rows = 1;
        if (rows > 5) rows = 5;
        return rows * 9;
    }

    public boolean canOpen(Player p) {
        return getPermission().isEmpty() || (p != null && p.hasPermission(getPermission()));
    }
}
