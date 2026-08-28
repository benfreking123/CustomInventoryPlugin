package com.example.custominventoryplugin.party;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.groupdrop.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Party roster GUI — invite, kick, promote, leave, loot mode, ready-check.
 */
public final class PartyInventory implements InventoryHolder {

    public static final String ACTION_KEY = "cip_party";
    public static final String ACT_CLOSE = "close";
    public static final String ACT_LEAVE = "leave";
    public static final String ACT_LOOT = "loot";
    public static final String ACT_READY = "ready";
    public static final String ACT_INVITE = "invite";
    public static final String ACT_KICK = "kick:";
    public static final String ACT_PROMOTE = "promote:";

    private static final int[] MEMBER_SLOTS = {10, 11, 12, 13};
    private static final int SLOT_LOOT = 15;
    private static final int SLOT_READY = 16;
    private static final int SLOT_INVITE = 19;
    private static final int SLOT_LEAVE = 22;
    private static final int SLOT_CLOSE = 25;

    private final CustomInventoryPlugin plugin;
    private final PartyService parties;
    private final Player viewer;
    private final Inventory inventory;
    private final NamespacedKey actionKey;

    public PartyInventory(CustomInventoryPlugin plugin, PartyService parties, Player viewer) {
        this.plugin = plugin;
        this.parties = parties;
        this.viewer = viewer;
        this.actionKey = new NamespacedKey(plugin, ACTION_KEY);
        this.inventory = Bukkit.createInventory(this, 27, Text.nexoBackground('\uA41C', 16));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void render() {
        inventory.clear();
        Optional<UUID> partyId = parties.getPartyId(viewer.getUniqueId());

        if (partyId.isEmpty()) {
            inventory.setItem(13, labeled(Material.OAK_SIGN, "No Party",
                    List.of("You are not in a party.",
                            "Invite someone nearby with",
                            "/ci party invite <player>",
                            "or click Invite below."),
                    ACT_INVITE));
            inventory.setItem(SLOT_INVITE, labeled(Material.WRITABLE_BOOK, "Invite",
                    List.of("Type: /ci party invite <player>"), ACT_INVITE));
            inventory.setItem(SLOT_CLOSE, labeled(Material.BARRIER, "Close", List.of(), ACT_CLOSE));
            return;
        }

        UUID pid = partyId.get();
        List<UUID> members = parties.getMembers(pid);
        UUID leader = parties.getLeader(pid).orElse(null);
        LootMode mode = parties.getLootMode(pid);
        boolean canLoot = parties.canChangeLootMode(viewer.getUniqueId());

        for (int i = 0; i < MEMBER_SLOTS.length; i++) {
            if (i >= members.size()) continue;
            UUID mid = members.get(i);
            OfflinePlayer op = Bukkit.getOfflinePlayer(mid);
            boolean isLeader = leader != null && leader.equals(mid);
            boolean online = op.isOnline();
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setOwningPlayer(op);
            String name = op.getName() != null ? op.getName() : mid.toString().substring(0, 8);
            meta.displayName(Component.text((isLeader ? "★ " : "") + name,
                    isLeader ? NamedTextColor.GOLD : NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(online ? "Online" : "Offline",
                    online ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            if (isLeader) {
                lore.add(Component.text("Leader", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            }
            if (parties.isLeader(viewer.getUniqueId()) && !mid.equals(viewer.getUniqueId())) {
                lore.add(Component.text("Click: kick", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Shift-click: promote", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, ACT_KICK + mid);
            }
            meta.lore(lore);
            skull.setItemMeta(meta);
            inventory.setItem(MEMBER_SLOTS[i], skull);
        }

        List<String> lootLore = new ArrayList<>();
        lootLore.add("Current: " + mode.displayName());
        if (canLoot) lootLore.add("Click to cycle modes");
        else lootLore.add("Only the leader can change this");
        inventory.setItem(SLOT_LOOT, labeled(Material.CHEST, "Loot Mode", lootLore, ACT_LOOT));

        inventory.setItem(SLOT_READY, labeled(Material.BELL, "Ready Check",
                List.of("Ping the party — 45s to confirm"), ACT_READY));
        inventory.setItem(SLOT_INVITE, labeled(Material.WRITABLE_BOOK, "Invite",
                List.of("Type: /ci party invite <player>"), ACT_INVITE));
        inventory.setItem(SLOT_LEAVE, labeled(Material.IRON_DOOR, "Leave Party",
                List.of("Leave your current party"), ACT_LEAVE));
        inventory.setItem(SLOT_CLOSE, labeled(Material.BARRIER, "Close", List.of(), ACT_CLOSE));
    }

    public String actionOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    private ItemStack labeled(Material mat, String name, List<String> loreLines, String action) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        if (loreLines != null && !loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        stack.setItemMeta(meta);
        return stack;
    }
}
