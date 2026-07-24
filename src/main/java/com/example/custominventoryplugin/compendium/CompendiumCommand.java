package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /compendium} — opens the player's Compendium (Account landing page).
 *
 * v1 has a single page; future tabs (Bestiary, Collections) will hang off the
 * nav row inside the GUI rather than as sub-commands, so this stays minimal.
 */
public class CompendiumCommand implements CommandExecutor {

    private final CustomInventoryPlugin plugin;

    public CompendiumCommand(CustomInventoryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("\u00a7cThis command can only be used by players.");
            return true;
        }
        CompendiumInventory gui = new CompendiumInventory(plugin, player);
        player.openInventory(gui.getInventory());
        return true;
    }
}
