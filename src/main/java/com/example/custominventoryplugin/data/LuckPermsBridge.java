package com.example.custominventoryplugin.data;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;

import java.util.UUID;

/**
 * Thin wrapper around LuckPerms' user-permission API.
 * Grants/revokes are persisted via LuckPerms' MariaDB backend, so they're
 * automatically cross-server within TheTower.
 */
public class LuckPermsBridge {

    private final CustomInventoryPlugin plugin;
    private LuckPerms api;

    public LuckPermsBridge(CustomInventoryPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        if (api != null) return true;
        try {
            api = LuckPermsProvider.get();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Grant a permission node to a player. Idempotent — safe to call repeatedly.
     */
    public void grant(UUID uuid, String permission) {
        if (!isAvailable()) {
            plugin.getLogger().warning("LuckPerms not available; cannot grant " + permission);
            return;
        }
        api.getUserManager().modifyUser(uuid, (User user) -> {
            DataMutateResult result = user.data().add(Node.builder(permission).value(true).build());
            if (result.wasSuccessful()) {
                plugin.getConfigManager().debug("LP grant: " + permission + " → " + uuid);
            }
        });
    }

    /**
     * Revoke a permission node from a player. Idempotent — safe to call repeatedly.
     */
    public void revoke(UUID uuid, String permission) {
        if (!isAvailable()) {
            plugin.getLogger().warning("LuckPerms not available; cannot revoke " + permission);
            return;
        }
        api.getUserManager().modifyUser(uuid, (User user) -> {
            DataMutateResult result = user.data().remove(Node.builder(permission).value(true).build());
            if (result.wasSuccessful()) {
                plugin.getConfigManager().debug("LP revoke: " + permission + " → " + uuid);
            }
        });
    }
}
