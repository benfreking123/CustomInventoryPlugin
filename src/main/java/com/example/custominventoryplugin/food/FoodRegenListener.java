package com.example.custominventoryplugin.food;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Turns eating into a regeneration buff.
 *
 * Listens at MONITOR and ignores cancelled events, so a consume that another
 * plugin vetoes does not hand out a buff for food the player still has.
 */
public final class FoodRegenListener implements Listener {

    private final FoodRegenConfig config;
    private final FoodRegenService service;

    public FoodRegenListener(FoodRegenConfig config, FoodRegenService service) {
        this.config = config;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (event.getItem() == null) return;
        FoodRegenConfig.Food food = config.forMaterial(event.getItem().getType());
        if (food == null) return;

        Player player = event.getPlayer();
        if (!service.apply(player, food.regen(), food.seconds())) return;

        // Regen is invisible by design (Divinity's regen indicators are off, or
        // every player would trail green numbers), so without this there is no
        // feedback that eating did anything at all.
        player.sendActionBar(
                Component.text("+" + food.regen() + " HP/s", NamedTextColor.GREEN)
                        .append(Component.text(" for " + food.seconds() + "s",
                                NamedTextColor.GRAY)));
    }

    /**
     * Without this the expiry task fires against unloaded player data and the
     * entry leaks until restart. Fabled drops the non-persistent modifier
     * itself, so this is about our own bookkeeping.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.clear(event.getPlayer().getUniqueId());
    }
}
