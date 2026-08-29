package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.config.ConfigManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import studio.magemonkey.divinity.api.event.DivinityDamageEvent;
import studio.magemonkey.fabled.Fabled;
import studio.magemonkey.fabled.api.player.PlayerData;
import studio.magemonkey.fabled.api.skills.Skill;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Critical hits for basic (non-skill) attacks, using Fabled's crit attributes.
 *
 * <p>This lives here rather than in a Fabled skill because of event ordering.
 * Fabled's {@code onPhysicalDamage} and Divinity's {@code onVanillaDamage} both
 * sit at {@link EventPriority#HIGHEST} on the same handler list, and Fabled
 * enables first, so it runs first. At that point the event damage is only the
 * vanilla weapon hit — Divinity has not yet added the elemental damage that
 * makes up most of a Tower weapon's output. A Fabled-side multiplier therefore
 * scaled roughly 3 of a 7.3 damage hit, turning a "x1.5 crit" into about +20%.
 *
 * <p>Hooking {@link DivinityDamageEvent.BeforeScale} instead means the damage
 * map is fully populated, and {@code computeDamage} scales every element at
 * once. The roll still reads Fabled attributes through
 * {@link PlayerData#getAttribute(String)} — the same call Fabled's own
 * {@code Value Attribute} mechanic makes — so gems, rings and invested points
 * all feed basic-attack crit exactly as they feed skill crit.
 *
 * <p>Skill damage is skipped: skills roll their own crit inside their skill
 * graph, and double-rolling would stack two multipliers on one hit.
 */
public class BasicAttackCritListener implements Listener {

    /** Fabled category suffix for melee weapons. */
    private static final String MELEE = "attack";
    /** Fabled category suffix for bows and crossbows. */
    private static final String RANGED = "projectile";

    private final ConfigManager config;

    public BasicAttackCritListener(ConfigManager config) {
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDivinityDamage(DivinityDamageEvent.BeforeScale event) {
        if (!this.config.isCritEnabled()) return;
        if (Skill.isSkillDamage()) return;
        if (!(event.getDamager() instanceof Player)) return;

        Player player = (Player) event.getDamager();
        String category = categoryOf(event.getWeapon(), event.isProjectile());
        if (category == null) return;

        PlayerData data = Fabled.getData((OfflinePlayer) player);
        if (data == null) return;

        double chance = critChance(data, category);
        if (chance <= 0.0) return;
        if (ThreadLocalRandom.current().nextDouble(100.0) >= chance) return;

        final double multiplier = critMultiplier(data, category);
        event.computeDamage(damage -> damage * multiplier);

        String message = this.config.getCritMessage();
        if (message != null && !message.isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    /**
     * Which Fabled crit category a hit belongs to, or null for weapons that do
     * not get basic-attack crit (wands cast skills, which crit on their own).
     */
    private static String categoryOf(ItemStack weapon, boolean projectile) {
        if (projectile) return RANGED;
        if (weapon == null) return null;
        Material material = weapon.getType();
        if (material == Material.BOW || material == Material.CROSSBOW) return RANGED;
        return material.name().endsWith("_SWORD") ? MELEE : null;
    }

    /**
     * Base chance plus the flat {@code base_crit_chance} layers, lifted by the
     * increased-percent {@code stat_crit_chance} layers. Mirrors the formula in
     * the Fabled skill graph, where the generic and per-category attributes are
     * summed rather than treated as alternatives.
     */
    private double critChance(PlayerData data, String category) {
        double flat = this.config.getCritBaseChance()
                + data.getAttribute("base_crit_chance")
                + data.getAttribute("base_crit_chance_" + category);
        double increased = data.getAttribute("stat_crit_chance")
                + data.getAttribute("stat_crit_chance_" + category);
        return flat * (1.0 + increased * 0.01);
    }

    /** Base multiplier plus the {@code stat_crit_damage} layers, as percent. */
    private double critMultiplier(PlayerData data, String category) {
        double bonus = data.getAttribute("stat_crit_damage")
                + data.getAttribute("stat_crit_damage_" + category);
        return this.config.getCritBaseMultiplier() + bonus * 0.01;
    }
}
