package com.example.custominventoryplugin;

import com.example.custominventoryplugin.commands.DebugCommand;
import com.example.custominventoryplugin.commands.GearCommand;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerGearData;
import com.example.custominventoryplugin.listeners.InventoryListener;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class CustomInventoryPlugin extends JavaPlugin implements Listener {
    private ConfigManager configManager;
    
    @Override
    public void onEnable() {
        // Initialize config manager
        this.configManager = new ConfigManager(this);
        
        // Initialize player gear data
        PlayerGearData.initialize(this);
        
        // Check if Fabled is present
        if (getServer().getPluginManager().getPlugin("Fabled") == null) {
            getLogger().severe("Fabled is not installed! This plugin requires Fabled to work.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register commands
        getCommand("ci").setExecutor(new GearCommand(configManager, this));
        getCommand("debug").setExecutor(new DebugCommand(configManager));
        
        // Register listeners
        getServer().getPluginManager().registerEvents(new InventoryListener(configManager, this), this);
        getServer().getPluginManager().registerEvents(this, this);
        
        getLogger().info("CustomInventoryPlugin has been enabled!");
    }
    
    @Override
    public void onDisable() {
        // The auto-save system will handle saving player data
        getLogger().info("CustomInventoryPlugin has been disabled!");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerGearData.loadPlayerData(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerGearData.unloadPlayerData(player.getUniqueId());
    }
} 