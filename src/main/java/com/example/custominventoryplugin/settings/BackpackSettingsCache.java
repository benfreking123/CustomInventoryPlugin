package com.example.custominventoryplugin.settings;

import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.data.BackpackSettingsData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, write-through cache for pickup settings. Populated on
 * {@code PlayerJoinEvent}, cleared on quit. Read on every pickup event, so the
 * hot path never touches the database.
 */
public class BackpackSettingsCache {

    private final BackpackSettingsData dao;

    private final Map<UUID, PlayerPickupSettings> playerSettings = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, BagSettings>> bagSettings = new ConcurrentHashMap<>();

    public BackpackSettingsCache(BackpackSettingsData dao) {
        this.dao = dao;
    }

    public void load(UUID uuid) {
        if (uuid == null) return;
        playerSettings.put(uuid, dao.loadPlayer(uuid));
        bagSettings.put(uuid, new ConcurrentHashMap<>(dao.loadBags(uuid)));
    }

    public void unload(UUID uuid) {
        if (uuid == null) return;
        playerSettings.remove(uuid);
        bagSettings.remove(uuid);
    }

    public boolean isLoaded(UUID uuid) {
        return uuid != null && playerSettings.containsKey(uuid);
    }

    // ─── player-level master toggles ──────────────────────────────────────

    public PlayerPickupSettings player(UUID uuid) {
        PlayerPickupSettings s = playerSettings.get(uuid);
        if (s == null) {
            s = dao.loadPlayer(uuid);
            playerSettings.put(uuid, s);
        }
        return s;
    }

    public void savePlayer(UUID uuid) {
        PlayerPickupSettings s = playerSettings.get(uuid);
        if (s != null) dao.savePlayer(uuid, s);
    }

    // ─── per-bag settings ─────────────────────────────────────────────────

    /** Returns the cached row, or a fresh defaults object derived from the def (also cached). */
    public BagSettings bag(UUID uuid, BackpackDef def) {
        Map<String, BagSettings> map = bagSettings.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        return map.computeIfAbsent(def.getId(), k -> BagSettings.defaultsFor(def));
    }

    public void saveBag(UUID uuid, String backpackId) {
        Map<String, BagSettings> map = bagSettings.get(uuid);
        if (map == null) return;
        BagSettings s = map.get(backpackId);
        if (s != null) dao.saveBag(uuid, backpackId, s);
    }
}
