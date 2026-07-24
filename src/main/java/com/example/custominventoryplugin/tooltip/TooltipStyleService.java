package com.example.custominventoryplugin.tooltip;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import studio.magemonkey.fabled.Fabled;
import studio.magemonkey.fabled.api.player.PlayerData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stamps {@code minecraft:tooltip_style} from Divinity tier (or config overrides),
 * reflows Divinity lore into the Wynn-style page-1 layout (badge row + Level Req,
 * attribute icon strip, stat totals, compact set line), and manages the F-key
 * page cycle: 1 = stats, 2 = set details (when the item belongs to a set),
 * last = source/notes. Docs/deisgn/tooltips.md is the layout reference.
 */
public final class TooltipStyleService {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();
    /** §-codes, for values Divinity stores/compares (fogus_loren-* lore tags). */
    private static final LegacyComponentSerializer SECTION =
            LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();
    /** Section divider glyph (network pack, U+E142). */
    private static final char DIVIDER_CHAR = '\uE142';
    private static final char CHECK = '\uE140', CROSS = '\uE141';
    private static final char DOT_ON = '\uE143', DOT_OFF = '\uE144';
    /** Attribute icons U+E120.. in this order (matches gen-tooltip-assets.py). */
    private static final String[] ATTR_ORDER =
            {"strength", "dexterity", "intelligence", "vitality", "agility", "wisdom"};
    private static final int ATTR_ICON_BASE = 0xE120;
    private static final int GLYPH_MIN = 0xE100, GLYPH_MAX = 0xE14F;
    /** Badge/pill glyph band: rarity E100.. + type pills E110.. */
    private static final int BADGE_MIN = 0xE100, BADGE_MAX = 0xE11F;

    /**
     * Unresolved Divinity placeholder left in final lore, e.g.
     * {@code %FABLED_ATTRIBUTE_DAMAGE_CHAOTIC%} — happens when a lore-format
     * references a Fabled attribute not registered on this server. Requires
     * an all-caps token wrapped in %...% so real text like "10%" is untouched.
     */
    private static final Pattern RAW_PLACEHOLDER =
            Pattern.compile("%[A-Z][A-Z0-9_]{2,}%");
    private static final Pattern LEVEL_VALUE =
            Pattern.compile("Player Level: (\\d+)");
    /** Old CIP text-style attr requirement line, replaced by the icon strip. */
    private static final Pattern OLD_ATTR_REQ = Pattern.compile(
            "^[\uE140\uE141]?\\s*(?:Strength|Dexterity|Intelligence|Vitality|Agility|Wisdom): \\d+\\+$");
    /** Base-six stat bonus line, optionally with a previously appended total. */
    private static final Pattern STAT_BONUS = Pattern.compile(
            "^(Strength|Dexterity|Intelligence|Vitality|Agility|Wisdom): ([+-]\\d+)(?:\\s*\\(\\d+\\))?$");
    /** A previously appended " (total)" suffix. */
    private static final Pattern TOTAL_SUFFIX = Pattern.compile("\\s*\\(\\d+\\)$");
    /** Core damage/defense lines, e.g. "Flat Physical Damage: +2", "Damage: 3-5". */
    private static final Pattern CORE_STAT = Pattern.compile(
            "^(?:Flat\\s+)?(?:(?:Physical|Fire|Ice|Lightning|Chaotic)\\s+)?(?:Damage|Defense):\\s.*");
    /** Leading legacy color codes of a line, e.g. "&5" / "&8". */
    private static final Pattern LEADING_CODES =
            Pattern.compile("^((?:&[0-9a-fk-orx])+)");
    /** Item ids that are jewelry/accessories (no Hand line wanted). */
    private static final Pattern ACCESSORY_ID =
            Pattern.compile("ring|amulet|bracelet|relic|talisman|charm|necklace");

    private final JavaPlugin plugin;
    private final TooltipConfig config;
    private final GemTooltip gemTooltip;
    private final NamespacedKey pageKey;
    private final NamespacedKey page1Key;
    private final NamespacedKey page2Key;     // source / notes page
    private final NamespacedKey pageSetKey;   // set details page
    private final NamespacedKey styledKey;
    private final NamespacedKey attrReqKey;
    private final NamespacedKey levelReqKey;
    private final NamespacedKey badgeBaseKey;
    private final NamespacedKey divinityItemIdKey;

    private boolean divinityAvailable;
    private Method itemStatsGetId;
    private Method itemStatsGetModule;
    private Method moduleGetModuleItem;
    private Method getTierMethod;
    private Method tierGetId;
    private Method itemStatsGetLevel;

    public TooltipStyleService(JavaPlugin plugin, TooltipConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.gemTooltip = new GemTooltip(plugin, config);
        this.pageKey = new NamespacedKey(plugin, "tt_page");
        this.page1Key = new NamespacedKey(plugin, "tt_page1");
        this.page2Key = new NamespacedKey(plugin, "tt_page2");
        this.pageSetKey = new NamespacedKey(plugin, "tt_page_set");
        this.styledKey = new NamespacedKey(plugin, "tt_styled");
        this.attrReqKey = new NamespacedKey(plugin, "tt_attr_req");
        this.levelReqKey = new NamespacedKey(plugin, "tt_level_req");
        this.badgeBaseKey = new NamespacedKey(plugin, "tt_badge_base");
        this.divinityItemIdKey = new NamespacedKey("divinity", "item_id");
        hookDivinity();
    }

