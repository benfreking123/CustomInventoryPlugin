package com.example.custominventoryplugin.listeners;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.ConfigManager;
import com.example.custominventoryplugin.data.LuckPermsBridge;
import com.example.custominventoryplugin.data.PlayerGearData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Handles skill-gem slots: when a gem is placed, grants the corresponding
 * <code>fabled.skill.&lt;name&gt;</code> permission via LuckPerms (cross-server).
 * When the gem is removed, revokes the permission AND tells Fabled to reset
 * the skill on the player so any existing hotbar bindings / spent points
 * are cleared.
 *
 * Permission node format mirrors the original plugin:
 *   "Bomb Gem"  → fabled.skill.bomb
 *   "Aoe Boost" → fabled.skill.aoe-boost
 *
 * The Fabled reset is dispatched as the console command:
 *   /class forceskill &lt;player&gt; reset &lt;skill name&gt;
 * matching the workflow MyServer's Skript used (-Gems.sk lines 52-56).
 * Without this, Fabled's `needs-permission: true` flag only hides the skill
 * from the tree — it does NOT revoke an already-bound hotbar skill.
 */
class SkillHandler {

    private final ConfigManager configManager;
    private final CustomInventoryPlugin plugin;
    private final LuckPermsBridge lp;

    public SkillHandler(ConfigManager configManager, CustomInventoryPlugin plugin) {
        this.configManager = configManager;
        this.plugin = plugin;
        this.lp = plugin.getLuckPermsBridge();
    }

    /** Called when a gem is placed in a skill-type slot. */
    public void handleSkillSlot(Player player, ItemStack gear, String slotId) {
        if (gear == null || gear.getItemMeta() == null) return;
        String displayName = gear.getItemMeta().getDisplayName();
        if (displayName == null) return;

        // Strip color codes before matching the " Gem" suffix
        String stripped = displayName.replaceAll("\u00a7.", "").trim();
        if (!stripped.endsWith(" Gem")) return;

        String skillName = stripped.substring(0, stripped.length() - 4)
                .trim().toLowerCase().replace(' ', '-');
        String permission = "fabled.skill." + skillName;
        UUID uuid = player.getUniqueId();

        // Revoke any previous permission this slot was granting (e.g. swap)
        String previous = PlayerGearData.getSlotPermission(uuid, slotId);
        if (previous != null && !previous.equals(permission)) {
            lp.revoke(uuid, previous);
            resetFabledSkill(player, previous);
        }

        // Track {slot → perm} so we can revoke on removal
        PlayerGearData.setSlotPermission(uuid, slotId, permission);
        lp.grant(uuid, permission);

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
        lp.revoke(uuid, permission);
        PlayerGearData.removeSlotPermission(uuid, slotId);
        resetFabledSkill(player, permission);
        configManager.debug("Revoked permission " + permission + " from " + player.getName() + " (slot " + slotId + ")");
    }

    /**
     * Translate a permission node back to a Fabled skill name and dispatch
     * <code>/class forceskill &lt;player&gt; reset &lt;skill&gt;</code> as console.
     *
     * Permission format: <code>fabled.skill.aoe-boost</code> →
     * skill name: <code>aoe boost</code> (Fabled is case-insensitive).
     */
    private void resetFabledSkill(Player player, String permission) {
        if (permission == null || !permission.startsWith("fabled.skill.")) return;
        String skillName = permission.substring("fabled.skill.".length()).replace('-', ' ');
        String cmd = "class forceskill " + player.getName() + " reset " + skillName;
        // Run on the main thread; LuckPerms callbacks may dispatch off-thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                configManager.debug("Dispatched: /" + cmd);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to dispatch '" + cmd + "': " + e.getMessage());
            }
        });
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
