package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.groupdrop.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Harvested &amp; Collected page. Farming comes from RivalHarvesterHoes
 * (cross-server MySQL) and mining from RivalPickaxes (per-server SQLite for
 * now) — both read via their PlaceholderAPI expansions, so this page shows
 * whatever the local server knows.
 */
public class HarvestInventory implements InventoryHolder {

    public static final String ACTION_KEY = "cmp_harvest_action";
    public static final String ACT_BACK = "back";

    private final Player player;
    private final Inventory inventory;

    public HarvestInventory(CustomInventoryPlugin plugin, Player player) {
        this.player = player;
        this.inventory = Bukkit.createInventory(this, 27, Text.title("&a&lHarvested & Collected"));

        NamespacedKey actionKey = new NamespacedKey(plugin, ACTION_KEY);
        ItemStack filler = filler();
        for (int i = 0; i < 27; i++) inventory.setItem(i, filler);

        boolean papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        boolean hoes = Bukkit.getPluginManager().getPlugin("RivalHarvesterHoes") != null;
        boolean picks = Bukkit.getPluginManager().getPlugin("RivalPickaxes") != null;

        // ── Farming (RivalHarvesterHoes) ────────────────────────────────
        List<String> farm = new ArrayList<>();
        farm.add("&7Lifetime farming totals");
        farm.add("");
        if (papi && hoes) {
            farm.add("&6Wheat: &f" + papi(player, "%rivalhoes_wheat_formatted%"));
            farm.add("&ePotatoes: &f" + papi(player, "%rivalhoes_potato_formatted%"));
            farm.add("&dEssence bank: &f" + papi(player, "%rivalhoes_essence_formatted%"));
        } else {
            farm.add("&8Farming stats live on the farm server.");
            farm.add("&8Visit your fields to see them.");
        }
        inventory.setItem(11, build(Material.GOLDEN_HOE, "&a&lFarming", farm));

        // ── Mining (RivalPickaxes) ──────────────────────────────────────
        List<String> mine = new ArrayList<>();
        mine.add("&7Lifetime mining totals &8(this server)");
        mine.add("");
        if (papi && picks) {
            mine.add("&bBlocks mined: &f" + papi(player, "%rivalpickaxes_blocks_formatted%"));
            mine.add("&dEssence bank: &f" + papi(player, "%rivalpickaxes_essence_formatted%"));
        } else {
            mine.add("&8Mining stats live where the mines are.");
            mine.add("&8Swing a pickaxe there to see them.");
        }
        inventory.setItem(15, build(Material.IRON_PICKAXE, "&b&lMining", mine));

        // ── Back ────────────────────────────────────────────────────────
        ItemStack back = build(Material.ARROW, "&7\u2190 Back", List.of("&7Return to the Compendium."));
        ItemMeta bm = back.getItemMeta();
        if (bm != null) {
            bm.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, ACT_BACK);
            back.setItemMeta(bm);
        }
        inventory.setItem(22, back);
    }

    /** Resolve a PAPI placeholder; "—" if the expansion left it unresolved. */
    private static String papi(Player player, String placeholder) {
        try {
            String out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);
            if (out == null || out.isBlank() || out.contains("%")) return "\u2014";
            return out;
        } catch (Throwable t) {
            return "\u2014";
        }
    }

    private ItemStack build(Material mat, String name, List<String> loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Text.c(name));
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(Text.c(l));
            m.lore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack filler() {
        ItemStack it = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Component.text(" "));
            it.setItemMeta(m);
        }
        return it;
    }

    public Player getPlayer() { return player; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }
}
