package com.example.custominventoryplugin.party;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

public final class PartyGuiListener implements Listener {

    private final CustomInventoryPlugin plugin;
    private final PartyService parties;

    public PartyGuiListener(CustomInventoryPlugin plugin, PartyService parties) {
        this.plugin = plugin;
        this.parties = parties;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PartyInventory gui)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack current = event.getCurrentItem();
        String action = gui.actionOf(current);
        if (action == null) return;

        if (action.equals(PartyInventory.ACT_CLOSE)) {
            player.closeInventory();
            return;
        }
        if (action.equals(PartyInventory.ACT_INVITE)) {
            player.closeInventory();
            player.sendMessage(Component.text("Invite a player: /party invite <name>", NamedTextColor.YELLOW));
            return;
        }
        if (action.equals(PartyInventory.ACT_ACCEPT)) {
            player.closeInventory();
            parties.acceptInvite(player, result -> {
                switch (result) {
                    case JOINED -> player.sendMessage(
                            Component.text("You joined the party.", NamedTextColor.GREEN));
                    case NO_INVITE -> player.sendMessage(
                            Component.text("That invite is no longer pending.", NamedTextColor.RED));
                    case ALREADY_IN_PARTY -> player.sendMessage(
                            Component.text("You are already in a party.", NamedTextColor.RED));
                    case PARTY_FULL -> player.sendMessage(
                            Component.text("That party is full.", NamedTextColor.RED));
                    case FAILED -> player.sendMessage(
                            Component.text("Could not join the party.", NamedTextColor.RED));
                }
            });
            return;
        }
        if (action.equals(PartyInventory.ACT_DENY)) {
            player.closeInventory();
            parties.denyInvite(player, ok -> player.sendMessage(ok
                    ? Component.text("Invite declined.", NamedTextColor.GRAY)
                    : Component.text("That invite is no longer pending.", NamedTextColor.RED)));
            return;
        }
        if (action.equals(PartyInventory.ACT_BOARD)) {
            PartyScoreboardService board = plugin.getPartyScoreboardService();
            if (board == null) {
                player.sendMessage(Component.text("The party sidebar is unavailable.", NamedTextColor.RED));
                return;
            }
            boolean on = board.toggle(player);
            player.sendMessage(Component.text("Party scoreboard " + (on ? "shown." : "hidden."),
                    on ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            gui.render();
            return;
        }
        if (action.equals(PartyInventory.ACT_LEAVE)) {
            player.closeInventory();
            parties.leave(player, ok -> {
                if (ok) {
                    player.sendMessage(Component.text("You left the party.", NamedTextColor.GRAY));
                }
            });
            return;
        }
        if (action.equals(PartyInventory.ACT_READY)) {
            if (parties instanceof PartiesPartyService pps) {
                // If a check is running, mark self ready; otherwise start one.
                if (!pps.markReady(player)) {
                    parties.startReadyCheck(player);
                }
            } else {
                parties.startReadyCheck(player);
            }
            gui.render();
            return;
        }
        if (action.equals(PartyInventory.ACT_LOOT)) {
            if (!parties.canChangeLootMode(player.getUniqueId())) {
                player.sendMessage(Component.text("Only the leader (or a moderator) can change loot mode.", NamedTextColor.RED));
                return;
            }
            Optional<UUID> pid = parties.getPartyId(player.getUniqueId());
            if (pid.isEmpty()) return;
            LootMode next = parties.getLootMode(pid.get()).next();
            parties.setLootMode(pid.get(), next);
            player.sendMessage(Component.text("Loot mode: " + next.displayName(), NamedTextColor.GREEN));
            gui.render();
            return;
        }
        if (action.startsWith(PartyInventory.ACT_KICK)) {
            UUID target = UUID.fromString(action.substring(PartyInventory.ACT_KICK.length()));
            if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
                parties.promote(player, target, ok -> {
                    if (ok) player.sendMessage(Component.text("Promoted new leader.", NamedTextColor.GOLD));
                    rerender(gui);
                });
            } else {
                parties.kick(player, target, ok -> {
                    if (ok) {
                        player.sendMessage(Component.text("Kicked from party.", NamedTextColor.GRAY));
                        Player online = Bukkit.getPlayer(target);
                        if (online != null) {
                            online.sendMessage(Component.text("You were kicked from the party.", NamedTextColor.RED));
                        }
                    }
                    rerender(gui);
                });
            }
        }
    }

    /** Roster mutations answer off the main thread; inventories are not safe there. */
    private void rerender(PartyInventory gui) {
        Bukkit.getScheduler().runTask(plugin, gui::render);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PartyInventory) {
            event.setCancelled(true);
        }
    }
}
