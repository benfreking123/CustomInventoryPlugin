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
            player.sendMessage(Component.text("Invite a player: /ci party invite <name>", NamedTextColor.YELLOW));
            return;
        }
        if (action.equals(PartyInventory.ACT_LEAVE)) {
            if (parties.leave(player)) {
                player.sendMessage(Component.text("You left the party.", NamedTextColor.GRAY));
            }
            player.closeInventory();
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
                if (parties.promote(player, target)) {
                    player.sendMessage(Component.text("Promoted new leader.", NamedTextColor.GOLD));
                }
            } else {
                if (parties.kick(player, target)) {
                    player.sendMessage(Component.text("Kicked from party.", NamedTextColor.GRAY));
                    Player online = Bukkit.getPlayer(target);
                    if (online != null) {
                        online.sendMessage(Component.text("You were kicked from the party.", NamedTextColor.RED));
                    }
                }
            }
            gui.render();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PartyInventory) {
            event.setCancelled(true);
        }
    }
}
