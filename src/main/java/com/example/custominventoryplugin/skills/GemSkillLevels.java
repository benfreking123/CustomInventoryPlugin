package com.example.custominventoryplugin.skills;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.data.PlayerGearData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import studio.magemonkey.fabled.Fabled;
import studio.magemonkey.fabled.api.player.PlayerData;
import studio.magemonkey.fabled.api.player.PlayerSkill;
import studio.magemonkey.fabled.api.skills.Skill;
import studio.magemonkey.fabled.cast.PlayerTextCastingData;

import java.util.Map;
import java.util.UUID;

/**
 * A socketed gem <em>is</em> the skill.
 *
 * <p>Fabled's {@code needs-permission: true} only makes a skill visible once
 * {@code fabled.skill.<name>} is held; the skill still sits at level 0 until
 * somebody left-clicks it in {@code /class skill} and spends a point. New
 * players never find that click (Lawless: a gem socketed in the tutorial,
 * two unspent points, "its a gem?" seventeen minutes later). This service
 * closes the gap: the moment a gem lands in a slot the skill is force-levelled
 * 0 -> 1 through {@link PlayerData#forceUpSkill}, which costs nothing and fires
 * {@code PlayerSkillUnlockEvent}, so Fabled's own {@code CastTextListener}
 * drops an active skill onto the first free action-bar key as well.
 *
 * <p><b>The refund trap.</b> Fabled's {@link PlayerData#refundSkill} pays back
 * {@code getInvestedCost()}, which is computed from the skill's <em>level</em>,
 * not from what was actually paid. A free level 1 therefore refunds one point
 * on any reset — {@code /class refund}, or the reset CIP already runs when a
 * gem is pulled — and socket/unsocket becomes a skill-point mint. Every path
 * that can refund a gem skill goes through here so the free level is dropped
 * with {@link PlayerData#forceDownSkill} (no refund) <em>before</em> the paid
 * levels are refunded. Which slots hold a free level is persisted as
 * {@code cip_player_slot_perms.free_level} so it survives restarts and server
 * hops; rows that predate the column default to "paid" and refund as before.
 */
public final class GemSkillLevels {

    private static final String PERM_PREFIX = "fabled.skill.";

    private final CustomInventoryPlugin plugin;

