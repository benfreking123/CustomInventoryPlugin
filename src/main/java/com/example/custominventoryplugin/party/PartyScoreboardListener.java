package com.example.custominventoryplugin.party;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Loads the sidebar preference on join and drops the board on quit. */
public final class PartyScoreboardListener implements Listener {

    private final PartyHudStore store;
    private final PartyScoreboardService scoreboards;

    public PartyScoreboardListener(PartyHudStore store, PartyScoreboardService scoreboards) {
        this.store = store;
        this.scoreboards = scoreboards;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        store.load(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        scoreboards.handleQuit(event.getPlayer());
    }
}
