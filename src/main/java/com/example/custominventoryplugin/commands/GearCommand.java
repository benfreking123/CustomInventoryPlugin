package com.example.custominventoryplugin.commands;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.inventory.GearInventory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class GearCommand implements CommandExecutor {
    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;
    
    public GearCommand(ConfigManager configManager, CustomInventoryPlugin plugin) {
        this.configManager = configManager;
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("custominventory.debug")) {
                sender.sendMessage("§cYou don't have permission to use this command!");
                return true;
            }
            
            plugin.getConfigManager().reloadConfig();
            sender.sendMessage("§aCustom Inventory configuration reloaded!");
            return true;
        }
        
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;
        GearInventory gearInventory = new GearInventory(player, configManager, plugin);
        player.openInventory(gearInventory.getInventory());
        
        return true;
    }
} 