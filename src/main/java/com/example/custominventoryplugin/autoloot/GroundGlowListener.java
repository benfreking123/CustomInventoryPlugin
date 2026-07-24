package com.example.custominventoryplugin.autoloot;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Rarity glow for every ground item, not just AutoLoot pop-outs — Q-drops,
 * chest spills and command gives read the same as mob loot, while item names
 * keep their hand-authored colors. Uses Paper's add/remove-from-world events
 * so chunk (un)loads keep the glow-team entries in step.
 */
public final class GroundGlowListener implements Listener {

    private final AutoLootConfig config;
    private final LootEffectService effects;

    public GroundGlowListener(AutoLootConfig config, LootEffectService effects) {
        this.config = config;
        this.effects = effects;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        if (!config.isAllGroundItems()) return;
        if (event.getEntity() instanceof Item item) {
            effects.decorate(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (!config.isAllGroundItems()) return;
        if (event.getEntity() instanceof Item item) {
            effects.undecorate(item);
        }
    }
}
