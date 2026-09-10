package com.example.custominventoryplugin.party;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

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

    /** Cached loot mode only — never touches the database. For repeating tasks. */
    Optional<LootMode> peekLootMode(UUID partyId);

    void setLootMode(UUID partyId, LootMode mode);

    /** True if the player may change loot mode (leader, or rank with party.lootmode). */
    boolean canChangeLootMode(UUID playerId);

    /*
     * The four mutators below are CALLBACK-BASED ON PURPOSE, and the callback
     * runs OFF the main thread.
     *
     * Every Parties API call that mutates the roster fires one of its
     * {@code ...Post...Event}s, and all of those are declared async-only, so
     * Bukkit throws IllegalStateException("may only be triggered
     * asynchronously") if they are called from the server thread. Only the
     * {@code Pre} events are sync. Returning a value would invite callers to
     * use these from a command or inventory-click handler, which is exactly
     * the mistake that produced "internal error" on every invite — and it
     * leaked a real party each time, because Parties creates the party before
     * it fires the event that throws.
     *
     * Sending messages from the callback is fine. Anything touching an
     * inventory is not — hop back with runTask() first.
     */

    /**
     * Invite {@code target}, creating a party for {@code from} if needed. The
     * roster plugin sends its own (clickable) invite messages, so callers
     * should only report failures.
     */
    void invite(Player from, Player target, Consumer<InviteResult> callback);

    /**
     * Name of whoever has a party invite outstanding for this player, if any.
     *
     * <p>Only sees invites made on THIS backend. Parties does forward an invite
     * to the proxy, but each side keeps its own copy in memory with its own
     * expiry, and the proxy hides {@code /party accept} when its copy is gone —
     * which is why CIP owns the whole cycle rather than handing players off.
     */
    Optional<String> pendingInviteFrom(UUID playerId);

    /** Every outstanding inviter, so a player with more than one can pick. */
    List<String> pendingInviters(UUID playerId);

    /** Accepts the invite {@link #pendingInviteFrom} named. */
    void acceptInvite(Player player, Consumer<AcceptResult> callback);

    /** Accepts a named inviter's invite; null falls back to the first. */
    void acceptInvite(Player player, String inviterName, Consumer<AcceptResult> callback);

    void denyInvite(Player player, Consumer<Boolean> callback);

    void denyInvite(Player player, String inviterName, Consumer<Boolean> callback);

    void kick(Player actor, UUID targetId, Consumer<Boolean> callback);

    void promote(Player actor, UUID targetId, Consumer<Boolean> callback);

    void leave(Player player, Consumer<Boolean> callback);

    /** Start a short ready-check; returns false if no party / already running. */
    boolean startReadyCheck(Player leader);

    void shutdown();
}
