package com.example.custominventoryplugin.tooltip;

import com.nexomc.nexo.api.events.resourcepack.NexoPostPackGenerateEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.serialize.minecraft.MinecraftResourcePackReader;

import java.io.File;
import java.util.List;
import java.util.logging.Level;

/**
 * Runs the tier glow into the pack Nexo is about to publish.
 *
 * The hook point is the whole trick. {@code NexoPostPackGenerateEvent} fires
 * after Nexo has built the pack and before it ships it, which is the only moment
 * both halves exist:
 *
 * <ul>
 *   <li>Before generation there is nothing to wrap — nearly every item model
 *       definition is generated, one per Nexo item and one per ModelEngine part.</li>
 *   <li>After generation is too late — Nexo uploads the pack to its CDN as part
 *       of generating it, so a player downloads that copy and never sees an edit
 *       made to pack.zip afterwards.</li>
 * </ul>
 *
 * An earlier version of this feature was a publish-script step that rewrote the
 * built zip on disk. Every check passed and it was invisible in game, because
 * the file it fixed was not the file anyone downloaded. Moving it here also
 * retired that design's other failure mode: a restart or a bare {@code nexo
 * reload} used to rebuild a pack with no glow until someone re-published.
 *
 * {@link ItemGlowWrapper} holds the actual surgery.
 */
public final class ItemGlowPackListener implements Listener {

    private final JavaPlugin plugin;
    private final TooltipConfig config;

    public ItemGlowPackListener(JavaPlugin plugin, TooltipConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPackGenerate(NexoPostPackGenerateEvent event) {
        if (!config.isGlowEnabled()) return;

        List<String> tiers = config.glowTiers();
        if (tiers.isEmpty()) {
            plugin.getLogger().warning("tier glow: no tooltip.tiers configured, slot glow skipped");
            return;
        }

        try {
            ResourcePack pack = event.getResourcePack();

            int added = 0;
            ResourcePack vanilla = readVanillaCache();
            if (vanilla != null) {
                added = ItemGlowWrapper.fillFromVanilla(pack, vanilla);
            }

            int wrapped = ItemGlowWrapper.apply(pack, tiers);
            plugin.getLogger().info("tier glow: wrapped " + wrapped + " item definition(s) for "
                    + tiers.size() + " tiers (" + added + " copied from the vanilla cache)");
        } catch (Throwable t) {
            // A broken glow must never cost the network its resource pack.
            plugin.getLogger().log(Level.SEVERE,
                    "tier glow: wrap failed, pack published without it", t);
        }
    }

    /**
     * Nexo downloads the client pack for the running version and caches it at
     * {@code plugins/Nexo/pack/.assetCache/<version>/<version>.zip}. Read-only —
     * we never write there. Newest wins, so a version bump is picked up without
     * anything to configure.
     *
     * A miss is survivable: the glow still applies to everything the pack does
     * define, it just won't reach plain vanilla materials.
     */
    private ResourcePack readVanillaCache() {
        Plugin nexo = plugin.getServer().getPluginManager().getPlugin("Nexo");
        if (nexo == null) return null;

        File cache = new File(nexo.getDataFolder(), "pack/.assetCache");
        File[] versions = cache.listFiles(File::isDirectory);
        if (versions == null || versions.length == 0) {
            plugin.getLogger().warning("tier glow: no vanilla asset cache under " + cache
                    + " — plain vanilla materials will not glow");
            return null;
        }

        File newest = null;
        for (File dir : versions) {
            File zip = new File(dir, dir.getName() + ".zip");
            if (zip.isFile() && (newest == null || zip.lastModified() > newest.lastModified())) {
                newest = zip;
            }
        }
        if (newest == null) {
            plugin.getLogger().warning("tier glow: vanilla asset cache has no version zip "
                    + "— plain vanilla materials will not glow");
            return null;
        }

        try {
            return MinecraftResourcePackReader.minecraft().readFromZipFile(newest);
        } catch (Throwable t) {
            plugin.getLogger().warning("tier glow: could not read vanilla cache " + newest.getName()
                    + " (" + t + ") — plain vanilla materials will not glow");
            return null;
        }
    }
}
