package com.example.custominventoryplugin.tooltip;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import studio.magemonkey.fabled.Fabled;
import studio.magemonkey.fabled.api.Settings;
import studio.magemonkey.fabled.api.player.PlayerData;
import studio.magemonkey.fabled.api.skills.Skill;
import studio.magemonkey.fabled.dynamic.DynamicSkill;
import studio.magemonkey.fabled.dynamic.EffectComponent;
import studio.magemonkey.fabled.dynamic.TriggerHandler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wynn-style page-1 reflow for skill gems (Divinity custom_items, ids
 * {@code gem_*} / {@code passive_*}). Layout:
 * badge row [RARITY][GEM][ELEMENT(%)] + Active/Passive tag, the gem's flavor
 * text, then a stats block sourced from the connected Fabled dynamic skill —
 * the skill's own icon-lore lines (damage-type split percentages, damage
 * classifier, crit info), buff multipliers and numeric damage walked out of
 * the component tree, and live mana/cooldown with per-level scaling. The
 * skill is resolved by display name minus " Gem" (SkillHandler's rule).
 * See Docs/deisgn/tooltips.md.
 */
final class GemTooltip {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    /** Rarity pills U+E100.. (gen-tooltip-assets.py order). */
    private static final Map<String, Character> RARITY_PILL = Map.of(
            "common", '\uE100', "uncommon", '\uE101', "rare", '\uE102',
            "mythic", '\uE103', "legendary", '\uE104');
    /** Type pill band ends with the gem pill (13th of TYPES). */
    private static final char GEM_PILL = '\uE11C';
    /** Element pills U+E130.. */
    private static final Map<String, Character> ELEMENT_PILL = Map.of(
            "physical", '\uE130', "fire", '\uE131', "ice", '\uE132',
            "lightning", '\uE133', "chaotic", '\uE134');
    private static final char DIVIDER = '\uE142';
    private static final int MAX_WALK_DEPTH = 16;
    /** Snapshot layout version (bump when the parse/stored shape changes). */
    private static final String SNAPSHOT_VERSION = "2";

    /** A lore line that is a stat, not flavor: "Cooldown: 3s", "Crit: ...". */
    private static final Pattern STAT_LABEL = Pattern.compile(
            "(?i)^(damage|crit|mana|cooldown|heals?|boost|duration|radius)\\b\\s*:.*");
    /** Fabled icon-lore placeholder, e.g. {attr:mana} / {attr:Damage.value}. */
    private static final Pattern ATTR_PLACEHOLDER = Pattern.compile("\\{attr:([^}]+)}");
    /** "Type: Name(150%)" value split. */
    private static final Pattern TYPE_VALUE = Pattern.compile("([^(]+)(?:\\((\\d+%)\\))?");

    private final TooltipConfig config;
    private final NamespacedKey pageKey;
    private final NamespacedKey gemInfoKey;
    private final NamespacedKey gemDescKey;
    private final NamespacedKey gemStatsKey;

    GemTooltip(JavaPlugin plugin, TooltipConfig config) {
        this.config = config;
        this.pageKey = new NamespacedKey(plugin, "tt_page");
        this.gemInfoKey = new NamespacedKey(plugin, "tt_gem");
        this.gemDescKey = new NamespacedKey(plugin, "tt_gem_desc");
        this.gemStatsKey = new NamespacedKey(plugin, "tt_gem_stats");
    }

    boolean isGem(String itemId) {
        if (!config.isGemTooltipEnabled() || itemId == null) return false;
        for (String prefix : config.getGemIdPrefixes()) {
            if (itemId.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Rarity id (lowercase, e.g. "uncommon") parsed from the gem's "Tier:"
     * lore line (or the PDC snapshot once reflowed). Drives the frame style.
     */
    String rarity(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        ItemMeta meta = stack.getItemMeta();
        Info info = loadInfo(meta.getPersistentDataContainer());
        if (info == null) info = parse(meta).info;
        return info == null || info.rarity == null ? null
                : info.rarity.toLowerCase(Locale.ROOT);
    }

    /**
     * Rebuild the live (page 1) lore. The original Form/Type/Tier, flavor
     * text and hand-written stat lines are snapshotted to PDC on first pass
     * (re-migrated when {@link #SNAPSHOT_VERSION} changes) so the reflow
     * stays idempotent.
     *
     * @return true when the item was handled as a gem (even if unchanged)
     */
    boolean reflow(ItemStack stack, Player viewer, Component footer) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.getOrDefault(pageKey, PersistentDataType.INTEGER, 1) != 1) return true;

        Info info = loadInfo(pdc);
        List<String> desc;
        List<String> loreStats;
        if (info == null) {
            Parsed parsed = parse(meta);
            if (parsed.info == null) return false; // no Form: line — not gem lore
            info = parsed.info;
            desc = parsed.desc;
            loreStats = parsed.stats;
            pdc.set(gemInfoKey, PersistentDataType.STRING, String.join("\n",
                    SNAPSHOT_VERSION, ns(info.form), ns(info.type), ns(info.typePercent), ns(info.rarity)));
            pdc.set(gemDescKey, PersistentDataType.STRING, String.join("\n", desc));
            pdc.set(gemStatsKey, PersistentDataType.STRING, String.join("\n", loreStats));
        } else {
            desc = splitLines(pdc.get(gemDescKey, PersistentDataType.STRING));
            loreStats = splitLines(pdc.get(gemStatsKey, PersistentDataType.STRING));
        }

        Skill skill = findSkill(stack);
        int playerLevel = skill == null ? 0 : skillLevel(viewer, skill.getName());
        int level = Math.max(1, playerLevel);
        IconDetails icon = skill == null
                ? new IconDetails(List.of(), List.of()) : iconDetails(skill, level);

        List<Component> lore = new ArrayList<>();
        lore.add(noShadow(LEGACY.deserialize(badgeRow(info))));
        lore.add(divider());
        // Prefer the Fabled skill's own lore body (type split + description);
        // the gem item's hand-written text is the fallback.
        for (String line : icon.body().isEmpty() ? desc : icon.body()) {
            lore.add(LEGACY.deserialize(line));
        }

        List<String> stats = statsBlock(skill, icon.stats(), loreStats, playerLevel);
        if (!stats.isEmpty()) {
            lore.add(divider());
            for (String line : stats) {
                lore.add(LEGACY.deserialize(line));
            }
        }
        lore.add(Component.empty());
        lore.add(footer);

        if (!lore.equals(meta.lore())) {
            meta.lore(lore);
        }
        stack.setItemMeta(meta); // PDC snapshot may be new even when lore matched
        return true;
    }

    // ─── layout ─────────────────────────────────────────────────────────────

    /** "[RARITY][GEM][ELEM] (150%)  Active" — non-element types as colored text. */
    private String badgeRow(Info info) {
        StringBuilder sb = new StringBuilder("&f");
        Character rarity = info.rarity == null ? null
                : RARITY_PILL.get(info.rarity.toLowerCase(Locale.ROOT));
        sb.append(rarity == null ? RARITY_PILL.get("common") : rarity);
        sb.append(' ').append(GEM_PILL);
        if (info.type != null && !info.type.isBlank()) {
            Character elem = ELEMENT_PILL.get(info.type.toLowerCase(Locale.ROOT));
            if (elem != null) {
                sb.append(' ').append(elem);
            } else {
                sb.append("  ").append(config.gemTypeColor(info.type)).append(info.type);
            }
            if (info.typePercent != null) {
                sb.append(" &7(").append(info.typePercent).append(')');
            }
        }
        if (info.form != null && !info.form.isBlank()) {
            sb.append("  &7").append(info.form);
        }
        return sb.toString();
    }

    /**
     * Stats section. With a connected skill: its icon-lore stat lines
     * (damage classifier, crit info), walked buff multipliers and numeric
     * damage, then live mana/cooldown and the viewer's level. Without one:
     * the gem's own hand-written stat lines, verbatim.
     */
    private List<String> statsBlock(Skill skill, List<String> iconStats,
                                    List<String> loreStats, int playerLevel) {
        if (skill == null) return new ArrayList<>(loreStats);

        boolean learned = playerLevel > 0;
        int level = Math.max(1, playerLevel);
        List<String> out = new ArrayList<>();

        boolean numericDamageShown = false;
        for (String line : iconStats) {
            out.add(line);
            String plain = PLAIN.serialize(LEGACY.deserialize(line)).trim();
            if (plain.matches("(?i)^damage\\s*:\\s*[0-9.]+.*")) numericDamageShown = true;
        }

        for (String line : buffLines(skill, level)) {
            out.add(line);
        }

        if (!numericDamageShown) {
            double[] dmg = bestMechanic(skill, "damage", level);
            if (dmg != null && dmg[0] > 0) {
                String line = "&2Damage: &f" + fmt(dmg[0]);
                if (dmg[1] > 1.5) line += " &8x" + Math.round(dmg[1]);
                out.add(line);
            }
        }
        double[] heal = bestMechanic(skill, "heal", level);
        if (heal != null && heal[0] > 0) {
            String line = "&2Heals: &a" + fmt(heal[0]);
            if (heal[1] > 1.5) line += " &8x" + Math.round(heal[1]);
            out.add(line);
        }

        double mana = safe(() -> skill.getManaCost(level));
        if (mana > 0) {
            double scale = safe(() -> skill.getManaCost(level + 1)) - mana;
            out.add("&2Mana: &b" + fmt(mana) + (scale > 0 ? " &8(+" + fmt(scale) + "/lvl)" : ""));
        }
        double cd = safe(() -> skill.getCooldown(level));
        if (cd > 0) out.add("&2Cooldown: &f" + fmt(cd) + "s");

        if (learned) {
            out.add("&2Level: &f" + playerLevel + "&8/" + skill.getMaxLevel());
        } else if (!out.isEmpty()) {
            out.add("&8Socket into a skill slot to unlock");
        }
        return out;
    }

    /**
     * The skill's icon-lore (its designed tooltip) split into two buckets,
     * source order preserved:
     * <ul>
     *   <li>{@code body} — the damage-type split ("Type: Physical (100%),
     *       Projectile (150%)") and description lines,</li>
     *   <li>{@code stats} — stat-labeled lines (damage classifier, crit
     *       info, and any other "Label: value" detail).</li>
     * </ul>
     * Skips name/tier/req furniture and the mana/cooldown/level lines we
     * compute live. Fabled stores these lines §-color-translated, so codes
     * are normalized before matching. {@code {attr:X}} placeholders are
     * resolved numerically or the line is dropped (e.g. Dart's scripted
     * damage variable).
     */
    private record IconDetails(List<String> body, List<String> stats) { }

    private IconDetails iconDetails(Skill skill, int level) {
        List<String> body = new ArrayList<>();
        List<String> stats = new ArrayList<>();
        for (String rawLine : iconLore(skill)) {
            if (rawLine == null) continue;
            String raw = rawLine.replace('\u00a7', '&');
            if (raw.contains("{name}") || raw.contains("{req:") || raw.contains("{type}")) continue;
            String plain = PLAIN.serialize(LEGACY.deserialize(raw)).trim();
            if (plain.isEmpty()) continue;
            int colon = plain.indexOf(':');
            String label = colon > 0
                    ? plain.substring(0, colon).trim().toLowerCase(Locale.ROOT) : "";
            switch (label) {
                case "tier", "level", "cost", "mana", "cooldown" -> { continue; }
                default -> { }
            }
            String resolved = resolveAttrs(raw, skill, level);
            if (resolved == null) continue;
            if (label.equals("type") || label.isEmpty()) {
                body.add(resolved);
            } else {
                stats.add(resolved);
            }
        }
        return new IconDetails(body, stats);
    }

    /** Raw icon-lore config lines (private on Fabled's Skill). */
    @SuppressWarnings("unchecked")
    private List<String> iconLore(Skill skill) {
        try {
            Field field = Skill.class.getDeclaredField("iconLore");
            field.setAccessible(true);
            Object v = field.get(skill);
            return v instanceof List<?> list ? (List<String>) list : List.of();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Replace {attr:X} with a number, or null when any placeholder resists. */
    private String resolveAttrs(String raw, Skill skill, int level) {
        Matcher m = ATTR_PLACEHOLDER.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Double value = attrValue(skill, m.group(1), level);
            if (value == null || value <= 0) return null;
            m.appendReplacement(sb, Matcher.quoteReplacement(fmt(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Icon-lore attribute value: dotted keys ("Damage.value") go through the
     * skill's icon-key component registry; plain keys through its settings.
     * Null when unresolvable or non-numeric (e.g. Dart's variable damage).
     */
    private Double attrValue(Skill skill, String key, int level) {
        try {
            int dot = key.indexOf('.');
            if (dot > 0) {
                if (!(skill instanceof DynamicSkill)) return null;
                Field field = DynamicSkill.class.getDeclaredField("attribKeys");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, EffectComponent> keys = (Map<String, EffectComponent>) field.get(skill);
                EffectComponent comp = keys.get(key.substring(0, dot));
                if (comp == null) return null;
                double v = comp.getSettings().getAttr(key.substring(dot + 1), level, 0);
                return v > 0 ? v : null;
            }
            double v = skill.getSettings().getAttr(key, level, 0);
            return v > 0 ? v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ─── original lore parsing ──────────────────────────────────────────────

    private record Info(String form, String type, String typePercent, String rarity) { }

    private record Parsed(Info info, List<String> desc, List<String> stats) { }

    private Info loadInfo(PersistentDataContainer pdc) {
        String raw = pdc.get(gemInfoKey, PersistentDataType.STRING);
        if (raw == null) return null;
        String[] parts = raw.split("\n", -1);
        if (parts.length < 5 || !SNAPSHOT_VERSION.equals(parts[0])) return null; // legacy: re-parse
        return new Info(nn(parts[1]), nn(parts[2]), nn(parts[3]), nn(parts[4]));
    }

    /**
     * Split the gem lore into Form/Type(%)/Tier, flavor text, and
     * hand-written stat lines. Handles both pristine lore and lore already
     * reflowed by an older snapshot version (furniture is regenerated).
     */
    private Parsed parse(ItemMeta meta) {
        List<Component> lore = meta.lore();
        if (lore == null) return new Parsed(null, List.of(), List.of());
        String form = null, type = null, percent = null, rarity = null;
        List<String> desc = new ArrayList<>();
        List<String> stats = new ArrayList<>();
        for (Component line : lore) {
            String plain = PLAIN.serialize(line);
            String trimmed = plain.trim();
            if (trimmed.startsWith("Form") && trimmed.contains(":")) {
                form = clean(trimmed.substring(trimmed.indexOf(':') + 1));
            } else if (trimmed.startsWith("Type") && trimmed.contains(":")) {
                Matcher m = TYPE_VALUE.matcher(clean(trimmed.substring(trimmed.indexOf(':') + 1)));
                if (m.matches()) {
                    type = m.group(1).trim();
                    percent = m.group(2);
                }
            } else if (trimmed.startsWith("Tier") && trimmed.contains(":")) {
                rarity = clean(trimmed.substring(trimmed.indexOf(':') + 1));
            } else if (isFurniture(plain, trimmed)) {
                continue; // pill badges, footers, dividers: rebuilt fresh
            } else if (STAT_LABEL.matcher(trimmed).matches()) {
                stats.add(LEGACY.serialize(line));
            } else {
                desc.add(LEGACY.serialize(line));
            }
        }
        while (!desc.isEmpty() && isBlankLegacy(desc.get(0))) desc.remove(0);
        while (!desc.isEmpty() && isBlankLegacy(desc.get(desc.size() - 1))) desc.remove(desc.size() - 1);
        return new Parsed(form == null ? null : new Info(form, type, percent, rarity), desc, stats);
    }

    private static boolean isFurniture(String plain, String trimmed) {
        if (trimmed.contains("Press F") || trimmed.contains("Socket into a skill slot")) return true;
        return plain.chars().anyMatch(c -> c >= 0xE100 && c <= 0xE14F);
    }

    /** Strip stray legacy artifacts (e.g. the sigil gems' broken "&S" code). */
    private static String clean(String s) {
        return s.replace("&", "").trim();
    }

    private static boolean isBlankLegacy(String legacy) {
        return PLAIN.serialize(LEGACY.deserialize(legacy)).isBlank();
    }

    private static List<String> splitLines(String raw) {
        return raw == null || raw.isEmpty()
                ? new ArrayList<>() : new ArrayList<>(List.of(raw.split("\n", -1)));
    }

    private static String ns(String s) {
        return s == null ? "" : s;
    }

    private static String nn(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Component noShadow(Component c) {
        return c.style(s -> s.shadowColor(ShadowColor.none()));
    }

    private static Component divider() {
        return noShadow(LEGACY.deserialize("&8" + String.valueOf(DIVIDER).repeat(3)));
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 0.005) return String.valueOf(Math.round(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static double safe(java.util.function.DoubleSupplier s) {
        try {
            return s.getAsDouble();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ─── Fabled bridge ──────────────────────────────────────────────────────

    /** Connected dynamic skill: display name minus " Gem" (SkillHandler's rule). */
    private Skill findSkill(ItemStack stack) {
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) return null;
            String stripped = PLAIN.serialize(meta.displayName()).trim();
            if (!stripped.endsWith(" Gem")) return null;
            return Fabled.getSkill(stripped.substring(0, stripped.length() - 4).trim());
        } catch (Throwable t) {
            return null;
        }
    }

    private int skillLevel(Player viewer, String skillName) {
        if (viewer == null) return 0;
        try {
            PlayerData data = Fabled.getData(viewer);
            return data == null ? 0 : data.getSkillLevel(skillName);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ─── component-tree walk ────────────────────────────────────────────────

    /**
     * "Boost" lines for Buff mechanics that multiply a Divinity damage type,
     * e.g. Frost Bomb's DIVINITY_damage_ice x1.5.
     */
    private List<String> buffLines(Skill skill, int level) {
        List<String> out = new ArrayList<>();
        if (!(skill instanceof DynamicSkill dyn)) return out;
        try {
            for (EffectComponent root : rootComponents(dyn)) {
                collectBuffs(root, level, 0, out);
            }
        } catch (Throwable ignored) {
            // walk is best-effort flair
        }
        return out;
    }

    private void collectBuffs(EffectComponent comp, int level, int depth, List<String> out) {
        if (comp == null || depth > MAX_WALK_DEPTH) return;
        try {
            if ("buff".equalsIgnoreCase(comp.getKey())) {
                Settings s = comp.getSettings();
                String type = s.getString("type", "");
                String modifier = s.getString("modifier", "");
                double value = s.getAttr("value", level, 0);
                if ("multiplier".equalsIgnoreCase(modifier)
                        && type.toLowerCase(Locale.ROOT).startsWith("divinity_damage_")
                        && value > 0 && Math.abs(value - 1.0) > 0.001) {
                    String elem = type.substring("divinity_damage_".length()).toLowerCase(Locale.ROOT);
                    String pretty = elem.substring(0, 1).toUpperCase(Locale.ROOT) + elem.substring(1);
                    out.add("&2Multiplier: " + config.gemTypeColor(elem) + pretty + " Damage &6x" + fmt(value));
                }
            }
        } catch (Throwable ignored) {
            // skip malformed component
        }
        for (EffectComponent child : comp.children) {
            collectBuffs(child, level, depth + 1, out);
        }
    }

    /**
     * Largest {@code value} of the given mechanic in the skill's component
     * tree, with the product of enclosing Repeat repetitions as a hit count.
     *
     * @return {value, hits} or null when the mechanic isn't present
     */
    private double[] bestMechanic(Skill skill, String mechanicKey, int level) {
        if (!(skill instanceof DynamicSkill)) return null;
        double[] best = null;
        try {
            for (EffectComponent root : rootComponents((DynamicSkill) skill)) {
                best = walk(root, mechanicKey, level, 1.0, 0, best);
            }
        } catch (Throwable t) {
            return null;
        }
        return best;
    }

    private double[] walk(EffectComponent comp, String mechanicKey, int level,
                          double hits, int depth, double[] best) {
        if (comp == null || depth > MAX_WALK_DEPTH) return best;
        String key;
        Settings settings;
        try {
            key = comp.getKey();
            settings = comp.getSettings();
        } catch (Throwable t) {
            return best;
        }
        if ("repeat".equalsIgnoreCase(key) && settings != null) {
            hits *= Math.max(1, settings.getAttr("repetitions", level, 1));
        } else if (mechanicKey.equalsIgnoreCase(key) && settings != null) {
            double value = settings.getAttr("value", level, 0);
            if (best == null || value * hits > best[0] * best[1]) {
                best = new double[]{value, hits};
            }
        }
        for (EffectComponent child : comp.children) {
            best = walk(child, mechanicKey, level, hits, depth + 1, best);
        }
        return best;
    }

    /**
     * Trigger roots of a dynamic skill. {@code triggers} / {@code castTriggers}
     * are private in Fabled, so reach them reflectively; failures just mean
     * no damage line.
     */
    @SuppressWarnings("unchecked")
    private List<EffectComponent> rootComponents(DynamicSkill skill) throws Exception {
        List<EffectComponent> roots = new ArrayList<>();
        Field triggers = DynamicSkill.class.getDeclaredField("triggers");
        triggers.setAccessible(true);
        for (TriggerHandler handler : (List<TriggerHandler>) triggers.get(skill)) {
            if (handler.getComponent() != null) roots.add(handler.getComponent());
        }
        for (String fieldName : new String[]{"castTriggers", "initializeTriggers"}) {
            Field field = DynamicSkill.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(skill);
            if (value instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof EffectComponent ec) roots.add(ec);
                }
            }
        }
        return roots;
    }
}
