package com.voxel.utils;

import java.util.HashMap;
import java.util.Map;

public class ShaderBlockRegistry {
    public static class State {
        public final int worldId;
        public final int extra;

        public State(int worldId, int extra) {
            this.worldId = worldId;
            this.extra = extra;
        }
    }

    private final Map<Integer, State> defaults = new HashMap<>();
    private final Map<Integer, Map<String, State>> variants = new HashMap<>();

    public void register(int canonicalId, int worldId) {
        defaults.put(canonicalId, new State(worldId, 0));
    }

    public void registerDirectional(int canonicalId, Direction direction, int worldId, int extra) {
        variants.computeIfAbsent(canonicalId, k -> new HashMap<>())
                .put(direction.name().toLowerCase(), new State(worldId, extra));
    }

    public void registerOnOff(int canonicalId, boolean on, int worldId) {
        variants.computeIfAbsent(canonicalId, k -> new HashMap<>())
                .put(on ? "on" : "off", new State(worldId, 0));
    }

    public State getDefault(int canonicalId) {
        State s = defaults.get(canonicalId);
        if (s != null) return s;
        return new State(canonicalId, 0);
    }

    public State getByDirection(int canonicalId, Direction direction) {
        if (direction == null) return getDefault(canonicalId);
        Map<String, State> map = variants.get(canonicalId);
        if (map != null) {
            State s = map.get(direction.name().toLowerCase());
            if (s != null) return s;
        }
        return getDefault(canonicalId);
    }

    public State getByPower(int canonicalId, boolean on) {
        Map<String, State> map = variants.get(canonicalId);
        if (map != null) {
            State s = map.get(on ? "on" : "off");
            if (s != null) return s;
        }
        return getDefault(canonicalId);
    }

    public int getWorldId(int canonicalId) {
        return getDefault(canonicalId).worldId;
    }

    public int getWorldId(int canonicalId, Direction direction) {
        return getByDirection(canonicalId, direction).worldId;
    }

    public int getWorldId(int canonicalId, boolean on) {
        return getByPower(canonicalId, on).worldId;
    }

    public int getExtra(int canonicalId, Direction direction) {
        return getByDirection(canonicalId, direction).extra;
    }

    public int getExtra(int canonicalId) {
        return getDefault(canonicalId).extra;
    }

    public boolean hasDirectional(int canonicalId) {
        Map<String, State> map = variants.get(canonicalId);
        if (map == null) return false;
        for (String key : map.keySet()) {
            try {
                Direction.valueOf(key.toUpperCase());
                return true;
            } catch (IllegalArgumentException e) {
                // not a direction key
            }
        }
        return false;
    }

    public boolean hasOnOff(int canonicalId) {
        Map<String, State> map = variants.get(canonicalId);
        return map != null && (map.containsKey("on") || map.containsKey("off"));
    }
}
