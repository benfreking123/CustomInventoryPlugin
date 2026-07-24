package com.example.custominventoryplugin.autoloot;

import com.example.custominventoryplugin.tooltip.TooltipStyleService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Rarity flair for ground loot during the AutoLoot pop-out window. Every drop
 * glows in its Divinity-tier colour (via a per-colour scoreboard team, the only
 * way to tint a non-player entity's glow); mythic/legendary drops also get a
 * tier-coloured particle burst.
 *
 * Tier is resolved through {@link TooltipStyleService#resolveTier(ItemStack)},
 * the same Divinity reflection hook the tooltip frames use.
 */
public final class LootEffectService {

    private final JavaPlugin plugin;
    private final AutoLootConfig config;
    private final TooltipStyleService tooltip;

    public LootEffectService(JavaPlugin plugin, AutoLootConfig config, TooltipStyleService tooltip) {
        this.plugin = plugin;
        this.config = config;
        this.tooltip = tooltip;
    }

    /** Glow + (mythic/legendary) burst on a freshly-spawned ground item. */
    public void decorate(Item entity) {
        if (entity == null || !config.effectsEnabled()) return;
        String tier = tooltip.resolveTier(entity.getItemStack());
        ChatColor glow = config.glowFor(tier);
        if (glow == null) return;

        entity.setGlowing(true);
        Team team = teamFor(glow);
        if (team != null) team.addEntry(entity.getUniqueId().toString());

        if (config.isBurst(tier)) {
            Color c = config.burstColorFor(tier);
            if (c != null && entity.getWorld() != null) {
                Location loc = entity.getLocation().add(0, 0.25, 0);
                entity.getWorld().spawnParticle(Particle.DUST, loc, 16, 0.25, 0.25, 0.25, 0,
                        new Particle.DustOptions(c, 1.3f));
            }
        }
    }

    /** Clear the glow team entry when the item is absorbed/removed. */
    public void undecorate(Item entity) {
        if (entity == null || !config.effectsEnabled()) return;
        String tier = tooltip.resolveTier(entity.getItemStack());
        ChatColor glow = config.glowFor(tier);
        if (glow == null) return;
        Team team = teamFor(glow);
        if (team != null) team.removeEntry(entity.getUniqueId().toString());
    }

    /** One shared team per glow colour on the main scoreboard, created on demand. */
    private Team teamFor(ChatColor color) {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            String name = "cip_lt_" + color.name();
            Team team = board.getTeam(name);
            if (team == null) {
                team = board.registerNewTeam(name);
                team.setColor(color);
            }
            return team;
        } catch (Throwable t) {
            plugin.getLogger().warning("Loot glow team failed: " + t.getMessage());
            return null;
        }
    }
}
