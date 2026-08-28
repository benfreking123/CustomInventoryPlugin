package com.example.custominventoryplugin.party;

import com.alessiodp.parties.api.events.bukkit.party.BukkitPartiesPartyPostCreateEvent;
import com.alessiodp.parties.api.events.bukkit.party.BukkitPartiesPartyPostDeleteEvent;
import com.alessiodp.parties.api.events.bukkit.player.BukkitPartiesPlayerPostJoinEvent;
import com.alessiodp.parties.api.events.bukkit.player.BukkitPartiesPlayerPostLeaveEvent;
import com.alessiodp.parties.api.interfaces.Party;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Keeps {@link PartySettingsStore} rows and cache in sync with party lifecycle.
 */
public final class PartyLifecycleListener implements Listener {

    private final PartySettingsStore settings;

    public PartyLifecycleListener(PartySettingsStore settings) {
        this.settings = settings;
    }

    @EventHandler
    public void onCreate(BukkitPartiesPartyPostCreateEvent event) {
        Party party = event.getParty();
        if (party != null) settings.ensureDefault(party.getId());
    }

    @EventHandler
    public void onDelete(BukkitPartiesPartyPostDeleteEvent event) {
        Party party = event.getParty();
        if (party != null) settings.delete(party.getId());
    }

    @EventHandler
    public void onJoin(BukkitPartiesPlayerPostJoinEvent event) {
        Party party = event.getParty();
        if (party != null) settings.invalidate(party.getId());
    }

    @EventHandler
    public void onLeave(BukkitPartiesPlayerPostLeaveEvent event) {
        Party party = event.getParty();
        if (party != null) settings.invalidate(party.getId());
    }
}
