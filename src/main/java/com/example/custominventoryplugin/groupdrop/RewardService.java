package com.example.custominventoryplugin.groupdrop;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hands out a {@link GroupDropOption}'s payload: bundle items (inventory, with
 * ground overflow) and console commands ({@code %player%} substituted —
 * supports itemgen / perms / money / customitems / anything console-runnable).
 *
 * Also mints and consumes redeemable token items.
 */
public class RewardService {

    public static final String TOKEN_KEY = "groupdrop_token";

    private final CustomInventoryPlugin plugin;
    private final NamespacedKey tokenKey;

    public RewardService(CustomInventoryPlugin plugin) {
        this.plugin = plugin;
        this.tokenKey = new NamespacedKey(plugin, TOKEN_KEY);
    }

    public NamespacedKey getTokenKey() { return tokenKey; }

    /** Grant one chosen option to the player. Runs on the main thread. */
    public void grant(Player player, GroupDropOption option) {
        for (ItemStack grant : option.effectiveGrants()) {
            if (grant == null || grant.getType().isAir()) continue;
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(grant.clone());
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
        for (String cmd : option.getCommands()) {
            if (cmd == null || cmd.trim().isEmpty()) continue;
            String resolved = cmd.replace("%player%", player.getName())
                                 .replace("%uuid%", player.getUniqueId().toString());
            if (resolved.startsWith("/")) resolved = resolved.substring(1);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        }
    }

    // ─── tokens ───────────────────────────────────────────────────────────

    /** Build a redeemable token item for a group. */
    public ItemStack createToken(GroupDrop group, int amount) {
        ItemStack base = group.getTokenIcon() != null
                ? group.getTokenIcon().clone()
                : new ItemStack(org.bukkit.Material.PAPER);
        base.setAmount(Math.max(1, Math.min(64, amount)));

        ItemMeta meta = base.getItemMeta();
        if (meta != null) {
            if (!meta.hasDisplayName()) {
                meta.displayName(Text.c("&6Reward Token: &e" + group.getId()));
            }
            List<net.kyori.adventure.text.Component> lore =
                    meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Text.c("&7Right-click to choose your reward."));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, group.getId());
            base.setItemMeta(meta);
        }
        return base;
    }

    /** Resolve the group id stamped on an item, or null if it isn't a token. */
    public String tokenGroupId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING);
    }

    /** Remove exactly one matching token (by group id) from the player. */
    public boolean consumeToken(Player player, String groupId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (groupId.equalsIgnoreCase(tokenGroupId(item))) {
                int amt = item.getAmount();
                if (amt <= 1) item.setAmount(0);
                else item.setAmount(amt - 1);
                return true;
            }
        }
        return false;
    }

    public boolean hasToken(Player player, String groupId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (groupId.equalsIgnoreCase(tokenGroupId(item))) return true;
        }
        return false;
    }
}
