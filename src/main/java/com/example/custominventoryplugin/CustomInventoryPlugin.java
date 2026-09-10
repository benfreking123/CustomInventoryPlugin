package com.example.custominventoryplugin;

import com.example.custominventoryplugin.commands.BackpackCommand;
import com.example.custominventoryplugin.commands.DebugCommand;
import com.example.custominventoryplugin.commands.GearCommand;
import com.example.custominventoryplugin.compendium.BestiaryConfig;
import com.example.custominventoryplugin.compendium.BestiaryData;
import com.example.custominventoryplugin.compendium.BestiaryKillListener;
import com.example.custominventoryplugin.compendium.CollectionsConfig;
import com.example.custominventoryplugin.compendium.CollectionsListener;
import com.example.custominventoryplugin.compendium.CollectionsService;
import com.example.custominventoryplugin.compendium.CipCountCommand;
import com.example.custominventoryplugin.compendium.CompendiumCommand;
import com.example.custominventoryplugin.compendium.CompendiumConfig;
import com.example.custominventoryplugin.compendium.CompendiumListener;
import com.example.custominventoryplugin.compendium.CounterData;
import com.example.custominventoryplugin.compendium.QuestProgress;
import com.example.custominventoryplugin.autoloot.AutoLootConfig;
import com.example.custominventoryplugin.autoloot.LootEffectService;
import com.example.custominventoryplugin.config.BackpackConfig;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.BackpackData;
import com.example.custominventoryplugin.data.BackpackSettingsData;
import com.example.custominventoryplugin.data.Database;
import com.example.custominventoryplugin.data.EconomyHook;
import com.example.custominventoryplugin.data.LuckPermsBridge;
import com.example.custominventoryplugin.data.PlayerGearData;
import com.example.custominventoryplugin.food.FoodRegenConfig;
import com.example.custominventoryplugin.food.FoodRegenListener;
import com.example.custominventoryplugin.food.FoodRegenService;
import com.example.custominventoryplugin.skills.GemSkillLevels;
import com.example.custominventoryplugin.skills.RefundCommand;
import com.example.custominventoryplugin.listeners.DeathLootListener;
import com.example.custominventoryplugin.listeners.HarvestPickupListener;
import com.example.custominventoryplugin.listeners.InfiniteArrowsListener;
import com.example.custominventoryplugin.pickup.PickupPipeline;
import com.example.custominventoryplugin.settings.BackpackSettingsCache;
import com.example.custominventoryplugin.groupdrop.GroupDropCommand;
import com.example.custominventoryplugin.groupdrop.GroupDropConfig;
import com.example.custominventoryplugin.groupdrop.GroupDropData;
import com.example.custominventoryplugin.groupdrop.GroupDropListener;
import com.example.custominventoryplugin.groupdrop.RewardService;
import com.example.custominventoryplugin.listeners.ArmorAttributeListener;
import com.example.custominventoryplugin.listeners.AttributeAuditService;
import com.example.custominventoryplugin.listeners.BackpackListener;
import com.example.custominventoryplugin.listeners.BackpackPickupListener;
import com.example.custominventoryplugin.listeners.InventoryListener;
import com.example.custominventoryplugin.listeners.BasicAttackCritListener;
import com.example.custominventoryplugin.listeners.MainHandAttributeListener;
import com.example.custominventoryplugin.party.NoOpPartyService;
import com.example.custominventoryplugin.party.PartiesPartyService;
import com.example.custominventoryplugin.party.PartyDisplayConfig;
import com.example.custominventoryplugin.party.PartyGuiListener;
import com.example.custominventoryplugin.party.PartyLifecycleListener;
import com.example.custominventoryplugin.party.PartyService;
import com.example.custominventoryplugin.party.PartyHudStore;
import com.example.custominventoryplugin.party.PartyScoreboardListener;
import com.example.custominventoryplugin.party.PartyScoreboardService;
import com.example.custominventoryplugin.party.PartySettingsStore;
import com.example.custominventoryplugin.placeholders.BackpackPlaceholders;
import com.example.custominventoryplugin.tooltip.TooltipConfig;
import com.example.custominventoryplugin.tooltip.ItemGlowPackListener;
import com.example.custominventoryplugin.tooltip.TooltipListener;
import com.example.custominventoryplugin.tooltip.TooltipStyleService;
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
    private GemSkillLevels gemSkillLevels;
    private GroupDropData groupDropData;
    private GroupDropConfig groupDropConfig;
    private RewardService rewardService;
    private BestiaryConfig bestiaryConfig;
    private BestiaryData bestiaryData;
    private CounterData counterData;
    private CollectionsConfig collectionsConfig;
    private CollectionsService collectionsService;
    private CollectionsListener collectionsListener;
    private QuestProgress questProgress;
    private CompendiumConfig compendiumConfig;
    private TooltipConfig tooltipConfig;
    private TooltipStyleService tooltipStyleService;
    private AutoLootConfig autoLootConfig;
    private PartyDisplayConfig partyDisplayConfig;
    private LootEffectService lootEffectService;
    private PartyService partyService;
    private PartySettingsStore partySettingsStore;
    private PartyHudStore partyHudStore;
    private PartyScoreboardService partyScoreboardService;
    private FoodRegenConfig foodRegenConfig;
    private FoodRegenService foodRegenService;

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

        // ─── gem skill levels (socket = level 1, refund-safe) ─────────────
        // Must exist before InventoryListener builds SlotHandler/SkillHandler.
        this.gemSkillLevels = new GemSkillLevels(this);
        getCommand("refund").setExecutor(new RefundCommand(this));
        getServer().getPluginManager().registerEvents(new RefundCommand.Intercept(this), this);

        // ─── food regen ───────────────────────────────────────────────────
        // Built before the tooltip service, which needs it to write the regen
        // line onto a food stack.
        this.foodRegenConfig = new FoodRegenConfig(this);
        this.foodRegenService = new FoodRegenService(this);
        getServer().getPluginManager().registerEvents(
                new FoodRegenListener(this.foodRegenConfig, this.foodRegenService), this);

        // ─── tooltip frames (Divinity tier → minecraft:tooltip_style) ─────
        this.tooltipConfig = new TooltipConfig(this);
        this.tooltipStyleService =
                new TooltipStyleService(this, this.tooltipConfig, this.foodRegenConfig);
        // Built before the tooltip listener so its 5-tick hand sweep can double
        // as the reconciler for main-hand Fabled attributes.
        MainHandAttributeListener mainHandAttributes =
                new MainHandAttributeListener(this, this.configManager);
        getServer().getPluginManager().registerEvents(mainHandAttributes, this);
        getServer().getPluginManager().registerEvents(
                new TooltipListener(this, this.tooltipStyleService, mainHandAttributes), this);
        // The other half of the tier glow: TooltipListener stamps the tier onto the
        // item, this writes the layer the pack draws for it. Guarded because Nexo
        // is a soft-depend — without it there is no pack to hook and the class
        // references types that would not resolve.
        if (getServer().getPluginManager().isPluginEnabled("Nexo")) {
            try {
                getServer().getPluginManager().registerEvents(
                        new ItemGlowPackListener(this, this.tooltipConfig), this);
            } catch (Throwable t) {
                getLogger().warning("tier glow: Nexo present but its pack API did not "
                        + "resolve, slot glow disabled (" + t + ")");
            }
        }
        // Fabled attr bonuses for vanilla armor (rings-style PDC read) + tooltip
        // restamps on attribute change so "(total)" figures stay fresh.
        getServer().getPluginManager().registerEvents(
                new ArmorAttributeListener(this, this.configManager), this);

        // Basic-attack crit. Guarded because Divinity is a soft-depend and the
        // listener references its event types, which would not resolve without it.
        if (getServer().getPluginManager().isPluginEnabled("Divinity")) {
            try {
                getServer().getPluginManager().registerEvents(
                        new BasicAttackCritListener(this.configManager), this);
            } catch (Throwable t) {
                getLogger().warning("basic-attack crit: Divinity present but its damage "
                        + "event API did not resolve, weapon crit disabled (" + t + ")");
            }
        }

        // Startup sweep. /reload and plugin-manager reloads bring players back
        // without a PlayerJoinEvent, so the per-login audit never runs for them
        // and a reload is exactly when the ledger is most likely to have been
        // left mid-write. Delayed a second for the same reason as the login
        // pass: player data loads asynchronously.
        getServer().getScheduler().runTaskLater(this, () -> {
            for (Player online : getServer().getOnlinePlayers()) {
                AttributeAuditService.warnOnDrift(online, getLogger(), "startup");
            }
        }, 20L);

        // ─── AutoLoot (server drop manager: rarity glow/burst + routing) ──
        this.autoLootConfig = new AutoLootConfig(this);
        this.lootEffectService = new LootEffectService(this, this.autoLootConfig, this.tooltipStyleService);
        this.partyDisplayConfig = new PartyDisplayConfig(this);

        GearCommand gearCommand = new GearCommand(this.configManager, this);
        getCommand("ci").setExecutor(gearCommand);
        getCommand("ci").setTabCompleter(gearCommand);
        getCommand("debug").setExecutor(new DebugCommand(this.configManager, this.tooltipConfig, this.autoLootConfig));

        BackpackCommand bpCommand = new BackpackCommand(this, this.backpackConfig, this.backpackData);
        getCommand("bp").setExecutor(bpCommand);
        getCommand("bp").setTabCompleter(bpCommand);

        getServer().getPluginManager().registerEvents(new InventoryListener(this.configManager, this), this);
        BackpackListener bpListener = new BackpackListener(this, this.backpackData);
        getServer().getPluginManager().registerEvents(bpListener, this);

        // ─── unified pickup pipeline (replaces the AutoPickup plugin) ──────
        this.pickupPipeline = new PickupPipeline(
                this, this.backpackConfig, this.backpackData, this.settingsCache,
                bpListener.getMarkerKey(), this.autoLootConfig);
        getServer().getPluginManager().registerEvents(new BackpackPickupListener(this, this.pickupPipeline), this);
        // ─── party roster (AlessioDP Parties softdepend) ───────────────────
        this.partySettingsStore = new PartySettingsStore(this, this.database);
        this.partyHudStore = new PartyHudStore(this, this.database);
        if (getServer().getPluginManager().getPlugin("Parties") != null) {
            try {
                PartiesPartyService pps = new PartiesPartyService(this, this.partySettingsStore);
                this.partyService = pps;
                getServer().getPluginManager().registerEvents(
                        new PartyLifecycleListener(this.partySettingsStore), this);
                getServer().getPluginManager().registerEvents(
                        new PartyGuiListener(this, this.partyService), this);
                this.partyScoreboardService =
                        new PartyScoreboardService(this, this.partyService, this.partyHudStore);
                this.partyScoreboardService.start();
                getServer().getPluginManager().registerEvents(
                        new PartyScoreboardListener(this.partyHudStore, this.partyScoreboardService), this);
                // Personal sidebars cost the viewer the main board's glow teams.
                this.lootEffectService.setBoardSource(this.partyScoreboardService::activeBoards);
                getLogger().info("Parties detected — party loot + GUI + sidebar enabled.");
            } catch (Throwable t) {
                this.partyService = new NoOpPartyService();
                getLogger().warning("Parties present but API failed to bind (" + t + ") — party features disabled.");
            }
        } else {
            this.partyService = new NoOpPartyService();
            getLogger().warning("Parties NOT detected — party loot/GUI disabled.");
        }

        getServer().getPluginManager().registerEvents(
                new DeathLootListener(this, this.pickupPipeline, this.autoLootConfig,
                        this.lootEffectService, this.partyService), this);
        getServer().getPluginManager().registerEvents(
                new com.example.custominventoryplugin.autoloot.GroundGlowListener(this.autoLootConfig, this.lootEffectService), this);
        getServer().getPluginManager().registerEvents(new HarvestPickupListener(this, this.pickupPipeline), this);
        getServer().getPluginManager().registerEvents(new InfiniteArrowsListener(this), this);
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

        // ─── compendium (account/meta + bestiary + collections) ───────────
        this.bestiaryConfig = new BestiaryConfig(this);
        this.bestiaryData = new BestiaryData(this, this.database);
        this.counterData = new CounterData(this, this.database);
        this.questProgress = new QuestProgress(this, this.database);
        this.compendiumConfig = new CompendiumConfig(this);
        this.collectionsConfig = new CollectionsConfig(this);
        this.collectionsService = new CollectionsService(this, this.collectionsConfig, this.counterData);
        getCommand("compendium").setExecutor(new CompendiumCommand(this));
        getCommand("cipcount").setExecutor(new CipCountCommand(this, this.counterData));
        getServer().getPluginManager().registerEvents(new CompendiumListener(this), this);
        this.collectionsListener = new CollectionsListener(this, this.collectionsService);
        getServer().getPluginManager().registerEvents(this.collectionsListener, this);
        this.collectionsListener.startSweepTask();
        if (getServer().getPluginManager().getPlugin("MythicMobs") != null) {
            getServer().getPluginManager().registerEvents(
                    new BestiaryKillListener(this, this.bestiaryConfig, this.bestiaryData), this);
            getLogger().info("MythicMobs detected — bestiary kill tracking enabled.");
        } else {
            getLogger().warning("MythicMobs NOT detected — bestiary kills will not be tracked.");
        }

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
        if (this.partyScoreboardService != null) this.partyScoreboardService.shutdown();
        if (this.partyService != null) this.partyService.shutdown();
        if (this.collectionsListener != null) this.collectionsListener.stopSweepTask();
        if (this.foodRegenService != null) this.foodRegenService.shutdown();
        if (this.database != null) this.database.stop();
        getLogger().info("CustomInventoryPlugin disabled.");
    }

    public ConfigManager   getConfigManager()    { return this.configManager; }
    public BackpackConfig  getBackpackConfig()   { return this.backpackConfig; }
    public LuckPermsBridge getLuckPermsBridge()  { return this.luckPermsBridge; }
    public GemSkillLevels  getGemSkillLevels()   { return this.gemSkillLevels; }
    public Database        getDatabase()         { return this.database; }
    public BackpackData    getBackpackData()     { return this.backpackData; }
    public BackpackSettingsData  getSettingsData()  { return this.settingsData; }
    public BackpackSettingsCache getSettingsCache() { return this.settingsCache; }
    public EconomyHook     getEconomyHook()      { return this.economyHook; }
    public PickupPipeline  getPickupPipeline()   { return this.pickupPipeline; }
    public GroupDropConfig getGroupDropConfig()  { return this.groupDropConfig; }
    public GroupDropData   getGroupDropData()    { return this.groupDropData; }
    public RewardService   getRewardService()    { return this.rewardService; }
    public BestiaryConfig  getBestiaryConfig()   { return this.bestiaryConfig; }
    public BestiaryData    getBestiaryData()     { return this.bestiaryData; }
    public CounterData     getCounterData()      { return this.counterData; }
    public CollectionsConfig  getCollectionsConfig()  { return this.collectionsConfig; }
    public CollectionsService getCollectionsService() { return this.collectionsService; }
    public QuestProgress   getQuestProgress()    { return this.questProgress; }
    public CompendiumConfig getCompendiumConfig() { return this.compendiumConfig; }
    public TooltipConfig   getTooltipConfig()    { return this.tooltipConfig; }
    public TooltipStyleService getTooltipStyleService() { return this.tooltipStyleService; }
    public AutoLootConfig  getAutoLootConfig()   { return this.autoLootConfig; }
    public LootEffectService getLootEffectService() { return this.lootEffectService; }
    public PartyService    getPartyService()     { return this.partyService; }
    public PartyScoreboardService getPartyScoreboardService() { return this.partyScoreboardService; }
    public PartyDisplayConfig getPartyDisplayConfig() { return this.partyDisplayConfig; }
    public FoodRegenConfig  getFoodRegenConfig()  { return this.foodRegenConfig; }
    public FoodRegenService getFoodRegenService() { return this.foodRegenService; }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerGearData.loadPlayerData(player.getUniqueId());
        if (this.settingsCache != null) this.settingsCache.load(player.getUniqueId());
        // Runs a tick late: BetterHud re-applies its saved .users/ HUD list
        // during login, so clearing on the join event itself can be overwritten.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                com.example.custominventoryplugin.inventory.StatsPanel.resetOnJoin(this, player);
            }
        }, 20L);
        // Gem skills socketed before 1.29.6 (or reset by an admin) still sit at
        // level 0. Fabled loads its player data from SQL off-thread, so give it
        // a moment before the audit reads levels.
        if (this.gemSkillLevels != null) {
            getServer().getScheduler().runTaskLater(this, () -> this.gemSkillLevels.reconcile(player), 60L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerGearData.unloadPlayerData(player.getUniqueId());
        if (this.settingsCache != null) this.settingsCache.unload(player.getUniqueId());
        com.example.custominventoryplugin.inventory.StatsPanel.forget(player.getUniqueId());
    }
}
