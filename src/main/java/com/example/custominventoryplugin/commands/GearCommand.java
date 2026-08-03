package com.example.custominventoryplugin.commands;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerResetService;
import com.example.custominventoryplugin.inventory.GearInventory;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /ci — opens the Gear Menu.
 * /ci reload — hot-reload every CIP config (settings, backpacks, groupdrops,
 *   tooltip, autoloot, compendium, quest tags, bestiary) without a restart.
 * /ci reset &lt;player&gt; [confirm] — wipe CIP MariaDB gear + backpacks.
 */
public class GearCommand implements CommandExecutor, TabCompleter {
    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;
    /** pending reset confirms: lower-name → expiry millis */
    private final Map<String, Long> pendingReset = new ConcurrentHashMap<>();

    public GearCommand(ConfigManager configManager, CustomInventoryPlugin plugin) {
        this.configManager = configManager;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("custominventory.debug")) {
                sender.sendMessage("\u00a7cYou don't have permission to use this command!");
                return true;
            }
            // Hot-reload every file-backed config. Each is guarded so one bad
            // file doesn't abort the rest. compendium.yml feeds both the
            // Account/menu config and the quest-tag map, so both are reloaded.
            int ok = 0;
            ok += safeReload(sender, "settings.yml", () -> plugin.getConfigManager().reloadConfig());
            ok += safeReload(sender, "backpacks.yml", () -> plugin.getBackpackConfig().reload());
            ok += safeReload(sender, "groupdrops", () -> plugin.getGroupDropConfig().reload());
            ok += safeReload(sender, "tooltip", () -> plugin.getTooltipConfig().reload());
            ok += safeReload(sender, "autoloot", () -> plugin.getAutoLootConfig().reload());
            ok += safeReload(sender, "compendium.yml", () -> plugin.getCompendiumConfig().load());
            ok += safeReload(sender, "quest tags", () -> plugin.getQuestProgress().load());
            ok += safeReload(sender, "bestiary.yml", () -> plugin.getBestiaryConfig().load());
            ok += safeReload(sender, "collections.yml", () -> plugin.getCollectionsConfig().load());
            sender.sendMessage("\u00a7aCustomInventory reloaded \u00a7f" + ok + "\u00a78/\u00a7f9\u00a7a config section(s).");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            if (!sender.hasPermission("custominventory.debug") && !sender.isOp()) {
                sender.sendMessage("\u00a7cYou don't have permission to reset player CIP data.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("\u00a7cUsage: \u00a7e/ci reset <player> [confirm]");
                return true;
            }
            String name = args[1];
            Player online = Bukkit.getPlayerExact(name);
            UUID uuid;
            String resolvedName;
            if (online != null) {
                uuid = online.getUniqueId();
                resolvedName = online.getName();
            } else {
                @SuppressWarnings("deprecation")
                var offline = Bukkit.getOfflinePlayer(name);
                if (offline.getUniqueId() == null || (!offline.hasPlayedBefore() && !offline.isOnline())) {
                    // Still allow UUID-based wipe if name resolves (Paper always gives a UUID).
                    uuid = offline.getUniqueId();
                    resolvedName = name;
                } else {
                    uuid = offline.getUniqueId();
                    resolvedName = offline.getName() != null ? offline.getName() : name;
                }
            }

            String key = resolvedName.toLowerCase(Locale.ROOT);
            boolean confirm = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
            boolean console = !(sender instanceof Player);
            if (!confirm) {
                pendingReset.put(key, System.currentTimeMillis() + 30_000L);
                sender.sendMessage("\u00a7eThis will wipe \u00a7f" + resolvedName
                        + "\u00a7e's /ci gear, backpacks, and pickup settings (MariaDB).");
                sender.sendMessage("\u00a7cType \u00a7f/ci reset " + resolvedName + " confirm \u00a7cwithin 30s.");
                return true;
            }
            if (!console) {
                Long expiry = pendingReset.remove(key);
                if (expiry == null || expiry < System.currentTimeMillis()) {
                    sender.sendMessage("\u00a7cNo pending reset (or it expired). Run \u00a7e/ci reset "
                            + resolvedName + " \u00a7cfirst.");
                    return true;
                }
            } else {
                pendingReset.remove(key);
            }

            PlayerResetService.reset(plugin, uuid, resolvedName);
            sender.sendMessage("\u00a7a[CIP] Reset gear + backpacks for \u00a7e" + resolvedName + "\u00a7a.");
            if (online != null) {
                online.sendMessage("\u00a7e[CIP] Your gear menu and backpacks were wiped.");
            }
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("\u00a7cThis command can only be used by players!");
            return true;
        }
        Player player = (Player) sender;
        GearInventory gearInventory = new GearInventory(player, this.configManager, this.plugin);
        player.openInventory(gearInventory.getInventory());
        return true;
    }

    /** Run one config reload, reporting (not throwing) on failure. Returns 1 on success. */
    private int safeReload(CommandSender sender, String label, Runnable action) {
        try {
            action.run();
            return 1;
        } catch (Throwable t) {
            sender.sendMessage("\u00a7c[CIP] Failed to reload " + label + ": " + t.getMessage());
            plugin.getLogger().warning("Config reload failed for " + label + ": " + t.getMessage());
            return 0;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            if ("reload".startsWith(p) && sender.hasPermission("custominventory.debug")) out.add("reload");
            if ("reset".startsWith(p) && (sender.hasPermission("custominventory.debug") || sender.isOp())) {
                out.add("reset");
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(pl.getName());
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            if ("confirm".startsWith(args[2].toLowerCase(Locale.ROOT))) out.add("confirm");
            return out;
        }
        return out;
    }
}
