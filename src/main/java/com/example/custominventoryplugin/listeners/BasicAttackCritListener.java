package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.combat.CritCalculator;
import com.example.custominventoryplugin.config.ConfigManager;
import org.bukkit.ChatColor;
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
 * once. The roll reads Fabled attributes through
 * {@link PlayerData#getAttribute(String)} — the same call Fabled's own
 * {@code Value Attribute} mechanic makes — so gems, rings and invested points
 * all feed basic-attack crit exactly as they feed skill crit.
 *
 * <p>Skill damage is skipped: skills roll their own crit inside their skill
 * graph, and double-rolling would stack two multipliers on one hit.
 */
public class BasicAttackCritListener implements Listener {

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

        double chance = CritCalculator.chance(data, category, this.config.getCritBaseChance());
        if (chance <= 0.0) return;
        if (ThreadLocalRandom.current().nextDouble(100.0) >= chance) return;

        final double multiplier =
                CritCalculator.multiplier(data, category, this.config.getCritBaseMultiplier());
        event.computeDamage(damage -> damage * multiplier);

        String message = this.config.getCritMessage();
        if (message != null && !message.isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    /**
     * Arrows arrive with the bow as the weapon, but a thrown trident or a
     * skill-spawned projectile can arrive with something else, so trust
     * Divinity's projectile flag first and fall back to the material.
     */
    private static String categoryOf(ItemStack weapon, boolean projectile) {
        if (projectile) return CritCalculator.RANGED;
        return CritCalculator.categoryOf(weapon == null ? null : weapon.getType());
    }
}
