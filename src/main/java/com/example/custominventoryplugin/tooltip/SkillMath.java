package com.example.custominventoryplugin.tooltip;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import studio.magemonkey.fabled.Fabled;
import studio.magemonkey.fabled.api.Settings;
import studio.magemonkey.fabled.api.player.PlayerData;
import studio.magemonkey.fabled.api.skills.Skill;
import studio.magemonkey.fabled.dynamic.ComponentType;
import studio.magemonkey.fabled.dynamic.DynamicSkill;
import studio.magemonkey.fabled.dynamic.EffectComponent;
import studio.magemonkey.fabled.dynamic.TriggerHandler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a skill's damage numbers out of the skill itself.
 *
 * Fabled has no damage formula of its own — a skill assembles one from
 * {@code Value Attribute} blocks (bind a player attribute to a local variable)
 * and {@code Value Math} blocks (arithmetic over those variables), and the
 * {@code Damage} mechanic then fires whichever variable holds the result. So
 * the only honest way to tell a player what a gem does is to evaluate the same
 * equations the skill will evaluate. See Docs/how-to/skills.md.
 *
 * Dart, for example, reduces to:
 * <pre>
 *   mod_damage   = {dmg_proj_mod}*1 + 4        base 4, Projectile weight 1.0
 *   perct_damage = {dmg_proj}*0.01 + 1
 *   dealt        = {mod_damage}*{perct_damage}*1.5    multiplier 1.5
 * </pre>
 * from which base, per-tag weights and the multiplier all fall out — no
 * hand-written lore to drift out of date, which matters because gem lore
 * demonstrably has (two gems still advertise the retired Energy type).
 */
final class SkillMath {

    /** A boost tag and how strongly this skill scales off it (1.0 = 100%). */
    record Tag(String name, double weight) { }

    /**
     * @param element    Divinity damage type delivered (one of the five)
     * @param tags       boost tags with weights, in skill order
     * @param base       damage with no player stats at all — the flat term
     * @param multiplier whole-damage multiplier applied after the fold
     * @param repeats    hits per cast (Repeat mechanic), 1 when single-hit
     * @param critChance percent chance for the viewer (skill base when no viewer)
     * @param critDamage crit damage multiplier, 1.5 = "+50%"
     * @param effective  damage per cast for the viewer, all hits summed
     */
    record Damage(String element, List<Tag> tags, double base, double multiplier,
                  int repeats, double critChance, double critDamage,
                  double effective) { }

    /** A passive's standing attribute grant, e.g. +25 base_strength. */
    record Grant(String attribute, double amount) { }

    private static final int MAX_DEPTH = 16;
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private SkillMath() { }

    /**
     * Attribute grants a passive skill applies while equipped. Passive gems
     * are an {@code Attribute} mechanic under a looping {@code Passive}, so the
     * gem can state what it gives without anyone writing it in lore twice.
     */
    static List<Grant> grants(Skill skill, int level) {
        List<Grant> out = new ArrayList<>();
        if (!(skill instanceof DynamicSkill dyn)) return out;
        try {
            for (EffectComponent root : roots(dyn)) {
                collectGrants(root, level, 0, out);
            }
        } catch (Throwable ignored) {
            // best effort: a passive with no readable grant just has no headline
        }
        return out;
    }

    private static void collectGrants(EffectComponent comp, int level, int depth,
                                      List<Grant> out) {
        if (comp == null || depth > MAX_DEPTH) return;
        try {
            if ("attribute".equalsIgnoreCase(comp.getKey())) {
                Settings s = comp.getSettings();
                double amount = s.getAttr("amount", level, 0);
                for (String attr : s.getStringList("key")) {
                    if (attr != null && !attr.isBlank() && amount != 0) {
                        out.add(new Grant(attr, amount));
                    }
                }
            }
        } catch (Throwable ignored) {
            // malformed component: keep walking
        }
        for (EffectComponent child : comp.children) {
            collectGrants(child, level, depth + 1, out);
        }
    }

    // ─── extraction ─────────────────────────────────────────────────────────

    /** Everything worth knowing, gathered in one walk of the component tree. */
    private static final class Parts {
        final Map<String, String> varAttr = new LinkedHashMap<>();   // var -> attribute
        final List<String[]> equations = new ArrayList<>();          // {key, function}
        /** Value Lore reads: {var, regex, hand, multiplier}. */
        final List<String[]> loreReads = new ArrayList<>();
        String damageVar, classifier = "", buffVar;
        double damageScale;
        int repeats = 1;
    }

