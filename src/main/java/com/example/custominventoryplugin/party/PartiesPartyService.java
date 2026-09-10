package com.example.custominventoryplugin.party;

import com.alessiodp.parties.api.Parties;
import com.alessiodp.parties.api.enums.Status;
import com.alessiodp.parties.api.interfaces.PartiesAPI;
import com.alessiodp.parties.api.interfaces.Party;
import com.alessiodp.parties.api.interfaces.PartyInvite;
import com.alessiodp.parties.api.interfaces.PartyPlayer;
import com.alessiodp.parties.api.interfaces.PartyRank;
import com.example.custominventoryplugin.CustomInventoryPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AlessioDP Parties-backed {@link PartyService}. Reads/writes the shared roster;
 * loot mode lives in {@link PartySettingsStore}.
 */
public final class PartiesPartyService implements PartyService {

    public static final double DEFAULT_ELIGIBILITY_RADIUS = 64.0;

    private final CustomInventoryPlugin plugin;
    private final PartySettingsStore settings;
    private final PartiesAPI api;
    private final Map<UUID, Integer> roundRobinCursor = new ConcurrentHashMap<>();
    private final Map<UUID, ReadyCheck> readyChecks = new ConcurrentHashMap<>();

    public PartiesPartyService(CustomInventoryPlugin plugin, PartySettingsStore settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = Parties.getApi();
    }

    public PartySettingsStore settings() {
        return settings;
    }

    /** Advance round-robin for this party and return the chosen member. */
    public Player nextRoundRobin(UUID partyId, List<Player> eligible) {
        if (eligible.isEmpty()) return null;
        int idx = roundRobinCursor.getOrDefault(partyId, 0);
        Player chosen = eligible.get(Math.floorMod(idx, eligible.size()));
        roundRobinCursor.put(partyId, idx + 1);
        return chosen;
    }

    @Override
    public boolean isAvailable() {
        return api != null;
    }

    @Override
    public Optional<UUID> getPartyId(UUID playerId) {
        Party party = api.getPartyOfPlayer(playerId);
        return party == null ? Optional.empty() : Optional.of(party.getId());
    }

    @Override
    public Optional<UUID> getLeader(UUID partyId) {
        Party party = api.getParty(partyId);
        if (party == null) return Optional.empty();
        UUID leader = party.getLeader();
        return leader == null ? Optional.empty() : Optional.of(leader);
    }

    @Override
    public List<UUID> getMembers(UUID partyId) {
        Party party = api.getParty(partyId);
        if (party == null) return List.of();
        return List.copyOf(party.getMembers());
    }

    @Override
    public boolean areInSameParty(UUID a, UUID b) {
        return api.areInTheSameParty(a, b);
    }

    @Override
    public boolean isLeader(UUID playerId) {
        Party party = api.getPartyOfPlayer(playerId);
        if (party == null) return false;
        UUID leader = party.getLeader();
        return leader != null && leader.equals(playerId);
    }

    @Override
    public List<Player> eligibleMembers(Player source, Location origin, double radius) {
        if (source == null || origin == null) return List.of();
        Party party = api.getPartyOfPlayer(source.getUniqueId());
        if (party == null) return List.of();

        World world = origin.getWorld();
        double radiusSq = radius < 0 ? -1 : radius * radius;
        List<Player> out = new ArrayList<>();
        for (UUID id : party.getMembers()) {
            Player member = Bukkit.getPlayer(id);
            if (member == null || !member.isOnline()) continue;
            if (world != null && !member.getWorld().equals(world)) continue;
            if (radiusSq >= 0 && member.getLocation().distanceSquared(origin) > radiusSq) continue;
            out.add(member);
        }
        return out;
    }

    @Override
    public LootMode getLootMode(UUID partyId) {
        return settings.getLootMode(partyId);
    }

    @Override
    public Optional<LootMode> peekLootMode(UUID partyId) {
        return settings.peekLootMode(partyId);
    }

    @Override
    public void setLootMode(UUID partyId, LootMode mode) {
        settings.setLootMode(partyId, mode);
    }

    @Override
    public boolean canChangeLootMode(UUID playerId) {
        PartyPlayer pp = api.getPartyPlayer(playerId);
        if (pp == null || !pp.isInParty()) return false;
        if (isLeader(playerId)) return true;
        int level = pp.getRank();
        for (PartyRank rank : api.getRanks()) {
            if (rank.getLevel() == level) {
                return rank.havePermission("party.lootmode") || rank.havePermission("*");
            }
        }
        return false;
    }