    private void hookDivinity() {
        if (Bukkit.getPluginManager().getPlugin("Divinity") == null) {
            plugin.getLogger().warning("Divinity not found — tooltip frames use id/material overrides only.");
            divinityAvailable = false;
            return;
        }
        try {
            Class<?> itemStats = Class.forName("studio.magemonkey.divinity.stats.items.ItemStats");
            itemStatsGetId = itemStats.getMethod("getId", ItemStack.class);
            itemStatsGetModule = itemStats.getMethod("getModule", ItemStack.class);
            Class<?> qModuleDrop = Class.forName("studio.magemonkey.divinity.modules.api.QModuleDrop");
            moduleGetModuleItem = qModuleDrop.getMethod("getModuleItem", ItemStack.class);
            // Tiered.getTier() / Tier.getId() resolved per-instance later
            Class<?> tiered = Class.forName("studio.magemonkey.divinity.stats.tiers.Tiered");
            getTierMethod = tiered.getMethod("getTier");
            Class<?> tier = Class.forName("studio.magemonkey.divinity.stats.tiers.Tier");
            tierGetId = tier.getMethod("getId");
            divinityAvailable = true;
            plugin.getLogger().info("Divinity hooked — tooltip_style will follow item tiers.");
        } catch (Throwable t) {
            divinityAvailable = false;
            plugin.getLogger().log(Level.WARNING, "Failed to hook Divinity for tooltips: " + t.getMessage(), t);
        }
        // Optional: item level drives attr-requirement scaling. A signature
        // mismatch here must not disable the whole tooltip hook.
        if (divinityAvailable) {
            try {
                itemStatsGetLevel = Class.forName("studio.magemonkey.divinity.stats.items.ItemStats")
                        .getMethod("getLevel", ItemStack.class);
            } catch (Throwable t) {
                itemStatsGetLevel = null;
                plugin.getLogger().warning("Divinity getLevel(ItemStack) unavailable; attr-req scaling uses level 1.");
            }
        }
    }

    public void stampPlayer(Player player) {
        if (!config.isEnabled() || player == null) return;
        for (ItemStack stack : player.getInventory().getContents()) {
            stamp(stack, player);
        }
        for (ItemStack stack : player.getInventory().getArmorContents()) {
            stamp(stack, player);
        }
        stamp(player.getInventory().getItemInOffHand(), player);
    }

    /** Apply tooltip_style (+ prep F-page) in place. No-op if already styled or not applicable. */
    public void stamp(ItemStack stack) {
        stamp(stack, null);
    }

    /** Like {@link #stamp(ItemStack)}; a viewer enables live ✓/✗ state and (have) totals. */
    public void stamp(ItemStack stack, Player viewer) {
        if (!config.isEnabled() || stack == null || stack.getType().isAir()) return;

        String itemId = resolveItemId(stack);
        String stylePath = resolveStylePath(stack, itemId);
        if (stylePath != null) {
            applyStyle(stack, stylePath);
        }

        ensureDetailPage(stack, itemId);
        if (stylePath != null || itemId != null) {
            if (gemTooltip.isGem(itemId)) {
                reflowGem(stack, viewer);
            } else {
                maybeRollAttrRequirement(stack, itemId);
                restructureLore(stack, itemId, viewer);
            }
            tidyLore(stack);
        }
    }