    static Damage of(Skill skill, Player viewer, int level) {
        if (!(skill instanceof DynamicSkill dyn)) return null;
        Parts p = new Parts();
        try {
            for (EffectComponent root : roots(dyn)) {
                collect(root, p, level, 0);
            }
        } catch (Throwable t) {
            return null;
        }
        if (p.damageVar == null || p.damageVar.isBlank() || p.equations.isEmpty()) {
            return null;
        }

        // A weapon read is not a player stat — it is part of the skill's own
        // baseline (Strike is "your sword, swung"), so it seeds both passes and
        // shows up in `base` rather than only in the viewer's figure.
        Map<String, Double> weapon = new LinkedHashMap<>();
        for (String[] read : p.loreReads) {
            weapon.put(read[0], loreValue(viewer, read));
        }

        Map<String, Double> zero = new LinkedHashMap<>(weapon);
        for (String var : p.varAttr.keySet()) zero.put(var, 0.0);
        Map<String, Double> live = new LinkedHashMap<>(weapon);
        for (Map.Entry<String, String> e : p.varAttr.entrySet()) {
            live.put(e.getKey(), (double) attribute(viewer, e.getValue()));
        }

        Map<String, Double> atZero = run(p, zero, level);
        Map<String, Double> atLive = run(p, live, level);

        String modVar = null;
        for (String[] eq : p.equations) {
            if (eq[0].startsWith("mod")) { modVar = eq[0]; break; }
        }
        double base = modVar == null ? 0 : atZero.getOrDefault(modVar, 0.0);
        double finalZero = deliver(p, atZero, level);
        double multiplier = base > 0 ? finalZero / base : 1.0;

        List<Tag> tags = new ArrayList<>();
        if (modVar != null) {
            for (Map.Entry<String, String> e : p.varAttr.entrySet()) {
                String tag = tagOf(e.getValue());
                if (tag == null) continue;
                boolean isMod = e.getValue().endsWith("_mod");
                Map<String, Double> probe = new LinkedHashMap<>(zero);
                probe.put(e.getKey(), 1.0);
                double delta = run(p, probe, level).getOrDefault(modVar, 0.0) - base;
                if (isMod && Math.abs(delta) > 1e-9) {
                    tags.add(new Tag(tag, delta));
                }
            }
        }

        String cat = critCategory(p);
        double critChance = atLive.getOrDefault("crit_chance",
                atZero.getOrDefault("crit_chance", 0.0));
        double critDamage = 1.5 + 0.01 * (attribute(viewer, "stat_crit_damage")
                + (cat == null ? 0 : attribute(viewer, "stat_crit_damage_" + cat)));

        double effective = deliver(p, atLive, level) * p.repeats;
        return new Damage(element(p.classifier), tags, base, multiplier,
                p.repeats, critChance, critDamage, effective);
    }

    /** Evaluate every Value Math in document order over the given inputs. */
    private static Map<String, Double> run(Parts p, Map<String, Double> seed, int level) {
        Map<String, Double> vars = new LinkedHashMap<>(seed);
        for (String[] eq : p.equations) {
            try {
                vars.put(eq[0], new Expr(eq[1], vars).parse());
            } catch (Throwable ignored) {
                vars.putIfAbsent(eq[0], 0.0);   // unparseable: treat as absent
            }
        }
        return vars;
    }

    /** Damage actually delivered per hit: the fired variable, times any Buff
     *  multiplier riding on the caster's element, plus per-level scaling. */
    private static double deliver(Parts p, Map<String, Double> vars, int level) {
        double value = vars.getOrDefault(p.damageVar, 0.0)
                + p.damageScale * Math.max(0, level - 1);
        if (p.buffVar != null) value *= vars.getOrDefault(p.buffVar, 1.0);
        return value;
    }

