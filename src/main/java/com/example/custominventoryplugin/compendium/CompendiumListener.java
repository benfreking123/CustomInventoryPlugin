package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;

/**
 * Handles the read-only Compendium GUIs (Account, Bestiary list, Bestiary
 * detail): cancels every click/drag and routes nav buttons by PDC action.
 */
public class CompendiumListener implements Listener {

    private final CustomInventoryPlugin plugin;
    private final NamespacedKey accountKey;
    private final NamespacedKey commandKey;
    private final NamespacedKey commandRightKey;
    private final NamespacedKey bestiaryKey;
    private final NamespacedKey harvestKey;

    public CompendiumListener(CustomInventoryPlugin plugin) {
        this.plugin = plugin;
        this.accountKey = new NamespacedKey(plugin, CompendiumInventory.ACTION_KEY);
        this.commandKey = new NamespacedKey(plugin, CompendiumInventory.COMMAND_KEY);
        this.commandRightKey = new NamespacedKey(plugin, CompendiumInventory.COMMAND_RIGHT_KEY);
        this.bestiaryKey = new NamespacedKey(plugin, BestiaryInventory.ACTION_KEY);
        this.harvestKey = new NamespacedKey(plugin, HarvestInventory.ACTION_KEY);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Object holder = event.getInventory().getHolder();
        if (!(holder instanceof CompendiumInventory)
                && !(holder instanceof BestiaryInventory)
                && !(holder instanceof BestiaryDetailInventory)
                && !(holder instanceof HarvestInventory)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        ItemStack clicked = event.getCurrentItem();
        ItemMeta meta = clicked == null ? null : clicked.getItemMeta();

        if (holder instanceof CompendiumInventory) {
            if (meta == null) return;
            String action = meta.getPersistentDataContainer().get(accountKey, PersistentDataType.STRING);
            if (action == null) return;
            switch (action) {
                case CompendiumInventory.ACT_CLOSE -> player.closeInventory();
                case CompendiumInventory.ACT_QUESTS -> {
                    player.closeInventory();
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> player.performCommand("myquest"));
                }
                case CompendiumInventory.ACT_BESTIARY -> openBestiary(player, BestiaryInventory.CAT_MOBS, null);
                case CompendiumInventory.ACT_HARVEST -> openHarvest(player);
                case CompendiumInventory.ACT_COLLECTIONS ->
                        player.sendMessage("\u00a77Collections aren't ready yet \u2014 coming soon.");
                case CompendiumInventory.ACT_COMMAND -> {
                    var pdc = meta.getPersistentDataContainer();
                    String cmd = pdc.get(commandKey, PersistentDataType.STRING);
                    String cmdRight = pdc.get(commandRightKey, PersistentDataType.STRING);
                    if (event.isRightClick() && cmdRight != null && !cmdRight.isEmpty()) {
                        cmd = cmdRight;
                    }
                    if (cmd != null && !cmd.isEmpty()) {
                        final String run = cmd;
                        player.closeInventory();
                        plugin.getServer().getScheduler().runTask(plugin,
                                () -> player.performCommand(run));
                    }
                }
                default -> { }
            }
            return;
        }

        if (holder instanceof HarvestInventory) {
            if (meta == null) return;
            String action = meta.getPersistentDataContainer().get(harvestKey, PersistentDataType.STRING);
            if (HarvestInventory.ACT_BACK.equals(action)) {
                openAccount(player);
            }
            return;
        }

        if (holder instanceof BestiaryInventory list) {
            // Decorative regions (tabs/arrows/close) are AIR — resolve by raw slot.
            String action = meta == null ? null
                    : meta.getPersistentDataContainer().get(bestiaryKey, PersistentDataType.STRING);
            if (action == null) action = list.actionAt(event.getRawSlot());
            if (action == null) return;

            if (action.equals(BestiaryInventory.ACT_ACCOUNT)) {
                openAccount(player);
            } else if (action.equals(BestiaryInventory.ACT_CLOSE)) {
                player.closeInventory();
            } else if (action.startsWith(BestiaryInventory.ACT_CAT)) {
                // In-place: repaint the open window (no reopen → no flicker).
                String cat = action.substring(BestiaryInventory.ACT_CAT.length());
                list.render(cat, null);
            } else if (action.equals(BestiaryInventory.ACT_PREV)) {
                list.render(list.getCategory(), adjacentFloor(list, -1));
            } else if (action.equals(BestiaryInventory.ACT_NEXT)) {
                list.render(list.getCategory(), adjacentFloor(list, +1));
            } else if (action.startsWith(BestiaryInventory.ACT_ENTRY)) {
                String id = action.substring(BestiaryInventory.ACT_ENTRY.length());
                openDetail(player, id, list.getCategory(), list.getFloor());
            }
            return;
        }

        if (holder instanceof BestiaryDetailInventory detail) {
            if (meta == null) return;
            String action = meta.getPersistentDataContainer().get(bestiaryKey, PersistentDataType.STRING);
            if (BestiaryInventory.ACT_BACK.equals(action)) {
                openBestiary(player, detail.getReturnCategory(), detail.getReturnFloor());
            }
        }
    }

