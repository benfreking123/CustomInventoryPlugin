package com.example.custominventoryplugin.commands;

import com.sucy.skill.api.player.PlayerData;
import com.sucy.skill.api.player.PlayerSkill;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class AttributeCommand implements CommandExecutor {
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;
        PlayerData playerData = com.sucy.skill.SkillAPI.getPlayerData(player);
        
        if (playerData == null) {
            player.sendMessage("Failed to get player data!");
            return true;
        }

        // Add 5 to strength using the correct Fabled API method
        playerData.giveAttribute("Strength", 5);
        playerData.updatePlayerStat(player);
        
        player.sendMessage("Added 5 to your Strength attribute!");
        return true;
    }
} 