    private static void collect(EffectComponent comp, Parts p, int level, int depth) {
        if (comp == null || depth > MAX_DEPTH) return;
        try {
            String key = comp.getKey() == null ? "" : comp.getKey().toLowerCase(Locale.ROOT);
            Settings s = comp.getSettings();
            switch (key) {
                case "value attribute" -> {
                    String var = s.getString("key", "");
                    String attr = s.getString("attribute", "");
                    if (!var.isBlank() && !attr.isBlank()) p.varAttr.put(var, attr);
                }
                case "value math" -> {
                    String var = s.getString("key", "");
                    String fn = s.getString("function", "");
                    if (!var.isBlank() && !fn.isBlank()) p.equations.add(new String[]{var, fn});
                }
                case "repeat" -> p.repeats = Math.max(p.repeats,
                        (int) Math.round(s.getAttr("repetitions", level, 1)));
                case "damage" -> {
                    String var = s.getString("value-base", "");
                    if (!var.isBlank() && !isNumber(var)) {
                        p.damageVar = var;
                        p.classifier = s.getString("classifier", "");
                        p.damageScale = number(s.getString("value-scale", "0"));
                    }
                }
                case "value lore" -> {
                    String var = s.getString("key", "");
                    if (!var.isBlank()) {
                        p.loreReads.add(new String[]{var, s.getString("regex", ""),
                                s.getString("hand", "Main"),
                                String.valueOf(s.getAttr("multiplier", level, 1))});
                    }
                }
                case "buff" -> {
                    String type = s.getString("type", "").toLowerCase(Locale.ROOT);
                    if ("multiplier".equalsIgnoreCase(s.getString("modifier", ""))
                            && type.startsWith("divinity_damage_")) {
                        String var = s.getString("value-base", "");
                        if (!var.isBlank() && !isNumber(var)) p.buffVar = var;
                    }
                }
                default -> { }
            }
        } catch (Throwable ignored) {
            // malformed component: keep walking, a partial read still helps
        }
        if (isCritGate(comp)) return;   // its equations only apply when you crit
        for (EffectComponent child : comp.children) {
            collect(child, p, level, depth + 1);
        }
    }

    /**
     * The crit block's {@code Value} condition on {@code crit_test}.
     *
     * Its child re-assigns the damage variable to {@code value*(1.5+…)}, which
     * only holds on a critical hit. Walking into it would fold the crit bonus
     * into the skill's ordinary damage — the multiplier reads 2.3 instead of
     * 1.5, and every damage figure is 50% high. Other conditions (the weapon
     * gates on Strike, Dart, Blade Fury) must still be walked, since the damage
     * mechanic itself lives under them.
     */
    private static boolean isCritGate(EffectComponent comp) {
        try {
            if (comp.getType() != ComponentType.CONDITION) return false;
            return comp.getSettings().getString("key", "")
                    .toLowerCase(Locale.ROOT).startsWith("crit");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Trigger roots; the lists are private on Fabled's DynamicSkill. */
    @SuppressWarnings("unchecked")
    private static List<EffectComponent> roots(DynamicSkill skill) throws Exception {
        List<EffectComponent> out = new ArrayList<>();
        Field triggers = DynamicSkill.class.getDeclaredField("triggers");
        triggers.setAccessible(true);
        for (TriggerHandler h : (List<TriggerHandler>) triggers.get(skill)) {
            if (h.getComponent() != null) out.add(h.getComponent());
        }
        for (String name : new String[]{"castTriggers", "initializeTriggers"}) {
            Field f = DynamicSkill.class.getDeclaredField(name);
            f.setAccessible(true);
            if (f.get(skill) instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof EffectComponent ec) out.add(ec);
                }
            }
        }
        return out;
    }

    // ─── naming ─────────────────────────────────────────────────────────────

    /** "damage_projectile_mod" -> "projectile"; null when not a boost tag. */
    private static String tagOf(String attribute) {
        if (attribute == null || !attribute.startsWith("damage_")) return null;
        String tag = attribute.substring("damage_".length());
        if (tag.endsWith("_mod")) tag = tag.substring(0, tag.length() - 4);
        return tag.isBlank() || tag.equals("weapon") ? null : tag;
    }

    /** Crit category, read off whichever crit attribute the skill bound. */
    private static String critCategory(Parts p) {
        for (String attr : p.varAttr.values()) {
            String prefix = "stat_crit_chance_";
            if (attr.startsWith(prefix)) return attr.substring(prefix.length());
        }
        return null;
    }

