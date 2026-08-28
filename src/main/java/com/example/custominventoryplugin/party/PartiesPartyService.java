package com.example.custominventoryplugin.party;

import com.alessiodp.parties.api.Parties;
import com.alessiodp.parties.api.enums.Status;
import com.alessiodp.parties.api.interfaces.PartiesAPI;
import com.alessiodp.parties.api.interfaces.Party;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    @Override
    public boolean invite(Player from, Player target) {
        if (from == null || target == null) return false;
        PartyPlayer inviter = api.getPartyPlayer(from.getUniqueId());
        PartyPlayer invited = api.getPartyPlayer(target.getUniqueId());
        if (inviter == null || invited == null) return false;

        Party party = api.getPartyOfPlayer(from.getUniqueId());
        if (party == null) {
            // Auto-create named after the leader (matches Parties dynamic naming).
            boolean created = api.createParty(from.getName(), inviter);
            if (!created) return false;
            party = api.getPartyOfPlayer(from.getUniqueId());
            if (party == null) return false;
            settings.ensureDefault(party.getId());
        }
        return party.invitePlayer(inviter, invited) != null;
    }

    @Override
    public boolean kick(Player actor, UUID targetId) {
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
    public boolean promote(Player actor, UUID targetId) {
        if (!isLeader(actor.getUniqueId())) return false;
        Party party = api.getPartyOfPlayer(actor.getUniqueId());
        PartyPlayer target = api.getPartyPlayer(targetId);
        if (party == null || target == null) return false;
        party.changeLeader(target);
        return true;
    }

    @Override
    public boolean leave(Player player) {
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
