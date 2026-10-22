package com.voxel.ai.brain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The learned half of the villager brain: a tiny feed-forward network that
 * ranks ordinary daytime plans.
 *
 * <p>Hybrid on purpose. Survival is not learned — panic, a visible threat,
 * night and swimming are hard rules in {@link VillagerBrain#decide} and the
 * network is never asked about them. What the network provides is a scorer for
 * the interesting, low-stakes choice (work, build, socialize, wander, stand
 * about) that generalizes across feature combinations the hand-written
 * utility order never enumerated. Its weights are trained offline by
 * {@code tools/train_villager_policy.py} to imitate that utility policy, then
 * shipped as {@code /ai/villager_policy.json}; the network runs here in plain
 * Java floats, no runtime dependency.</p>
 *
 * <p>The loader validates the feature and action order recorded in the file
 * against {@link #FEATURES}/{@link VillagerBrain.Action}. A mismatched export
 * is rejected (the brain keeps its rule-based choice) rather than scored with
 * the wrong columns, which would silently produce nonsense.</p>
 */
public final class VillagerPolicy {

    /** Resource path of the trained weights. */
    public static final String RESOURCE = "/ai/villager_policy.json";

    /**
     * Feature order. Must match {@code features} in the exported JSON and the
     * vector built by {@link VillagerBrain#policyFeatures}.
     */
    public static final String[] FEATURES = {
            "build_pending", "social_open", "has_job_site", "chaos",
            "friend_near", "player_near", "threat_far", "daytime"
    };

    /** Actions the network may choose, in output order. */
    static final VillagerBrain.Action[] ACTIONS = {
            VillagerBrain.Action.GO_BUILD,
            VillagerBrain.Action.SOCIALIZE,
            VillagerBrain.Action.WORK,
            VillagerBrain.Action.WANDER,
            VillagerBrain.Action.IDLE
    };

    private static volatile VillagerPolicy shared;
    private static volatile boolean sharedLoaded;

    private final String[] features;
    private final String[] actions;
    private final double[][] w1;
    private final double[] b1;
    private final double[][] w2;
    private final double[] b2;
    private final double accuracy;

    private VillagerPolicy(String[] features, String[] actions,
                           double[][] w1, double[] b1, double[][] w2, double[] b2,
                           double accuracy) {
        this.features = features;
        this.actions = actions;
        this.w1 = w1;
        this.b1 = b1;
        this.w2 = w2;
        this.b2 = b2;
        this.accuracy = accuracy;
    }

    /**
     * The shipped policy, or null when the resource is missing or invalid.
     * Callers must keep their rule-based fallback; a null policy is a normal
     * state (an unbundled dev build), not an error.
     */
    public static VillagerPolicy get() {
        if (sharedLoaded) return shared;
        synchronized (VillagerPolicy.class) {
            if (sharedLoaded) return shared;
            InputStream in = VillagerPolicy.class.getResourceAsStream(RESOURCE);
            shared = load(in);
            sharedLoaded = true;
            return shared;
        }
    }

    /** Parse a policy document; null when the stream or document is unusable. */
    static VillagerPolicy load(InputStream in) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            System.err.println("VillagerPolicy: cannot read " + RESOURCE + ": "
                    + unreadable.getMessage());
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing to do while falling back to the rules.
            }
        }
    }

    /** Parse and validate a policy document; null when it does not match the API. */
    static VillagerPolicy parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            String[] fileFeatures = strings(root.getJSONArray("features"));
            String[] fileActions = strings(root.getJSONArray("actions"));
            if (!java.util.Arrays.equals(fileFeatures, FEATURES)) {
                System.err.println("VillagerPolicy: feature order mismatch, ignoring "
                        + RESOURCE);
                return null;
            }
            if (fileActions.length != ACTIONS.length) {
                System.err.println("VillagerPolicy: expected " + ACTIONS.length
                        + " actions, got " + fileActions.length);
                return null;
            }
            for (int i = 0; i < ACTIONS.length; i++) {
                if (!ACTIONS[i].name().equals(fileActions[i])) {
                    System.err.println("VillagerPolicy: action " + i + " is "
                            + fileActions[i] + ", expected " + ACTIONS[i].name());
                    return null;
                }
            }
            double[][] w1 = matrix(root.getJSONArray("w1"));
            double[][] w2 = matrix(root.getJSONArray("w2"));
            double[] b1 = vector(root.getJSONArray("b1"));
            double[] b2 = vector(root.getJSONArray("b2"));
            if (w1.length != FEATURES.length) {
                System.err.println("VillagerPolicy: w1 rows " + w1.length
                        + " != features " + FEATURES.length);
                return null;
            }
            int hidden = w1[0].length;
            if (b1.length != hidden || w2.length != hidden
                    || b2.length != ACTIONS.length) {
                System.err.println("VillagerPolicy: hidden/output dimensions disagree");
                return null;
            }
            for (double[] row : w2) {
                if (row.length != ACTIONS.length) {
                    System.err.println("VillagerPolicy: w2 row width mismatch");
                    return null;
                }
            }
            return new VillagerPolicy(fileFeatures, fileActions, w1, b1, w2, b2,
                    root.optDouble("accuracy", 0.0));
        } catch (RuntimeException broken) {
            System.err.println("VillagerPolicy: invalid " + RESOURCE + ": " + broken);
            return null;
        }
    }

    private static String[] strings(JSONArray array) {
        String[] values = new String[array.length()];
        for (int i = 0; i < values.length; i++) values[i] = array.getString(i);
        return values;
    }

    private static double[] vector(JSONArray array) {
        double[] values = new double[array.length()];
        for (int i = 0; i < values.length; i++) values[i] = array.getDouble(i);
        return values;
    }

    private static double[][] matrix(JSONArray array) {
        double[][] values = new double[array.length()][];
        for (int i = 0; i < values.length; i++) values[i] = vector(array.getJSONArray(i));
        return values;
    }

    /** Imitation accuracy recorded at training time (diagnostics/tests). */
    public double accuracy() {
        return accuracy;
    }

    /** Raw scores for one feature vector, in {@link #ACTIONS} order. */
    public float[] scores(float[] features) {
        double[] hidden = new double[b1.length];
        for (int h = 0; h < hidden.length; h++) {
            double sum = b1[h];
            for (int f = 0; f < w1.length; f++) {
                // Continuous features (chaos, threat distance) pass through as
                // they are; only the booleans are 0/1 by construction.
                sum += w1[f][h] * features[f];
            }
            hidden[h] = Math.tanh(sum);
        }
        float[] out = new float[ACTIONS.length];
        for (int a = 0; a < ACTIONS.length; a++) {
            double sum = b2[a];
            for (int h = 0; h < hidden.length; h++) {
                sum += w2[h][a] * hidden[h];
            }
            out[a] = (float) sum;
        }
        return out;
    }

    /** The best planning action for these features (never null). */
    public VillagerBrain.Action choose(float[] features) {
        float[] scores = scores(features);
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) best = i;
        }
        return ACTIONS[best];
    }

    /** Test hook: drop the cached policy so the loader runs again. */
    static void resetForTest() {
        synchronized (VillagerPolicy.class) {
            shared = null;
            sharedLoaded = false;
        }
    }
}
