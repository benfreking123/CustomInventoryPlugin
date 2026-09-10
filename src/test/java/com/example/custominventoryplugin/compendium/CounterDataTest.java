package com.example.custominventoryplugin.compendium;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.example.custominventoryplugin.compendium.CounterData.countPrefix;
import static com.example.custominventoryplugin.compendium.CounterData.sumPrefix;
import static com.example.custominventoryplugin.compendium.CounterData.sumPrefixSuffix;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The aggregation the Account book and the Crates tile read counters through.
 * Prefix arithmetic is the whole contract between CIP and the plugins that
 * write counters, so the shapes those hooks emit are pinned here.
 */
class CounterDataTest {

    /** What TowerCrates writes: one key per opening, ".mimic" when it bit. */
    private static final Map<String, Integer> CRATES = Map.of(
            "crate.floor.common", 12,
            "crate.floor.common.mimic", 3,
            "crate.floor.uncommon", 4,
            "crate.vote.rare", 2,
            "crystal.floor2", 9);

    /**
     * A mimic opening is counted once, under the suffixed key only — so the
     * total is the plain prefix sum and mimics are a share of it. Counting a
     * mimic under both keys would make this 21 and overstate every tally.
     */
    @Test
    void everyOpeningIsCountedExactlyOnceUnderTheCrateRoot() {
        assertEquals(21, sumPrefix(CRATES, "crate."));
        assertEquals(3, sumPrefixSuffix(CRATES, "crate.", ".mimic"));
    }

    /** The suffix ordering is what keeps a tier's roll-up whole. */
    @Test
    void aTiersTotalIncludesItsMimicsBecauseMimicIsASuffix() {
        assertEquals(15, sumPrefix(CRATES, "crate.floor.common"));
        assertEquals(19, sumPrefix(CRATES, "crate.floor."));
        assertEquals(2, sumPrefix(CRATES, "crate.vote."));
    }

    /** Counter families are namespaced, so one prefix never reads another's rows. */
    @Test
    void onePrefixDoesNotReadAnothersRows() {
        assertEquals(9, sumPrefix(CRATES, "crystal."));
        assertEquals(0, sumPrefix(CRATES, "dungeon."));
        assertEquals(0, sumPrefixSuffix(CRATES, "crystal.", ".mimic"));
    }

    /**
     * Camps are discovery flags rather than tallies: the count is how many keys
     * exist, not what they add up to.
     */
    @Test
    void discoveryFlagsAreCountedNotSummed() {
        Map<String, Integer> camps = Map.of(
                "checkpoint.floor1", 1,
                "checkpoint.floor2", 4,
                "checkpoint.floor3_1", 0);

        assertEquals(2, countPrefix(camps, "checkpoint."));
    }

    @Test
    void aPlayerWithNoCountersReadsAsZeroRatherThanThrowing() {
        assertEquals(0, sumPrefix(Map.of(), "crate."));
        assertEquals(0, sumPrefixSuffix(Map.of(), "crate.", ".mimic"));
        assertEquals(0, countPrefix(Map.of(), "checkpoint."));
    }
}
