package com.voxel.ai.brain;

import org.json.JSONObject;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The villager brain is hybrid: the shipped network must load, imitate the
 * utility policy it was trained on, and never override a safety rule.
 */
public class VillagerPolicyTest {

    @Test
    public void shippedPolicyLoadsAndValidates() {
        VillagerPolicy policy = VillagerPolicy.get();
        assertNotNull("the trained policy must ship in the jar", policy);
        assertTrue("imitation accuracy should stay high: " + policy.accuracy(),
                policy.accuracy() >= 0.95);
    }

    @Test
    public void scoresAreDeterministicAndSizedLikeTheActionTable() {
        VillagerPolicy policy = VillagerPolicy.get();
        float[] features = VillagerBrain.policyFeatures(false, false, true,
                0.2f, false, true, 0.8f);
        float[] first = policy.scores(features);
        float[] second = policy.scores(features);
        assertEquals(VillagerPolicy.ACTIONS.length, first.length);
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], second[i], 1e-6f);
            assertTrue("score must be finite", Float.isFinite(first[i]));
        }
        assertEquals(policy.choose(features), policy.choose(features));
    }

    @Test
    public void networkImitatesTheUtilityPolicy() {
        VillagerPolicy policy = VillagerPolicy.get();
        int agree = 0;
        int total = 0;
        for (int build = 0; build < 2; build++) {
            for (int social = 0; social < 2; social++) {
                for (int job = 0; job < 2; job++) {
                    for (int friend = 0; friend < 2; friend++) {
                        for (int c = 0; c < 4; c++) {
                            float chaos = c / 3f;
                            float[] features = VillagerBrain.policyFeatures(
                                    build == 1, social == 1, job == 1, chaos,
                                    friend == 1, false, 0.9f);
                            VillagerBrain.Action expected = trainingLabel(
                                    build == 1, social == 1 && friend == 1,
                                    job == 1, chaos, 0.9f);
                            VillagerBrain.Action actual = policy.choose(features);
                            if (expected == actual) agree++;
                            total++;
                        }
                    }
                }
            }
        }
        double ratio = agree / (double) total;
        assertTrue("net only agrees with the rules " + ratio + " of the time",
                ratio >= 0.9);
    }

    @Test
    public void safetyRulesAreNotNeuralAndCannotBeOutvoted() {
        // A visible threat, panic, alert, night and swimming all decide
        // before the network is consulted.
        assertEquals(VillagerBrain.Action.PANIC_FLEE, VillagerBrain.decide(
                true, 0f, 0f, false, false, true, true, true, 1f, true, true, 1f));
        assertEquals(VillagerBrain.Action.PANIC_FLEE, VillagerBrain.decide(
                false, 3f, 0f, false, false, true, true, true, 1f, true, true, 20f));
        assertEquals(VillagerBrain.Action.PANIC_FLEE, VillagerBrain.decide(
                false, 0f, 0f, false, true, true, true, true, 1f, true, true, 20f));
        assertEquals(VillagerBrain.Action.ALERT_LOOK, VillagerBrain.decide(
                false, 0f, 2f, false, false, true, true, true, 1f, true, true, 20f));
        assertEquals(VillagerBrain.Action.GO_INDOORS, VillagerBrain.decide(
                false, 0f, 0f, true, false, true, true, true, 1f, true, true, 20f));
    }

    @Test
    public void daytimePlanningUsesTheNetwork() {
        // Queued build work is the strongest planning signal in the training set.
        assertEquals(VillagerBrain.Action.GO_BUILD, VillagerBrain.decide(
                false, 0f, 0f, false, false, true, true, true, 0f, true, true, 20f));
        // Work needs an actual job site.
        assertEquals(VillagerBrain.Action.GO_BUILD, VillagerBrain.decide(
                false, 0f, 0f, false, false, true, false, false, 0f, false, false, 20f));
    }

    @Test
    public void featureVectorMatchesTheTrainedOrder() {
        float[] features = VillagerBrain.policyFeatures(true, false, true,
                0.5f, true, false, 0.25f);
        assertEquals(VillagerPolicy.FEATURES.length, features.length);
        assertEquals(1f, features[0], 0f); // build_pending
        assertEquals(0f, features[1], 0f); // social_open
        assertEquals(1f, features[2], 0f); // has_job_site
        assertEquals(0.5f, features[3], 1e-6f); // chaos
        assertEquals(1f, features[4], 0f); // friend_near
        assertEquals(0f, features[5], 0f); // player_near
        assertEquals(0.25f, features[6], 1e-6f); // threat_far
        assertEquals(1f, features[7], 0f); // daytime
    }

    @Test
    public void aMismatchedExportIsRejectedInsteadOfScored() {
        String good = new String(readResource(), StandardCharsets.UTF_8);
        assertNotNull("control: the shipped document parses",
                VillagerPolicy.parse(good));

        JSONObject renamed = new JSONObject(good);
        renamed.put("features", new org.json.JSONArray(
                new String[]{"build_pending", "social_open", "has_job_site", "chaos",
                        "friend_near", "player_near", "threat_far", "night"}));
        assertNull("a renamed feature must be rejected",
                VillagerPolicy.parse(renamed.toString()));

        JSONObject truncated = new JSONObject(good);
        truncated.put("w1", new org.json.JSONArray(new double[][]{{1.0, 2.0}}));
        assertNull("wrong dimensions must be rejected",
                VillagerPolicy.parse(truncated.toString()));

        assertNull(VillagerPolicy.load(null));
        assertNull(VillagerPolicy.parse("{ not json"));
    }

    @Test
    public void missingPolicyFallsBackToTheRules() {
        // Simulate an unbundled build: the loader yields null and the brain
        // must still make the utility choice.
        VillagerPolicy.resetForTest();
        assertNotNull("test JVM has the resource", VillagerPolicy.get());

        InputStream missing = new ByteArrayInputStream(new byte[0]);
        assertNull(VillagerPolicy.load(missing));

        VillagerBrain.Action fallback = VillagerBrain.chooseAction(
                false, 0f, 0f, false, false, true, false);
        assertEquals(VillagerBrain.Action.GO_BUILD, fallback);
    }

    /**
     * The label the trainer used (tools/train_villager_policy.py). It is the
     * utility policy plus the idle beat, which {@code chooseAction} predates.
     */
    private static VillagerBrain.Action trainingLabel(boolean build, boolean social,
                                                      boolean job, float chaos,
                                                      float threatFar) {
        if (build) return VillagerBrain.Action.GO_BUILD;
        if (social) return VillagerBrain.Action.SOCIALIZE;
        if (job) return VillagerBrain.Action.WORK;
        boolean idle = (0.25 + 0.35 * chaos) > 0.45 && threatFar < 0.3f;
        return idle ? VillagerBrain.Action.IDLE : VillagerBrain.Action.WANDER;
    }

    private static byte[] readResource() {
        try (InputStream in = VillagerPolicyTest.class
                .getResourceAsStream(VillagerPolicy.RESOURCE)) {
            assertNotNull("resource " + VillagerPolicy.RESOURCE, in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }
}
