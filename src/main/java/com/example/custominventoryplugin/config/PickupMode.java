package com.example.custominventoryplugin.config;

/**
 * Per-(player, backpack) auto-pickup behaviour. Replaces the old global
 * {@code smart-pickup} boolean.
 *
 *   OFF       — the bag never auto-collects.
 *   MATCH     — only top up stacks of items already present in the bag.
 *   OVERFLOW  — the player inventory fills first; the bag catches what won't fit.
 *   BAG_FIRST — the bag fills first (partial stacks then empty slots), then inventory.
 */
public enum PickupMode {
    OFF,
    MATCH,
    OVERFLOW,
    BAG_FIRST;

    public static PickupMode fromString(String s, PickupMode def) {
        if (s == null) return def;
        try {
            return PickupMode.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }

    /** Cycle order for the control-bar button: OFF → MATCH → OVERFLOW → BAG_FIRST → OFF. */
    public PickupMode next() {
        return switch (this) {
            case OFF -> MATCH;
            case MATCH -> OVERFLOW;
            case OVERFLOW -> BAG_FIRST;
            case BAG_FIRST -> OFF;
        };
    }

    public String display() {
        return switch (this) {
            case OFF -> "&cOff";
            case MATCH -> "&eMatch existing";
            case OVERFLOW -> "&aOverflow (inv first)";
            case BAG_FIRST -> "&aBag first";
        };
    }
}
