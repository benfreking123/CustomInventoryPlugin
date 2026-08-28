package com.example.custominventoryplugin.party;

/**
 * Per-party loot distribution mode. Stored in {@code cip_party_settings}.
 * Solo players (no party) always behave as {@link #KILLER}.
 */
public enum LootMode {
    /** Eligible members take turns; total loot unchanged. Default. */
    ROUND_ROBIN,
    /** Each item goes to a random eligible member. */
    RANDOM,
    /** Everything to whoever landed the kill (pre-party behaviour). */
    KILLER;

    public static LootMode fromString(String raw) {
        if (raw == null || raw.isBlank()) return ROUND_ROBIN;
        try {
            return LootMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ROUND_ROBIN;
        }
    }

    public LootMode next() {
        LootMode[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }

    public String displayName() {
        return switch (this) {
            case ROUND_ROBIN -> "Round Robin";
            case RANDOM -> "Random";
            case KILLER -> "Killer Only";
        };
    }
}
