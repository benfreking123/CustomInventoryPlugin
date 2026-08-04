package com.example.custominventoryplugin.tooltip;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Pixel arithmetic for tooltip lines.
 *
 * Minecraft gives no layout control beyond "draw the next glyph", so every
 * aligned thing on a tooltip is built from measured advances plus the pack's
 * signed space glyphs. Two jobs live here:
 *
 * <ul>
 *   <li><b>Measuring</b> a line, so a section bar can be sized to the widest
 *       content line and therefore span the whole tooltip. Widths come from
 *       {@code glyph-advances.yml}, written by infra/gen-tooltip-assets.py from
 *       the PNGs it just baked — a hardcoded table would drift the first time a
 *       pill's label changed.</li>
 *   <li><b>Composing</b> a bar of an arbitrary pixel width out of its four
 *       baked parts: labeled left cap, 8px filler, 1px filler, end cap.</li>
 * </ul>
 */
final class TooltipGlyphs {

    /** Section bars, in the order infra/gen-tooltip-assets.py assigns them. */
    enum Bar {
            ATTACK, DEFENSE, REQUIREMENTS, BONUSES, SET, DESCRIPTION, DAMAGE, COST,
            SKILL, CONTENTS, VALUE;

        /** Left cap of this bar; the other three parts follow it. */
        char left() {
            return (char) (0xE190 + ordinal() * 4);
        }
    }

    private static final int FILL_WIDE = 8;
    private static final int END_WIDTH = 3;
    /** VT323 at the body size. Overridden for the big-font headline. */
    static final int BODY_CHAR = 4;
    static final int BIG_CHAR = 6;

    private final Map<Character, Integer> advances = new HashMap<>();
    private final File file;
    private int textAdvance = BODY_CHAR;
    private long loadedStamp = -1;

    TooltipGlyphs(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "glyph-advances.yml");
        refresh();
    }

    /**
     * Re-read the table when the pack has been regenerated. Cheap enough to
     * call once per tooltip build (one stat), and it means a
     * gen-tooltip-assets.py run takes effect without a reload — including the
     * case where the file only appears after the plugin has started.
     */
    void refresh() {
        long stamp = file.isFile() ? file.lastModified() : 0;
        if (stamp == loadedStamp) return;
        loadedStamp = stamp;
        advances.clear();
        textAdvance = BODY_CHAR;
        if (stamp == 0) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        textAdvance = yml.getInt("text-advance", BODY_CHAR);
        ConfigurationSection section = yml.getConfigurationSection("advances");
        if (section == null) return;
        for (String hex : section.getKeys(false)) {
            try {
                advances.put((char) Integer.parseInt(hex, 16), section.getInt(hex));
            } catch (NumberFormatException ignored) {
                // a malformed row costs one glyph's precision, not the tooltip
            }
        }
    }

    boolean ready() {
        return !advances.isEmpty();
    }

    /**
     * Exact horizontal cursor move, using the pack's `space` provider
     * (E1F0.. = +1,+2,+4,+8,+16,+32 / E1F8.. = the negatives). Negative moves
     * are how text lands *inside* a glyph already drawn — the level in the
     * REQUIREMENTS bar, the repeat count in the DAMAGE bar.
     */
    static String pad(int px) {
        if (px == 0) return "";
        int base = px > 0 ? 0xE1F0 : 0xE1F8;
        int n = Math.abs(px);
        StringBuilder sb = new StringBuilder();
        for (int k = 5; k >= 0; k--) {
            int unit = 1 << k;
            while (n >= unit) {
                sb.append((char) (base + k));
                n -= unit;
            }
        }
        return sb.toString();
    }

    /** Width of a legacy-coded string in pixels, body font. */
    int width(String legacy) {
        return width(legacy, textAdvance);
    }

    /** Width of a legacy-coded string, with an explicit per-character advance. */
    int width(String legacy, int charAdvance) {
        int total = 0;
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if ((c == '&' || c == '\u00a7') && i + 1 < legacy.length()) {
                i++;                                 // colour code, no advance
                continue;
            }
            Integer glyph = advances.get(c);
            if (glyph != null) {
                total += glyph;
            } else if (c >= 0xE1F0 && c <= 0xE1FF) {
                int unit = 1 << (c & 0x7);
                total += (c & 0x8) == 0 ? unit : -unit;
            } else if (c >= 0xE100 && c <= 0xE1EF) {
                total += 0;                          // unknown glyph: no guess
            } else {
                total += charAdvance;
            }
        }
        return total;
    }

    /**
     * A section bar exactly {@code width} pixels wide.
     *
     * Every bitmap glyph advances one pixel further than it inks, so each tile
     * is followed by a 1px negative move. Doing it per tile rather than once at
     * the end matters twice over: the tiles butt together instead of leaving a
     * seam every 8px, and the bar's ink stops where its advance stops. Banking
     * the whole correction at the end left the ink running past the width the
     * line reported, so the tooltip frame — sized from that width — was
     * narrower than the bars drawn inside it.
     */
    String bar(Bar bar, int width) {
        char left = bar.left();
        int leftArt = Math.max(0, advance(left) - 1);
        int fill = Math.max(0, width - leftArt - END_WIDTH);
        int wide = fill / FILL_WIDE;
        int thin = fill % FILL_WIDE;

        String back = pad(-1);
        StringBuilder sb = new StringBuilder();
        sb.append(left).append(back);
        sb.append((String.valueOf((char) (left + 1)) + back).repeat(wide));
        sb.append((String.valueOf((char) (left + 2)) + back).repeat(thin));
        sb.append((char) (left + 3)).append(back);
        return sb.toString();
    }

    /** Advance of one glyph, 0 when the pack hasn't reported it. */
    int advance(char glyph) {
        return advances.getOrDefault(glyph, 0);
    }
}
