package com.example.custominventoryplugin.groupdrop;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /groupdrop &lt;id&gt;                       open the chooser for yourself
 * /groupdrop open &lt;player&gt; &lt;id&gt;          force-open for someone (console-friendly)
 * /groupdrop list                          list groups
 * /groupdrop create &lt;id&gt;                   create an empty group
 * /groupdrop edit &lt;id&gt;                     open the WYSIWYG editor
 * /groupdrop delete &lt;id&gt;                   delete a group
 * /groupdrop set &lt;id&gt; &lt;key&gt; &lt;value...&gt;     picks|distinct|claim|perm|title
 * /groupdrop option &lt;id&gt; &lt;slot&gt; addcmd &lt;cmd...&gt;
 * /groupdrop option &lt;id&gt; &lt;slot&gt; clearcmd
 * /groupdrop option &lt;id&gt; &lt;slot&gt; label &lt;text...&gt;
 * /groupdrop option &lt;id&gt; &lt;slot&gt; seticon          (uses held item)
 * /groupdrop token give &lt;id&gt; [player] [amount]
 * /groupdrop reset &lt;id|all&gt; &lt;player&gt;      clear one claim, or every claim they hold
 * /groupdrop export &lt;id|all&gt; · import &lt;id|all&gt; · reload
 */
