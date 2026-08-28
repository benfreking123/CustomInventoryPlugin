package com.example.custominventoryplugin.party;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** No-op when AlessioDP Parties is not installed. */
public final class NoOpPartyService implements PartyService {
    @Override public boolean isAvailable() { return false; }
    @Override public Optional<UUID> getPartyId(UUID playerId) { return Optional.empty(); }
    @Override public Optional<UUID> getLeader(UUID partyId) { return Optional.empty(); }
    @Override public List<UUID> getMembers(UUID partyId) { return List.of(); }
    @Override public boolean areInSameParty(UUID a, UUID b) { return false; }
    @Override public boolean isLeader(UUID playerId) { return false; }
    @Override public List<Player> eligibleMembers(Player source, Location origin, double radius) {
        return Collections.emptyList();
    }
    @Override public LootMode getLootMode(UUID partyId) { return LootMode.KILLER; }
    @Override public void setLootMode(UUID partyId, LootMode mode) {}
    @Override public boolean canChangeLootMode(UUID playerId) { return false; }
    @Override public boolean invite(Player from, Player target) { return false; }
    @Override public boolean kick(Player actor, UUID targetId) { return false; }
    @Override public boolean promote(Player actor, UUID targetId) { return false; }
    @Override public boolean leave(Player player) { return false; }
    @Override public boolean startReadyCheck(Player leader) { return false; }
    @Override public void shutdown() {}
}
