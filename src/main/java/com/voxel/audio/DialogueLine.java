package com.voxel.audio;

import villager.voice.SpeechOptions;

/** A dialogue line plus the metadata used to render its voice. */
public final class DialogueLine {
    private final String id;
    private final String text;
    private final String profession;
    private final String period;
    private final int variant;
    private final SpeechOptions options;

    public DialogueLine(String id, String text, String profession, String period,
                        int variant, SpeechOptions options) {
        this.id = required(id, "id");
        this.text = required(text, "text");
        this.profession = required(profession, "profession");
        this.period = required(period, "period");
        if (variant < 0) {
            throw new IllegalArgumentException("variant must not be negative");
        }
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        this.variant = variant;
        this.options = options;
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public String getProfession() {
        return profession;
    }

    public String getPeriod() {
        return period;
    }

    public int getVariant() {
        return variant;
    }

    public SpeechOptions getOptions() {
        return options;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value.trim();
    }
}