public class GroupDropCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "custominventory.groupdrop.admin";

    private static final List<String> SUBS = Arrays.asList(
            "open", "list", "create", "edit", "delete", "set", "option",
            "token", "reset", "export", "import", "reload");

    private final CustomInventoryPlugin plugin;
    private final GroupDropConfig config;
    private final GroupDropData data;
    private final RewardService rewards;
    private final GroupDropListener listener;

    public GroupDropCommand(CustomInventoryPlugin plugin, GroupDropConfig config,
                            GroupDropData data, RewardService rewards, GroupDropListener listener) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
        this.rewards = rewards;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Text.c("&7/groupdrop <id> &8— open a reward selection"));
            if (sender.hasPermission(ADMIN)) {
                sender.sendMessage(Text.c("&7Admin: &flist, create, edit, delete, set, option, token, reset, export, import, reload"));
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        // Non-reserved word → treat as a group id to open for the sender.
        if (!SUBS.contains(sub)) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Text.c("&cConsole must use /groupdrop open <player> <id>."));
                return true;
            }
            GroupDrop g = config.get(sub);
            if (g == null) { player.sendMessage(Text.c("&cUnknown group drop: &f" + sub)); return true; }
            if (!g.canOpen(player)) { player.sendMessage(Text.c("&cYou can't open that reward.")); return true; }
            listener.openChooser(player, g, false);
            return true;
        }

        switch (sub) {
            case "open":   return cmdOpen(sender, args);
            case "list":   return cmdList(sender);
            case "reload": return cmdReload(sender);
        }

        // Everything below is admin-only.
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Text.c("&cYou don't have permission."));
            return true;
        }

        switch (sub) {
            case "create": return cmdCreate(sender, args);
            case "edit":   return cmdEdit(sender, args);
            case "delete": return cmdDelete(sender, args);
            case "set":    return cmdSet(sender, args);
            case "option": return cmdOption(sender, args);
            case "token":  return cmdToken(sender, args);
            case "reset":  return cmdReset(sender, args);
            case "export": return cmdExport(sender, args);
            case "import": return cmdImport(sender, args);
            default:
                sender.sendMessage(Text.c("&cUnknown subcommand."));
                return true;
        }
    }

    // ─── subcommands ──────────────────────────────────────────────────────

    private boolean cmdOpen(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) { sender.sendMessage(Text.c("&cNo permission.")); return true; }
        if (args.length < 3) { sender.sendMessage(Text.c("&7/groupdrop open <player> <id>")); return true; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { sender.sendMessage(Text.c("&cPlayer not online: &f" + args[1])); return true; }
        GroupDrop g = config.get(args[2]);
        if (g == null) { sender.sendMessage(Text.c("&cUnknown group drop: &f" + args[2])); return true; }
        listener.openChooser(target, g, false);
        sender.sendMessage(Text.c("&aOpened &b" + g.getId() + "&a for &f" + target.getName()));
        return true;
    }

    private boolean cmdList(CommandSender sender) {
        if (config.all().isEmpty()) { sender.sendMessage(Text.c("&7No group drops defined.")); return true; }
        sender.sendMessage(Text.c("&8Group drops:"));
        for (GroupDrop g : config.all()) {
            sender.sendMessage(Text.c("&8• &b" + g.getId() + " &7— " + g.optionCount() + " opts, pick "
                    + g.effectivePicks() + ", " + g.getClaimMode().name().toLowerCase()
                    + (g.isTokenEnabled() ? ", token" : "")));
        }
        return true;
    }

    private boolean cmdReload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) { sender.sendMessage(Text.c("&cNo permission.")); return true; }
        config.reload();
        sender.sendMessage(Text.c("&aReloaded " + config.all().size() + " group drop(s) from the database."));
        return true;
    }

    private boolean cmdCreate(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(Text.c("&7/groupdrop create <id>")); return true; }
        String id = args[1].toLowerCase();
        if (config.exists(id)) { sender.sendMessage(Text.c("&cThat group already exists.")); return true; }
        config.save(new GroupDrop(id));
        sender.sendMessage(Text.c("&aCreated group drop &b" + id + "&a. Use &f/groupdrop edit " + id + "&a to add options."));
        return true;
    }

    private boolean cmdEdit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(Text.c("&cPlayers only.")); return true; }
        if (args.length < 2) { player.sendMessage(Text.c("&7/groupdrop edit <id>")); return true; }
        GroupDrop g = config.get(args[1]);
        if (g == null) { player.sendMessage(Text.c("&cUnknown group drop: &f" + args[1])); return true; }
        listener.openEditor(player, g);
        return true;
    }

    private boolean cmdDelete(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(Text.c("&7/groupdrop delete <id>")); return true; }
        if (!config.exists(args[1])) { sender.sendMessage(Text.c("&cUnknown group drop: &f" + args[1])); return true; }
        config.delete(args[1]);
        sender.sendMessage(Text.c("&aDeleted group drop &b" + args[1].toLowerCase() + "&a (and all claims)."));
        return true;
    }

    private boolean cmdSet(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Text.c("&7/groupdrop set <id> <picks|distinct|claim|perm|title> <value...>"));
            return true;
        }
        GroupDrop g = config.get(args[1]);
        if (g == null) { sender.sendMessage(Text.c("&cUnknown group drop: &f" + args[1])); return true; }
        String key = args[2].toLowerCase();
        String value = join(args, 3);
        switch (key) {
            case "picks" -> {
                try { g.setPicks(Integer.parseInt(value.trim())); }
                catch (NumberFormatException e) { sender.sendMessage(Text.c("&cpicks must be a number.")); return true; }
            }
            case "distinct" -> g.setDistinct(value.trim().equalsIgnoreCase("true") || value.trim().equalsIgnoreCase("on"));
            case "claim"    -> g.setClaimMode(GroupDrop.ClaimMode.from(value));
            case "perm", "permission" -> g.setPermission(value.trim().equalsIgnoreCase("none") ? "" : value.trim());
            case "title"    -> g.setTitle(value);
            default -> { sender.sendMessage(Text.c("&cUnknown key. Use picks|distinct|claim|perm|title.")); return true; }
        }
        config.save(g);
        sender.sendMessage(Text.c("&aUpdated &b" + g.getId() + "&a: " + key + " = &f" + value));
        return true;
    }

    private boolean cmdOption(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Text.c("&7/groupdrop option <id> <slot> <addcmd|clearcmd|label|seticon> [...]"));
            return true;
        }
        GroupDrop g = config.get(args[1]);
        if (g == null) { sender.sendMessage(Text.c("&cUnknown group drop: &f" + args[1])); return true; }
        int slot;
        try { slot = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) { sender.sendMessage(Text.c("&cslot must be a number (0-44).")); return true; }

        GroupDropOption opt = g.getOption(slot);
        String action = args[3].toLowerCase();

        if (action.equals("seticon")) {
            if (!(sender instanceof Player player)) { sender.sendMessage(Text.c("&cPlayers only for seticon.")); return true; }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) { sender.sendMessage(Text.c("&cHold an item to use as the icon.")); return true; }
            if (opt == null) { opt = new GroupDropOption(slot, hand.clone()); g.getOptions().put(slot, opt); }
            else opt.setIcon(hand.clone());
            config.save(g);
            sender.sendMessage(Text.c("&aSet icon for option @" + slot + " in &b" + g.getId()));
            return true;
        }

        if (opt == null) { sender.sendMessage(Text.c("&cNo option at slot " + slot + ". Place one in the editor or use seticon.")); return true; }

        switch (action) {
            case "addcmd" -> {
                String cmd = join(args, 4);
                if (cmd.isEmpty()) { sender.sendMessage(Text.c("&cProvide a command.")); return true; }
                opt.getCommands().add(cmd);
                sender.sendMessage(Text.c("&aAdded command to option @" + slot + ": &f" + cmd));
            }
            case "clearcmd" -> {
                opt.getCommands().clear();
                sender.sendMessage(Text.c("&aCleared commands on option @" + slot));
            }
            case "label" -> {
                String lbl = join(args, 4);
                opt.setLabel(lbl.isEmpty() ? null : lbl);
                sender.sendMessage(Text.c("&aSet label for option @" + slot));
            }
            default -> { sender.sendMessage(Text.c("&cUnknown action. Use addcmd|clearcmd|label|seticon.")); return true; }
        }
        config.save(g);
        return true;
    }

    private boolean cmdToken(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("give")) {
            sender.sendMessage(Text.c("&7/groupdrop token give <id> [player] [amount]"));
            return true;
        }
        GroupDrop g = config.get(args[2]);
        if (g == null) { sender.sendMessage(Text.c("&cUnknown group drop: &f" + args[2])); return true; }
        if (!g.isTokenEnabled()) { sender.sendMessage(Text.c("&cTokens are disabled for that group (enable in the editor).")); return true; }

        Player target;
        if (args.length >= 4) {
            target = Bukkit.getPlayerExact(args[3]);
            if (target == null) { sender.sendMessage(Text.c("&cPlayer not online: &f" + args[3])); return true; }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Text.c("&cConsole must specify a player.")); return true;
        }

        int amount = 1;
        if (args.length >= 5) { try { amount = Integer.parseInt(args[4]); } catch (NumberFormatException ignored) {} }

        ItemStack token = rewards.createToken(g, amount);
        for (ItemStack leftover : target.getInventory().addItem(token).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover);
        }
        sender.sendMessage(Text.c("&aGave &f" + amount + "&a token(s) of &b" + g.getId() + "&a to &f" + target.getName()));
        return true;
    }

    private boolean cmdReset(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage(Text.c("&7/groupdrop reset <id|all> <player>")); return true; }
        boolean all = args[1].equalsIgnoreCase("all");
        if (!all && !config.exists(args[1])) {
            sender.sendMessage(Text.c("&cUnknown group drop: &f" + args[1]));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        java.util.UUID uuid = target != null ? target.getUniqueId()
                : Bukkit.getOfflinePlayer(args[2]).getUniqueId();
        if (all) {
            int n = data.resetAllClaims(uuid);
            sender.sendMessage(Text.c("&aReset &f" + n + "&a group drop claim(s) for &f" + args[2]));
            return true;
        }
        data.resetClaim(uuid, args[1]);
        sender.sendMessage(Text.c("&aReset claim for &f" + args[2] + "&a on &b" + args[1].toLowerCase()));
        return true;
    }

    private boolean cmdExport(CommandSender sender, String[] args) {
        String which = args.length >= 2 ? args[1] : "all";
        try {
            int n = config.exportYaml(which);
            sender.sendMessage(Text.c("&aExported " + n + " group(s) to groupdrops.yml"));
        } catch (Exception e) {
            sender.sendMessage(Text.c("&cExport failed: " + e.getMessage()));
        }
        return true;
    }

    private boolean cmdImport(CommandSender sender, String[] args) {
        String which = args.length >= 2 ? args[1] : "all";
        int n = config.importYaml(which);
        sender.sendMessage(Text.c("&aImported " + n + " group(s) from groupdrops.yml"));
        return true;
    }

    private String join(String[] args, int from) {
        if (from >= args.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    // ─── tab completion ───────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase();
            for (GroupDrop g : config.all()) if (g.getId().startsWith(p)) out.add(g.getId());
            if (sender.hasPermission(ADMIN)) for (String s : SUBS) if (s.startsWith(p)) out.add(s);
            return out;
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            String p = args[1].toLowerCase();
            if (sub.equals("open")) {
                for (Player pl : Bukkit.getOnlinePlayers()) if (pl.getName().toLowerCase().startsWith(p)) out.add(pl.getName());
            } else if (sub.equals("export") || sub.equals("import") || sub.equals("reset")) {
                if ("all".startsWith(p)) out.add("all");
                for (GroupDrop g : config.all()) if (g.getId().startsWith(p)) out.add(g.getId());
            } else if (sub.equals("token")) {
                if ("give".startsWith(p)) out.add("give");
            } else {
                for (GroupDrop g : config.all()) if (g.getId().startsWith(p)) out.add(g.getId());
            }
            return out;
        }
        if (args.length == 3 && sub.equals("set")) {
            for (String k : new String[]{"picks", "distinct", "claim", "perm", "title"}) {
                if (k.startsWith(args[2].toLowerCase())) out.add(k);
            }
        }
        if (args.length == 4 && sub.equals("option")) {
            for (String k : new String[]{"addcmd", "clearcmd", "label", "seticon"}) {
                if (k.startsWith(args[3].toLowerCase())) out.add(k);
            }
        }
        return out;
    }
}
