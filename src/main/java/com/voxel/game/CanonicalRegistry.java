package com.voxel.game;

import com.voxel.utils.BlockDataManager;
import java.util.*;

/**
 * A deduplicated registry of blocks and items — one entry per logical block/item,
 * linking to potentially multiple lower-level block IDs (direction variants, 
 * flowing water levels, separate crafting-table vs dropped-item models, etc.).
 *
 * <p>This sits above BlockDataManager/BlockRegistry/ShaderBlockRegistry and
 * provides a single source of truth for commands like /give and inventory lookups.
 */
public class CanonicalRegistry {

    /** One canonical block/item entry. */
    public static final class CanonicalEntry {
        public final String canonicalName;
        public final int primaryBlockId;       // default for /give, inventory icon
        public final int[] allBlockIds;        // all variant block IDs including primary

        CanonicalEntry(String canonicalName, int primaryBlockId, int[] allBlockIds) {
            this.canonicalName = canonicalName;
            this.primaryBlockId = primaryBlockId;
            this.allBlockIds = allBlockIds;
        }
    }

    private final Map<String, CanonicalEntry> byName = new LinkedHashMap<>();
    private final Map<Integer, CanonicalEntry> byBlockId = new HashMap<>();

    /**
     * Register a canonical entry explicitly.
     * @param canonicalName  deduplicated name (e.g. "flint")
     * @param primaryBlockId default block ID for placement, icon, /give
     * @param otherBlockIds  additional variant block IDs
     */
    public void register(String canonicalName, int primaryBlockId, int... otherBlockIds) {
        int[] all = new int[1 + otherBlockIds.length];
        all[0] = primaryBlockId;
        System.arraycopy(otherBlockIds, 0, all, 1, otherBlockIds.length);

        CanonicalEntry entry = new CanonicalEntry(canonicalName, primaryBlockId, all);
        byName.put(canonicalName.toLowerCase(), entry);
        for (int id : all) {
            byBlockId.put(id, entry);
        }
    }

    /**
     * Auto-populate the canonical registry by scanning BlockDataManager's registered
     * names and grouping level/drop-model variants under their base name.
     */
    public void build(BlockDataManager blockDataManager, ItemDefinitions itemDefs) {
        byName.clear();
        byBlockId.clear();

        Map<String, Integer> nameToId = blockDataManager.getAllRegisteredNames();
        if (nameToId == null || nameToId.isEmpty()) return;

        // First pass: collect all IDs by their canonical (base) name
        Map<String, List<Integer>> canonicalToIds = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> e : nameToId.entrySet()) {
            String name = e.getKey();
            int id = e.getValue();
            String canonical = toCanonicalName(name);

            canonicalToIds.computeIfAbsent(canonical, k -> new ArrayList<>()).add(id);
        }

        // Second pass: for each canonical name, determine the primary ID
        for (Map.Entry<String, List<Integer>> e : canonicalToIds.entrySet()) {
            String canonicalName = e.getKey();
            List<Integer> ids = e.getValue();
            Collections.sort(ids); // deterministic order

            // Primary ID: prefer the /give-able item's blockId, else lowest ID
            int primary = ids.get(0);
            // If an ItemDefinition exists for this canonical name, use its blockId as primary
            if (itemDefs != null) {
                ItemDefinitions.ItemDefinition def = itemDefs.getDefinition(canonicalName);
                if (def != null && def.blockId > 0 && ids.contains(def.blockId)) {
                    primary = def.blockId;
                }
            }
            // For item_* names, prefer the non-drop ID (lower one)
            for (int id : ids) {
                String n = blockDataManager.getName(id);
                if (n != null && !n.startsWith("item_drop_")) {
                    primary = id;
                    break;
                }
            }

            int[] allIds = ids.stream().mapToInt(Integer::intValue).toArray();
            CanonicalEntry entry = new CanonicalEntry(canonicalName, primary, allIds);
            byName.put(canonicalName, entry);
            for (int id : allIds) {
                byBlockId.put(id, entry);
            }
        }
    }

    /**
     * Convert a raw registered name (e.g. "item_drop_flint", "water_3") 
     * to its canonical base name (e.g. "flint", "water").
     */
    private static String toCanonicalName(String raw) {
        // Strip "item_drop_" prefix → falls under the item_ or base name
        if (raw.startsWith("item_drop_")) {
            return raw.substring(10); // "item_drop_flint" → "flint"
        }
        // Strip "item_" prefix for tool items and flat models
        if (raw.startsWith("item_")) {
            return raw.substring(5); // "item_wood_pickaxe" → "wood_pickaxe"
        }
        // Strip level suffix: "water_3", "water_15" → "water"
        int lastUnderscore = raw.lastIndexOf('_');
        if (lastUnderscore > 0 && lastUnderscore < raw.length() - 1) {
            String suffix = raw.substring(lastUnderscore + 1);
            if (suffix.matches("\\d+")) {
                return raw.substring(0, lastUnderscore);
            }
        }
        return raw;
    }

    /** Look up a canonical entry by its canonical name. */
    public CanonicalEntry resolve(String canonicalName) {
        return byName.get(canonicalName.toLowerCase());
    }

    /** Look up a canonical entry by any registered block ID. */
    public CanonicalEntry resolve(int blockId) {
        return byBlockId.get(blockId);
    }

    /** All canonical entries, in registration order. */
    public Collection<CanonicalEntry> getAll() {
        return byName.values();
    }

    /** Number of canonical entries. */
    public int size() {
        return byName.size();
    }

    public Map<String, CanonicalEntry> getNameMap() {
        return byName;
    }
}
