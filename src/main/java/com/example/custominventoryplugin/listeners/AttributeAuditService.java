package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.data.PlayerGearData;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Checks that {@code cip_player_slot_attrs} still describes reality.
 *
 * WHY THIS IS THE ONE WORTH WATCHING. Every other failure in this plugin is
 * self-healing: a tooltip renders stale until the next stamp, a gear slot shows
 * the wrong icon until the next open. Attribute grants are not, because they go
 * through Fabled's INVESTED-POINTS pool. A point given and never taken back is
 * not a cache that expires — it is written to Fabled's own SQL store, survives
 * relog, and the next grant adds to it. So a single missed revoke does not cost
 * one session of wrong stats, it permanently inflates the player, and every
 * login after that compounds from the new wrong baseline.
 *
 * The ledger row is the only record that a grant happened. If a row says the
 * head slot gave +4 vitality and the player is bare-headed, one of two things
 * is true: the points are still in the pool with nothing left to revoke them
 * (a leak), or they were already revoked and the row is stale (harmless but
 * indistinguishable from here). Both are worth a WARNING, because a clean
 * server produces neither.
 *
 * This class only ever REPORTS. Repair is the reconcile pass on each listener,
 * which knows how to correct one slot safely; auditing decides nothing.
 */
public final class AttributeAuditService {

    private AttributeAuditService() {}

    /** One slot where the ledger and the live item disagree. */
    public record Drift(String slotId, Map<String, Integer> ledger, Map<String, Integer> live) {

        /**
         * The leak shape: we recorded a grant and there is no item to justify it.
         * Distinguished from a plain mismatch because it is the one an admin can
         * act on without needing to know what the item used to roll.
         */
        public boolean isOrphan() {
            return live.isEmpty() && !ledger.isEmpty();
        }

        @Override
        public String toString() {
            if (isOrphan()) return slotId + ": ledger " + ledger + " but slot is empty";
            if (ledger.isEmpty()) return slotId + ": item has " + live + " but nothing was granted";
            return slotId + ": ledger " + ledger + " vs item " + live;
        }
    }

    /**
     * Compare every slot that either holds a ledger row or could produce one.
     * Returns empty for a healthy player, which is the normal case and the
     * reason this is cheap enough to run on every login.
     */
    public static List<Drift> audit(Player player) {
        List<Drift> out = new ArrayList<>();
        if (player == null || !player.isOnline()) return out;
        UUID uuid = player.getUniqueId();
        // No ledger in memory means we cannot tell "granted nothing" from
        // "have not loaded yet", and reporting that as drift would warn on
        // every login before the row load finishes.
        if (!PlayerGearData.isLoaded(uuid)) return out;

        for (String slotId : slotsToCheck(uuid)) {
            Map<String, Integer> ledger = new TreeMap<>(PlayerGearData.getPlayerSlotAttributes(uuid, slotId));
            Map<String, Integer> live = new TreeMap<>(liveContribution(player, slotId));
            if (!ledger.equals(live)) out.add(new Drift(slotId, ledger, live));
        }
        return out;
    }

    /**
     * Log any drift at WARNING. Deliberately not debug: the whole point is that
     * this must be visible in a log nobody opted into reading, because the
     * damage is silent and cumulative and there is no in-game symptom until a
     * player is noticeably too strong.
     */
    public static void warnOnDrift(Player player, Logger logger, String context) {
        List<Drift> drift = audit(player);
        if (drift.isEmpty()) return;
        logger.warning("Attribute ledger drift for " + player.getName() + " (" + context + "), "
                + drift.size() + " slot(s). Fabled points may be leaking — see /ci attrcheck.");
        for (Drift d : drift) logger.warning("  " + d);
    }

    /**
     * The union of slots holding a ledger row, slots holding a gear item, and
     * the fixed hand/armor slots. Ledger rows are included even for slot ids
     * nothing recognises any more, since an unrecognised row is precisely the
     * one with no owner left to revoke it.
     */
    private static Set<String> slotsToCheck(UUID uuid) {
        Set<String> slots = new LinkedHashSet<>();
        slots.add(MainHandAttributeListener.SLOT_ID);
        slots.add(MainHandAttributeListener.OFFHAND_SLOT_ID);
        slots.addAll(List.of(ArmorAttributeListener.ARMOR_SLOT_IDS));
        slots.addAll(PlayerGearData.getGearSlotIds(uuid));
        slots.addAll(PlayerGearData.getLedgerSlotIds(uuid));
        return slots;
    }

    /**
     * What the item currently in a slot ought to be contributing. Must match
     * what that slot's owner would grant, or the audit invents drift that the
     * reconcile pass then refuses to fix — hence both sides going through
     * {@link GearAttributes}.
     */
    private static Map<String, Integer> liveContribution(Player player, String slotId) {
        if (MainHandAttributeListener.SLOT_ID.equals(slotId)) {
            return GearAttributes.ofHeld(player.getInventory().getItemInMainHand());
        }
        if (MainHandAttributeListener.OFFHAND_SLOT_ID.equals(slotId)) {
            return GearAttributes.ofOffHand(player.getInventory().getItemInOffHand());
        }
        int armorIndex = armorIndex(slotId);
        if (armorIndex >= 0) {
            ItemStack[] worn = player.getInventory().getArmorContents();
            return GearAttributes.of(armorIndex < worn.length ? worn[armorIndex] : null);
        }
        return GearAttributes.of(PlayerGearData.getPlayerGear(player.getUniqueId(), slotId));
    }

    private static int armorIndex(String slotId) {
        for (int i = 0; i < ArmorAttributeListener.ARMOR_SLOT_IDS.length; i++) {
            if (ArmorAttributeListener.ARMOR_SLOT_IDS[i].equals(slotId)) return i;
        }
        return -1;
    }
}
