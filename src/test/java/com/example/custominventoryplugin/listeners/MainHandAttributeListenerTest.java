package com.example.custominventoryplugin.listeners;

import org.junit.jupiter.api.Test;

import static com.example.custominventoryplugin.listeners.MainHandAttributeListener.appliesFromHand;
import static com.example.custominventoryplugin.listeners.MainHandAttributeListener.isWand;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainHandAttributeListenerTest {

    @Test
    void weaponsAndWandsEarnTheirAttributesByBeingHeld() {
        assertTrue(appliesFromHand("DIAMOND_SWORD", "uncommon_sword"));
        assertTrue(appliesFromHand("BOW", "common_bow"));
        assertTrue(appliesFromHand("STICK", "uncommon_wand"));
        assertTrue(appliesFromHand("NETHERITE_PICKAXE", "rocky_hammer"));
    }

    @Test
    void armorEarnsNothingFromTheHandBecauseItIsEarnedByWearing() {
        assertFalse(appliesFromHand("DIAMOND_HELMET", "uncommon_helmet"));
        assertFalse(appliesFromHand("LEATHER_CHESTPLATE", "torn_necromancer_chestplate"));
        assertFalse(appliesFromHand("IRON_LEGGINGS", null));
        assertFalse(appliesFromHand("GOLDEN_BOOTS", null));
        assertFalse(appliesFromHand("ELYTRA", null));
    }

    @Test
    void jewelryEarnsNothingFromTheHandBecauseTheGearMenuOwnsIt() {
        assertFalse(appliesFromHand("EMERALD", "uncommon_emerald_ring"));
        assertFalse(appliesFromHand("PAPER", "rings_act2_talisman"));
        assertFalse(appliesFromHand("PAPER", "act1_bracelet"));
        assertFalse(appliesFromHand("PAPER", "act1_relic"));
        assertFalse(appliesFromHand("PAPER", "act1_charm"));
        assertFalse(appliesFromHand("PAPER", "act1_amulet"));
        assertFalse(appliesFromHand("PAPER", "act1_necklace"));
    }

    @Test
    void anItemWithNoDivinityIdIsJudgedOnItsMaterialAlone() {
        assertTrue(appliesFromHand("IRON_SWORD", null));
        assertFalse(appliesFromHand("IRON_HELMET", null));
        assertFalse(appliesFromHand(null, null));
    }

    @Test
    void onlyWandsAreAllowedInTheOffHand() {
        // Divinity item ids are template file names, so match is on substring.
        assertTrue(isWand("CommonWand"));
        assertTrue(isWand("UncommonWand"));
        assertTrue(isWand("commonwand"));
    }

    @Test
    void theOffHandFailsClosedOnAnythingItCannotIdentify() {
        // Two-handed swords and bows should never reach the off hand, but if a
        // template ever misses its hand-types roll the off hand must still
        // refuse them rather than hand out a second set of weapon rolls.
        assertFalse(isWand("CommonSword"));
        assertFalse(isWand("UncommonBow"));
        assertFalse(isWand("rockyhammer"));
        // Unlike the main hand, a missing id is a refusal and not a default-allow.
        assertFalse(isWand(null));
    }
}
