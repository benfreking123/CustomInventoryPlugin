package com.example.custominventoryplugin.inventory;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tab strip for the BetterHud stats panel, living in the Gear Menu's top-right
 * corner.
 *
 * Why this is here at all: BetterHud draws overlays into the client's HUD layer,
 * which cannot receive clicks. The stats panel therefore needs its switching UI
 * to live in something that *is* clickable, and the Gear Menu is already open
 * beside it. So the four buttons below are the panel's only controls.
 *
 * How the two halves talk: this class is the single source of truth for which
 * tab a player is on, and it publishes that as {@code %customip_stats_tab%}
 * (see {@code placeholders/BackpackPlaceholders}). Every stats HUD carries a
 * HUD-level {@code conditions:} block testing that placeholder, so switching
 * tabs is just a value change — no HUD is added or removed on a tab switch, and
 * nothing flickers. HUDs are attached once when the player first opens a tab and
 * detached when they hide the panel or close the menu.
 *
 * State is deliberately in-memory only. The panel is a companion to an open GUI,
 * so "which tab was I on last Tuesday" is not worth a database column; a fresh
 * login starts at {@link #OFF}, which is also what Ben asked for — the panel
 * should appear only once a button is actually clicked.
 */
public final class StatsPanel {

    /** PDC key tagging a tab button with its tab id. */
    public static final String TAB_BUTTON_KEY = "ci_stats_tab";

    // Top-right of row 0. Slots 5-8 were decorative frame cells plus the two
    // backpack buttons; the backpacks moved down to 16/17 in backpacks.yml so
    // this row could become the tab strip.
    public static final int SLOT_OFFENSE = 5;
    public static final int SLOT_DEFENSE = 6;
    public static final int SLOT_UTILITY = 7;
    public static final int SLOT_HIDE    = 8;

    /** Tab ids. These strings ARE the placeholder values the HUDs match on. */
    public static final String OFFENSE = "offense";
    public static final String DEFENSE = "defense";
    public static final String UTILITY = "utility";
    public static final String OFF     = "off";

    /**
     * HUDs that make up the panel. `stats_frame` is the chrome and the shared
     * header (level + primaries) and shows for any tab; the other three are the
     * bodies and show one at a time.
     */
    private static final List<String> HUDS =
            List.of("stats_frame", "stats_offense", "stats_defense", "stats_utility");

    private static final Map<UUID, String> TAB = new ConcurrentHashMap<>();
    /** Who currently has the HUDs attached, so we don't spam console commands. */
    private static final Map<UUID, Boolean> ATTACHED = new ConcurrentHashMap<>();

    private StatsPanel() { }

    /** Current tab for a player; {@link #OFF} when the panel is hidden. */
    public static String tab(UUID uuid) {
        return TAB.getOrDefault(uuid, OFF);
    }

    /** Maps a Gear Menu slot to the tab it selects, or null if not a tab slot. */
    public static String tabAtSlot(int slot) {
        return switch (slot) {
            case SLOT_OFFENSE -> OFFENSE;
            case SLOT_DEFENSE -> DEFENSE;
            case SLOT_UTILITY -> UTILITY;
            case SLOT_HIDE    -> OFF;
            default           -> null;
        };
    }

    public static boolean isTab(String id) {
        if (id == null) return false;
        String s = id.toLowerCase(Locale.ROOT);
        return s.equals(OFFENSE) || s.equals(DEFENSE) || s.equals(UTILITY) || s.equals(OFF);
    }

    /**
     * Clicking the tab you are already on hides the panel, so one button both
     * opens and closes it. Only for the GUI buttons — an explicit
     * {@code /ci stats offense} should mean "show offense", not "show offense
     * unless it was already showing", so commands call {@link #select} instead.
     */
    public static void toggleTo(CustomInventoryPlugin plugin, Player player, String requested) {
        String want = requested.toLowerCase(Locale.ROOT);
        select(plugin, player, want.equals(tab(player.getUniqueId())) ? OFF : want);
    }

    /** Sets a tab, or hides the panel with {@link #OFF}, and syncs BetterHud. */
    public static void select(CustomInventoryPlugin plugin, Player player, String requested) {
        UUID uuid = player.getUniqueId();
        String want = requested.toLowerCase(Locale.ROOT);

        if (want.equals(OFF)) {
            TAB.remove(uuid);
            detach(plugin, player);
        } else {
            TAB.put(uuid, want);
            attach(plugin, player);
        }
    }

    /** Hides the panel and drops the HUDs. Called on menu close and quit. */
    public static void clear(CustomInventoryPlugin plugin, Player player) {
        TAB.remove(player.getUniqueId());
        detach(plugin, player);
    }

    /** Drops all memory of a player, for quit handling. */
    public static void forget(UUID uuid) {
        TAB.remove(uuid);
        ATTACHED.remove(uuid);
    }

    /**
     * Forces the panel off at the start of a session.
     *
     * BetterHud persists per-player HUD assignments in its {@code .users/}
     * folder, so a player who quits with the panel up would have it re-applied
     * on login — i.e. showing without anyone clicking a button, which is exactly
     * what we promised wouldn't happen. Removing on quit alone is not enough:
     * that races BetterHud's own save on disconnect. So we also clear on join,
     * where nothing is racing us. Unconditional by design — it ignores the
     * ATTACHED bookkeeping because the whole point is to undo state this server
     * process never recorded.
     */
    public static void resetOnJoin(CustomInventoryPlugin plugin, Player player) {
        TAB.remove(player.getUniqueId());
        ATTACHED.remove(player.getUniqueId());
        hud(plugin, player, "remove");
    }

    private static void attach(CustomInventoryPlugin plugin, Player player) {
        if (Boolean.TRUE.equals(ATTACHED.get(player.getUniqueId()))) return;
        if (!hud(plugin, player, "add")) return;
        ATTACHED.put(player.getUniqueId(), Boolean.TRUE);
    }

    private static void detach(CustomInventoryPlugin plugin, Player player) {
        if (!Boolean.TRUE.equals(ATTACHED.remove(player.getUniqueId()))) return;
        hud(plugin, player, "remove");
    }

    /**
     * Runs `betterhud hud <add|remove> <player> <hud>` for each panel HUD.
     * BetterHud is a soft, optional relationship — the Gear Menu has to keep
     * working on a backend where it isn't installed, so a missing plugin just
     * means the buttons do nothing visible rather than throwing.
     */
    private static boolean hud(CustomInventoryPlugin plugin, Player player, String action) {
        if (plugin.getServer().getPluginManager().getPlugin("BetterHud") == null) return false;
        for (String h : HUDS) {
            plugin.getServer().dispatchCommand(
                    plugin.getServer().getConsoleSender(),
                    "betterhud hud " + action + " " + player.getName() + " " + h);
        }
        return true;
    }

    /**
     * Paints the four buttons into an open Gear Menu. The active tab gets the
     * enchant glint so the current page is obvious without a lore line.
     */
    public static void decorate(Inventory inv, Player player, org.bukkit.NamespacedKey key) {
        String active = tab(player.getUniqueId());
        inv.setItem(SLOT_OFFENSE, button(Material.DIAMOND_SWORD, "\u00a7cOffense",
                OFFENSE, active, key, "Damage, crit and effect scaling."));
        inv.setItem(SLOT_DEFENSE, button(Material.SHIELD, "\u00a7bDefense",
                DEFENSE, active, key, "Armor, resistances and resources."));
        inv.setItem(SLOT_UTILITY, button(Material.SPYGLASS, "\u00a7aUtility",
                UTILITY, active, key, "Speed, range, luck and gathering."));

        boolean shown = !active.equals(OFF);
        inv.setItem(SLOT_HIDE, button(
                shown ? Material.LIME_DYE : Material.GRAY_DYE,
                shown ? "\u00a77Hide stats panel" : "\u00a78Stats panel hidden",
                OFF, "\u0000", key,
                shown ? "Click to hide the overlay." : "Pick a tab above to show it."));
    }

    private static ItemStack button(Material mat, String name, String tabId,
                                    String activeTab, org.bukkit.NamespacedKey key,
                                    String blurb) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(List.of("\u00a77" + blurb));
            if (tabId.equals(activeTab)) {
                // Paper 1.20.5+: glint without a real enchantment, so the button
                // can't pick up an "Unbreaking I" line or be mistaken for gear.
                meta.setEnchantmentGlintOverride(Boolean.TRUE);
            }
            meta.getPersistentDataContainer().set(
                    key, org.bukkit.persistence.PersistentDataType.STRING, tabId);
            it.setItemMeta(meta);
        }
        return it;
    }
}
