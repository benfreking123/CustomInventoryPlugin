package com.example.custominventoryplugin.compendium;

import com.example.custominventoryplugin.CustomInventoryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides what a stack is worth to the collection and records first sightings.
 *
 * Item identity comes from the tooltip service (which already hooks Divinity's
 * {@code ItemStats.getId}); armour sets are matched through Divinity's
 * SetManager because generated pieces share no item id.
 *
 * Every player gets an in-memory set of counter keys already written, primed
 * once from the database on join. Without it an inventory sweep would issue a
 * write per stack per sweep.
 */
public class CollectionsService {

    private final CustomInventoryPlugin plugin;
    private final CollectionsConfig config;
    private final CounterData counters;

    /** uuid → counter keys already recorded (prefix-scoped). */
    private final Map<UUID, Set<String>> known = new ConcurrentHashMap<>();

    private boolean setsAvailable;
    private Object setManager;
    private Method getItemSet;
    private Method itemSetGetId;
    private Method itemSetGetName;
    private Method itemSetGetElements;
    private Method elementGetId;
    private Method elementIsValid;

    public CollectionsService(CustomInventoryPlugin plugin, CollectionsConfig config,
                              CounterData counters) {
        this.plugin = plugin;
        this.config = config;
        this.counters = counters;
    }

    // ── Divinity SetManager hook ──────────────────────────────────────────

