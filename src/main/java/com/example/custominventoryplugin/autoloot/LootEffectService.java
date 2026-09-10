package com.example.custominventoryplugin.autoloot;

import com.example.custominventoryplugin.party.PartyScoreboardService;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    private Supplier<Collection<Scoreboard>> boardSource;

    public LootEffectService(JavaPlugin plugin, AutoLootConfig config, TooltipStyleService tooltip) {
        this.plugin = plugin;
        this.config = config;
        this.tooltip = tooltip;
    }

    /**
     * Extra scoreboards to tint on, set after construction because the party
     * services are built later in onEnable.
     */
    public void setBoardSource(Supplier<Collection<Scoreboard>> boardSource) {
        this.boardSource = boardSource;
    }

    /** Glow + (mythic/legendary) burst on a freshly-spawned ground item. */
    public void decorate(Item entity) {
        if (entity == null || !config.effectsEnabled()) return;
        String tier = tooltip.resolveTier(entity.getItemStack());
        ChatColor glow = config.glowFor(tier);
        if (glow == null) return;

        entity.setGlowing(true);
        forEachTeam(glow, team -> team.addEntry(entity.getUniqueId().toString()));

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
        forEachTeam(glow, team -> team.removeEntry(entity.getUniqueId().toString()));
    }

    /**
     * Apply {@code action} to the glow team on every scoreboard a player might
     * be viewing.
     *
     * <p>Glow tint is read from the VIEWER's scoreboard, so the main board alone
     * is not enough: anyone holding a personal board (the party sidebar) would
     * see plain white glow. {@link PartyScoreboardService} mirrors the team
     * definitions onto those boards; the entries have to be written here,
     * because items spawn long after a board is handed out.
     */
    private void forEachTeam(ChatColor color, Consumer<Team> action) {
        String name = "cip_lt_" + color.name();
        List<Scoreboard> boards = new ArrayList<>();
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        if (boardSource != null) boards.addAll(boardSource.get());

        for (Scoreboard board : boards) {
            try {
                Team team = board.getTeam(name);
                if (team == null) {
                    team = board.registerNewTeam(name);
                    team.setColor(color);
                }
                action.accept(team);
            } catch (Throwable t) {
                plugin.getLogger().warning("Loot glow team failed: " + t.getMessage());
            }
        }
    }
}
