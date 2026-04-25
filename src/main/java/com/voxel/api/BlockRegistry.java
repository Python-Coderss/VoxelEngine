package com.voxel.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockRegistry {
    private static final Map<String, Block> blocksByName = new HashMap<>();
    private static final List<Block> blocksById = new ArrayList<>();

    static {
        // Register an empty block for ID 0 (Air)
        blocksById.add(null);
    }

    public static void register(Block block) {
        if (blocksByName.containsKey(block.getName())) {
            throw new IllegalArgumentException("Block already registered: " + block.getName());
        }
        blocksByName.put(block.getName(), block);
        blocksById.add(block);
    }

    public static Block getByName(String name) {
        return blocksByName.get(name);
    }

    public static Block getById(int id) {
        if (id < 0 || id >= blocksById.size()) return null;
        return blocksById.get(id);
    }

    public static int getId(Block block) {
        return blocksById.indexOf(block);
    }

    public static int getId(String name) {
        Block block = blocksByName.get(name);
        return block != null ? blocksById.indexOf(block) : 0;
    }
    
    public static List<Block> getAllBlocks() {
        return blocksById;
    }
}
