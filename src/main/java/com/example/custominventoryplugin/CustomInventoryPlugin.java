package com.example.custominventoryplugin;

import com.example.custominventoryplugin.commands.DebugCommand;
import com.example.custominventoryplugin.commands.GearCommand;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerGearData;
import com.example.custominventoryplugin.listeners.InventoryListener;
import org.bukkit.plugin.java.JavaPlugin;

public class CustomInventoryPlugin extends JavaPlugin {
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
        getCommand("gear").setExecutor(new GearCommand(configManager, this));
        getCommand("debug").setExecutor(new DebugCommand(configManager));
        
        // Register listeners
        getServer().getPluginManager().registerEvents(new InventoryListener(configManager, this), this);
        
        getLogger().info("CustomInventoryPlugin has been enabled!");
    }
    
    @Override
    public void onDisable() {
        // Save player gear data
        PlayerGearData.saveData();
        getLogger().info("CustomInventoryPlugin has been disabled!");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
} 