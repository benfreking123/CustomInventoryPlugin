package com.example.custominventoryplugin.combat;

import org.bukkit.Material;
import studio.magemonkey.fabled.api.player.PlayerData;

/**
 * The one implementation of Tower's crit formula.
 *
 * <p>Three places need these numbers: the listener that rolls a crit, the
 * weapon tooltip that states the baseline, and the stats panel that reports
 * your effective rate. Keeping the arithmetic here is what stops the HUD from
 * confidently displaying a number the game does not actually use.
 *
 * <p>The shape mirrors the Fabled skill graph, where the generic (unsuffixed)
 * and per-category attributes are summed rather than treated as alternatives:
 *
 * <pre>
 * chance% = (base + base_crit_chance + base_crit_chance_&lt;cat&gt;)
 *           * (1 + (stat_crit_chance_&lt;cat&gt; + stat_crit_chance) * 0.01)
 * damage *= base_mult + (stat_crit_damage_&lt;cat&gt; + stat_crit_damage) * 0.01
 * </pre>
 */
public final class CritCalculator {

    /** Fabled category suffix for melee weapons. */
    public static final String MELEE = "attack";
    /** Fabled category suffix for bows and crossbows. */
    public static final String RANGED = "projectile";
    /** Fabled category suffix for wand-cast skills. */
    public static final String SPELL = "spell";

    private CritCalculator() {
    }

    /**
     * Whether basic attacks with this material can crit. Wands are absent on
     * purpose: they cast skills, which roll their own crit inside the skill.
     */
    public static boolean isCritWeapon(Material material) {
        return categoryOf(material) != null;
    }

    /** Crit category for a held weapon, or null if it gets no basic-attack crit. */
    public static String categoryOf(Material material) {
        if (material == null) return null;
        if (material == Material.BOW || material == Material.CROSSBOW) return RANGED;
        return material.name().endsWith("_SWORD") ? MELEE : null;
    }

    /**
     * Percentage points this player adds to whatever flat base the hit starts
     * from. Reported on its own because that base is not a constant: basic
     * attacks use the configured baseline, while each skill hardcodes its own
     * (2 to 4 across the current roster). Only the layer is universally true.
     */
    public static double baseLayer(PlayerData data, String category) {
        if (data == null) return 0.0;
        return data.getAttribute("base_crit_chance")
                + data.getAttribute("base_crit_chance_" + category);
    }

    /** Increased-crit-chance percent, applied multiplicatively to the flat total. */
    public static double increasedLayer(PlayerData data, String category) {
        if (data == null) return 0.0;
        return data.getAttribute("stat_crit_chance")
                + data.getAttribute("stat_crit_chance_" + category);
    }

    /**
     * Effective crit chance in percent.
     *
     * @param base the flat chance before attributes — the configured
     *             basic-attack baseline, or a skill's own built-in chance
     */
    public static double chance(PlayerData data, String category, double base) {
        if (data == null) return base;
        return (base + baseLayer(data, category))
                * (1.0 + increasedLayer(data, category) * 0.01);
    }

    /**
     * Effective damage multiplier on a crit.
     *
     * @param base the multiplier before attributes, normally 1.5
     */
    public static double multiplier(PlayerData data, String category, double base) {
        if (data == null) return base;
        double bonus = data.getAttribute("stat_crit_damage")
                + data.getAttribute("stat_crit_damage_" + category);
        return base + bonus * 0.01;
    }
}
