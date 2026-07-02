package com.example.custominventoryplugin.commands;

import com.example.custominventoryplugin.config.ConfigManager;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

public class DebugCommand
implements CommandExecutor,
TabCompleter {
    private final ConfigManager configManager;

    public DebugCommand(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("custominventory.debug")) {
            sender.sendMessage("\u00a7cYou don't have permission to use this command!");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("\u00a7cUsage: /debug <toggle|reload>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "toggle": {
                boolean newState = !this.configManager.isDebugEnabled();
                this.configManager.setDebugEnabled(newState);
                sender.sendMessage("\u00a7aDebug mode " + (newState ? "enabled" : "disabled"));
                break;
            }
            case "reload": {
                this.configManager.reloadConfig();
                sender.sendMessage("\u00a7aConfiguration reloaded");
                break;
            }
            default: {
                sender.sendMessage("\u00a7cUnknown subcommand. Use: toggle or reload");
            }
        }
        return true;
    }

    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        ArrayList<String> completions = new ArrayList<String>();
        if (args.length == 1) {
            completions.add("toggle");
            completions.add("reload");
        }
        return completions;
    }
}

