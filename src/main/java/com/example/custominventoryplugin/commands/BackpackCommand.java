package com.example.custominventoryplugin.commands;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.BackpackConfig;
import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.data.BackpackData;
import com.example.custominventoryplugin.inventory.BackpackInventory;
import com.example.custominventoryplugin.inventory.BackpackListInventory;
import com.example.custominventoryplugin.inventory.GearInventory;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /bp                — open the GUI list of accessible backpacks
 * /bp list           — same as /bp (explicit alias)
 * /bp &lt;id&gt;          — open a specific backpack directly
 * /bp reload         — admin: reload backpacks.yml
 *
 * Tab-complete only lists backpacks the sender has perm for, plus the
 * fixed "list" subcommand and "reload" (when permitted).
 */
public class BackpackCommand implements CommandExecutor, TabCompleter {

    private final CustomInventoryPlugin plugin;
    private final BackpackConfig config;
    private final BackpackData data;
    private final NamespacedKey iconKey;

    public BackpackCommand(CustomInventoryPlugin plugin, BackpackConfig config, BackpackData data) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
        this.iconKey = new NamespacedKey(plugin, GearInventory.BACKPACK_BUTTON_KEY);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("custominventory.debug")) {
                sender.sendMessage("\u00a7cYou don't have permission.");
                return true;
            }
            config.reload();
            sender.sendMessage("\u00a7aReloaded " + config.all().size() + " backpack(s) from backpacks.yml.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("\u00a7cThis command can only be used by players.");
            return true;
        }

        // /bp           → list GUI
        // /bp list      → list GUI (explicit)
        // /bp <id>      → open that backpack
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            openListGui(player);
            return true;
        }

        String requested = args[0].toLowerCase();
        BackpackDef def = config.get(requested);

        if (def == null) {
            player.sendMessage("\u00a7cUnknown backpack: \u00a7f" + requested);
            return true;
        }
        if (!def.canAccess(player)) {
            player.sendMessage("\u00a7cYou don't have access to that backpack.");
            return true;
        }

        BackpackInventory.open(plugin, player, def);
        return true;
    }

    private void openListGui(Player player) {
        List<BackpackDef> accessible = config.accessible(player);
        if (accessible.isEmpty()) {
            player.sendMessage("\u00a77You don't have access to any backpacks.");
            return;
        }
        BackpackListInventory list = new BackpackListInventory(plugin, player, config, data, iconKey);
        player.openInventory(list.getInventory());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return new ArrayList<>();
        if (!(sender instanceof Player player)) return new ArrayList<>();

        String partial = args[0].toLowerCase();
        List<String> ids = config.accessible(player).stream()
                .map(BackpackDef::getId)
                .filter(id -> id.startsWith(partial))
                .collect(Collectors.toList());

        if ("list".startsWith(partial)) ids.add("list");
        if (sender.hasPermission("custominventory.debug") && "reload".startsWith(partial)) {
            ids.add("reload");
        }
        return ids;
    }
}
