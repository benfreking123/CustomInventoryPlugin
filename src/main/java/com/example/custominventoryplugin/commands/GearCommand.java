package com.example.custominventoryplugin.commands;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.PlayerResetService;
import com.example.custominventoryplugin.inventory.GearInventory;
import com.example.custominventoryplugin.inventory.StatsPanel;
import com.example.custominventoryplugin.listeners.AttributeAuditService;
import com.example.custominventoryplugin.party.PartiesPartyService;
import com.example.custominventoryplugin.party.PartyInventory;
import com.example.custominventoryplugin.party.PartyScoreboardService;
import com.example.custominventoryplugin.party.PartyService;
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
 * /ci attrcheck [player|*] — report attribute-ledger drift (Fabled point leaks).
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
        if (args.length > 0 && args[0].equalsIgnoreCase("party")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("\u00a7cPlayers only.");
                return true;
            }
            PartyService parties = plugin.getPartyService();
            if (parties == null || !parties.isAvailable()) {
                player.sendMessage("\u00a7cParty system is not available on this server.");
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("invite")) {
                if (args.length < 3) {
                    player.sendMessage("\u00a7cUsage: \u00a7e/party invite <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    // This command only sees players on this backend; the proxy
                    // command reaches the whole network.
                    player.sendMessage("\u00a7c" + args[2] + " is not on this server. \u00a7e/p "
                            + args[2] + "\u00a7c them to meet, then invite from \u00a7e/party\u00a7c.");
                    return true;
                }
                String name = target.getName();
                parties.invite(player, target, result -> {
                    switch (result) {
                        // Parties messages both sides from the PROXY, which
                        // receives the invite packet whether or not the backend
                        // was told to send messages — so anything added here
                        // arrives as a second, duplicate prompt.
                        case SENT -> { }
                        case SELF -> player.sendMessage("\u00a7cYou cannot invite yourself.");
                        case ALREADY_IN_PARTY ->
                                player.sendMessage("\u00a7c" + name + " is already in a party.");
                        case ALREADY_INVITED ->
                                player.sendMessage("\u00a7e" + name + " already has a pending invite.");
                        case PARTY_FULL ->
                                player.sendMessage("\u00a7cYour party is full.");
                        case FAILED ->
                                player.sendMessage("\u00a7cCould not invite " + name + ".");
                    }
                });
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("accept")) {
                // Optional inviter, for a player holding more than one invite.
                String who = args.length >= 3 ? args[2] : null;
                parties.acceptInvite(player, who, result -> {
                    switch (result) {
                        case JOINED -> player.sendMessage("\u00a7aYou joined the party.");
                        case NO_INVITE -> player.sendMessage(noInviteText(player, who));
                        case ALREADY_IN_PARTY -> player.sendMessage("\u00a7cYou are already in a party.");
                        case PARTY_FULL -> player.sendMessage("\u00a7cThat party is full.");
                        case FAILED -> player.sendMessage("\u00a7cCould not join the party.");
                    }
                });
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("deny")) {
                String who = args.length >= 3 ? args[2] : null;
                parties.denyInvite(player, who, ok -> player.sendMessage(ok
                        ? "\u00a77Invite declined."
                        : noInviteText(player, who)));
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("board")) {
                PartyScoreboardService board = plugin.getPartyScoreboardService();
                if (board == null) {
                    player.sendMessage("\u00a7cThe party sidebar is unavailable.");
                    return true;
                }
                boolean on = board.toggle(player);
                player.sendMessage(on ? "\u00a7aParty scoreboard shown." : "\u00a77Party scoreboard hidden.");
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("ready")) {
                if (parties instanceof PartiesPartyService pps) {
                    if (!pps.markReady(player)) {
                        parties.startReadyCheck(player);
                    }
                } else {
                    parties.startReadyCheck(player);
                }
                return true;
            }
            PartyInventory gui = new PartyInventory(plugin, parties, player);
            player.openInventory(gui.getInventory());
            return true;
        }

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
            ok += safeReload(sender, "party display", () -> plugin.getPartyDisplayConfig().reload());
            ok += safeReload(sender, "food regen", () -> plugin.getFoodRegenConfig().reload());
            sender.sendMessage("\u00a7aCustomInventory reloaded \u00a7f" + ok + "\u00a78/\u00a7f11\u00a7a config section(s).");
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

        // /ci attrcheck [player|*]
        //
        // On-demand version of the login sweep. Reports where the attribute
        // ledger and the player's actual gear disagree, which is the only way
        // to see a Fabled point leak — the symptom is a player who is quietly
        // too strong, with nothing in any log unless someone asks.
        //
        // Reports only, never repairs. A repair here would race the reconcile
        // passes and, worse, would make the drift disappear before anyone
        // worked out what caused it. The listeners already self-correct on the
        // next pass; what they cannot do is tell you it happened.
        if (args.length > 0 && args[0].equalsIgnoreCase("attrcheck")) {
            if (!sender.hasPermission("custominventory.debug") && !sender.isOp()) {
                sender.sendMessage("\u00a7cYou don't have permission to audit attributes.");
                return true;
            }
            List<Player> targets = new ArrayList<>();
            if (args.length < 2 || args[1].equals("*")) {
                targets.addAll(Bukkit.getOnlinePlayers());
            } else {
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    sender.sendMessage("\u00a7cPlayer not online on this server.");
                    return true;
                }
                targets.add(t);
            }

            int dirty = 0;
            for (Player t : targets) {
                List<AttributeAuditService.Drift> drift = AttributeAuditService.audit(t);
                if (drift.isEmpty()) continue;
                dirty++;
                sender.sendMessage("\u00a7c" + t.getName() + " \u00a77— " + drift.size() + " slot(s) adrift:");
                for (AttributeAuditService.Drift d : drift) {
                    sender.sendMessage("  \u00a77" + (d.isOrphan() ? "\u00a7c[leak] \u00a77" : "") + d);
                }
            }
            if (dirty == 0) {
                sender.sendMessage("\u00a7a[CIP] Attribute ledger matches gear for \u00a7f"
                        + targets.size() + "\u00a7a player(s).");
            } else {
                sender.sendMessage("\u00a7e[CIP] \u00a7f" + dirty + "\u00a7e of \u00a7f" + targets.size()
                        + "\u00a7e player(s) adrift. A \u00a7c[leak]\u00a7e is a grant with no item behind it.");
            }
            return true;
        }

        // /ci stats <offense|defense|utility|off|toggle>
        //
        // The tab strip in the Gear Menu is the primary control, but the panel
        // also has to be reachable without opening a GUI — that's what the
        // player-facing /stats and /statsb in transport-less Skript
        // (inventory/gear-menu-overlay.sk) forward to. Kept as a /ci subcommand
        // rather than its own command so there's one owner of the tab state and
        // no new entry in plugin.yml to clash with another plugin.
        if (args.length > 0 && args[0].equalsIgnoreCase("stats")) {
            if (!(sender instanceof Player statsPlayer)) {
                sender.sendMessage("\u00a7cOnly players have a stats panel.");
                return true;
            }
            String want = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
            if (want.equals("toggle")) {
                // Hidden opens on Offense; anything visible hides.
                want = StatsPanel.tab(statsPlayer.getUniqueId()).equals(StatsPanel.OFF)
                        ? StatsPanel.OFFENSE
                        : StatsPanel.OFF;
            }
            if (!StatsPanel.isTab(want)) {
                sender.sendMessage("\u00a7cUsage: \u00a7e/ci stats <offense|defense|utility|off|toggle>");
                return true;
            }
            StatsPanel.select(this.plugin, statsPlayer, want);
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

    /**
     * "No invite" reads as a lie when the player does have one from somebody
     * else, so name who is actually waiting on them.
     */
    private String noInviteText(Player player, String requested) {
        List<String> inviters = plugin.getPartyService().pendingInviters(player.getUniqueId());
        if (inviters.isEmpty()) {
            return "\u00a7cYou have no pending party invite on this server.";
        }
        if (requested != null) {
            return "\u00a7cNo invite from \u00a7f" + requested + "\u00a7c. Waiting on you: \u00a7f"
                    + String.join("\u00a7c, \u00a7f", inviters);
        }
        return "\u00a7cThat invite expired. Waiting on you: \u00a7f"
                + String.join("\u00a7c, \u00a7f", inviters);
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
            if ("attrcheck".startsWith(p) && (sender.hasPermission("custominventory.debug") || sender.isOp())) {
                out.add("attrcheck");
            }
            if ("stats".startsWith(p)) out.add("stats");
            if ("party".startsWith(p)) out.add("party");
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("attrcheck")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            if ("*".startsWith(p)) out.add("*");
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(pl.getName());
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("party")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            for (String t : List.of("invite", "accept", "deny", "ready", "board")) {
                if (t.startsWith(p)) out.add(t);
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("party") && args[1].equalsIgnoreCase("invite")) {
            String p = args[2].toLowerCase(Locale.ROOT);
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(pl.getName());
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            for (String t : List.of("offense", "defense", "utility", "off", "toggle")) {
                if (t.startsWith(p)) out.add(t);
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
