package com.example.custominventoryplugin.settings;

/**
 * Player-level master pickup toggles, surfaced in the {@code /bp} list GUI.
 * Persisted in {@code cip_player_pickup_settings}.
 */
public final class PlayerPickupSettings {

    private boolean masterEnabled;   // "Pickup On/Off"
    private boolean grabEverything;  // "Pickup Everything"

    public PlayerPickupSettings(boolean masterEnabled, boolean grabEverything) {
        this.masterEnabled = masterEnabled;
        this.grabEverything = grabEverything;
    }

    public static PlayerPickupSettings defaults() {
        return new PlayerPickupSettings(false, false);
    }

    public boolean isMasterEnabled()  { return masterEnabled; }
    public boolean isGrabEverything() { return grabEverything; }

    public void setMasterEnabled(boolean v)  { this.masterEnabled = v; }
    public void setGrabEverything(boolean v) { this.grabEverything = v; }
}
