package com.example.custominventoryplugin.party;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Façade over the network party roster. Callers never touch AlessioDP Parties
 * directly — if the roster plugin is swapped, only the impl changes.
 */
public interface PartyService {

    boolean isAvailable();

    Optional<UUID> getPartyId(UUID playerId);

    Optional<UUID> getLeader(UUID partyId);

    List<UUID> getMembers(UUID partyId);

    boolean areInSameParty(UUID a, UUID b);

    boolean isLeader(UUID playerId);

    /**
     * Party members online on THIS backend, same world, within {@code radius}
     * of {@code origin}. Empty when the player is not in a party or nobody
     * else qualifies — callers should fall back to the killer alone.
     */
    List<Player> eligibleMembers(Player source, Location origin, double radius);

    LootMode getLootMode(UUID partyId);

    void setLootMode(UUID partyId, LootMode mode);

    /** True if the player may change loot mode (leader, or rank with party.lootmode). */
    boolean canChangeLootMode(UUID playerId);

    boolean invite(Player from, Player target);

    boolean kick(Player actor, UUID targetId);

    boolean promote(Player actor, UUID targetId);

    boolean leave(Player player);

    /** Start a short ready-check; returns false if no party / already running. */
    boolean startReadyCheck(Player leader);

    void shutdown();
}
