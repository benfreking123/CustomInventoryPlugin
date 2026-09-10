package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.LuckPermsBridge;
import com.example.custominventoryplugin.data.PlayerGearData;
import com.example.custominventoryplugin.skills.GemSkillLevels;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Handles skill-gem slots: when a gem is placed, grants the corresponding
 * <code>fabled.skill.&lt;name&gt;</code> permission via LuckPerms (cross-server)
 * and levels the skill 0 -> 1 for free, so the gem is usable the moment it is
 * socketed. When the gem is removed, revokes the permission and resets the
 * skill on the player so any existing hotbar bindings / spent points are
 * cleared — with the free level stripped first so it never refunds a point.
 *
 * Permission node format mirrors the original plugin:
 *   "Bomb Gem"  → fabled.skill.bomb
 *   "Aoe Boost" → fabled.skill.aoe-boost
 *
 * The Fabled side (force-up, force-down, refund, the free-level ledger) lives
 * in {@link GemSkillLevels}; this class only decides <em>when</em>. Without
 * the reset, Fabled's `needs-permission: true` flag only hides the skill from
 * the tree — it does NOT revoke an already-bound hotbar skill.
 */
class SkillHandler {

    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;
    private final LuckPermsBridge lp;
    private final GemSkillLevels levels;

    public SkillHandler(ConfigManager configManager, CustomInventoryPlugin plugin) {
        this.configManager = configManager;
        this.plugin = plugin;
        this.lp = plugin.getLuckPermsBridge();
        this.levels = plugin.getGemSkillLevels();
    }

    /**
     * The {@code fabled.skill.<name>} node a gem grants, from its display
     * name ("Bomb Gem" → fabled.skill.bomb), or null if the item is not a gem.
     */
    static String permissionFor(ItemStack gear) {
        if (gear == null || gear.getItemMeta() == null) return null;
        String displayName = gear.getItemMeta().getDisplayName();
        if (displayName == null) return null;
        // Strip color codes before matching the " Gem" suffix
        String stripped = displayName.replaceAll("\u00a7.", "").trim();
        if (!stripped.endsWith(" Gem")) return null;
        String skillName = stripped.substring(0, stripped.length() - 4)
                .trim().toLowerCase().replace(' ', '-');
        return "fabled.skill." + skillName;
    }

    /**
     * Slot id of another skill slot already holding a gem for the same skill,
     * or null. Two copies of one gem share a permission node and a Fabled
     * skill, so the second socket has nothing to grant and the first unsocket
     * revokes both — the caller must refuse the placement instead.
     */
    public String duplicateGemSlot(Player player, ItemStack gear, String targetSlotId) {
        String permission = permissionFor(gear);
        if (permission == null) return null;
        for (var e : PlayerGearData.getPlayerSlotPerms(player.getUniqueId()).entrySet()) {
            if (!e.getKey().equals(targetSlotId) && permission.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    /** Called when a gem is placed in a skill-type slot. */
    public void handleSkillSlot(Player player, ItemStack gear, String slotId) {
        String permission = permissionFor(gear);
        if (permission == null) return;
        UUID uuid = player.getUniqueId();

        // Revoke any previous permission this slot was granting (e.g. swap).
        // The free-level flag must be read before the ledger row is rewritten.
        String previous = PlayerGearData.getSlotPermission(uuid, slotId);
        if (previous != null && !previous.equals(permission)) {
            releasePermission(player, slotId, previous);
        }

        // Track {slot → perm} so we can revoke on removal
        PlayerGearData.setSlotPermission(uuid, slotId, permission);
        // The gem is the skill: level 0 -> 1 now, no point spent, and Fabled
        // puts an active on the action bar itself off the unlock event.
        // Chained on the grant: the LP node is added on a LuckPerms worker, and
        // the skill's Initialize trigger checks that node (`Permission`
        // condition). Level up before it lands and a passive's buff is never
        // applied — there is no longer a 10s loop to catch it on the next pass.
        lp.grant(uuid, permission).whenComplete((v, err) -> {
            if (err != null) {
                plugin.getLogger().warning("LP grant of " + permission + " failed for " + player.getName() + ": " + err);
            }
            levels.onSocket(player, slotId, permission);
        });

        configManager.debug("Granted permission: " + permission + " to " + player.getName() + " (slot " + slotId + ")");
    }

    /** Called when a gem is removed from a specific skill-type slot. */
    public void removeSkillSlotPermission(Player player, String slotId) {
        UUID uuid = player.getUniqueId();
        String permission = PlayerGearData.getSlotPermission(uuid, slotId);
        if (permission == null) {
            configManager.debug("No permission tracked for slot " + slotId);
            return;
        }
        releasePermission(player, slotId, permission);
        PlayerGearData.removeSlotPermission(uuid, slotId);
        configManager.debug("Revoked permission " + permission + " from " + player.getName() + " (slot " + slotId + ")");
    }

    /**
     * Take {@code permission} away from {@code slotId}: reset the Fabled skill
     * (free level dropped, paid levels refunded) and revoke the LuckPerms node.
     * Order matters — the reset reads the free-level flag from the ledger row
     * the caller is about to delete or rewrite.
     *
     * <p>If another skill slot still holds a gem for the same skill (duplicates
     * socketed before the one-gem-per-skill rule), the skill and node are left
     * alone so the surviving gem keeps working; only this slot's row goes.
     */
    private void releasePermission(Player player, String slotId, String permission) {
        UUID uuid = player.getUniqueId();
        for (var e : PlayerGearData.getPlayerSlotPerms(uuid).entrySet()) {
            if (!e.getKey().equals(slotId) && permission.equals(e.getValue())) {
                // The free-level flag follows the skill, not the slot.
                if (PlayerGearData.isSlotFreeLevel(uuid, slotId)) {
                    PlayerGearData.setSlotFreeLevel(uuid, e.getKey(), true);
                }
                configManager.debug("Kept " + permission + " for " + player.getName()
                        + ": duplicate gem still in slot " + e.getKey());
                return;
            }
        }
        levels.onUnsocket(player, slotId, permission);
        lp.revoke(uuid, permission);
    }

    /**
     * Legacy entry point. The original plugin called this on inventory close
     * to wipe all skill perms — that was a bug (closing /ci should NOT remove
     * skill access). With LuckPerms-backed perms persisting across sessions
     * and slot/perm tracking in MariaDB, this is now a no-op.
     */
    public void removeAllPermissions(Player player) {
        configManager.debug("removeAllPermissions called — no-op (perms persist via LuckPerms)");
    }
}