    public GemSkillLevels(CustomInventoryPlugin plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Socket / unsocket
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A gem was just placed in {@code slotId} and its permission granted.
     * Levels the skill 0 -> 1 for free if it is not already levelled and
     * remembers that the level was free. Safe to call on any thread; the
     * Fabled work runs on the next main-thread tick.
     */
    public void onSocket(Player player, String slotId, String permission) {
        runSync(() -> {
            Resolved r = resolve(player, permission);
            if (r == null) return;
            int level = r.playerSkill.getLevel();
            if (level > 0) {
                // A passive left at level 1 (old sessions; refundSkill ignores
                // cost-0 skills) never fires Initialize again on its own, and
                // its buff only applies from Initialize now that the 10s
                // re-apply loop is gone. Bounce it so the trigger runs with
                // the permission present. Level is preserved; nothing is
                // charged or refunded. Actives are left alone — re-unlocking
                // would re-run Fabled's action-bar assignment.
                if (!r.skill.canCast()) {
                    r.data.forceDownSkill(r.playerSkill, level);
                    r.data.forceUpSkill(r.playerSkill, level);
                    debug(player, r, "already level " + level + " — passive re-initialised");
                } else {
                    debug(player, r, "already level " + level + " — nothing to grant");
                }
                return;
            }
            r.data.forceUpSkill(r.playerSkill);
            PlayerGearData.setSlotFreeLevel(player.getUniqueId(), slotId, true);
            debug(player, r, "free level 1 granted (slot " + slotId + ")");
            tellUnlocked(player, r);
        });
    }

    /**
     * The gem in {@code slotId} is about to lose {@code permission}. Drops the
     * free level without a refund, then refunds whatever levels were paid for
     * and returns the skill to level 0 — the same end state the old
     * {@code /class forceskill reset} produced, minus the minted point.
     * Runs synchronously when already on the main thread so the slot ledger
     * can be cleared by the caller straight after.
     */
    public void onUnsocket(Player player, String slotId, String permission) {
        boolean free = PlayerGearData.isSlotFreeLevel(player.getUniqueId(), slotId);
        runSync(() -> {
            Resolved r = resolve(player, permission);
            if (r == null) return;
            resetSkill(r, free);
            debug(player, r, "reset on unsocket (free level was " + free + ")");
        });
    }

    /**
     * Login audit: any tracked gem whose skill is still level 0 (socketed
     * before this shipped, or reset by an admin) gets its free level now.
     * Fabled loads player data from SQL asynchronously, so the caller should
     * delay this a couple of seconds after the join event.
     */
    public void reconcile(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        Map<String, String> slots = PlayerGearData.getPlayerSlotPerms(uuid);
        if (slots.isEmpty()) return;
        runSync(() -> {
            if (!player.isOnline() || !Fabled.hasPlayerData(player)) return;
            for (Map.Entry<String, String> e : slots.entrySet()) {
                Resolved r = resolve(player, e.getValue());
                if (r == null || r.playerSkill.getLevel() > 0) continue;
                r.data.forceUpSkill(r.playerSkill);
                PlayerGearData.setSlotFreeLevel(uuid, e.getKey(), true);
                debug(player, r, "reconciled: free level 1 granted (slot " + e.getKey() + ")");
                tellUnlocked(player, r);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Refund
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Player-facing replacement for {@code /class refund}. Refunds every paid
     * skill level, but free gem levels are stripped first so they pay out
     * nothing and are put back afterwards so the socketed gem still works.
     *
     * @return points refunded, or -1 if Fabled has no data for the player.
     */
    public int refundAll(Player player) {
        if (player == null || !Fabled.hasPlayerData(player)) return -1;
        PlayerData data = Fabled.getData(player);
        UUID uuid = player.getUniqueId();

        // Strip free levels first so they refund nothing.
        Map<String, String> slots = PlayerGearData.getPlayerSlotPerms(uuid);
        for (Map.Entry<String, String> e : slots.entrySet()) {
            if (!PlayerGearData.isSlotFreeLevel(uuid, e.getKey())) continue;
            Resolved r = resolve(player, e.getValue());
            if (r != null && r.playerSkill.getLevel() >= 1) r.data.forceDownSkill(r.playerSkill);
        }

        int before = data.getMainClass() != null ? data.getMainClass().getPoints() : 0;
        data.refundSkills();
        int after = data.getMainClass() != null ? data.getMainClass().getPoints() : 0;

        // Every socketed gem must still work afterwards: whatever is now at
        // level 0 (the stripped free levels, and paid level-1s that were just
        // refunded) gets a free level 1 and is flagged as such.
        int restored = 0;
        for (Map.Entry<String, String> e : slots.entrySet()) {
            Resolved r = resolve(player, e.getValue());
            if (r == null || r.playerSkill.getLevel() > 0) continue;
            r.data.forceUpSkill(r.playerSkill);
            PlayerGearData.setSlotFreeLevel(uuid, e.getKey(), true);
            restored++;
        }
        plugin.getConfigManager().debug("refundAll for " + player.getName() + ": +" + (after - before)
                + " points, " + restored + " gem level(s) re-granted free");
        return after - before;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Drop the free level (if any) without refund, then refund the rest.
     *
     * <p>{@link PlayerData#refundSkill} is a silent no-op when the skill's
     * cost is 0 — every passive gem — so a passive would stay at level 1 with
     * its buff still applied after the gem came out. Anything still above 0
     * after the refund is forced down instead; there are no points in it.
     */
    private void resetSkill(Resolved r, boolean freeLevel) {
        if (freeLevel && r.playerSkill.getLevel() >= 1) {
            r.data.forceDownSkill(r.playerSkill);
        }
        if (r.playerSkill.getLevel() > 0) {
            r.data.refundSkill(r.playerSkill);
        }
        if (r.playerSkill.getLevel() > 0) {
            r.data.forceDownSkill(r.playerSkill, r.playerSkill.getLevel());
        }
    }

    private void tellUnlocked(Player player, Resolved r) {
        String name = r.skill.getName();
        if (!r.skill.canCast()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&8[&dSkill&8] &f" + name + " &7is active while the gem is socketed."));
            return;
        }
        int key = castKey(r.data, name);
        String how = key > 0
                ? "press &fF&7, then &f" + key + "&7."
                : "open &f/class skill&7 and press a number key on it, then &fF&7 and that number.";
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&dSkill&8] &f" + name + " &7is ready: hold your weapon in slot 1, " + how));
    }

    /** 1-based hotbar key the action bar has this skill on, or 0 if unassigned. */
    private static int castKey(PlayerData data, String skillName) {
        PlayerTextCastingData bar = data.getTextCastingData();
        if (bar == null) return 0;
        for (int i = 0; i < 9; i++) {
            String s;
            try { s = bar.getSkill(i); } catch (RuntimeException ex) { break; }
            if (s != null && s.equalsIgnoreCase(skillName)) return i + 1;
        }
        return 0;
    }

    /** Resolve a {@code fabled.skill.<dashed-name>} node to live Fabled objects, or null. */
    private Resolved resolve(Player player, String permission) {
        if (player == null || permission == null || !permission.startsWith(PERM_PREFIX)) return null;
        if (!Fabled.hasPlayerData(player)) return null;
        String dashed = permission.substring(PERM_PREFIX.length());
        Skill skill = Fabled.getSkill(dashed.replace('-', ' '));
        if (skill == null) {
            // A skill whose real name carries a hyphen would round-trip badly
            // through the permission node; fall back to a scan.
            for (Skill s : Fabled.getSkills().values()) {
                if (s.getName().toLowerCase().replace(' ', '-').equals(dashed)) { skill = s; break; }
            }
        }
        if (skill == null) {
            plugin.getConfigManager().debug("GemSkillLevels: no Fabled skill for " + permission);
            return null;
        }
        PlayerData data = Fabled.getData(player);
        PlayerSkill ps = data.getSkill(skill.getName());
        if (ps == null) {
            plugin.getConfigManager().debug("GemSkillLevels: " + player.getName()
                    + " has no class entry for " + skill.getName() + " (not on Climber?)");
            return null;
        }
        return new Resolved(data, skill, ps);
    }

    private void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void debug(Player player, Resolved r, String what) {
        plugin.getConfigManager().debug("GemSkillLevels: " + player.getName() + " / " + r.skill.getName() + ": " + what);
    }

    private record Resolved(PlayerData data, Skill skill, PlayerSkill playerSkill) {}
}
