package com.example.custominventoryplugin.tooltip;

import com.google.gson.JsonPrimitive;
import net.kyori.adventure.key.Key;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.item.CompositeItemModel;
import team.unnamed.creative.item.ConditionItemModel;
import team.unnamed.creative.item.EmptyItemModel;
import team.unnamed.creative.item.Item;
import team.unnamed.creative.item.ItemModel;
import team.unnamed.creative.item.SelectItemModel;
import team.unnamed.creative.item.property.ItemBooleanProperty;
import team.unnamed.creative.item.property.ItemStringProperty;
import team.unnamed.creative.overlay.Overlay;
import team.unnamed.creative.overlay.ResourceContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rewrites a built pack so a tier-coloured glow draws behind an item in its slot.
 *
 * Pure pack surgery, deliberately free of Bukkit so it can be exercised against a
 * real pack.zip offline — see {@link ItemGlowPackListener} for where it runs and
 * why it has to run there.
 *
 * Every definition's model is wrapped, never replaced:
 *
 * <pre>
 *   select display_context
 *     case "gui" -&gt; composite [ &lt;glow&gt;, &lt;original&gt; ]
 *     fallback   -&gt; &lt;original&gt;
 * </pre>
 *
 * so the hand, the world and the ground view keep the original exactly. The glow
 * layer is a {@code carried} condition (empty while the stack rides the cursor,
 * or the background drags around with it) wrapping a {@code selected} condition
 * (the still frame in the highlighted hotbar slot, so it doesn't animate). Both
 * branches select on {@code custom_model_data} strings[0], where
 * {@link TooltipStyleService} stamps the tier.
 *
 * The empty fallback on that select is load-bearing: an untiered item renders
 * exactly as before, which is what makes wrapping every definition safe and
 * means there is no per-item list to keep.
 *
 * Output is deterministic — sorted everywhere, no map iteration order — because
 * every backend must build a byte-identical pack or NexoProxy's hash dedup
 * breaks and players lose custom textures when they change server.
 *
 * Only the definitions are touched. The glow textures, their .mcmeta animation
 * and their atlas entries are all produced by infra/gen-item-glow.py and are
 * already in the pack by the time this runs; adding them here as well just
 * grows the atlas by a duplicate set every generation.
 */
final class ItemGlowWrapper {

    static final String GLOW_NAMESPACE = "tower";
    static final String GLOW_PATH = "item/glow/";

    private ItemGlowWrapper() {
    }

    /**
     * Copies in any vanilla definition the pack does not already override.
     *
     * Nexo only emits a definition for a material it actually needs — thirteen of
     * them — but Divinity gear is mostly plain vanilla materials, so an iron
     * sword or a bow has nothing in the pack to wrap and would never glow. The
     * copies come from Nexo's cached client pack rather than being written by
     * hand because a bow's definition range-dispatches on pull and a shield's is
     * a condition; inventing {@code item/<id>} for those would break them.
     *
     * Returns the number added.
     */
    static int fillFromVanilla(ResourcePack pack, ResourcePack vanilla) {
        List<Item> items = new ArrayList<>(vanilla.items());
        items.sort(Comparator.comparing(item -> item.key().asString()));

        int added = 0;
        for (Item item : items) {
            if (pack.item(item.key()) != null) continue;
            pack.item(item);
            added++;
        }
        return added;
    }

    /** Wraps the base pack and every overlay. Returns definitions changed. */
    static int apply(ResourcePack pack, List<String> tiers) {
        int wrapped = wrapContainer(pack, tiers);

        List<Overlay> overlays = new ArrayList<>(pack.overlays());
        overlays.sort(Comparator.comparing(Overlay::directory));
        for (Overlay overlay : overlays) {
            wrapped += wrapContainer(overlay, tiers);
        }
        return wrapped;
    }

    static int wrapContainer(ResourceContainer container, List<String> tiers) {
        List<Item> items = new ArrayList<>(container.items());
        items.sort(Comparator.comparing(item -> item.key().asString()));

        int count = 0;
        for (Item item : items) {
            ItemModel original = item.model();
            if (original == null || isWrapped(original)) continue;
            container.item(Item.item(item.key(), glowWrap(original, tiers),
                    item.handAnimationOnSwap(), item.oversizedInGui(), item.swapAnimationScale()));
            count++;
        }
        return count;
    }

    private static ItemModel glowWrap(ItemModel original, List<String> tiers) {
        ItemModel glow = ItemModel.conditional(ItemBooleanProperty.carried(),
                ItemModel.empty(),
                ItemModel.conditional(ItemBooleanProperty.selected(),
                        tierSelect(tiers, true),
                        tierSelect(tiers, false)));

        return ItemModel.select()
                .property(ItemStringProperty.displayContext())
                .addCase(SelectItemModel.Case._case(
                        ItemModel.composite(List.of(glow, original)),
                        new JsonPrimitive("gui")))
                .fallback(original)
                .build();
    }

    private static ItemModel tierSelect(List<String> tiers, boolean still) {
        SelectItemModel.Builder builder = ItemModel.select()
                .property(ItemStringProperty.customModelData(0));
        for (String tier : tiers) {
            Key model = Key.key(GLOW_NAMESPACE, GLOW_PATH + tier + (still ? "_static" : ""));
            builder.addCase(SelectItemModel.Case._case(ItemModel.reference(model),
                    new JsonPrimitive(tier)));
        }
        return builder.fallback(ItemModel.empty()).build();
    }

    /**
     * Vanilla's own trident and bundle definitions are {@code display_context}
     * selects and still need wrapping, so recognising our own work keys on the
     * whole shape rather than just the outer select.
     */
    static boolean isWrapped(ItemModel model) {
        if (!(model instanceof SelectItemModel select)) return false;
        if (select.cases().size() != 1) return false;
        if (!(select.cases().get(0).model() instanceof CompositeItemModel composite)) return false;
        if (composite.models().size() < 2) return false;
        return composite.models().get(0) instanceof ConditionItemModel condition
                && condition.onTrue() instanceof EmptyItemModel;
    }

}
