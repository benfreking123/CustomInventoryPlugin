package com.example.custominventoryplugin.data;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.listeners.AttributeHandler;
import com.example.custominventoryplugin.settings.BackpackSettingsCache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;

/**
 * Admin wipe of all CustomInventoryPlugin MariaDB state for one player:
 * gear slots, backpack contents, and pickup settings. If the player is
 * online on this backend, also clears live inventory mirrors and revokes
 * any skill-gem LuckPerms nodes tracked for their gear slots.
 */
public final class PlayerResetService {

    private PlayerResetService() {}

    public static void reset(CustomInventoryPlugin plugin, UUID uuid, String playerName) {
        if (uuid == null) return;

        // Ensure slot→perm map is loaded from DB (offline / never joined this session).
        PlayerGearData.loadPlayerData(uuid);

        // Revoke skill perms + Fabled bindings before wiping slot→perm rows.
        Map<String, String> slotPerms = PlayerGearData.getPlayerSlotPerms(uuid);
        if (!slotPerms.isEmpty()) {
            LuckPermsBridge lp = plugin.getLuckPermsBridge();
            Player online = Bukkit.getPlayer(uuid);
            for (String perm : slotPerms.values()) {
                if (perm == null || perm.isBlank()) continue;
                lp.revoke(uuid, perm);
                if (online != null) {
                    resetFabledSkill(online, perm);
                } else if (playerName != null && !playerName.isBlank()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            "class forceskill " + playerName + " reset " + skillNameFromPerm(perm));
                }
            }
        }

        // Give the Fabled points back BEFORE the ledger rows go. clearPlayerData
        // deletes cip_player_slot_attrs, and those rows are the only record that
        // the grants happened — dropping them first strands the points in
        // Fabled's invested pool forever, so every wipe made the player
        // permanently stronger. Only possible while they are online, since the
        // subtraction has to go through live PlayerData.
        Player toRevoke = Bukkit.getPlayer(uuid);
        if (toRevoke != null && toRevoke.isOnline()) {
            new AttributeHandler(plugin.getConfigManager()).removeAllSlotAttributes(toRevoke);
        } else if (!PlayerGearData.getLedgerSlotIds(uuid).isEmpty()) {
            Bukkit.getLogger().warning("[CustomInventoryPlugin] Resetting OFFLINE player " + playerName
                    + " with " + PlayerGearData.getLedgerSlotIds(uuid).size()
                    + " attribute ledger row(s). Their Fabled points cannot be handed back from here"
                    + " and will stay in the invested pool — run /ci attrcheck on them once they log in.");
        }

        PlayerGearData.clearPlayerData(uuid);
        plugin.getBackpackData().clearAllForPlayer(uuid);
        plugin.getSettingsData().clearAllForPlayer(uuid);
        // Compendium progression: bestiary kills + generic counters
        // (crystals, dungeon runs, checkpoint discoveries).
        plugin.getBestiaryData().clearAllForPlayer(uuid);
        plugin.getCounterData().clearAllForPlayer(uuid);
        BackpackSettingsCache cache = plugin.getSettingsCache();
        if (cache != null) cache.unload(uuid);

        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) {
            // Clear vanilla inv on this backend (CIP gear lives in SQL; hotbar is per-server).
            online.getInventory().clear();
            online.getInventory().setArmorContents(null);
            online.getInventory().setItemInOffHand(new ItemStack(org.bukkit.Material.AIR));
            online.getEnderChest().clear();
            online.updateInventory();
            online.closeInventory();
        }
    }

    private static void resetFabledSkill(Player player, String permission) {
        String skill = skillNameFromPerm(permission);
        if (skill == null) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "class forceskill " + player.getName() + " reset " + skill);
    }

    private static String skillNameFromPerm(String permission) {
        if (permission == null || !permission.startsWith("fabled.skill.")) return null;
        // "fabled.skill.ice-shard" → "ice shard" (Fabled forceskill wants spaced name)
        String dashed = permission.substring("fabled.skill.".length());
        return dashed.replace('-', ' ');
    }
}
