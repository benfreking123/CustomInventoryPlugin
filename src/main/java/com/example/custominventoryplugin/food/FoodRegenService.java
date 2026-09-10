package com.example.custominventoryplugin.food;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import studio.magemonkey.fabled.Fabled;
import studio.magemonkey.fabled.api.enums.Operation;
import studio.magemonkey.fabled.api.player.PlayerAttributeModifier;
import studio.magemonkey.fabled.api.player.PlayerData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Grants and expires the food regeneration buff.
 *
 * Implemented as a Fabled {@link PlayerAttributeModifier} on
 * {@code stat_health_regen_mod} rather than as its own healing loop, so it
 * lands in the number the Natural Regeneration passive already reads once a
 * second. Nothing else in the game needs to know that food exists — including
 * the tooltip, which reports the attribute, not this class.
 */
public final class FoodRegenService {

    /** Fabled attribute the buff adds to. See Fabled/attributes.yml. */
    private static final String ATTR = "stat_health_regen_mod";
    private static final String MODIFIER_NAME = "cip-food-regen";

    private final JavaPlugin plugin;

    /** At most one buff per player: eating again refreshes, never stacks. */
    private final Map<UUID, Active> active = new HashMap<>();

    private record Active(UUID modifierId, BukkitTask expiry) { }

    public FoodRegenService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Apply or refresh the buff. Returns false when Fabled has no data for the
     * player, which happens during login before their class loads.
     */
    public boolean apply(Player player, int regen, int seconds) {
        PlayerData data = Fabled.getData(player);
        if (data == null) return false;

        clear(player);

        PlayerAttributeModifier modifier = new PlayerAttributeModifier(
                MODIFIER_NAME, regen, Operation.ADD_NUMBER, false);
        data.addAttributeModifier(ATTR, modifier, true);

        UUID modifierId = modifier.getUUID();
        UUID playerId = player.getUniqueId();
        BukkitTask expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            active.remove(playerId);
            removeModifier(playerId, modifierId);
        }, seconds * 20L);
        active.put(playerId, new Active(modifierId, expiry));
        return true;
    }

    /** Drop any active buff — on re-eat, on logout, on shutdown. */
    public void clear(Player player) {
        if (player == null) return;
        clear(player.getUniqueId());
    }

    public void clear(UUID playerId) {
        Active current = active.remove(playerId);
        if (current == null) return;
        current.expiry().cancel();
        removeModifier(playerId, current.modifierId());
    }

    /**
     * The modifier is non-persistent, so Fabled drops it when player data
     * unloads. This exists for the reload case, where the plugin goes away but
     * Fabled and its loaded data do not.
     */
    public void shutdown() {
        for (UUID playerId : new ArrayList<>(active.keySet())) {
            clear(playerId);
        }
    }

    public boolean hasBuff(Player player) {
        return player != null && active.containsKey(player.getUniqueId());
    }

    private void removeModifier(UUID playerId, UUID modifierId) {
        PlayerData data = Fabled.getData(Bukkit.getOfflinePlayer(playerId));
        if (data != null) data.removeAttributeModifier(modifierId, true);
    }
}