    /** Gem page-1 rebuild (badge row + Fabled skill stats) via {@link GemTooltip}. */
    private void reflowGem(ItemStack stack, Player viewer) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        int total = totalPages(meta.getPersistentDataContainer());
        gemTooltip.reflow(stack, viewer, buildFooter(1, total));
    }

    // ─── page-1 reflow ──────────────────────────────────────────────────────

    /**
     * Reflow the live (page 1) lore:
     * 1. pull the set block out to the set page, leave "Set: Name (x/n)",
     * 2. merge "Player Level: N+" into the badge row as "✓ Level Req: N",
     * 3. replace the old text attr requirement with the 6-icon strip,
     * 4. append the viewer's running total to base-six stat bonus lines,
     * 5. swap the "Press F" footer for page dots.
     * Idempotent; refreshes viewer-dependent state on every stamp.
     */
    private void restructureLore(ItemStack stack, String itemId, Player viewer) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasLore()) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int page = pdc.getOrDefault(pageKey, PersistentDataType.INTEGER, 1);
        if (page != 1) return; // never mangle a detail page being shown
        List<Component> lore = meta.lore();
        if (lore == null || lore.isEmpty()) return;

        boolean changed = false;
        changed |= stripAccessoryHandLine(itemId, lore);
        changed |= extractSetBlock(pdc, lore);
        changed |= mergeLevelReqAndAttrStrip(pdc, lore, viewer);
        changed |= appendStatTotals(meta, lore, viewer);
        changed |= applyFooterDots(pdc, lore);
        changed |= liftSealsAboveFooter(lore);
        changed |= liftContentBelowFooter(lore);

        if (changed) {
            meta.lore(lore);
            stack.setItemMeta(meta);
        }
    }

    /**
     * Jewelry isn't held, so Divinity's "Hand: One-handed" stat line is noise
     * there. (Left intact on weapons where two-handedness matters.)
     */
    private boolean stripAccessoryHandLine(String itemId, List<Component> lore) {
        if (itemId == null || !ACCESSORY_ID.matcher(itemId).find()) return false;
        boolean changed = false;
        for (int i = lore.size() - 1; i >= 0; i--) {
            String trimmed = PLAIN.serialize(lore.get(i)).trim();
            if (trimmed.startsWith("Hand: ")) {
                lore.remove(i);
                changed = true;
            }
        }
        return changed;
    }

    /** Move the Divinity set section to its own page; keep a compact one-liner. */
    private boolean extractSetBlock(PersistentDataContainer pdc, List<Component> lore) {
        int setIdx = -1;
        for (int i = 0; i < lore.size(); i++) {
            String trimmed = PLAIN.serialize(lore.get(i)).trim();
            // set header ends with "Set:" (compact line is "Set: Name (x/n)" and won't match)
            if (trimmed.endsWith("Set:")) { setIdx = i; break; }
        }
        if (setIdx < 0) return false;

        int end = setIdx + 1;
        while (end < lore.size()) {
            String trimmed = PLAIN.serialize(lore.get(end)).trim();
            if (isDividerText(trimmed) || trimmed.contains("Press F")
                    || containsGlyph(trimmed, DOT_ON, DOT_OFF)) break;
            end++;
        }

        List<String> block = new ArrayList<>();
        int total = 0, worn = 0;
        for (int i = setIdx; i < end; i++) {
            String legacy = LEGACY.serialize(lore.get(i));
            block.add(legacy);
            String trimmed = PLAIN.serialize(lore.get(i)).trim();
            if (trimmed.startsWith("•")) {
                total++;
                if (trimmed.contains("✓")) worn++;
            }
        }
        while (!block.isEmpty() && PLAIN.serialize(LEGACY.deserialize(block.get(block.size() - 1))).isBlank()) {
            block.remove(block.size() - 1);
        }
        if (block.isEmpty()) return false;

        String headerLegacy = block.get(0);
        String headerPlain = PLAIN.serialize(LEGACY.deserialize(headerLegacy)).trim();
        String name = headerPlain.substring(0, headerPlain.length() - 1).trim(); // drop ':'
        if (name.endsWith(" Set")) name = name.substring(0, name.length() - 4);
        Matcher colorMatch = LEADING_CODES.matcher(headerLegacy);
        String nameColor = colorMatch.find() ? colorMatch.group(1) : "&d";

        pdc.set(pageSetKey, PersistentDataType.STRING, String.join("\n", block));

        Component compact = LEGACY.deserialize(
                "&7Set: " + nameColor + name + " &7(" + worn + "/" + Math.max(total, 1) + ")");
        List<Component> replaced = new ArrayList<>(lore.subList(0, setIdx));
        replaced.add(compact);
        replaced.addAll(lore.subList(end, lore.size()));
        lore.clear();
        lore.addAll(replaced);
        return true;
    }

    /**
     * Fold "Player Level: N+" into the badge row and maintain the 6-attribute
     * icon strip in the requirements section.
     */
    private boolean mergeLevelReqAndAttrStrip(PersistentDataContainer pdc, List<Component> lore, Player viewer) {
        int badgeIdx = -1, levelIdx = -1, oldAttrIdx = -1;
        List<Integer> stripIdxs = new ArrayList<>();
        Integer levelReq = pdc.get(levelReqKey, PersistentDataType.INTEGER);
        for (int i = 0; i < lore.size(); i++) {
            String plain = PLAIN.serialize(lore.get(i));
            String trimmed = plain.trim();
            if (badgeIdx < 0 && containsGlyphRange(plain, BADGE_MIN, BADGE_MAX)) badgeIdx = i;
            if (levelIdx < 0 && plain.contains("Player Level")) {
                levelIdx = i;
                Matcher m = LEVEL_VALUE.matcher(plain);
                if (m.find()) levelReq = Integer.parseInt(m.group(1));
            }
            if (containsGlyphRange(plain, ATTR_ICON_BASE, ATTR_ICON_BASE + 5)) stripIdxs.add(i);
            if (oldAttrIdx < 0 && OLD_ATTR_REQ.matcher(trimmed).matches()) oldAttrIdx = i;
        }

        boolean changed = false;

        // 1. merge level req into the badge row
        if (badgeIdx >= 0 && levelReq != null) {
            pdc.set(levelReqKey, PersistentDataType.INTEGER, levelReq);
            String base = pdc.get(badgeBaseKey, PersistentDataType.STRING);
            if (base == null) {
                base = LEGACY.serialize(lore.get(badgeIdx));
                pdc.set(badgeBaseKey, PersistentDataType.STRING, base);
            }
            String suffix;
            if (viewer != null) {
                boolean met = playerLevel(viewer) >= levelReq;
                suffix = met ? "  &f" + CHECK + " &7Level Req: &f" + levelReq
                             : "  &f" + CROSS + " &7Level Req: &c" + levelReq;
            } else {
                suffix = "  &7Level Req: &f" + levelReq;
            }
            Component merged = LEGACY.deserialize(base + suffix)
                    .style(s -> s.shadowColor(ShadowColor.none()));
            if (!merged.equals(lore.get(badgeIdx))) {
                lore.set(badgeIdx, merged);
                changed = true;
            }
        }

        // 2. requirements anchor = first of: existing strip, level line, old attr line
        int anchor = !stripIdxs.isEmpty() ? stripIdxs.get(0)
                : (levelIdx >= 0 ? levelIdx : oldAttrIdx);
        if (anchor < 0) return changed; // no requirements section on this item

        // 3. build the two strip rows (3 chips each)
        Map.Entry<String, Integer> req = getAttrRequirementFrom(pdc);
        Component row1 = buildAttrStripRow(req, viewer, 0);
        Component row2 = buildAttrStripRow(req, viewer, 3);

        // 4. remove level line, old attr line and stale strip rows (highest index first)
        List<Component> before = new ArrayList<>(lore);
        List<Integer> removals = new ArrayList<>(stripIdxs);
        if (levelIdx >= 0) removals.add(levelIdx);
        if (oldAttrIdx >= 0) removals.add(oldAttrIdx);
        removals.sort(java.util.Comparator.reverseOrder());
        int fallback = anchor;
        for (int idx : removals) {
            lore.remove(idx);
            if (idx < fallback) fallback--;
        }

        // 5. re-insert below the damage/defense block, above item-stats/fabled
        int insertAt = Math.min(afterCoreStats(lore, fallback), lore.size());
        lore.add(insertAt, row2);
        lore.add(insertAt, row1);
        return changed || !lore.equals(before);
    }

    /**
     * Index just after the last damage/defense line (the core stat block), so
     * the requirement strip sits below attack/defense but above item-stats and
     * fabled bonuses. Falls back to the old requirements position when the
     * item has no core stats.
     */
    private int afterCoreStats(List<Component> lore, int fallback) {
        int last = -1;
        for (int i = 0; i < lore.size(); i++) {
            if (CORE_STAT.matcher(PLAIN.serialize(lore.get(i)).trim()).matches()) last = i;
        }
        return last >= 0 ? last + 1 : Math.max(fallback, 0);
    }

    /** One strip row: three attribute chips from {@code start}, each "req(have)". */
    private Component buildAttrStripRow(Map.Entry<String, Integer> req, Player viewer, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < start + 3; i++) {
            String name = ATTR_ORDER[i];
            char icon = (char) (ATTR_ICON_BASE + i);
            int need = (req != null && req.getKey().toLowerCase(Locale.ROOT).contains(name))
                    ? req.getValue() : 0;
            Integer have = viewer == null ? null : playerAttribute(viewer, attrKeyFor(name));
            sb.append("&f").append(icon).append(' ');
            if (need > 0) {
                sb.append(have != null && have >= need ? "&a" : "&c");
            } else {
                sb.append("&7");
            }
            sb.append(need);
            if (have != null) sb.append("&8(").append(have).append(')');
            if (i < start + 2) sb.append("  ");
        }
        return LEGACY.deserialize(sb.toString())
                .style(s -> s.shadowColor(ShadowColor.none()));
    }

    private String attrKeyFor(String name) {
        for (String key : config.getAttrReqAttributes().keySet()) {
            if (key.toLowerCase(Locale.ROOT).contains(name)) return key;
        }
        return "base_" + name;
    }

    /** Append the viewer's running total to base-six stat bonus lines. */
    private boolean appendStatTotals(ItemMeta meta, List<Component> lore, Player viewer) {
        if (viewer == null) return false;
        boolean changed = false;
        for (int i = 0; i < lore.size(); i++) {
            String trimmed = PLAIN.serialize(lore.get(i)).trim();
            Matcher m = STAT_BONUS.matcher(trimmed);
            if (!m.matches()) continue;
            String name = m.group(1);
            String value = m.group(2);
            int have = playerAttribute(viewer, attrKeyFor(name.toLowerCase(Locale.ROOT)));
            Component fresh = LEGACY.deserialize("&b" + name + "&7: " + value + " &8(" + have + ")");
            if (!fresh.equals(lore.get(i))) {
                lore.set(i, fresh);
                changed = true;
            }
            // Keep Divinity's stored copy of this line in step with the edit,
            // even when the visible line didn't change this pass (heals items
            // stamped before this sync existed).
            changed |= syncFabledLoreTag(meta, name + ": " + value, SECTION.serialize(fresh));
        }
        return changed;
    }

    /**
     * Divinity (and Smithy on top of it) locates a stat's lore line by
     * comparing the color-stripped text stored in the {@code fogus_loren-*}
     * PDC tag against the item's lore. Since we append " (total)" to the
     * base-six fabled lines, the stored copy must be updated too or every
     * consumer — Smithy flux/chaos/purge/scrap/lock eligibility, and
     * Divinity's own lore rebuild — treats the stat as no longer applied.
     */
    private boolean syncFabledLoreTag(ItemMeta meta, String baseText, String freshSection) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            String k = key.getKey();
            if (!k.startsWith("fogus_loren-item_fabled_attr_")) continue;
            String stored = pdc.get(key, PersistentDataType.STRING);
            if (stored == null || stored.contains("__x__")) continue; // multi-line: not ours
            String storedBase = TOTAL_SUFFIX.matcher(stripSectionCodes(stored).trim()).replaceFirst("");
            if (!storedBase.equals(baseText)) continue;
            if (!stored.equals(freshSection)) {
                pdc.set(key, PersistentDataType.STRING, freshSection);
                return true;
            }
            return false;
        }
        return false;
    }

    private static String stripSectionCodes(String s) {
        return s == null ? "" : s.replaceAll("§[0-9a-fk-orxA-FK-ORX]", "");
    }

    /**
     * Smithy's Lock Seal re-appends its "Smithy Seals:" block at the very
     * bottom of the lore (below our page-dots footer). Keep the footer last.
     */
    private boolean liftSealsAboveFooter(List<Component> lore) {
        int footer = -1, sealStart = -1;
        for (int i = 0; i < lore.size(); i++) {
            String plain = PLAIN.serialize(lore.get(i));
            if (footer < 0 && (plain.contains("Press F") || containsGlyph(plain, DOT_ON, DOT_OFF))) {
                footer = i;
            } else if (plain.trim().equalsIgnoreCase("Smithy Seals:")) {
                sealStart = i;
            }
        }
        if (footer < 0 || sealStart <= footer) return false;

        int from = sealStart;
        if (from > 0 && PLAIN.serialize(lore.get(from - 1)).isBlank()) from--;
        int to = sealStart + 1;
        while (to < lore.size()) {
            String t = PLAIN.serialize(lore.get(to)).trim();
            if (t.startsWith("★") || t.startsWith("*")) to++;
            else break;
        }
        List<Component> block = new ArrayList<>(lore.subList(from, to));
        lore.subList(from, to).clear();
        lore.addAll(footer, block);
        return true;
    }

    /**
     * Smithy (Chunk of Scrap and friends) and Divinity re-identify append a
     * newly added stat line at the very bottom of the lore — below our
     * page-dots footer. Lift any non-blank content that lands after the footer
     * back into the stat block (just above the footer) so the footer stays
     * last and the new stat shows where attributes normally do. Complements
     * {@link #liftSealsAboveFooter} (which groups the Seals block); this
     * handles the general single-stat case.
     */
    private boolean liftContentBelowFooter(List<Component> lore) {
        int footer = -1;
        for (int i = 0; i < lore.size(); i++) {
            String plain = PLAIN.serialize(lore.get(i));
            if (plain.contains("Press F") || containsGlyph(plain, DOT_ON, DOT_OFF)) {
                footer = i;
                break;
            }
        }
        if (footer < 0 || footer >= lore.size() - 1) return false;

        List<Component> moved = new ArrayList<>();
        for (int i = footer + 1; i < lore.size(); i++) {
            if (!PLAIN.serialize(lore.get(i)).trim().isEmpty()) moved.add(lore.get(i));
        }
        if (moved.isEmpty()) return false;

        lore.subList(footer + 1, lore.size()).clear();
        int insertAt = footer;
        if (insertAt > 0 && PLAIN.serialize(lore.get(insertAt - 1)).trim().isEmpty()) insertAt--;
        lore.addAll(insertAt, moved);
        return true;
    }

    /** Replace the "Press F" footer with page dots. */
    private boolean applyFooterDots(PersistentDataContainer pdc, List<Component> lore) {
        int totalPages = totalPages(pdc);
        for (int i = 0; i < lore.size(); i++) {
            String plain = PLAIN.serialize(lore.get(i));
            if (!plain.contains("Press F") && !containsGlyph(plain, DOT_ON, DOT_OFF)) continue;
            Component footer = buildFooter(1, totalPages);
            if (footer.equals(lore.get(i))) return false;
            lore.set(i, footer);
            return true;
        }
        return false;
    }

    private int totalPages(PersistentDataContainer pdc) {
        int total = 1;
        if (pdc.has(pageSetKey, PersistentDataType.STRING)) total++;
        if (pdc.has(page2Key, PersistentDataType.STRING)) total++;
        return total;
    }

    private static Component buildFooter(int page, int totalPages) {
        StringBuilder dots = new StringBuilder("&f");
        for (int i = 1; i <= totalPages; i++) {
            dots.append(i == page ? DOT_ON : DOT_OFF);
            if (i < totalPages) dots.append(' ');
        }
        dots.append("  &8Press &fF &8for next page");
        return LEGACY.deserialize(dots.toString())
                .style(s -> s.shadowColor(ShadowColor.none()));
    }

    // ─── lore cleanup ───────────────────────────────────────────────────────

    /**
     * Cleanup pass over stamped lore:
     * - collapse consecutive section dividers (left behind when a Divinity
     *   section like item-stats or fabled attrs rolled empty),
     * - drop dividers at the very start/end of the lore,
     * - remove the text shadow on glyph-only lines (pills/dividers otherwise
     *   render with a doubled/ghosted edge).
     * Idempotent; runs on every stamp.
     */
    private void tidyLore(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasLore()) return;
        List<Component> lore = meta.lore();
        if (lore == null || lore.isEmpty()) return;

        List<Component> out = new ArrayList<>(lore.size());
        boolean changed = false;
        boolean prevKeptIsDivider = true; // also strips leading dividers
        for (Component line : lore) {
            String plain = PLAIN.serialize(line);
            String trimmed = plain.trim();
            // Drop unresolved Divinity placeholders (unregistered Fabled attrs, etc.)
            if (RAW_PLACEHOLDER.matcher(trimmed).find()) {
                changed = true;
                continue;
            }
            boolean isDivider = isDividerText(trimmed);
            if (isDivider && prevKeptIsDivider) {
                changed = true;
                continue;
            }
            Component fixed = stripGlyphShadow(line, plain);
            if (fixed != line) changed = true;
            out.add(fixed);
            if (!trimmed.isEmpty()) prevKeptIsDivider = isDivider;
        }
        while (!out.isEmpty()) {
            String tail = PLAIN.serialize(out.get(out.size() - 1)).trim();
            if (!isDividerText(tail)) break;
            out.remove(out.size() - 1);
            changed = true;
        }
        if (changed) {
            meta.lore(out);
            stack.setItemMeta(meta);
        }
    }

    private static boolean isDividerText(String trimmed) {
        return !trimmed.isEmpty() && trimmed.chars().allMatch(c -> c == DIVIDER_CHAR);
    }

    private static boolean containsGlyphRange(String plain, int min, int max) {
        return plain.chars().anyMatch(c -> c >= min && c <= max);
    }

    private static boolean containsGlyph(String plain, char a, char b) {
        return plain.indexOf(a) >= 0 || plain.indexOf(b) >= 0;
    }

    private static Component stripGlyphShadow(Component line, String plain) {
        boolean hasGlyph = plain.chars().anyMatch(c -> c >= GLYPH_MIN && c <= GLYPH_MAX);
        if (!hasGlyph) return line;
        if (ShadowColor.none().equals(line.style().shadowColor())) return line;
        return line.style(line.style().shadowColor(ShadowColor.none()));
    }

    // ─── derived attribute requirements ────────────────────────────────────

    /**
     * Set once per item: the attribute governing the item's dominant rolled
     * Fabled stat (via {@link TooltipConfig#attributeForStat}), valued by the
     * item's Divinity level and tier. Divinity has no native attribute
     * requirement, so CIP derives it deterministically from what the generator
     * already rolled — a +spell-damage item asks for Intelligence, a defensive
     * piece asks for Vitality, etc. See Docs/deisgn/tooltips.md.
     */
    private void maybeRollAttrRequirement(ItemStack stack, String itemId) {
        if (!config.isAttrReqEnabled() || itemId == null) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(attrReqKey, PersistentDataType.STRING)) return;

        String tier = resolveDivinityTier(stack);
        if (!config.attrReqApplies(tier, itemId)) return;

        String attr = dominantAttribute(pdc);
        if (attr == null) return; // no thematic stat to gate on

        int level = itemLevel(stack);
        double raw = (config.getAttrReqScaleBase() + config.getAttrReqScalePerLevel() * level)
                * config.attrReqTierMultiplier(tier);
        int value = (int) Math.round(raw);
        value = Math.max(config.getAttrReqMin(), Math.min(config.getAttrReqMax(), value));

        pdc.set(attrReqKey, PersistentDataType.STRING, attr + ":" + value);
        stack.setItemMeta(meta);
    }

    /** PDC tag prefix Divinity writes for each rolled Fabled stat. */
    private static final String FABLED_ATTR_PREFIX = "fogus_loren-item_fabled_attr_";
    private static final Pattern FIRST_NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /**
     * The base attribute governing this item's largest mapped Fabled stat, or
     * null when the item has no mappable stat. Weighted by each stat's rolled
     * magnitude; ties break in configured attribute order (Str, Dex, …).
     */
    private String dominantAttribute(PersistentDataContainer pdc) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (NamespacedKey key : pdc.getKeys()) {
            String k = key.getKey();
            if (!k.startsWith(FABLED_ATTR_PREFIX)) continue;
            String attr = config.attributeForStat(k.substring(FABLED_ATTR_PREFIX.length()));
            if (attr == null) continue;
            weights.merge(attr, statMagnitude(pdc.get(key, PersistentDataType.STRING)), Double::sum);
        }
        String best = null;
        double bestWeight = -1;
        for (String attr : config.getAttrReqAttributes().keySet()) {
            Double w = weights.get(attr);
            if (w != null && w > bestWeight) {
                bestWeight = w;
                best = attr;
            }
        }
        return best;
    }

    /** Absolute numeric magnitude parsed from a stored (section-coded) stat line. */
    private double statMagnitude(String stored) {
        if (stored == null) return 1.0;
        Matcher m = FIRST_NUMBER.matcher(stripSectionCodes(stored));
        if (m.find()) {
            try {
                return Math.abs(Double.parseDouble(m.group()));
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 1.0;
    }

    /** Divinity item level, or 1 when unavailable. Drives requirement scaling. */
    private int itemLevel(ItemStack stack) {
        if (divinityAvailable && itemStatsGetLevel != null) {
            try {
                Object v = itemStatsGetLevel.invoke(null, stack);
                if (v instanceof Number n) return Math.max(1, n.intValue());
            } catch (Throwable ignored) {
                // fall through to default
            }
        }
        return 1;
    }

    /** Parsed requirement, or null. */
    public Map.Entry<String, Integer> getAttrRequirement(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return getAttrRequirementFrom(stack.getItemMeta().getPersistentDataContainer());
    }

    private Map.Entry<String, Integer> getAttrRequirementFrom(PersistentDataContainer pdc) {
        String raw = pdc.get(attrReqKey, PersistentDataType.STRING);
        if (raw == null) return null;
        int i = raw.lastIndexOf(':');
        if (i <= 0) return null;
        try {
            return Map.entry(raw.substring(0, i), Integer.parseInt(raw.substring(i + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @return null if met (or no requirement), otherwise a player-facing denial message. */
    public String unmetAttrRequirement(ItemStack stack, Player player) {
        Map.Entry<String, Integer> req = getAttrRequirement(stack);
        if (req == null || player == null) return null;
        if (playerAttribute(player, req.getKey()) >= req.getValue()) return null;
        String display = config.getAttrReqAttributes().getOrDefault(req.getKey(), req.getKey());
        return "Requires " + req.getValue() + " " + display;
    }

    private int playerAttribute(Player player, String attrKey) {
        try {
            PlayerData data = Fabled.getData(player);
            return data == null ? 0 : data.getAttribute(attrKey);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** RPG level from Fabled (vanilla XP level is not the RPG level). */
    private int playerLevel(Player player) {
        try {
            PlayerData data = Fabled.getData(player);
            if (data == null) return 0;
            var main = data.getMainClass();
            if (main != null) return main.getLevel();
            // no main class group: take the highest class level the player has
            int best = 0;
            for (var pc : data.getClasses()) {
                best = Math.max(best, pc.getLevel());
            }
            return best;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ─── style + item identity ──────────────────────────────────────────────

    private String resolveStylePath(ItemStack stack, String itemId) {
        String byId = config.styleForItemId(itemId);
        if (byId != null) return byId;

        // Gems: frame follows the displayed "Tier:" rarity, not the Divinity
        // tier id (which is a folder artifact like "archieve").
        if (gemTooltip.isGem(itemId)) {
            String byRarity = config.styleForTier(gemTooltip.rarity(stack));
            if (byRarity != null) return byRarity;
        }

        String tier = resolveDivinityTier(stack);
        String byTier = config.styleForTier(tier);
        if (byTier != null) return byTier;

        return config.styleForMaterial(stack.getType().name());
    }

    private String resolveItemId(ItemStack stack) {
        if (divinityAvailable && itemStatsGetId != null) {
            try {
                Object id = itemStatsGetId.invoke(null, stack);
                if (id instanceof String s && !s.isBlank()) return s.toLowerCase(Locale.ROOT);
            } catch (Throwable ignored) { }
        }
        if (stack.hasItemMeta()) {
            String pdc = stack.getItemMeta().getPersistentDataContainer()
                    .get(divinityItemIdKey, PersistentDataType.STRING);
            if (pdc != null && !pdc.isBlank()) return pdc.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** Public tier lookup for other systems (e.g. AutoLoot effects). Null if not a Divinity item. */
    public String resolveTier(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        return resolveDivinityTier(stack);
    }

    private String resolveDivinityTier(ItemStack stack) {
        if (!divinityAvailable) return null;
        try {
            Object module = itemStatsGetModule.invoke(null, stack);
            if (module == null) return null;
            Object moduleItem = moduleGetModuleItem.invoke(module, stack);
            if (moduleItem == null) return null;
            if (!getTierMethod.getDeclaringClass().isInstance(moduleItem)) return null;
            Object tier = getTierMethod.invoke(moduleItem);
            if (tier == null) return null;
            Object id = tierGetId.invoke(tier);
            return id instanceof String s ? s.toLowerCase(Locale.ROOT) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void applyStyle(ItemStack stack, String stylePath) {
        try {
            Key key = Key.key(config.getNamespace() + ":" + stylePath.toLowerCase(Locale.ROOT));
            Key existing = stack.getData(DataComponentTypes.TOOLTIP_STYLE);
            if (key.equals(existing)) return;
            stack.setData(DataComponentTypes.TOOLTIP_STYLE, key);
            // setData can leave ItemMeta stale for PDC writes that follow
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "tooltip_style stamp failed (" + stylePath + "): " + t.getMessage());
        }
    }

    // ─── F-key page cycle ───────────────────────────────────────────────────

    private void ensureDetailPage(ItemStack stack, String itemId) {
        // Always attach a source page so "Press F" works for every Divinity (or override) item.
        // Prefer hand-written source; otherwise a placeholder line. Footer is added at display.
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        if (meta.getPersistentDataContainer().has(page2Key, PersistentDataType.STRING)) return;

        // Only for items we style (Divinity / material override) — skip vanilla junk.
        String stylePath = resolveStylePath(stack, itemId);
        if (stylePath == null && itemId == null) return;

        String source = config.sourceForItemId(itemId);
        List<String> page = new ArrayList<>();
        page.add("&8Source");
        page.add(source != null && !source.isBlank() ? "&f" + source : "&7No notes yet.");
        meta.getPersistentDataContainer().set(page2Key, PersistentDataType.STRING, String.join("\n", page));
        meta.getPersistentDataContainer().set(pageKey, PersistentDataType.INTEGER, 1);
        meta.getPersistentDataContainer().set(styledKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
    }

    /**
     * Cycle lore pages: stats → set details (when present) → source → stats.
     * @return true if the page changed
     */
    public boolean toggleDetailPage(ItemStack stack, Player viewer) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String setRaw = pdc.get(pageSetKey, PersistentDataType.STRING);
        String sourceRaw = pdc.get(page2Key, PersistentDataType.STRING);
        if (setRaw == null && sourceRaw == null) return false;

        int totalPages = totalPages(pdc);
        int page = pdc.getOrDefault(pageKey, PersistentDataType.INTEGER, 1);

        if (page == 1) {
            pdc.set(page1Key, PersistentDataType.STRING, serializeLore(meta.lore()));
            if (setRaw != null) {
                showStoredPage(meta, setRaw, 2, totalPages);
            } else {
                showStoredPage(meta, sourceRaw, 2, totalPages);
            }
            pdc.set(pageKey, PersistentDataType.INTEGER, 2);
        } else if (page == 2 && setRaw != null && sourceRaw != null) {
            showStoredPage(meta, sourceRaw, 3, totalPages);
            pdc.set(pageKey, PersistentDataType.INTEGER, 3);
        } else {
            String page1Raw = pdc.get(page1Key, PersistentDataType.STRING);
            if (page1Raw == null) return false;
            meta.lore(deserializeLore(page1Raw));
            pdc.set(pageKey, PersistentDataType.INTEGER, 1);
        }
        stack.setItemMeta(meta);
        if (pdc.getOrDefault(pageKey, PersistentDataType.INTEGER, 1) == 1) {
            // back on the live page: refresh viewer state (level ✓, totals, strip)
            String itemId = resolveItemId(stack);
            if (gemTooltip.isGem(itemId)) {
                reflowGem(stack, viewer);
            } else {
                restructureLore(stack, itemId, viewer);
            }
        }
        // legacy round-trip drops shadow-color styling on glyph lines
        tidyLore(stack);
        return true;
    }

    private void showStoredPage(ItemMeta meta, String raw, int page, int totalPages) {
        List<Component> lore = new ArrayList<>();
        List<String> lines = new ArrayList<>(List.of(raw.split("\n", -1)));
        // strip footers baked by older CIP builds
        while (!lines.isEmpty()) {
            String tail = PLAIN.serialize(LEGACY.deserialize(lines.get(lines.size() - 1))).trim();
            if (tail.isEmpty() || tail.contains("Press F")) lines.remove(lines.size() - 1);
            else break;
        }
        for (String line : lines) {
            lore.add(LEGACY.deserialize(line));
        }
        lore.add(Component.empty());
        lore.add(buildFooter(page, totalPages));
        meta.lore(lore);
    }

    public boolean hasDetailPage(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        return pdc.has(page2Key, PersistentDataType.STRING)
                || pdc.has(pageSetKey, PersistentDataType.STRING);
    }

    private static String serializeLore(List<Component> lore) {
        if (lore == null || lore.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lore.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(LEGACY.serialize(lore.get(i)));
        }
        return sb.toString();
    }

    private static List<Component> deserializeLore(String raw) {
        List<Component> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            out.add(Component.text("").color(NamedTextColor.GRAY));
            return out;
        }
        for (String line : raw.split("\n", -1)) {
            out.add(LEGACY.deserialize(line));
        }
        return out;
    }

    /** True if this looks like a Divinity/styled gameplay item worth scanning. */
    public boolean isCandidate(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        if (resolveItemId(stack) != null) return true;
        return config.styleForMaterial(stack.getType().name()) != null;
    }
}
