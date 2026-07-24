package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Console hook for the generic compendium counters:
 *   /cipcount <player|uuid> <key> [amount]
 *
 * Called by Skript (charged crystals, checkpoints) and MythicDungeons
 * FunctionCommands (dungeon runs). Writes are async; fire-and-forget.
 */
public class CipCountCommand implements CommandExecutor {

    private final CustomInventoryPlugin plugin;
    private final CounterData counters;

    public CipCountCommand(CustomInventoryPlugin plugin, CounterData counters) {
        this.plugin = plugin;
        this.counters = counters;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /cipcount <player|uuid> <key> [amount]");
            return true;
        }

        UUID uuid = resolveUuid(args[0]);
        if (uuid == null) {
            sender.sendMessage("cipcount: unknown player '" + args[0] + "'");
            return true;
        }

        String key = args[1].toLowerCase();
        int amount = 1;
        if (args.length >= 3) {
            try { amount = Integer.parseInt(args[2]); }
            catch (NumberFormatException e) {
                sender.sendMessage("cipcount: bad amount '" + args[2] + "'");
                return true;
            }
        }

        final int amt = amount;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> counters.increment(uuid, key, amt));
        return true;
    }

    private UUID resolveUuid(String arg) {
        try {
            return UUID.fromString(arg);
        } catch (IllegalArgumentException ignored) {
        }
        Player online = Bukkit.getPlayerExact(arg);
        return online != null ? online.getUniqueId() : null;
    }
}
