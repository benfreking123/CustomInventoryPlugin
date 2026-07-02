package com.example.custominventoryplugin.groupdrop;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All click/close handling for the four group-drop windows, plus token
 * right-click redemption and editor session lifecycle.
 *
 *   Chooser  — read-only; left-click picks, right-click previews
 *   Preview  — read-only; closing returns to the chooser
 *   Editor   — option grid (0..44, placeable) + control bar (45..53)
 *   Bundle   — free container; closing returns to the editor
 */
public class GroupDropListener implements Listener {

    private final CustomInventoryPlugin plugin;
    private final GroupDropConfig config;
    private final GroupDropData data;
    private final RewardService rewards;

    /** Active editor sessions by player. */
    private final Map<UUID, GroupDropEditSession> sessions = new HashMap<>();

    public GroupDropListener(CustomInventoryPlugin plugin, GroupDropConfig config,
                             GroupDropData data, RewardService rewards) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
        this.rewards = rewards;
    }

    // ─── public open helpers (used by command + token) ────────────────────

    /** Open the selection window for a player; handles claim gating + messaging. */
    public void openChooser(Player player, GroupDrop group, boolean tokenTriggered) {
        if (group.optionCount() == 0) {
            player.sendMessage(Text.c("&cThat reward group has no options yet."));
            return;
        }

        int remaining;
        Set<Integer> chosen;
        if (group.getClaimMode() == GroupDrop.ClaimMode.ONCE) {
            int used = data.getPicksUsed(player.getUniqueId(), group.getId());
            remaining = group.effectivePicks() - used;
            chosen = data.getChosen(player.getUniqueId(), group.getId());
            if (remaining <= 0) {
                player.sendMessage(Text.c("&7You've already claimed your reward from &b" + group.getId() + "&7."));
                return;
            }
        } else {
            remaining = group.effectivePicks();
            chosen = new HashSet<>();
        }

        GroupDropChooser chooser = new GroupDropChooser(player, group, tokenTriggered, remaining, chosen);
        player.openInventory(chooser.getInventory());
    }

    /** Open the in-game editor for a group (clones it into a working draft). */
    public void openEditor(Player player, GroupDrop group) {
        GroupDropEditSession session = new GroupDropEditSession(cloneGroup(group));
        sessions.put(player.getUniqueId(), session);
        GroupDropEditor editor = new GroupDropEditor(player, session);
        player.openInventory(editor.getInventory());
        player.sendMessage(Text.c("&8[GroupDrop] &7Editing &b" + group.getId() + "&7:"));
        player.sendMessage(Text.c("&7• Drop items in the top 5 rows to add options."));
        player.sendMessage(Text.c("&7• Shift-click an option to edit its bundle (multi-item reward)."));
        player.sendMessage(Text.c("&7• Use the bottom row to set picks/claim/token, then SAVE."));
    }

    // ─── chooser ──────────────────────────────────────────────────────────

    @EventHandler
    public void onChooserClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GroupDropChooser chooser)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != chooser.getInventory()) return;

        GroupDrop group = chooser.getGroup();
        GroupDropOption opt = group.getOption(event.getRawSlot());
        if (opt == null) return;

        // Right-click → preview bundle contents.
        if (event.getClick() == ClickType.RIGHT && opt.hasPreview()) {
            chooser.setOpeningPreview(true);
            GroupDropPreview preview = new GroupDropPreview(player, opt, chooser);
            plugin.getServer().getScheduler().runTask(plugin, () -> player.openInventory(preview.getInventory()));
            return;
        }

        // Left-click (or right-click with no preview) → choose.
        if (group.isDistinct() && chooser.getChosen().contains(opt.getSlot())) {
            player.sendActionBar(Text.c("&cYou already chose that option."));
            return;
        }
        if (chooser.getPicksRemaining() <= 0) {
            player.closeInventory();
            return;
        }

        // Token gate: consume one token on the first pick of a token session.
        if (chooser.isTokenTriggered() && !chooser.isTokenConsumed()) {
            if (!rewards.consumeToken(player, group.getId())) {
                player.sendMessage(Text.c("&cYou no longer have a token for this reward."));
                player.closeInventory();
                return;
            }
            chooser.markTokenConsumed();
        }

        rewards.grant(player, opt);
        if (group.getClaimMode() == GroupDrop.ClaimMode.ONCE) {
            data.recordPick(player.getUniqueId(), group.getId(), opt.getSlot());
        }
        chooser.getChosen().add(opt.getSlot());
        chooser.decrementPicks();
        player.sendMessage(Text.c("&aReward claimed!"));

        if (chooser.getPicksRemaining() <= 0) {
            player.closeInventory();
            player.sendActionBar(Text.c("&aSelection complete."));
        } else {
            chooser.render();
            player.sendActionBar(Text.c("&e" + chooser.getPicksRemaining() + " pick(s) remaining."));
        }
    }

    // ─── preview ──────────────────────────────────────────────────────────

    @EventHandler
    public void onPreviewClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof GroupDropPreview) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPreviewClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof GroupDropPreview preview)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        GroupDropChooser parent = preview.getParent();
        parent.setOpeningPreview(false);
        plugin.getServer().getScheduler().runTask(plugin, () -> player.openInventory(parent.getInventory()));
    }

    // ─── editor ───────────────────────────────────────────────────────────

    @EventHandler
    public void onEditorClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GroupDropEditor editor)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int raw = event.getRawSlot();
        int topSize = editor.getInventory().getSize();
        ClickType ct = event.getClick();

        // Bottom inventory: allow normal picks, but block shift-dumping into the grid.
        if (raw >= topSize) {
            if (ct.isShiftClick()) event.setCancelled(true);
            return;
        }

        if (ct == ClickType.NUMBER_KEY || ct == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
        }

        // Option grid.
        if (raw <= GroupDropEditor.OPTION_AREA_MAX) {
            ItemStack current = event.getCurrentItem();
            if (ct.isShiftClick()) {
                event.setCancelled(true);
                if (current != null && !current.getType().isAir()) {
                    openBundle(editor, player, raw);
                }
                return;
            }
            // Normal placement / pickup allowed.
            return;
        }

        // Token icon slot is placeable when tokens are enabled.
        if (raw == GroupDropEditor.SLOT_TOKEN_ICON) {
            if (editor.getSession().draft.isTokenEnabled() && !ct.isShiftClick()) return;
            event.setCancelled(true);
            return;
        }

        // Remaining control slots: never move items, just act.
        event.setCancelled(true);
        GroupDrop g = editor.getSession().draft;
        switch (raw) {
            case GroupDropEditor.SLOT_PICKS -> {
                editor.syncFromInventory();
                if (ct.isLeftClick()) g.setPicks(g.getPicks() + 1);
                else if (ct.isRightClick()) g.setPicks(g.getPicks() - 1);
                editor.render();
            }
            case GroupDropEditor.SLOT_DISTINCT -> {
                editor.syncFromInventory();
                g.setDistinct(!g.isDistinct());
                editor.render();
            }
            case GroupDropEditor.SLOT_CLAIM -> {
                editor.syncFromInventory();
                g.setClaimMode(g.getClaimMode() == GroupDrop.ClaimMode.ONCE
                        ? GroupDrop.ClaimMode.REPEATABLE : GroupDrop.ClaimMode.ONCE);
                editor.render();
            }
            case GroupDropEditor.SLOT_TOKEN -> {
                editor.syncFromInventory();
                g.setTokenEnabled(!g.isTokenEnabled());
                editor.render();
            }
            case GroupDropEditor.SLOT_SAVE -> {
                editor.syncFromInventory();
                player.closeInventory(); // close handler persists
            }
            case GroupDropEditor.SLOT_CANCEL -> {
                editor.getSession().cancelled = true;
                player.closeInventory();
            }
            default -> { /* filler */ }
        }
    }

    @EventHandler
    public void onEditorClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof GroupDropEditor editor)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        GroupDropEditSession session = editor.getSession();

        // Transitioning to the bundle sub-editor — don't save or clear.
        if (session.suspend) {
            session.suspend = false;
            return;
        }
        if (session.cancelled) {
            sessions.remove(player.getUniqueId());
            player.sendMessage(Text.c("&7Edit cancelled — no changes saved."));
            return;
        }
        editor.syncFromInventory();
        config.save(session.draft);
        sessions.remove(player.getUniqueId());
        player.sendMessage(Text.c("&aSaved group drop &b" + session.draft.getId()
                + " &7(" + session.draft.optionCount() + " options)."));
    }

    private void openBundle(GroupDropEditor editor, Player player, int optionSlot) {
        GroupDropEditSession session = editor.getSession();
        editor.syncFromInventory();           // capture current grid (creates the option)
        if (session.draft.getOption(optionSlot) == null) return;
        session.bundleSlot = optionSlot;
        session.suspend = true;               // editor close becomes a no-op
        player.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            GroupDropBundleEditor bundle = new GroupDropBundleEditor(player, session, optionSlot);
            player.openInventory(bundle.getInventory());
        });
    }

    // ─── bundle sub-editor ────────────────────────────────────────────────

    @EventHandler
    public void onBundleClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof GroupDropBundleEditor bundle)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        bundle.syncToOption();
        GroupDropEditSession session = bundle.getSession();
        session.bundleSlot = -1;
        // Reopen the main editor next tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            GroupDropEditor editor = new GroupDropEditor(player, session);
            player.openInventory(editor.getInventory());
        });
    }

    // ─── tokens ───────────────────────────────────────────────────────────

    @EventHandler
    public void onTokenInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        String gid = rewards.tokenGroupId(item);
        if (gid == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        GroupDrop group = config.get(gid);
        if (group == null) {
            player.sendMessage(Text.c("&cThis token's reward group no longer exists."));
            return;
        }
        if (!group.canOpen(player)) {
            player.sendMessage(Text.c("&cYou can't use this token."));
            return;
        }
        openChooser(player, group, true);
    }

    // ─── cleanup ──────────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private GroupDrop cloneGroup(GroupDrop src) {
        GroupDrop g = new GroupDrop(src.getId());
        g.setTitle(src.getRawTitle());
        g.setPicks(src.getPicks());
        g.setDistinct(src.isDistinct());
        g.setClaimMode(src.getClaimMode());
        g.setPermission(src.getPermission());
        g.setTokenEnabled(src.isTokenEnabled());
        g.setTokenIcon(src.getTokenIcon() == null ? null : src.getTokenIcon().clone());
        for (GroupDropOption srcOpt : src.getOptions().values()) {
            GroupDropOption opt = new GroupDropOption(srcOpt.getSlot(),
                    srcOpt.getIcon() == null ? null : srcOpt.getIcon().clone());
            opt.setLabel(srcOpt.getLabel());
            for (ItemStack it : srcOpt.getGrants()) if (it != null) opt.getGrants().add(it.clone());
            opt.getCommands().addAll(srcOpt.getCommands());
            g.getOptions().put(opt.getSlot(), opt);
        }
        return g;
    }
}
