package com.example.custominventoryplugin.pickup;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import com.example.custominventoryplugin.autoloot.LootEffectService;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawns a short-lived owned ground item, then vacuum it through
 * {@link PickupPipeline} after {@code delayTicks}. Other players cannot
 * steal it (Paper {@link Item#setOwner(java.util.UUID)}).
 *
 * When a {@link LootEffectService} is supplied the item is decorated with its
 * rarity glow/burst for the pop-out window and cleaned up on removal.
 */
public final class DelayedGroundPickup {

    private DelayedGroundPickup() {}

    public static void schedule(CustomInventoryPlugin plugin, PickupPipeline pipeline,
                                Player owner, Location loc, ItemStack stack, int delayTicks) {
        schedule(plugin, pipeline, owner, loc, stack, delayTicks, null);
    }

    public static void schedule(CustomInventoryPlugin plugin, PickupPipeline pipeline,
                                Player owner, Location loc, ItemStack stack, int delayTicks,
                                LootEffectService effects) {
        if (owner == null || loc == null || loc.getWorld() == null) return;
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return;

        int delay = Math.max(1, delayTicks);
        Item entity = loc.getWorld().dropItem(loc, stack.clone());
        // Fan drops out in a random direction so a multi-item kill doesn't pile
        // into one unreadable stack — each item pops up and scatters onto the
        // ground where it lingers before the vacuum runs.
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double angle = rng.nextDouble(Math.PI * 2);
        double speed = 0.12 + rng.nextDouble(0.13); // 0.12..0.25 horizontal
        entity.setVelocity(new Vector(
                Math.cos(angle) * speed,
                0.18 + rng.nextDouble(0.10), // 0.18..0.28 upward pop
                Math.sin(angle) * speed));
        entity.setOwner(owner.getUniqueId());
        // Keep vanilla pickup blocked until our vacuum runs (owner-only anyway).
        entity.setPickupDelay(delay + 5);
        entity.setUnlimitedLifetime(true);
        if (effects != null) effects.decorate(entity);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!entity.isValid()) return;
            if (!owner.isOnline()) {
                if (effects != null) effects.undecorate(entity);
                entity.remove();
                return;
            }
            ItemStack remaining = entity.getItemStack();
            if (remaining == null || remaining.getType().isAir()) {
                if (effects != null) effects.undecorate(entity);
                entity.remove();
                return;
            }
            int before = remaining.getAmount();
            int absorbed = pipeline.routeSingle(owner, remaining);
            if (absorbed > 0) {
                owner.playSound(owner.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.35f, 1.2f);
            }
            if (remaining.getAmount() <= 0 || absorbed >= before) {
                if (effects != null) effects.undecorate(entity);
                // Vanilla "item flies to the player" animation, then despawn.
                PickupAnimator.collect(entity, owner, Math.max(1, absorbed));
                entity.remove();
            } else {
                entity.setItemStack(remaining);
                entity.setPickupDelay(0);
                entity.setOwner(owner.getUniqueId());
            }
        }, delay);
    }
}
