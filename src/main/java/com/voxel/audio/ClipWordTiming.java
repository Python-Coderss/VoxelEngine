package com.voxel.audio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Word-level timings for recorded clips, produced offline by a local speech
 * recogniser ({@code tools/align_voice_clips.py}).
 *
 * <p>Each clip id maps to the start time of every word in its transcript, in
 * seconds of the source recording. The runtime resamples clips by speed and
 * emotion pitch, so the loader scales the times by the same factor; the caption
 * then reveals a word when the voice reaches it instead of guessing from the
 * text length. When the file is absent, or a clip's word count disagrees with
 * the transcript, callers fall back to pause detection over the rendered PCM
 * ({@link CaptionTiming}), so sync never depends on this resource existing.</p>
 */
public final class ClipWordTiming {

    /** Resource written by {@code tools/align_voice_clips.py}. */
    public static final String RESOURCE = "/voice/clip_word_times.json";

    private static volatile ClipWordTiming shared;
    private static volatile boolean loaded;

    private final Map<String, float[]> times;

    private ClipWordTiming(Map<String, float[]> times) {
        this.times = times;
    }

    /** The shipped timings, or null when the resource is absent or unusable. */
    public static ClipWordTiming get() {
        if (loaded) return shared;
        synchronized (ClipWordTiming.class) {
            if (loaded) return shared;
            InputStream in = ClipWordTiming.class.getResourceAsStream(RESOURCE);
            shared = load(in);
            loaded = true;
            return shared;
        }
    }

    static ClipWordTiming load(InputStream in) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            System.err.println("ClipWordTiming: cannot read " + RESOURCE + ": "
                    + unreadable.getMessage());
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Fall back to the PCM timing path.
            }
        }
    }

    static ClipWordTiming parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject clips = root.getJSONObject("clips");
            Map<String, float[]> map = new HashMap<String, float[]>();
            java.util.Iterator<String> keys = clips.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONArray starts = clips.getJSONObject(id).getJSONArray("word_starts");
                float[] values = new float[starts.length()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = (float) starts.getDouble(i);
                }
                map.put(id, values);
            }
            return new ClipWordTiming(map);
        } catch (RuntimeException broken) {
            System.err.println("ClipWordTiming: invalid " + RESOURCE + ": " + broken);
            return null;
        }
    }

    public int size() {
        return times.size();
    }

    /**
     * Start times (seconds, source-recording clock) for a clip, scaled to the
     * playback factor, or null when the clip is unknown or the word count does
     * not match the transcript this line will caption.
     */
    public float[] scaled(String clipId, int wordCount, double playbackFactor) {
        if (clipId == null || wordCount <= 0) return null;
        float[] source = times.get(clipId);
        if (source == null || source.length != wordCount) return null;
        float factor = (float) (playbackFactor <= 0.01 ? 1.0 : playbackFactor);
        float[] scaled = new float[source.length];
        for (int i = 0; i < source.length; i++) {
            scaled[i] = source[i] / factor;
        }
        return scaled;
    }

    /** Test hook: drop the cached document. */
    static void resetForTest() {
        synchronized (ClipWordTiming.class) {
            shared = null;
            loaded = false;
        }
    }
}
