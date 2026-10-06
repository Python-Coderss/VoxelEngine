package villager.voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The addon-style script: situations mapped onto the recorded clip catalog.
 * Every spoken line in the game is a real clip transcript — this only decides
 * which recording fits the moment (a panicked villager gets the closest thing
 * the addon ever recorded to panicking). Nothing is ever invented: the chosen
 * clip's transcript is what plays and what the live captions show.
 */
public final class ClipScript {

    /** The situations brains and dialogue can ask the catalog to play. */
    public enum Topic {
        PANIC, CHICKEN_OUT, BLUFF, SHINY, FORGET, ADMIRE,
        BUILD_OOPS, BUILD_PROUD, ARGUE, GOSSIP, WRONG_WAVE, FOLLOW, TRIP,
        HUNT_CONFUSED, MOB_COWARD, MOB_TAUNT, SMALLTALK, GREETING
    }

    private static final Map<Topic, String[]> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put(Topic.PANIC, new String[]{
                "help", "run", "running", "scared", "afraid", "danger", "hide",
                "ahh", "aah", "no", "away", "monster", "zombie", "attack", "save",
                "eek", "yikes", "oh"});
        KEYWORDS.put(Topic.CHICKEN_OUT, new String[]{
                "mistake", "wrong", "sorry", "away", "nope", "regret", "oops",
                "back", "run", "leave", "go", "bad"});
        KEYWORDS.put(Topic.BLUFF, new String[]{
                "brave", "fear", "scared", "afraid", "tough", "strong", "fight",
                "beat", "dare", "destroy", "win", "hero", "try", "come", "mess"});
        KEYWORDS.put(Topic.SHINY, new String[]{
                "look", "ooh", "oooh", "wow", "shiny", "sparkly", "amazing",
                "whoa", "woah", "new", "magnificent", "dramatic", "beautiful", "cool"});
        KEYWORDS.put(Topic.FORGET, new String[]{
                "forget", "remember", "what", "wait", "hmm", "think", "thought",
                "where", "confused", "lost", "mind", "idea", "doing"});
        KEYWORDS.put(Topic.ADMIRE, new String[]{
                "beautiful", "magnificent", "perfect", "nice", "amazing", "great",
                "wonderful", "love", "best", "glorious", "fine", "impressive",
                "masterpiece"});
        KEYWORDS.put(Topic.BUILD_OOPS, new String[]{
                "oops", "ow", "broken", "broke", "wrong", "mistake", "bad", "hurt",
                "ouch", "careful", "dropped", "thumb", "sorry"});
        KEYWORDS.put(Topic.BUILD_PROUD, new String[]{
                "made", "built", "build", "perfect", "done", "great", "masterpiece",
                "wall", "house", "beautiful", "nice", "look", "thing", "works"});
        KEYWORDS.put(Topic.ARGUE, new String[]{
                "no", "wrong", "dont", "never", "stop", "my", "why", "should",
                "rude", "dare", "not", "excuse", "fine", "nope"});
        KEYWORDS.put(Topic.GOSSIP, new String[]{
                "hear", "heard", "secret", "tell", "about", "everyone", "rumor",
                "whisper", "between", "know", "think", "suspicious", "did"});
        KEYWORDS.put(Topic.WRONG_WAVE, new String[]{
                "hello", "hi", "hey", "wave", "hiya", "sorry", "wrong", "oh",
                "who", "there"});
        KEYWORDS.put(Topic.FOLLOW, new String[]{
                "come", "with", "where", "going", "follow", "can", "you", "help",
                "stay", "near", "walk"});
        KEYWORDS.put(Topic.TRIP, new String[]{
                "oops", "whoa", "ow", "ground", "fell", "fall", "trip", "careful",
                "ouch", "clumsy", "slip", "watch"});
        KEYWORDS.put(Topic.HUNT_CONFUSED, new String[]{
                "where", "what", "hey", "lost", "gone", "find", "see", "hiding",
                "confused", "wait", "went", "did"});
        KEYWORDS.put(Topic.MOB_COWARD, new String[]{
                "nope", "run", "away", "sorry", "retreat", "mistake", "leave",
                "wrong", "not", "go", "back", "please", "help"});
        KEYWORDS.put(Topic.MOB_TAUNT, new String[]{
                "come", "here", "get", "back", "stop", "hey", "you", "where",
                "going", "running", "mine", "now"});
        KEYWORDS.put(Topic.SMALLTALK, new String[]{
                "hmm", "oh", "ah", "well", "so", "day", "nice", "thing", "think",
                "yes", "yeah", "right", "okay"});
        KEYWORDS.put(Topic.GREETING, new String[]{
                "hello", "hi", "hey", "good", "morning", "evening", "day", "see",
                "welcome", "there", "how", "again"});
    }

    private ClipScript() {
    }

    /** Natural delivery emotion for a topic (valid SpeechOptions emotion). */
    public static String emotionFor(Topic topic) {
        if (topic == null) return "neutral";
        switch (topic) {
            case PANIC:
            case CHICKEN_OUT:
            case HUNT_CONFUSED:
                return "scared";
            case BLUFF:
            case ARGUE:
            case MOB_TAUNT:
                return "angry";
            case SHINY:
            case ADMIRE:
            case BUILD_PROUD:
            case WRONG_WAVE:
            case GREETING:
            case FOLLOW:
                return "happy";
            case BUILD_OOPS:
            case TRIP:
            case MOB_COWARD:
            case FORGET:
                return "sad";
            case GOSSIP:
            case SMALLTALK:
            default:
                return "neutral";
        }
    }

    /** How well a clip's transcript fits a topic (distinct keyword hits). */
    public static int score(ClipIndex.Clip clip, Topic topic) {
        if (clip == null || topic == null) return 0;
        String[] words = KEYWORDS.get(topic);
        if (words == null) return 0;
        int hits = 0;
        for (String keyword : words) {
            for (String token : clip.tokens) {
                if (keyword.equals(token)) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * Choose the recorded clip for a topic. Clips that fit better are more
     * likely; recently played clips are avoided when alternatives exist. The
     * result is always an actual catalog clip (or null when the catalog is
     * empty) — never invented text.
     */
    public static ClipIndex.Clip pick(ClipIndex index, Topic topic, Random rng,
                                      List<String> avoidIds) {
        if (index == null) return null;
        Set<String> avoid = avoidIds == null ? Collections.<String>emptySet()
                : new HashSet<>(avoidIds);
        Random random = rng == null ? new Random() : rng;

        List<ClipIndex.Clip> fresh = new ArrayList<>();
        List<ClipIndex.Clip> stale = new ArrayList<>();
        int freshWeight = 0;
        for (ClipIndex.Clip clip : index.spokenClips()) {
            int weight = score(clip, topic);
            if (weight == 0) continue;
            if (avoid.contains(clip.id)) {
                stale.add(clip);
            } else {
                fresh.add(clip);
                freshWeight += weight;
            }
        }
        if (!fresh.isEmpty()) {
            int roll = Math.floorMod(random.nextInt(), Math.max(1, freshWeight));
            for (ClipIndex.Clip clip : fresh) {
                roll -= score(clip, topic);
                if (roll < 0) return clip;
            }
            return fresh.get(fresh.size() - 1);
        }
        // Nothing topical and fresh: another real clip line beats replaying
        // the same recording again.
        List<ClipIndex.Clip> alternatives = new ArrayList<>();
        for (ClipIndex.Clip clip : index.vocalClips()) {
            if (!avoid.contains(clip.id)) alternatives.add(clip);
        }
        for (ClipIndex.Clip clip : index.spokenClips()) {
            if (!avoid.contains(clip.id)) alternatives.add(clip);
        }
        if (!alternatives.isEmpty()) {
            return alternatives.get(
                    Math.floorMod(random.nextInt(), alternatives.size()));
        }
        // Everything is recent: repeating beats silence.
        if (!stale.isEmpty()) {
            return stale.get(Math.floorMod(random.nextInt(), stale.size()));
        }
        return index.bestMatch("", random, avoidIds);
    }
}