    /** Divinity classifier -> element name. Empty classifier is physical. */
    private static String element(String classifier) {
        if (classifier == null || classifier.isBlank()) return "physical";
        String c = classifier.toLowerCase(Locale.ROOT);
        int cut = c.lastIndexOf('_');
        String tail = cut < 0 ? c : c.substring(cut + 1);
        return switch (tail) {
            case "fire", "ice", "lightning", "chaotic" -> tail;
            default -> "physical";
        };
    }

    /**
     * Resolve a {@code Value Lore} read the way Fabled's {@code ItemChecker}
     * does: strip colours, substitute {@code {value}} with a number capture,
     * and search each lore line of the held item.
     *
     * Strike, Rising Slash and Charge all read their weapon this way, which
     * makes the gear tooltip's *text* a machine interface — moving the value in
     * front of the label silently zeroed all three (fixed 2026-08-03). When
     * nothing matches (no weapon in hand while browsing gems) fall back to a
     * nominal figure so the DAMAGE block still says something useful.
     */
    private static final double WEAPON_FALLBACK = 3;

    private static double loreValue(Player viewer, String[] read) {
        double multiplier = number(read[3]);
        if (multiplier == 0) multiplier = 1;
        if (viewer == null || read[1].isBlank()) return WEAPON_FALLBACK * multiplier;
        try {
            ItemStack item = "off".equalsIgnoreCase(read[2]) || "offhand".equalsIgnoreCase(read[2])
                    ? viewer.getInventory().getItemInOffHand()
                    : viewer.getInventory().getItemInMainHand();
            if (item != null && item.hasItemMeta() && item.getItemMeta().hasLore()) {
                Pattern pattern = Pattern.compile(Pattern.quote(read[1])
                        .replace("{value}", "\\E([+-]?[0-9]+([.,][0-9]+)?)\\Q"));
                for (Component line : item.getItemMeta().lore()) {
                    Matcher m = pattern.matcher(PLAIN.serialize(line));
                    if (m.find()) {
                        return number(m.group(1).replace(',', '.')) * multiplier;
                    }
                }
            }
        } catch (Throwable ignored) {
            // unreadable item: fall through to the nominal figure
        }
        return WEAPON_FALLBACK * multiplier;
    }

    private static int attribute(Player viewer, String attribute) {
        if (viewer == null) return 0;
        try {
            PlayerData data = Fabled.getData(viewer);
            return data == null ? 0 : data.getAttribute(attribute);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ─── expression evaluator ───────────────────────────────────────────────

    /**
     * The subset of arithmetic Fabled's {@code Value Math} uses: numbers,
     * {@code {variable}} references, {@code + - * /} and parentheses. An
     * unknown variable evaluates to 0, which is what Fabled does with an
     * unresolved placeholder.
     */
    private static final class Expr {
        private final String src;
        private final Map<String, Double> vars;
        private int at;

        Expr(String src, Map<String, Double> vars) {
            this.src = src;
            this.vars = vars;
        }

        double parse() {
            double v = sum();
            return Double.isFinite(v) ? v : 0;
        }

        private double sum() {
            double v = product();
            while (true) {
                skip();
                if (eat('+')) v += product();
                else if (eat('-')) v -= product();
                else return v;
            }
        }

        private double product() {
            double v = unary();
            while (true) {
                skip();
                if (eat('*')) v *= unary();
                else if (eat('/')) {
                    double d = unary();
                    v = d == 0 ? 0 : v / d;
                } else return v;
            }
        }

        private double unary() {
            skip();
            if (eat('-')) return -unary();
            if (eat('+')) return unary();
            return atom();
        }

        private double atom() {
            skip();
            if (eat('(')) {
                double v = sum();
                skip();
                eat(')');
                return v;
            }
            if (eat('{')) {
                int start = at;
                while (at < src.length() && src.charAt(at) != '}') at++;
                String name = src.substring(start, at);
                eat('}');
                return vars.getOrDefault(name, 0.0);
            }
            int start = at;
            while (at < src.length()
                    && (Character.isDigit(src.charAt(at)) || src.charAt(at) == '.')) {
                at++;
            }
            if (start == at) {          // unexpected token: skip it, contribute 0
                at++;
                return 0;
            }
            return number(src.substring(start, at));
        }

        private void skip() {
            while (at < src.length() && Character.isWhitespace(src.charAt(at))) at++;
        }

        private boolean eat(char c) {
            skip();
            if (at < src.length() && src.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }
    }
}
