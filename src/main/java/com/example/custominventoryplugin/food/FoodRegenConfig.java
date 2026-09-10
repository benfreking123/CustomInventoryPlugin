package com.example.custominventoryplugin.food;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Food-granted regeneration, read from the {@code food-regen} section of
 * settings.yml.
 *
 * Eating is the only thing food does for health now. The
 * {@code natural_health_regeneration} gamerule is false on every world, so
 * vanilla's "a full hunger bar heals you" path is gone; what replaces it is a
 * timed bonus to Fabled's {@code stat_health_regen_mod}, which the Natural
 * Regeneration passive reads once a second.
 *
 * Amounts are whole HP per second on purpose: {@code PlayerData.getAttribute()}
 * returns an {@code int}, so a fractional bonus would truncate to nothing and
 * look like the feature was broken.
 */
public final class FoodRegenConfig {

    /** One food's effect. {@code regen} is flat HP/s for {@code seconds}. */
    public record Food(int regen, int seconds) { }

    private final JavaPlugin plugin;
    private final Map<Material, Food> foods = new EnumMap<>(Material.class);
    private boolean enabled = true;

    public FoodRegenConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        foods.clear();

        File settingsFile = new File(plugin.getDataFolder(), "settings.yml");
        FileConfiguration settings = YamlConfiguration.loadConfiguration(settingsFile);
        ConfigurationSection root = settings.getConfigurationSection("food-regen");
        if (root == null) {
            // No section at all means an un-migrated settings.yml. Staying off
            // is the honest outcome: inventing defaults here would put numbers
            // on tooltips that the config does not actually contain.
            enabled = false;
            plugin.getLogger().warning("food-regen: no 'food-regen' section in settings.yml — "
                    + "food grants no regeneration");
            return;
        }
        enabled = root.getBoolean("enabled", true);

        ConfigurationSection list = root.getConfigurationSection("foods");
        if (list == null) return;
        for (String key : list.getKeys(false)) {
            ConfigurationSection f = list.getConfigurationSection(key);
            if (f == null) continue;
            Material mat = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
            if (mat == null) {
                plugin.getLogger().warning("food-regen: '" + key + "' is not a material — skipped");
                continue;
            }
            int regen = f.getInt("regen", 0);
            int seconds = f.getInt("seconds", 0);
            if (regen <= 0 || seconds <= 0) {
                plugin.getLogger().warning("food-regen: " + mat + " has regen=" + regen
                        + " seconds=" + seconds + "; both must be above 0 — skipped");
                continue;
            }
            foods.put(mat, new Food(regen, seconds));
        }
    }

    public boolean isEnabled() { return enabled; }

    /** The effect for a material, or null when this food grants nothing. */
    public Food forMaterial(Material material) {
        if (!enabled || material == null) return null;
        return foods.get(material);
    }

    /** Every configured food, for the tooltip pass and {@code /debug}. */
    public Map<Material, Food> all() {
        return Collections.unmodifiableMap(foods);
    }
}
