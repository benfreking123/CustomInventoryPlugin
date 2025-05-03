package com.example.custominventoryplugin.commands;

import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.inventory.GearInventory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class GearCommand implements CommandExecutor {
    private final ConfigManager configManager;
    
    public GearCommand(ConfigManager configManager) {
        this.configManager = configManager;
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;
        GearInventory gearInventory = new GearInventory(player, configManager);
        player.openInventory(gearInventory.getInventory());
        
        return true;
    }
} 