    /** Neighbouring floor (by delta) within the list's current category, or its current floor. */
    private Integer adjacentFloor(BestiaryInventory list, int delta) {
        BestiaryConfig cfg = plugin.getBestiaryConfig();
        if (cfg == null) return list.getFloor();
        List<Integer> floors = BestiaryInventory.floorsForCategory(cfg, list.getCategory());
        int idx = floors.indexOf(list.getFloor());
        int next = idx + delta;
        if (idx < 0 || next < 0 || next >= floors.size()) return list.getFloor();
        return floors.get(next);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Object holder = event.getInventory().getHolder();
        if (holder instanceof CompendiumInventory
                || holder instanceof BestiaryInventory
                || holder instanceof BestiaryDetailInventory
                || holder instanceof HarvestInventory) {
            event.setCancelled(true);
        }
    }

    private void openHarvest(Player player) {
        player.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            HarvestInventory gui = new HarvestInventory(plugin, player);
            player.openInventory(gui.getInventory());
        });
    }

    private void openAccount(Player player) {
        player.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            CompendiumInventory gui = new CompendiumInventory(plugin, player);
            player.openInventory(gui.getInventory());
        });
    }

    /** Open the bestiary for a category; {@code floor == null} picks the first floor. */
    private void openBestiary(Player player, String category, Integer floor) {
        BestiaryConfig cfg = plugin.getBestiaryConfig();
        BestiaryData data = plugin.getBestiaryData();
        if (cfg == null || data == null || cfg.entries().isEmpty()) {
            player.sendMessage("\u00a77Bestiary has no entries loaded.");
            return;
        }
        List<Integer> floors = BestiaryInventory.floorsForCategory(cfg, category);
        if (floors.isEmpty()) {
            player.sendMessage("\u00a77Nothing to show in that category yet.");
            return;
        }
        final int f = (floor != null && floors.contains(floor)) ? floor : floors.get(0);
        player.closeInventory();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Integer> kills = data.loadAll(player.getUniqueId());
            Map<String, Integer> counters = plugin.getCounterData() != null
                    ? plugin.getCounterData().loadAll(player.getUniqueId()) : Map.of();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                BestiaryInventory gui = new BestiaryInventory(plugin, player, category, f, cfg,
                        kills, counters);
                player.openInventory(gui.getInventory());
            });
        });
    }

    private void openDetail(Player player, String entryId, String returnCategory, int returnFloor) {
        BestiaryConfig cfg = plugin.getBestiaryConfig();
        BestiaryData data = plugin.getBestiaryData();
        if (cfg == null || data == null) return;
        BestiaryEntry entry = cfg.get(entryId);
        if (entry == null) return;
        player.closeInventory();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Integer> kills = data.loadAll(player.getUniqueId());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                BestiaryDetailInventory gui = new BestiaryDetailInventory(
                        plugin, player, entry, cfg.thresholds(), kills, returnCategory, returnFloor,
                        cfg.revealPermission());
                player.openInventory(gui.getInventory());
            });
        });
    }
}
