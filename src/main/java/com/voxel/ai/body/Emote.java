package com.voxel.ai.body;

/**
 * Body-language vocabulary. Emotes are first-class actions brains can play;
 * each carries a fixed duration in seconds.
 */
public final class Emote {

    public static final Emote NOD = new Emote("nod", 0.9f);
    public static final Emote HEAD_SHAKE = new Emote("shake", 1.1f);
    public static final Emote WAVE_FRANTIC = new Emote("wave", 2.0f);
    public static final Emote JUMP_CHEER = new Emote("cheer", 1.6f);
    public static final Emote COWER = new Emote("cower", 1.4f);
    public static final Emote HAMMER = new Emote("hammer", 0.55f);
    public static final Emote POINT = new Emote("point", 1.5f);
    public static final Emote TUG = new Emote("tug", 0.8f);

    public final String name;
    public final float duration;

    private Emote(String name, float duration) {
        this.name = name;
        this.duration = duration;
    }

    @Override
    public String toString() {
        return name;
    }
}
