package com.example.custominventoryplugin;

import com.example.custominventoryplugin.commands.BackpackCommand;
import com.example.custominventoryplugin.commands.DebugCommand;
import com.example.custominventoryplugin.commands.GearCommand;
import com.example.custominventoryplugin.config.BackpackConfig;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.BackpackData;
import com.example.custominventoryplugin.data.BackpackSettingsData;
import com.example.custominventoryplugin.data.Database;
import com.example.custominventoryplugin.data.EconomyHook;
import com.example.custominventoryplugin.data.LuckPermsBridge;
import com.example.custominventoryplugin.data.PlayerGearData;
import com.example.custominventoryplugin.listeners.DeathLootListener;
import com.example.custominventoryplugin.listeners.HarvestPickupListener;
import com.example.custominventoryplugin.pickup.PickupPipeline;
import com.example.custominventoryplugin.settings.BackpackSettingsCache;
import com.example.custominventoryplugin.groupdrop.GroupDropCommand;
import com.example.custominventoryplugin.groupdrop.GroupDropConfig;
import com.example.custominventoryplugin.groupdrop.GroupDropData;
import com.example.custominventoryplugin.groupdrop.GroupDropListener;
import com.example.custominventoryplugin.groupdrop.RewardService;
import com.example.custominventoryplugin.listeners.BackpackListener;
import com.example.custominventoryplugin.listeners.BackpackPickupListener;
import com.example.custominventoryplugin.listeners.InventoryListener;
import com.example.custominventoryplugin.placeholders.BackpackPlaceholders;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public class CustomInventoryPlugin extends JavaPlugin implements Listener {

    private ConfigManager configManager;
    private BackpackConfig backpackConfig;
    private Database database;
    private BackpackData backpackData;
    private BackpackSettingsData settingsData;
    private BackpackSettingsCache settingsCache;
    private EconomyHook economyHook;
    private PickupPipeline pickupPipeline;
    private LuckPermsBridge luckPermsBridge;
    private GroupDropData groupDropData;
    private GroupDropConfig groupDropConfig;
    private RewardService rewardService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        this.backpackConfig = new BackpackConfig(this);

        this.database = new Database(this);
        try {
            this.database.start();
        } catch (SQLException e) {
            getLogger().severe("Database init failed — disabling plugin: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.luckPermsBridge = new LuckPermsBridge(this);
        if (luckPermsBridge.isAvailable()) {
            getLogger().info("LuckPerms detected — skill gems will grant permissions cross-server.");
        } else {
            getLogger().warning("LuckPerms NOT detected — skill gems will NOT grant permissions.");
        }

        PlayerGearData.initialize(this, this.database);
        this.backpackData = new BackpackData(this, this.database);
        this.settingsData = new BackpackSettingsData(this, this.database);
        this.settingsCache = new BackpackSettingsCache(this.settingsData);
        this.economyHook = new EconomyHook(this);

        if (getServer().getPluginManager().getPlugin("Fabled") == null) {
            getLogger().severe("Fabled is not installed! This plugin requires Fabled to work.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getCommand("ci").setExecutor(new GearCommand(this.configManager, this));
        getCommand("debug").setExecutor(new DebugCommand(this.configManager));

        BackpackCommand bpCommand = new BackpackCommand(this, this.backpackConfig, this.backpackData);
        getCommand("bp").setExecutor(bpCommand);
        getCommand("bp").setTabCompleter(bpCommand);

        getServer().getPluginManager().registerEvents(new InventoryListener(this.configManager, this), this);
        BackpackListener bpListener = new BackpackListener(this, this.backpackData);
        getServer().getPluginManager().registerEvents(bpListener, this);

        // ─── unified pickup pipeline (replaces the AutoPickup plugin) ──────
        this.pickupPipeline = new PickupPipeline(
                this, this.backpackConfig, this.backpackData, this.settingsCache, bpListener.getMarkerKey());
        getServer().getPluginManager().registerEvents(new BackpackPickupListener(this, this.pickupPipeline), this);
        getServer().getPluginManager().registerEvents(new DeathLootListener(this, this.pickupPipeline), this);
        getServer().getPluginManager().registerEvents(new HarvestPickupListener(this, this.pickupPipeline), this);
        getServer().getPluginManager().registerEvents(this, this);

        // ─── group drops (choose-your-reward) ─────────────────────────────
        this.groupDropData = new GroupDropData(this, this.database);
        this.groupDropConfig = new GroupDropConfig(this, this.groupDropData);
        this.rewardService = new RewardService(this);
        GroupDropListener gdListener =
                new GroupDropListener(this, this.groupDropConfig, this.groupDropData, this.rewardService);
        getServer().getPluginManager().registerEvents(gdListener, this);
        GroupDropCommand gdCommand =
                new GroupDropCommand(this, this.groupDropConfig, this.groupDropData, this.rewardService, gdListener);
        getCommand("groupdrop").setExecutor(gdCommand);
        getCommand("groupdrop").setTabCompleter(gdCommand);

        // PlaceholderAPI expansion (soft-depend)
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new BackpackPlaceholders(this, this.backpackConfig, this.backpackData).register();
                getLogger().info("PlaceholderAPI detected — registered 'customip' expansion.");
            } catch (Throwable t) {
                getLogger().warning("Failed to register PAPI expansion: " + t.getMessage());
            }
        }

        getLogger().info("CustomInventoryPlugin v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (this.database != null) this.database.stop();
        getLogger().info("CustomInventoryPlugin disabled.");
    }

    public ConfigManager   getConfigManager()    { return this.configManager; }
    public BackpackConfig  getBackpackConfig()   { return this.backpackConfig; }
    public LuckPermsBridge getLuckPermsBridge()  { return this.luckPermsBridge; }
    public Database        getDatabase()         { return this.database; }
    public BackpackData    getBackpackData()     { return this.backpackData; }
    public BackpackSettingsData  getSettingsData()  { return this.settingsData; }
    public BackpackSettingsCache getSettingsCache() { return this.settingsCache; }
    public EconomyHook     getEconomyHook()      { return this.economyHook; }
    public PickupPipeline  getPickupPipeline()   { return this.pickupPipeline; }
    public GroupDropConfig getGroupDropConfig()  { return this.groupDropConfig; }
    public GroupDropData   getGroupDropData()    { return this.groupDropData; }
    public RewardService   getRewardService()    { return this.rewardService; }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerGearData.loadPlayerData(player.getUniqueId());
        if (this.settingsCache != null) this.settingsCache.load(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerGearData.unloadPlayerData(player.getUniqueId());
        if (this.settingsCache != null) this.settingsCache.unload(player.getUniqueId());
    }
}
