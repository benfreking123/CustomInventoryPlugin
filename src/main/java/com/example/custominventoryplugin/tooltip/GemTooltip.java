package com.example.custominventoryplugin.tooltip;

import net.kyori.adventure.key.Key;
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
 * Sectioned page-1 reflow for skill gems (Divinity custom_items, ids
 * {@code gem_*} / {@code passive_*}), matching the gear layout's vocabulary of
 * labeled bars and rails:
 *
 * <pre>
 *   [COMMON][GEM][ACTIVE][PROJECTILE][PHYSICAL]   badges, wrapped as needed
 *   18 Physical Damage                            what the viewer actually hits for
 *   DESCRIPTION ─────────────────────
 *   ▌ what the skill does
 *   DAMAGE  3x hits ──────── Crit 4% / +50%
 *   ▌ PHYSICAL (100%)  PROJECTILE (100%)          element share + tag weights
 *   ▌ base 4   multiplier x1.5
 *   COST ────────────────────────────
 *   ▌ MANA 5 (+2/lvl)  COOLDOWN 3s
 *   SKILL ─────────────────── Lv 2/5
 * </pre>
 *
 * The damage numbers are computed by {@link SkillMath} from the connected
 * Fabled skill's own {@code Value Math} equations rather than read from lore,
 * because hand-written gem lore drifts — some still advertises the retired
 * Energy damage type. The skill is resolved by display name minus " Gem"
 * (SkillHandler's rule). See Docs/dev-design/tooltips.md.
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
    /** Boost-tag pills U+E180.., in gen-tooltip-assets.py order. */
    private static final Map<String, Character> TAG_PILL = Map.of(
            "attack", '\uE180', "projectile", '\uE181', "spell", '\uE182',
            "area", '\uE183', "poison", '\uE184', "buff", '\uE185',
            "movement", '\uE186', "sigil", '\uE187');
    private static final char PILL_MANA = '\uE188', PILL_COOLDOWN = '\uE189';
    private static final char PILL_ACTIVE = '\uE18A', PILL_PASSIVE = '\uE18B';
    private static final char CHECK = '\uE140', CROSS = '\uE141';
    private static final Map<String, Character> RAIL = Map.of(
            "description", '\uE164', "damage", '\uE165',
            "cost", '\uE166', "skill", '\uE167');
    private static final int MAX_WALK_DEPTH = 16;
    /** Narrowest tooltip we lay out to; also the gear chip row's width. */
    private static final int MIN_WIDTH = 163;
    /** Gap from a bar's baked label to text drawn after it, and from its end. */
    private static final int LABEL_GAP = 3, RIGHT_GAP = 6;
    /** Snapshot layout version (bump when the parse/stored shape changes). */
    private static final String SNAPSHOT_VERSION = "2";

    private static final Key FONT_BODY = Key.key("tower", "tooltip");
    private static final Key FONT_BIG = Key.key("tower", "big");

    /** A lore line that is a stat, not flavor: "Cooldown: 3s", "Crit: ...". */
    private static final Pattern STAT_LABEL = Pattern.compile(
            "(?i)^(damage|crit|mana|cooldown|heals?|boost|duration|radius)\\b\\s*:.*");
    /** Fabled icon-lore placeholder, e.g. {attr:mana} / {attr:Damage.value}. */
    private static final Pattern ATTR_PLACEHOLDER = Pattern.compile("\\{attr:([^}]+)}");
    /** "Type: Name(150%)" value split. */
    private static final Pattern TYPE_VALUE = Pattern.compile("([^(]+)(?:\\((\\d+%)\\))?");

    private final TooltipConfig config;
    private final TooltipGlyphs glyphs;
    private final NamespacedKey pageKey;
    private final NamespacedKey gemInfoKey;
    private final NamespacedKey gemDescKey;
    private final NamespacedKey gemStatsKey;

    GemTooltip(JavaPlugin plugin, TooltipConfig config) {
        this.config = config;
        this.glyphs = new TooltipGlyphs(plugin);
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

        List<Component> lore = layout(info, skill, icon, desc, loreStats,
                viewer, playerLevel, footer);

        if (!lore.equals(meta.lore())) {
            meta.lore(lore);
        }
        stack.setItemMeta(meta); // PDC snapshot may be new even when lore matched
        return true;
    }

    // ─── layout ─────────────────────────────────────────────────────────────

    /** Legacy colours matching the six base-attribute chips. */
    private static final Map<String, String> ATTR_COLOR = Map.of(
            "strength", "&c", "dexterity", "&a", "intelligence", "&9",
            "vitality", "&d", "agility", "&b", "wisdom", "&5");

    /** A bar line: the section, text tucked in after its label, text hung off
     *  its right end. Both insets are optional. */
    private record BarLine(TooltipGlyphs.Bar bar, String after, String right) { }

    /** A headline drawn in the big font, which measures 6px per character. */
    private record Big(String text) { }

    /**
     * Build page 1.
     *
     * Laid out in two passes: collect the content, then size every bar to the
     * widest line so the bars span the tooltip instead of stopping short of it
     * — a bar that is the widest line *becomes* the tooltip's width, so it
     * always reaches both edges.
     */
    private List<Component> layout(Info info, Skill skill, IconDetails icon,
                                   List<String> desc, List<String> loreStats,
                                   Player viewer, int playerLevel, Component footer) {
        glyphs.refresh();
        int level = Math.max(1, playerLevel);
        SkillMath.Damage damage = skill == null ? null : SkillMath.of(skill, viewer, level);
        List<SkillMath.Grant> grants = skill == null
                ? List.of() : SkillMath.grants(skill, level);
        boolean passive = info.form != null
                && info.form.toLowerCase(Locale.ROOT).startsWith("pass");

        List<Object> rows = new ArrayList<>();
        rows.addAll(badgeRows(info, damage, passive));
        rows.add("");

        String headline = headline(damage, grants, info);
        if (headline != null) {
            rows.add(new Big(headline));
            rows.add("");
        }

        List<String> body = descriptionLines(icon, desc, loreStats);
        if (!body.isEmpty()) {
            rows.add(new BarLine(TooltipGlyphs.Bar.DESCRIPTION, null, null));
            for (String line : body) rows.add(railed("description", line));
            rows.add("");
        }

        if (damage != null && damage.base() > 0) {
            rows.add(new BarLine(TooltipGlyphs.Bar.DAMAGE,
                    damage.repeats() > 1 ? "&f" + damage.repeats() + "x&8 hits" : null,
                    critText(damage)));
            rows.add(railed("damage", elementRow(damage)));
            rows.add(railed("damage", baseRow(damage)));
            rows.add("");
        }

        String cost = costRow(skill, level);
        if (cost != null) {
            rows.add(new BarLine(TooltipGlyphs.Bar.COST, null, null));
            rows.add(railed("cost", cost));
            rows.add("");
        }

        if (skill != null) {
            boolean learned = playerLevel > 0;
            rows.add(new BarLine(TooltipGlyphs.Bar.SKILL, null, learned
                    ? CHECK + " &7Lv &f" + playerLevel + "&8/" + skill.getMaxLevel()
                    : CROSS + " &cNot socketed"));
            if (!learned) {
                rows.add(railed("skill", "&8Socket into " + (passive ? "a passive" : "an active")
                        + " skill slot using &7/ci"));
            }
            rows.add("");
        }

        int width = MIN_WIDTH;
        for (Object row : rows) {
            if (row instanceof String s) {
                width = Math.max(width, glyphs.width(s));
            } else if (row instanceof Big b) {
                width = Math.max(width, glyphs.width(b.text(), TooltipGlyphs.BIG_CHAR));
            }
        }

        List<Component> lore = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Big b) {
                lore.add(noShadow(LEGACY.deserialize(b.text()).font(FONT_BIG)));
            } else if (row instanceof BarLine bl) {
                lore.add(noShadow(LEGACY.deserialize(barText(bl, width)).font(FONT_BODY)));
            } else {
                String s = (String) row;
                lore.add(s.isEmpty() ? Component.empty()
                        : noShadow(LEGACY.deserialize(s).font(FONT_BODY)));
            }
        }
        if (!lore.isEmpty() && lore.get(lore.size() - 1).equals(Component.empty())) {
            lore.remove(lore.size() - 1);
        }
        lore.add(Component.empty());
        lore.add(footer);
        return lore;
    }

    /** A bar of the given width with its insets drawn on top of it. */
    private String barText(BarLine line, int width) {
        StringBuilder sb = new StringBuilder("&f");
        sb.append(glyphs.bar(line.bar(), width));
        int cursor = width;
        if (line.after() != null) {
            int x = glyphs.advance(line.bar().left()) + LABEL_GAP;
            sb.append(TooltipGlyphs.pad(x - cursor)).append(line.after());
            cursor = x + glyphs.width(line.after());
        }
        if (line.right() != null) {
            int x = width - glyphs.width(line.right()) - RIGHT_GAP;
            sb.append(TooltipGlyphs.pad(x - cursor)).append(line.right());
        }
        return sb.toString();
    }

    /**
     * Badge pills — rarity, gem, active/passive, boost tags, element — wrapped
     * onto extra lines rather than pushing the tooltip wide.
     */
    private List<String> badgeRows(Info info, SkillMath.Damage damage, boolean passive) {
        List<Character> pills = new ArrayList<>();
        Character rarity = info.rarity == null ? null
                : RARITY_PILL.get(info.rarity.toLowerCase(Locale.ROOT));
        pills.add(rarity == null ? RARITY_PILL.get("common") : rarity);
        pills.add(GEM_PILL);
        pills.add(passive ? PILL_PASSIVE : PILL_ACTIVE);

        List<String> tags = new ArrayList<>();
        if (damage != null) {
            for (SkillMath.Tag tag : damage.tags()) tags.add(tag.name());
        }
        if (info.type != null) {                 // sigils/buffs carry it as Type
            String type = info.type.toLowerCase(Locale.ROOT);
            if (TAG_PILL.containsKey(type) && !tags.contains(type)) tags.add(type);
        }
        for (String tag : tags) {
            Character pill = TAG_PILL.get(tag);
            if (pill != null) pills.add(pill);
        }
        Character element = ELEMENT_PILL.get(damage != null ? damage.element()
                : info.type == null ? "" : info.type.toLowerCase(Locale.ROOT));
        if (element != null) pills.add(element);

        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        int used = 0;
        for (char pill : pills) {
            int step = glyphs.advance(pill) + (row.isEmpty() ? 0 : 3);
            if (!row.isEmpty() && used + step > MIN_WIDTH) {
                rows.add("&f" + row);
                row.setLength(0);
                used = 0;
                step = glyphs.advance(pill);
            }
            if (!row.isEmpty()) row.append(TooltipGlyphs.pad(3));
            row.append(pill);
            used += step;
        }
        if (!row.isEmpty()) rows.add("&f" + row);
        return rows;
    }

    /**
     * The one number a player wants: what this gem hits them for right now,
     * every hit counted. Passives headline their granted attribute instead.
     */
    private String headline(SkillMath.Damage damage, List<SkillMath.Grant> grants,
                            Info info) {
        if (damage != null && damage.effective() > 0) {
            String element = damage.element();
            String pretty = element.substring(0, 1).toUpperCase(Locale.ROOT)
                    + element.substring(1);
            // The number carries the element's color too — a lightning gem should
            // read as lightning at a glance, not as white text with a colored word.
            String color = config.gemTypeColor(element);
            String line = color + fmt(damage.effective()) + " " + pretty + " Damage";
            if (damage.repeats() > 1) line += " &8x" + damage.repeats();
            return line;
        }
        if (!grants.isEmpty()) {
            SkillMath.Grant grant = grants.get(0);
            String color = ATTR_COLOR.getOrDefault(grant.attribute(), "&e");
            return "&f+" + fmt(grant.amount()) + " " + color + pretty(grant.attribute());
        }
        return null;
    }

    /** "PHYSICAL (100%)  PROJECTILE (100%)" — element share, then tag weights. */
    private String elementRow(SkillMath.Damage damage) {
        StringBuilder sb = new StringBuilder("&f");
        Character element = ELEMENT_PILL.get(damage.element());
        if (element != null) {
            sb.append(element).append(TooltipGlyphs.pad(2))
              .append(config.gemTypeColor(damage.element())).append("(100%)");
        }
        for (SkillMath.Tag tag : damage.tags()) {
            Character pill = TAG_PILL.get(tag.name());
            if (pill == null) continue;
            sb.append(TooltipGlyphs.pad(7)).append("&f").append(pill)
              .append(TooltipGlyphs.pad(2))
              .append("&f(").append(fmt(tag.weight() * 100)).append("%)");
        }
        return sb.toString();
    }

    /** "base 4   multiplier x1.5" — the flat term and the whole-damage scalar. */
    private String baseRow(SkillMath.Damage damage) {
        String line = "&7base &f" + fmt(damage.base());
        if (Math.abs(damage.multiplier() - 1.0) > 0.005) {
            line += TooltipGlyphs.pad(8) + "&7multiplier &6x" + fmt(damage.multiplier());
        }
        return line;
    }

    private String critText(SkillMath.Damage damage) {
        if (damage.critChance() <= 0) return null;
        return "&7Crit &6" + fmt(damage.critChance()) + "%&8 / &6+"
                + fmt((damage.critDamage() - 1) * 100) + "%";
    }

    /** "MANA 5 (+2/lvl)  COOLDOWN 3s", or null when the skill costs nothing. */
    private String costRow(Skill skill, int level) {
        if (skill == null) return null;
        StringBuilder sb = new StringBuilder("&f");
        double mana = safe(() -> skill.getManaCost(level));
        if (mana > 0) {
            sb.append(PILL_MANA).append(TooltipGlyphs.pad(2)).append("&f").append(fmt(mana));
            double scale = safe(() -> skill.getManaCost(level + 1)) - mana;
            if (scale > 0) sb.append(" &8(+").append(fmt(scale)).append("/lvl)");
        }
        double cooldown = safe(() -> skill.getCooldown(level));
        if (cooldown > 0) {
            if (sb.length() > 2) sb.append(TooltipGlyphs.pad(8));
            sb.append("&f").append(PILL_COOLDOWN).append(TooltipGlyphs.pad(2))
              .append("&f").append(fmt(cooldown)).append("s");
        }
        return sb.length() > 2 ? sb.toString() : null;
    }

    /**
     * Flavor text. The Fabled skill's own lore body wins over the gem item's
     * hand-written copy, minus its "Type:" line — the tags are pills now — and
     * minus stat lines, which the sections below render from the skill itself.
     */
    private List<String> descriptionLines(IconDetails icon, List<String> desc,
                                          List<String> loreStats) {
        List<String> source = icon.body().isEmpty() ? desc : icon.body();
        List<String> out = new ArrayList<>();
        for (String line : source) {
            String plain = PLAIN.serialize(LEGACY.deserialize(line)).trim();
            if (plain.isEmpty()) continue;
            if (plain.toLowerCase(Locale.ROOT).startsWith("type")) continue;
            if (STAT_LABEL.matcher(plain).matches()) continue;
            out.add(line);
        }
        if (out.isEmpty()) {
            for (String line : loreStats) {
                String plain = PLAIN.serialize(LEGACY.deserialize(line)).trim();
                if (!plain.isEmpty()) out.add(line);
            }
        }
        return out;
    }

    private String railed(String section, String line) {
        Character rail = RAIL.get(section);
        return (rail == null ? "" : "&f" + rail + TooltipGlyphs.pad(3)) + line;
    }

    private static String pretty(String attribute) {
        String name = attribute.startsWith("base_")
                ? attribute.substring("base_".length()) : attribute;
        return name.isEmpty() ? name
                : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
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
