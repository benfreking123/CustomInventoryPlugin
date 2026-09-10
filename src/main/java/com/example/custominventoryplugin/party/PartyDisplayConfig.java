package com.example.custominventoryplugin.party;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Background art for the party GUI, from the {@code party.display} section of
 * settings.yml.
 *
 * <p>The party menu used to hardcode {@code codex_bg} — the bestiary's frame —
 * which is why it read as the Compendium. It now has its own {@code party_bg}
 * glyph, and the pair is configurable for the same reason the bestiary's is:
 * nudging a 256px frame over a chest is trial and error, and the alternative is
 * a jar rebuild per attempt.
 */
public final class PartyDisplayConfig {

    /** ꐷ party_bg in Nexo's interface.yml. */
    public static final char DEFAULT_GLYPH = '\uA437';
    public static final int DEFAULT_SHIFT = 16;

    private final JavaPlugin plugin;

    private char backgroundGlyph = DEFAULT_GLYPH;
    private int backgroundShift = DEFAULT_SHIFT;

    public PartyDisplayConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        backgroundGlyph = DEFAULT_GLYPH;
        backgroundShift = DEFAULT_SHIFT;

        File settingsFile = new File(plugin.getDataFolder(), "settings.yml");
        FileConfiguration settings = YamlConfiguration.loadConfiguration(settingsFile);
        ConfigurationSection party = settings.getConfigurationSection("party");
        if (party == null) {
            return;
        }
        ConfigurationSection disp = party.getConfigurationSection("display");
        if (disp == null) {
            return;
        }
        String glyph = disp.getString("glyph", "");
        if (glyph != null && !glyph.isEmpty()) {
            backgroundGlyph = glyph.charAt(0);
        }
        backgroundShift = disp.getInt("shift", DEFAULT_SHIFT);
    }

    public char backgroundGlyph() { return backgroundGlyph; }

    public int backgroundShift() { return backgroundShift; }
}
