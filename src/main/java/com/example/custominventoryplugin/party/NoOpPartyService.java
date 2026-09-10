package com.example.custominventoryplugin.party;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

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
    @Override public Optional<LootMode> peekLootMode(UUID partyId) { return Optional.of(LootMode.KILLER); }
    @Override public void setLootMode(UUID partyId, LootMode mode) {}
    @Override public boolean canChangeLootMode(UUID playerId) { return false; }
    @Override public void invite(Player from, Player target, Consumer<InviteResult> cb) {
        cb.accept(InviteResult.FAILED);
    }
    @Override public Optional<String> pendingInviteFrom(UUID playerId) { return Optional.empty(); }
    @Override public List<String> pendingInviters(UUID playerId) { return List.of(); }
    @Override public void acceptInvite(Player player, Consumer<AcceptResult> cb) {
        cb.accept(AcceptResult.FAILED);
    }
    @Override public void acceptInvite(Player player, String inviterName, Consumer<AcceptResult> cb) {
        cb.accept(AcceptResult.FAILED);
    }
    @Override public void denyInvite(Player player, Consumer<Boolean> cb) { cb.accept(false); }
    @Override public void denyInvite(Player player, String inviterName, Consumer<Boolean> cb) {
        cb.accept(false);
    }
    @Override public void kick(Player actor, UUID targetId, Consumer<Boolean> cb) { cb.accept(false); }
    @Override public void promote(Player actor, UUID targetId, Consumer<Boolean> cb) { cb.accept(false); }
    @Override public void leave(Player player, Consumer<Boolean> cb) { cb.accept(false); }
    @Override public boolean startReadyCheck(Player leader) { return false; }
    @Override public void shutdown() {}
}