    /**
     * Resolve the SetManager lazily: Divinity may enable after CIP, and a
     * missing sets module must degrade to "items only" rather than break
     * collection tracking outright.
     */
    private boolean ensureSets() {
        if (setsAvailable) return true;
        if (setManager != null) return false;            // tried and failed
        if (Bukkit.getPluginManager().getPlugin("Divinity") == null) return false;
        try {
            Class<?> div = Class.forName("studio.magemonkey.divinity.Divinity");
            Object instance = div.getMethod("getInstance").invoke(null);
            if (instance == null) return false;
            Object cache = div.getMethod("getModuleCache").invoke(instance);
            if (cache == null) return false;
            Object mgr = cache.getClass().getMethod("getSetManager").invoke(cache);
            if (mgr == null) return false;

            Class<?> setMgr = Class.forName(
                    "studio.magemonkey.divinity.modules.list.sets.SetManager");
            Class<?> itemSet = Class.forName(
                    "studio.magemonkey.divinity.modules.list.sets.SetManager$ItemSet");
            Class<?> element = Class.forName(
                    "studio.magemonkey.divinity.modules.list.sets.SetManager$SetElement");

            getItemSet = setMgr.getMethod("getItemSet", ItemStack.class);
            itemSetGetElements = itemSet.getMethod("getElements");
            itemSetGetName = itemSet.getMethod("getName");
            try {
                itemSetGetId = itemSet.getMethod("getId");
            } catch (NoSuchMethodException e) {
                itemSetGetId = null;                     // fall back to name matching
            }
            elementGetId = element.getMethod("getId");
            elementIsValid = element.getMethod("isValidElement", ItemStack.class);

            setManager = mgr;
            setsAvailable = true;
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Collections: Divinity SetManager unavailable — "
                    + "armour sets will not be tracked (" + t.getClass().getSimpleName() + ").");
            setManager = new Object();                   // poison so we stop retrying
            return false;
        }
    }

    // ── discovery ─────────────────────────────────────────────────────────

    /** Load a player's already-recorded keys so sweeps stay cheap. */
    public void prime(UUID uuid) {
        if (uuid == null || known.containsKey(uuid)) return;
        Map<String, Integer> all = counters.loadAll(uuid);
        Set<String> keys = ConcurrentHashMap.newKeySet();
        String prefix = config.counterPrefix();
        for (Map.Entry<String, Integer> e : all.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue() > 0) keys.add(e.getKey());
        }
        known.put(uuid, keys);
    }

    public void forget(UUID uuid) {
        if (uuid != null) known.remove(uuid);
    }

    /** Keys recorded for this player, including writes still in flight. */
    public Set<String> knownKeys(UUID uuid) {
        Set<String> keys = uuid == null ? null : known.get(uuid);
        return keys == null ? Set.of() : Set.copyOf(keys);
    }

    /**
     * Record a single stack if it is collectable and new to this player.
     * Safe to call from the main thread — the database write is deferred.
     *
     * @return true when this call discovered something new
     */
    public boolean observe(Player player, ItemStack stack) {
        if (player == null || stack == null || stack.getType().isAir()) return false;
        if (config.isEmpty()) return false;
        String key = keyFor(stack);
        if (key == null) return false;
        return record(player.getUniqueId(), key);
    }

    /** Sweep a player's full inventory; returns how many new entries were found. */
    public int observeInventory(Player player) {
        if (player == null || config.isEmpty()) return 0;
        int found = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (observe(player, stack)) found++;
        }
        return found;
    }

    /** The counter key this stack maps to, or null when it is not collectable. */
    public String keyFor(ItemStack stack) {
        String itemId = plugin.getTooltipStyleService() == null ? null
                : plugin.getTooltipStyleService().resolveItemId(stack);
        if (itemId != null) {
            CollectionEntry entry = config.get(itemId);
            if (entry != null) return config.keyFor(entry);
        }
        return setKeyFor(stack);
    }

    /** {@code collect.sets.<set>.<element>} when the stack is a known set piece. */
    private String setKeyFor(ItemStack stack) {
        if (config.sets().isEmpty() || !ensureSets()) return null;
        try {
            Object set = getItemSet.invoke(setManager, stack);
            if (set == null) return null;

            String setId = null;
            if (itemSetGetId != null) {
                Object id = itemSetGetId.invoke(set);
                if (id instanceof String s && !s.isBlank()) setId = s.toLowerCase(Locale.ROOT);
            }
            if (setId == null || config.getSet(setId) == null) {
                setId = matchSetByName(set);
            }
            if (setId == null) return null;

            CollectionsConfig.SetEntry cfgSet = config.getSet(setId);
            if (cfgSet == null) return null;

            Object elements = itemSetGetElements.invoke(set);
            if (elements instanceof Iterable<?> it) {
                for (Object element : it) {
                    Object valid = elementIsValid.invoke(element, stack);
                    if (!(valid instanceof Boolean b) || !b) continue;
                    Object eid = elementGetId.invoke(element);
                    if (eid instanceof String es && !es.isBlank()) {
                        String el = es.toLowerCase(Locale.ROOT);
                        if (cfgSet.elements.contains(el)) {
                            return config.keyForSetPiece(cfgSet.id, el);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // A malformed stack must never break a pickup.
        }
        return null;
    }

    /** Older Divinity builds expose no getId on ItemSet; fall back to display name. */
    private String matchSetByName(Object set) {
        try {
            Object name = itemSetGetName.invoke(set);
            if (!(name instanceof String raw) || raw.isBlank()) return null;
            String plain = strip(raw);
            for (CollectionsConfig.SetEntry s : config.sets().values()) {
                if (strip(s.display).equalsIgnoreCase(plain)) return s.id;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String strip(String s) {
        return s.replaceAll("[&\u00a7][0-9a-fk-orA-FK-OR]", "").trim();
    }

    /** Write the key once per player, off the main thread. */
    private boolean record(UUID uuid, String key) {
        Set<String> keys = known.get(uuid);
        if (keys == null) {
            // Not primed yet (rare: pickup during login). Prime async, then retry.
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                prime(uuid);
                Set<String> late = known.get(uuid);
                if (late != null && late.add(key)) counters.increment(uuid, key, 1);
            });
            return false;
        }
        if (!keys.add(key)) return false;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> counters.increment(uuid, key, 1));
        return true;
    }

    // ── progress ──────────────────────────────────────────────────────────

    /** Distinct collectables a player has discovered, from a counters snapshot. */
    public int discovered(Map<String, Integer> counterSnapshot) {
        if (counterSnapshot == null || counterSnapshot.isEmpty()) return 0;
        int n = 0;
        for (Map.Entry<String, Integer> e : counterSnapshot.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (!e.getKey().startsWith(config.counterPrefix())) continue;
            if (isLiveKey(e.getKey())) n++;
        }
        return n;
    }

    /**
     * True when a stored key still corresponds to something in the config.
     * Keys outlive edits — an item pulled from collections.yml must stop
     * counting, or progress could read over 100%.
     */
    private boolean isLiveKey(String key) {
        for (CollectionEntry e : config.entries().values()) {
            if (config.keyFor(e).equals(key)) return true;
        }
        for (CollectionsConfig.SetEntry s : config.sets().values()) {
            for (String el : s.elements) {
                if (config.keyForSetPiece(s.id, el).equals(key)) return true;
            }
        }
        return false;
    }

    /** Elements of a set this player has found. */
    public int setPiecesFound(Map<String, Integer> snapshot, CollectionsConfig.SetEntry set) {
        if (snapshot == null) return 0;
        int n = 0;
        for (String el : set.elements) {
            if (snapshot.getOrDefault(config.keyForSetPiece(set.id, el), 0) > 0) n++;
        }
        return n;
    }

    public boolean has(Map<String, Integer> snapshot, CollectionEntry entry) {
        return snapshot != null && snapshot.getOrDefault(config.keyFor(entry), 0) > 0;
    }

    /** All keys this service owns, for a reset. */
    public Set<String> keysFor(Map<String, Integer> snapshot) {
        Set<String> out = new HashSet<>();
        if (snapshot == null) return out;
        for (String k : snapshot.keySet()) {
            if (k.startsWith(config.counterPrefix())) out.add(k);
        }
        return out;
    }
}
