package com.example.custominventoryplugin.inventory;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.settings.BagSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * InventoryHolder for an open backpack. Listeners use {@code instanceof
 * BackpackInventory} to detect backpack windows and {@link #getDef()} to
 * know which backpack the player is interacting with.
 *
 * The window is split into a <strong>storage region</strong> {@code [0,
 * storageSize)} and a <strong>control bar</strong> {@code [storageSize,
 * windowSize)} (the bottom 9 slots) for bags whose window is at least 18.
 * Only the storage region is ever persisted (see
 * {@link com.example.custominventoryplugin.data.BackpackData#saveAll}).
 */
public class BackpackInventory implements InventoryHolder {

    private final Player player;
    private final BackpackDef def;
    private final Inventory inventory;
    private final int windowSize;
    private final int storageSize;

    public BackpackInventory(Player player, BackpackDef def, int windowSize, ItemStack[] initialContents) {
        this.player = player;
        this.def = def;
        this.windowSize = windowSize;
        this.storageSize = storageSize(windowSize);

        Component title = LegacyComponentSerializer.legacyAmpersand().deserialize(def.getDisplayName());
        this.inventory = Bukkit.createInventory(this, windowSize, title);

        if (initialContents != null) {
            int copyLen = Math.min(initialContents.length, storageSize);
            for (int i = 0; i < copyLen; i++) {
                inventory.setItem(i, initialContents[i]);
            }
        }
    }

    /** Usable storage slots for a given window size (bottom 9 reserved as a control bar when >= 18). */
    public static int storageSize(int windowSize) {
        return windowSize >= 18 ? windowSize - 9 : windowSize;
    }

    public Player      getPlayer()       { return player; }
    public BackpackDef getDef()          { return def; }
    public int         getWindowSize()   { return windowSize; }
    public int         getStorageSize()  { return storageSize; }
    public boolean     hasControlBar()   { return windowSize >= 18; }

    @Override @NotNull
    public Inventory getInventory() { return inventory; }

    /**
     * Central entry point for opening a backpack for a player. Resolves the
     * player's tier → window size, loads storage from the DB, renders the
     * control bar, and opens the window.
     */
    public static void open(CustomInventoryPlugin plugin, Player player, BackpackDef def) {
        BagSettings settings = plugin.getSettingsCache().bag(player.getUniqueId(), def);
        int windowSize = def.windowSize(settings.getTier());
        int storage = storageSize(windowSize);

        ItemStack[] contents = plugin.getBackpackData().load(player.getUniqueId(), def.getId(), storage);
        BackpackInventory bp = new BackpackInventory(player, def, windowSize, contents);
        if (bp.hasControlBar()) {
            BackpackControlBar.render(plugin, bp.getInventory(), def, settings);
        }
        player.openInventory(bp.getInventory());
    }
}
