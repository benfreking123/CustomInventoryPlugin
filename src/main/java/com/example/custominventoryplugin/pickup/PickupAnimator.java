package com.example.custominventoryplugin.pickup;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

/**
 * Plays the vanilla "item flies into the collector" pickup animation via the
 * ProtocolLib {@code COLLECT} (take-item-entity) packet. The client lerps the
 * item entity from its current position to the collector, so we send this while
 * the loot still sits on the ground and only then remove it server-side.
 *
 * <p>ProtocolLib is an optional dependency: all references to it live in this
 * class so CIP still loads if ProtocolLib is missing (animation simply no-ops).
 */
public final class PickupAnimator {

    private static Boolean available;
    private static ProtocolManager manager;

    private PickupAnimator() {}

    private static boolean ready() {
        if (available == null) {
            boolean ok = false;
            try {
                if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
                    manager = ProtocolLibrary.getProtocolManager();
                    ok = manager != null;
                }
            } catch (Throwable t) {
                ok = false;
            }
            available = ok;
        }
        return available;
    }

    /**
     * Animate {@code entity} being sucked into {@code collector} for everyone
     * who can see it. Call immediately before {@link Item#remove()}.
     */
    public static void collect(Item entity, Player collector, int count) {
        if (entity == null || collector == null) return;
        if (!ready()) return;
        try {
            PacketContainer packet = manager.createPacket(PacketType.Play.Server.COLLECT);
            packet.getIntegers().write(0, entity.getEntityId());
            packet.getIntegers().write(1, collector.getEntityId());
            packet.getIntegers().write(2, Math.max(1, count));

            Set<Player> viewers = new HashSet<>(entity.getTrackedBy());
            viewers.add(collector);
            for (Player viewer : viewers) {
                if (viewer.isOnline()) manager.sendServerPacket(viewer, packet);
            }
        } catch (Throwable ignored) {
            // never let a cosmetic packet break the pickup
        }
    }
}