    /** Run {@code work} off the primary thread; see PartyService for why. */
    private void offThread(Runnable work) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, work);
        } else {
            work.run();
        }
    }

    @Override
    public void invite(Player from, Player target, Consumer<InviteResult> callback) {
        offThread(() -> callback.accept(inviteNow(from, target)));
    }

    private InviteResult inviteNow(Player from, Player target) {
        if (from == null || target == null) return InviteResult.FAILED;
        if (from.getUniqueId().equals(target.getUniqueId())) return InviteResult.SELF;
        PartyPlayer inviter = api.getPartyPlayer(from.getUniqueId());
        PartyPlayer invited = api.getPartyPlayer(target.getUniqueId());
        if (inviter == null || invited == null) {
            plugin.getLogger().warning("party invite: PartyPlayer missing for "
                    + from.getName() + " -> " + target.getName());
            return InviteResult.FAILED;
        }
        // getPartyOfPlayer is authoritative; isInParty() only checks that a
        // party id is SET, so a row left pointing at a deleted party reports
        // "already in a party" for someone the GUI correctly shows as partyless.
        // Clear the dangling id rather than leaving them permanently uninvitable.
        if (api.getPartyOfPlayer(target.getUniqueId()) != null) {
            return InviteResult.ALREADY_IN_PARTY;
        }
        if (invited.isInParty()) {
            plugin.getLogger().warning("party invite: " + target.getName()
                    + " had a dangling party id with no party behind it — clearing it");
            try {
                api.removePlayerFromParty(invited);
            } catch (Throwable t) {
                // The state is already broken; a failure here just means the
                // player has to /party leave before they can be invited.
                plugin.getLogger().warning("party invite: could not clear it: " + t);
            }
        }

        Party party = api.getPartyOfPlayer(from.getUniqueId());
        boolean createdHere = false;
        if (party == null) {
            // Auto-create named after the leader (matches Parties dynamic naming).
            if (!api.createParty(from.getName(), inviter)) {
                plugin.getLogger().warning("party invite: createParty failed for " + from.getName());
                return InviteResult.FAILED;
            }
            party = api.getPartyOfPlayer(from.getUniqueId());
            if (party == null) {
                plugin.getLogger().warning("party invite: party missing after create for " + from.getName());
                return InviteResult.FAILED;
            }
            createdHere = true;
            settings.ensureDefault(party.getId());
        }
        if (party.isFull()) return abandon(party, createdHere, InviteResult.PARTY_FULL);
        for (PartyInvite pending : party.getInviteRequests()) {
            PartyPlayer pendingTarget = pending.getInvitedPlayer();
            if (pendingTarget != null
                    && pendingTarget.getPlayerUUID().equals(target.getUniqueId())) {
                return abandon(party, createdHere, InviteResult.ALREADY_INVITED);
            }
        }

        // Argument order is (invited, inviter): Parties runs isInParty() on the
        // FIRST argument, so passing them swapped fails once the inviter has a
        // party — which, after the create above, is always.
        //
        // sendMessages is false because Parties' own invite message offers
        // /party accept, and that command belongs to the proxy, which cannot
        // see an invite this backend holds in memory. The caller sends a
        // message pointing at /ci party accept instead.
        if (party.invitePlayer(invited, inviter, false) == null) {
            plugin.getLogger().warning("party invite: Parties refused "
                    + from.getName() + " -> " + target.getName());
            return abandon(party, createdHere, InviteResult.FAILED);
        }
        return InviteResult.SENT;
    }

    /**
     * Undo a party we auto-created when the invite it existed for did not go
     * through. Without this, every failed invite strands a one-member party
     * named after the inviter, and they pile up in {@code parties_parties}.
     */
    private InviteResult abandon(Party party, boolean createdHere, InviteResult result) {
        if (createdHere && party != null) {
            try {
                settings.delete(party.getId());
                api.deleteParty(party);
            } catch (Throwable t) {
                plugin.getLogger().warning("party invite: could not roll back the party "
                        + "created for this invite: " + t);
            }
        }
        return result;
    }

    @Override
    public Optional<String> pendingInviteFrom(UUID playerId) {
        PartyInvite invite = firstPendingInvite(playerId);
        if (invite == null) return Optional.empty();
        String name = inviterName(invite);
        return Optional.of(name != null ? name : "someone");
    }

    @Override
    public List<String> pendingInviters(UUID playerId) {
        List<String> names = new ArrayList<>();
        for (PartyInvite invite : pendingInvites(playerId)) {
            String name = inviterName(invite);
            if (name != null) names.add(name);
        }
        return names;
    }

    /**
     * Pending invites in a STABLE order. {@code getPendingInvites()} is an
     * unordered collection, so with two invites outstanding the name we showed
     * the player and the party {@code accept} actually joined could differ.
     * Sorted by inviter name so the two always agree.
     */
    private List<PartyInvite> pendingInvites(UUID playerId) {
        if (playerId == null) return List.of();
        PartyPlayer pp = api.getPartyPlayer(playerId);
        if (pp == null) return List.of();
        List<PartyInvite> out = new ArrayList<>();
        for (PartyInvite invite : pp.getPendingInvites()) {
            if (invite.getParty() != null) out.add(invite);
        }
        out.sort(Comparator.comparing(i -> {
            String name = inviterName(i);
            return name == null ? "\uffff" : name.toLowerCase(Locale.ROOT);
        }));
        return out;
    }

    private String inviterName(PartyInvite invite) {
        PartyPlayer inviter = invite == null ? null : invite.getInviter();
        return inviter == null ? null : inviter.getName();
    }

    private PartyInvite firstPendingInvite(UUID playerId) {
        List<PartyInvite> invites = pendingInvites(playerId);
        return invites.isEmpty() ? null : invites.get(0);
    }

    /** Named invite, so a player with two can choose. Null name = the first. */
    private PartyInvite pendingInvite(UUID playerId, String inviterName) {
        if (inviterName == null || inviterName.isBlank()) return firstPendingInvite(playerId);
        for (PartyInvite invite : pendingInvites(playerId)) {
            String name = inviterName(invite);
            if (name != null && name.equalsIgnoreCase(inviterName)) return invite;
        }
        return null;
    }

    @Override
    public void acceptInvite(Player player, Consumer<AcceptResult> callback) {
        acceptInvite(player, null, callback);
    }

    @Override
    public void acceptInvite(Player player, String inviterName, Consumer<AcceptResult> callback) {
        offThread(() -> callback.accept(acceptInviteNow(player, inviterName)));
    }

    private AcceptResult acceptInviteNow(Player player, String inviterName) {
        if (player == null) return AcceptResult.FAILED;
        if (api.getPartyOfPlayer(player.getUniqueId()) != null) {
            return AcceptResult.ALREADY_IN_PARTY;
        }
        PartyInvite invite = pendingInvite(player.getUniqueId(), inviterName);
        if (invite == null) return AcceptResult.NO_INVITE;
        Party party = invite.getParty();
        if (party.isFull()) return AcceptResult.PARTY_FULL;
        try {
            // false: CIP words its own accept/deny replies, and Parties' copy
            // would point players at /party accept, which is the proxy command
            // and reads a roster this backend has already changed.
            invite.accept(false);
        } catch (Throwable t) {
            plugin.getLogger().warning("party accept failed for " + player.getName() + ": " + t);
            return AcceptResult.FAILED;
        }
        settings.invalidate(party.getId());
        announceJoin(party, player);
        return AcceptResult.JOINED;
    }

    /**
     * Tell the party someone arrived. Suppressing Parties' messages above also
     * suppressed this, which left the inviter with no sign their invite landed
     * unless they happened to have the sidebar on.
     */
    private void announceJoin(Party party, Player joined) {
        String text = "\u00a7a" + joined.getName() + " \u00a77joined the party.";
        List<UUID> members = new ArrayList<>(party.getMembers());
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID id : members) {
                if (id.equals(joined.getUniqueId())) continue;
                Player member = Bukkit.getPlayer(id);
                if (member != null && member.isOnline()) member.sendMessage(text);
            }
        });
    }

    @Override
    public void denyInvite(Player player, Consumer<Boolean> callback) {
        denyInvite(player, null, callback);
    }

    @Override
    public void denyInvite(Player player, String inviterName, Consumer<Boolean> callback) {
        offThread(() -> callback.accept(denyInviteNow(player, inviterName)));
    }

    private boolean denyInviteNow(Player player, String inviterName) {
        PartyInvite invite = pendingInvite(player == null ? null : player.getUniqueId(), inviterName);
        if (invite == null) return false;
        try {
            invite.deny(false);
        } catch (Throwable t) {
            plugin.getLogger().warning("party deny failed for " + player.getName() + ": " + t);
            return false;
        }
        return true;
    }

    @Override
    public void kick(Player actor, UUID targetId, Consumer<Boolean> callback) {
        offThread(() -> callback.accept(kickNow(actor, targetId)));
    }

    private boolean kickNow(Player actor, UUID targetId) {
        Party party = api.getPartyOfPlayer(actor.getUniqueId());
        if (party == null) return false;
        if (!isLeader(actor.getUniqueId()) && !canChangeLootMode(actor.getUniqueId())) {
            // Moderators have kick via Parties ranks; check invite/kick-equivalent:
            PartyPlayer actorPp = api.getPartyPlayer(actor.getUniqueId());
            if (actorPp == null) return false;
            boolean allowed = false;
            for (PartyRank rank : api.getRanks()) {
                if (rank.getLevel() == actorPp.getRank()
                        && (rank.havePermission("party.kick") || rank.havePermission("*"))) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) return false;
        }
        PartyPlayer target = api.getPartyPlayer(targetId);
        if (target == null) return false;
        Status status = api.removePlayerFromParty(target);
        return status == Status.SUCCESS;
    }

    @Override
    public void promote(Player actor, UUID targetId, Consumer<Boolean> callback) {
        offThread(() -> callback.accept(promoteNow(actor, targetId)));
    }

    private boolean promoteNow(Player actor, UUID targetId) {
        if (!isLeader(actor.getUniqueId())) return false;
        Party party = api.getPartyOfPlayer(actor.getUniqueId());
        PartyPlayer target = api.getPartyPlayer(targetId);
        if (party == null || target == null) return false;
        party.changeLeader(target);
        return true;
    }

    @Override
    public void leave(Player player, Consumer<Boolean> callback) {
        offThread(() -> callback.accept(leaveNow(player)));
    }

    private boolean leaveNow(Player player) {
        PartyPlayer pp = api.getPartyPlayer(player.getUniqueId());
        if (pp == null) return false;
        Status status = api.removePlayerFromParty(pp);
        return status == Status.SUCCESS;
    }

    @Override
    public boolean startReadyCheck(Player leader) {
        if (!isLeader(leader.getUniqueId())) {
            leader.sendMessage(Component.text("Only the party leader can start a ready check.", NamedTextColor.RED));
            return false;
        }
        Optional<UUID> partyId = getPartyId(leader.getUniqueId());
        if (partyId.isEmpty()) {
            leader.sendMessage(Component.text("You are not in a party.", NamedTextColor.RED));
            return false;
        }
        UUID pid = partyId.get();
        if (readyChecks.containsKey(pid)) {
            leader.sendMessage(Component.text("A ready check is already running.", NamedTextColor.YELLOW));
            return false;
        }

        List<Player> online = new ArrayList<>();
        for (UUID id : getMembers(pid)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) online.add(p);
        }
        if (online.isEmpty()) return false;

        ReadyCheck check = new ReadyCheck(pid, leader.getUniqueId());
        check.ready.add(leader.getUniqueId());
        readyChecks.put(pid, check);

        for (Player p : online) {
            if (!p.getUniqueId().equals(leader.getUniqueId())) {
                p.sendMessage(Component.text(leader.getName() + " started a ready check. Click [Ready] or type /ci party ready",
                        NamedTextColor.GOLD));
            } else {
                p.sendMessage(Component.text("Ready check started (" + online.size() + " online).", NamedTextColor.GOLD));
            }
        }

        check.task = Bukkit.getScheduler().runTaskLater(plugin, () -> finishReadyCheck(pid, false), 20L * 45);
        markReady(leader);
        return true;
    }

    public boolean markReady(Player player) {
        Optional<UUID> partyId = getPartyId(player.getUniqueId());
        if (partyId.isEmpty()) return false;
        ReadyCheck check = readyChecks.get(partyId.get());
        if (check == null) return false;
        check.ready.add(player.getUniqueId());
        player.sendMessage(Component.text("You are ready.", NamedTextColor.GREEN));

        int needed = 0;
        for (UUID id : getMembers(partyId.get())) {
            if (Bukkit.getPlayer(id) != null) needed++;
        }
        if (check.ready.size() >= needed) {
            finishReadyCheck(partyId.get(), true);
        }
        return true;
    }

    private void finishReadyCheck(UUID partyId, boolean allReady) {
        ReadyCheck check = readyChecks.remove(partyId);
        if (check == null) return;
        if (check.task != null) check.task.cancel();

        Player leader = Bukkit.getPlayer(check.leaderId);
        String msg = allReady
                ? "Party is ready — the leader can open the dungeon portal."
                : "Ready check timed out.";
        for (UUID id : getMembers(partyId)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(Component.text(msg, allReady ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
        }
        if (leader != null && allReady) {
            leader.sendMessage(Component.text("All members ready.", NamedTextColor.GREEN));
        }
    }

    @Override
    public void shutdown() {
        for (ReadyCheck check : readyChecks.values()) {
            if (check.task != null) check.task.cancel();
        }
        readyChecks.clear();
    }

    private static final class ReadyCheck {
        final UUID partyId;
        final UUID leaderId;
        final Set<UUID> ready = ConcurrentHashMap.newKeySet();
        BukkitTask task;

        ReadyCheck(UUID partyId, UUID leaderId) {
            this.partyId = partyId;
            this.leaderId = leaderId;
        }
    }
}
