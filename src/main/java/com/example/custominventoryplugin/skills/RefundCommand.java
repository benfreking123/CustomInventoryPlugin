package com.example.custominventoryplugin.skills;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code /refund} — refund spent skill points without paying out the free
 * gem levels (see {@link GemSkillLevels#refundAll}).
 *
 * <p>Fabled's own {@code /class refund} calls {@code refundSkills()} straight,
 * which would hand back a point for every socketed gem. {@link Intercept}
 * catches a typed {@code /class refund} and routes it here instead, so there
 * is one refund path. (The Compendium tile is pointed at {@code /refund}
 * directly, because {@code Player#performCommand} skips the preprocess event.)
 * {@code /class refund attribute <name>} is left alone — attributes have no
 * free levels.
 */
public final class RefundCommand implements CommandExecutor {

    private final CustomInventoryPlugin plugin;

    public RefundCommand(CustomInventoryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can refund their own skills.");
            return true;
        }
        run(plugin, player);
        return true;
    }

    static void run(CustomInventoryPlugin plugin, Player player) {
        int refunded = plugin.getGemSkillLevels().refundAll(player);
        if (refunded < 0) {
            player.sendMessage(color("&cYour class data is still loading — try again in a moment."));
            return;
        }
        player.sendMessage(color("&8[&dSkill&8] &7Refunded &f" + refunded + "&7 skill point"
                + (refunded == 1 ? "" : "s") + ". Socketed gems stay at level 1."));
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Reroutes a typed {@code /class refund} (no further args) to {@link RefundCommand}. */
    public static final class Intercept implements Listener {

        private static final Pattern CLASS_REFUND =
                Pattern.compile("^/(?:fabled:)?class\\s+refund\\s*$", Pattern.CASE_INSENSITIVE);

        private final CustomInventoryPlugin plugin;

        public Intercept(CustomInventoryPlugin plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onCommand(PlayerCommandPreprocessEvent event) {
            String msg = event.getMessage().trim().toLowerCase(Locale.ROOT);
            if (!msg.contains("refund") || !CLASS_REFUND.matcher(msg).matches()) return;
            event.setCancelled(true);
            run(plugin, event.getPlayer());
        }
    }
}
