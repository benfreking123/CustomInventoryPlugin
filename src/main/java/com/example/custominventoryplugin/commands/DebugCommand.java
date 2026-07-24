package com.example.custominventoryplugin.commands;

import com.example.custominventoryplugin.autoloot.AutoLootConfig;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.tooltip.TooltipConfig;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DebugCommand
implements CommandExecutor,
TabCompleter {
    private final ConfigManager configManager;
    private final TooltipConfig tooltipConfig;
    private final AutoLootConfig autoLootConfig;

    public DebugCommand(ConfigManager configManager) {
        this(configManager, null, null);
    }

    public DebugCommand(ConfigManager configManager, @Nullable TooltipConfig tooltipConfig) {
        this(configManager, tooltipConfig, null);
    }

    public DebugCommand(ConfigManager configManager, @Nullable TooltipConfig tooltipConfig,
                        @Nullable AutoLootConfig autoLootConfig) {
        this.configManager = configManager;
        this.tooltipConfig = tooltipConfig;
        this.autoLootConfig = autoLootConfig;
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
                if (this.tooltipConfig != null) {
                    this.tooltipConfig.reload();
                }
                if (this.autoLootConfig != null) {
                    this.autoLootConfig.reload();
                }
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

