package com.example.custominventoryplugin.listeners;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The one place that reads an item's {@code item_fabled_attr_*} payload.
 *
 * Every slot owner ({@link AttributeHandler} when granting, the armor and hand
 * listeners when deciding whether anything changed, {@link AttributeAuditService}
 * when checking the ledger) has to agree byte for byte on what a given item is
 * worth. When they disagree the reconcile passes never settle: one of them keeps
 * seeing a difference, corrects it, and the next pass corrects it back. Keeping
 * the read in a single method is what makes "the ledger equals the item" a
 * statement you can actually test.
 *
 * Keys are stored stripped of the {@code item_fabled_attr_} prefix, matching the
 * ledger in {@code cip_player_slot_attrs}.
 */
final class GearAttributes {

    static final String PREFIX = "item_fabled_attr_";

    /**
     * The one attribute CIP writes itself rather than reading off a Divinity
     * roll — see {@code TooltipStyleService.stampWeaponDamage}.
     */
    static final String WEAPON_DAMAGE = "stat_weapon_damage";

    private GearAttributes() {}

    /**
     * Everything an item carries, with no opinion about where it is. Correct for
     * the gear-menu slots and worn armor, where being in the slot is the whole
     * qualification.
     */
    static Map<String, Integer> of(ItemStack item) {
        Map<String, Integer> out = new HashMap<>();
        PersistentDataContainer pdc = pdc(item);
        if (pdc == null) return out;
        for (NamespacedKey key : pdc.getKeys()) {
            String k = key.getKey();
            if (!k.startsWith(PREFIX)) continue;
            // A key stored under an unexpected type is skipped rather than
            // thrown on: this runs inside the 5-tick sweep, and one malformed
            // item must not take the sweep down for everyone every 5 ticks.
            Integer value = read(pdc, key);
            if (value == null || value <= 0) continue;
            out.put(k.substring(PREFIX.length()), value);
        }
        return out;
    }

    /**
     * What an item contributes while being HELD, which is {@link #of} filtered by
     * {@link MainHandAttributeListener#appliesFromHand}. Armor and jewelry return
     * empty: they are earned by wearing and by socketing, so holding a spare
     * must not turn the hand into an extra accessory slot.
     */
    static Map<String, Integer> ofHeld(ItemStack item) {
        PersistentDataContainer pdc = pdc(item);
        if (pdc == null) return new HashMap<>();
        String divinityId = null;
        try {
            divinityId = pdc.get(MainHandAttributeListener.DIVINITY_ITEM_ID, PersistentDataType.STRING);
        } catch (Exception ignored) {
            // Wrong type stored under the id key — treat as unidentified.
        }
        if (!MainHandAttributeListener.appliesFromHand(item.getType().name(), divinityId)) {
            return new HashMap<>();
        }
        return of(item);
    }

    /**
     * What an item contributes from the OFF HAND, which is wands and nothing
     * else.
     *
     * The old rule was that the off hand contributes nothing at all, to stop a
     * player holding two swords and collecting both sets of rolls. Two-handed
     * swords and bows now enforce that at the source — Divinity clears the off
     * hand when a two-handed weapon is equipped — so the blanket ban is no
     * longer what is protecting us, and it was also blocking the one pairing we
     * want. Wands are the only weapon left rolling ONE, so restricting to wands
     * keeps the original guarantee even if a two-handed roll is ever missed on
     * some template: worst case the off hand is empty, never double-dipping.
     */
    static Map<String, Integer> ofOffHand(ItemStack item) {
        PersistentDataContainer pdc = pdc(item);
        if (pdc == null) return new HashMap<>();
        String divinityId;
        try {
            divinityId = pdc.get(MainHandAttributeListener.DIVINITY_ITEM_ID, PersistentDataType.STRING);
        } catch (Exception e) {
            return new HashMap<>();
        }
        if (!MainHandAttributeListener.isWand(divinityId)) return new HashMap<>();
        Map<String, Integer> out = of(item);
        // Weapon damage is a property of THE weapon you are swinging, not a
        // total across both fists. Leaving it in would make a second wand add
        // its damage to every skill, so dual-wielding would beat a single wand
        // on raw spell damage before any of its other rolls are counted.
        out.remove(WEAPON_DAMAGE);
        return out;
    }

    private static PersistentDataContainer pdc(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer();
    }

    private static Integer read(PersistentDataContainer pdc, NamespacedKey key) {
        try {
            return pdc.get(key, PersistentDataType.INTEGER);
        } catch (Exception e) {
            return null;
        }
    }
}
