package com.voxel.utils;

import java.util.HashMap;
import java.util.Map;

public class BlockRegistry {
    private final Map<String, Integer> nameToId = new HashMap<>();
    private final Map<Integer, String> idToName = new HashMap<>();

    public void register(String name, int id) {
        if (name == null) return;
        nameToId.put(name.toLowerCase(), id);
        idToName.put(id, name);
    }

    public int getId(String name) {
        if (name == null) return 0;
        Integer id = nameToId.get(name.toLowerCase());
        return id != null ? id : 0;
    }

    public String getName(int id) {
        String name = idToName.get(id);
        return name != null ? name : "unknown";
    }

    public boolean hasName(String name) {
        return name != null && nameToId.containsKey(name.toLowerCase());
    }

    public boolean hasId(int id) {
        return idToName.containsKey(id);
    }
}
