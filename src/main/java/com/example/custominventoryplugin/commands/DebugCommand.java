package com.example.custominventoryplugin.commands;

import com.example.custominventoryplugin.config.ConfigManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class DebugCommand implements CommandExecutor, TabCompleter {
    private final ConfigManager configManager;

    public DebugCommand(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("custominventory.debug")) {
            sender.sendMessage("§cYou don't have permission to use this command!");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§cUsage: /debug <toggle|reload>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "toggle":
                boolean newState = !configManager.isDebugEnabled();
                configManager.setDebugEnabled(newState);
                sender.sendMessage("§aDebug mode " + (newState ? "enabled" : "disabled"));
                break;

            case "reload":
                configManager.reloadConfig();
                sender.sendMessage("§aConfiguration reloaded");
                break;

            default:
                sender.sendMessage("§cUnknown subcommand. Use: toggle or reload");
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.add("toggle");
            completions.add("reload");
        }

        return completions;
    }
} 