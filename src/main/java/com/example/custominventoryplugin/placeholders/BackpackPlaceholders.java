package com.example.custominventoryplugin.placeholders;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.config.BackpackConfig;
import com.example.custominventoryplugin.config.BackpackConfig.BackpackDef;
import com.example.custominventoryplugin.data.BackpackData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion for backpacks. Registers under the prefix
 * {@code customip}. Examples:
 *
 *   %customip_backpack_pouch_used%       — slots currently used in 'pouch'
 *   %customip_backpack_pouch_size%       — total capacity of 'pouch'
 *   %customip_backpack_pouch_free%       — pouch.size - pouch.used
 *   %customip_backpack_pouch_pct%        — round(100 * used/size)
 *   %customip_backpack_total_used%       — sum of used across accessible bags
 *
 * Falls back to "0" / "-" for unknown ids so HUDs don't break.
 */
public class BackpackPlaceholders extends PlaceholderExpansion {

    private final CustomInventoryPlugin plugin;
    private final BackpackConfig config;
    private final BackpackData data;

    public BackpackPlaceholders(CustomInventoryPlugin plugin, BackpackConfig config, BackpackData data) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
    }

    @Override public @NotNull String getIdentifier() { return "customip"; }
    @Override public @NotNull String getAuthor()     { return "TheTower"; }
    @Override public @NotNull String getVersion()    { return plugin.getDescription().getVersion(); }
    @Override public boolean persist()               { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        // Strip optional "backpack_" prefix to keep the placeholder names short
        // and consistent.
        if (params.startsWith("backpack_")) params = params.substring("backpack_".length());

        if (params.equalsIgnoreCase("total_used")) {
            int total = 0;
            for (BackpackDef def : config.all().values()) {
                if (player.isOnline() && def.canAccess(player.getPlayer())) {
                    total += data.countUsed(player.getUniqueId(), def.getId());
                }
            }
            return Integer.toString(total);
        }

        // Pattern: <id>_<field>
        int sep = params.lastIndexOf('_');
        if (sep <= 0 || sep == params.length() - 1) return "";

        String id = params.substring(0, sep).toLowerCase();
        String field = params.substring(sep + 1).toLowerCase();

        BackpackDef def = config.get(id);
        if (def == null) return "-";

        int used = data.countUsed(player.getUniqueId(), def.getId());
        int size = def.getSize();
        return switch (field) {
            case "used" -> Integer.toString(used);
            case "size" -> Integer.toString(size);
            case "free" -> Integer.toString(Math.max(0, size - used));
            case "pct"  -> Integer.toString(size == 0 ? 0 : (int) Math.round(100.0 * used / size));
            default     -> "";
        };
    }
}
