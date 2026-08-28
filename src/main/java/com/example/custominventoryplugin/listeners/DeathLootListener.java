package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.autoloot.AutoLootConfig;
import com.example.custominventoryplugin.autoloot.LootEffectService;
import com.example.custominventoryplugin.party.LootMode;
import com.example.custominventoryplugin.party.PartiesPartyService;
import com.example.custominventoryplugin.party.PartyService;
import com.example.custominventoryplugin.pickup.DelayedGroundPickup;
import com.example.custominventoryplugin.pickup.PickupPipeline;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/**
 * Mob-death auto-loot feed — CIP is the server's drop manager. Registered at
 * {@link EventPriority#HIGHEST} with {@code softdepend: [Divinity]} so it runs
 * AFTER Divinity's DropManager (which injects custom loot into getDrops()) and
 * BEFORE Divinity's LootManager. With the Divinity {@code loot} module disabled,
 * no loot-box skull is created; CIP presents and routes every drop instead.
 *
 * When the killer is in a party, each item is assigned per the party's
 * {@link LootMode} to an eligible member (same world, within radius). Solo
 * and {@link LootMode#KILLER} keep today's behaviour.
 */
public class DeathLootListener implements Listener {

    private final CustomInventoryPlugin plugin;
    private final PickupPipeline pipeline;
    private final AutoLootConfig config;
    private final LootEffectService effects;
    private final PartyService parties;

    public DeathLootListener(CustomInventoryPlugin plugin, PickupPipeline pipeline,
                             AutoLootConfig config, LootEffectService effects,
                             PartyService parties) {
        this.plugin = plugin;
        this.pipeline = pipeline;
        this.config = config;
        this.effects = effects;
        this.parties = parties;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        if (!pipeline.masterEnabled(killer)) return;
        if (isBlacklisted(event.getEntity())) return;

        List<ItemStack> drops = event.getDrops();
        if (drops.isEmpty()) {
            grantXp(event, killer);
            return;
        }

        int delay = (config != null)
                ? config.getGroundLingerTicks()
                : plugin.getConfigManager().getAutopickupGroundDelayTicks();
        List<ItemStack> toShow = new ArrayList<>();
        Iterator<ItemStack> it = drops.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (stack == null || stack.getType().isAir()) continue;
            toShow.add(stack.clone());
            it.remove();
        }

        var loc = event.getEntity().getLocation().add(0, 0.25, 0);
        List<Player> eligible = resolveEligible(killer, loc);
        Optional<UUID> partyId = parties.getPartyId(killer.getUniqueId());
        LootMode mode = partyId.map(parties::getLootMode).orElse(LootMode.KILLER);

        for (ItemStack stack : toShow) {
            Player recipient = pickRecipient(mode, killer, eligible, partyId.orElse(null));
            if (recipient == null) recipient = killer;
            if (!pipeline.masterEnabled(recipient)) recipient = killer;
            DelayedGroundPickup.schedule(plugin, pipeline, recipient, loc, stack, delay, effects);
        }

        grantXp(event, killer);
    }

    private List<Player> resolveEligible(Player killer, org.bukkit.Location loc) {
        if (!parties.isAvailable()) return List.of(killer);
        List<Player> members = parties.eligibleMembers(killer, loc, PartiesPartyService.DEFAULT_ELIGIBILITY_RADIUS);
        if (members.isEmpty()) return List.of(killer);
        // Always include the killer if somehow filtered out
        if (!members.contains(killer)) {
            List<Player> withKiller = new ArrayList<>(members);
            withKiller.add(killer);
            return withKiller;
        }
        return members;
    }

    private Player pickRecipient(LootMode mode, Player killer, List<Player> eligible, UUID partyId) {
        if (eligible == null || eligible.isEmpty()) return killer;
        return switch (mode) {
            case KILLER -> killer;
            case RANDOM -> eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
            case ROUND_ROBIN -> {
                if (parties instanceof PartiesPartyService pps && partyId != null) {
                    Player next = pps.nextRoundRobin(partyId, eligible);
                    yield next != null ? next : killer;
                }
                yield eligible.get(0);
            }
        };
    }

    private boolean isBlacklisted(Entity entity) {
        if (config == null) return false;
        if (config.getBlacklistEntityTypes().contains(entity.getType().name())) return true;
        CreatureSpawnEvent.SpawnReason reason = entity.getEntitySpawnReason();
        return reason != null && config.getBlacklistSpawnReasons().contains(reason.name());
    }

    private void grantXp(EntityDeathEvent event, Player killer) {
        int xp = event.getDroppedExp();
        if (xp > 0) {
            killer.giveExp(xp);
            event.setDroppedExp(0);
        }
    }
}
