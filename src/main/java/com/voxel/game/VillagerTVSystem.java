package com.voxel.game;

import com.voxel.entity.VillagerEntity;
import java.util.*;

/**
 * VillagerTVSystem - Manages TV channels and content for the Villager TV block.
 * Inspired by Element Animation's Villager News series.
 * 
 * Channels:
 * 0 = Static/Off-Air
 * 1 = Villager Shopping Network (VSN)
 * 2 = Weather & Time Channel
 * 3 = VNN - Villager News Network
 */
public class VillagerTVSystem {

    public static final int CHANNEL_STATIC = 0;
    public static final int CHANNEL_SHOPPING = 1;
    public static final int CHANNEL_WEATHER = 2;
    public static final int CHANNEL_VNN = 3;
    public static final int NUM_CHANNELS = 4;

    public static final String[] CHANNEL_NAMES = {
        "Static / Off-Air",
        "Villager Shopping Network",
        "Weather & Time",
        "VNN Villager News"
    };

    /** Per-TV state: block position -> active channel */
    private final Map<String, Integer> tvChannels = new HashMap<>();
    
    /** Per-TV: list of villagers watching this TV */
    private final Map<String, List<VillagerEntity>> tvViewers = new HashMap<>();

    // ── VNN News stories ──
    private final List<String> vnnNewsStories = new ArrayList<>();
    private int currentNewsStory = 0;
    private float newsStoryTimer = 0.0f;
    private static final float NEWS_STORY_DURATION = 8.0f;

    // ── Shopping items ──
    private final List<String> shoppingItems = new ArrayList<>();
    private int currentShoppingItem = 0;
    private float shoppingTimer = 0.0f;
    private static final float SHOPPING_ITEM_DURATION = 5.0f;

    public VillagerTVSystem() {
        initNewsStories();
        initShoppingItems();
    }

    private void initNewsStories() {
        vnnNewsStories.add("BREAKING: Creeper spotted near Oak Village! Residents advised to stay indoors.");
        vnnNewsStories.add("Local villager 'Hrmm' wins annual pumpkin growing contest with 43-pound pumpkin.");
        vnnNewsStories.add("Weird Walking Cactus seen wandering desert biome - experts baffled.");
        vnnNewsStories.add("VILLAGER NEWS! The testificate evening news. Hrm. Hrm. Hrm.");
        vnnNewsStories.add("Iron Golem union demands more poppy flowers in workplace. 'We protect, we deserve.'");
        vnnNewsStories.add("Weather forecast: Sunny with a chance of Enderman teleportations. Stay inside after dark.");
        vnnNewsStories.add("New trading hall opens in Plains Village! 'Best deals this side of spawn,' says mayor.");
        vnnNewsStories.add("Zombie siege last night repelled by village walls. 'They never stood a chance,' says guard.");
        vnnNewsStories.add("Economy report: Emerald values stable. Wheat futures up 3 emeralds per stack. Hrm.");
        vnnNewsStories.add("CULTURAL CORNER: The art of the 'Hrmm' - a deep dive into villager communication.");
        vnnNewsStories.add("SPORTS: Annual villager sprint results - fastest time: 3 blocks in 45 seconds. New record!");
        vnnNewsStories.add("MYSTERY: Who keeps moving the village bell? Residents suspect invisible spider. More at 8.");
    }

    private void initShoppingItems() {
        shoppingItems.add("BUY NOW! Genuine Emerald Chunks - only 64 emeralds each! That's right, ONE emerald for 64 emeralds!");
        shoppingItems.add("LIMITED OFFER: Enchanted Stick of Poking! Does absolutely nothing! Only 12 emeralds!");
        shoppingItems.add("VSN EXCLUSIVE: Pre-owned dirt blocks! 'Gently used, slightly grassy.' 2 emeralds per stack!");
        shoppingItems.add("DEAL OF THE DAY: Diamond Hoe! Because why not? Now 50% off! (Still costs 50 emeralds)");
        shoppingItems.add("NEW PRODUCT: Auto-Hrmm-er! Automatically says Hrmm for you! Battery not included.");
        shoppingItems.add("AS SEEN ON TV: The Block-o-Matic 3000! Places blocks automatically! Results may vary.");
    }

    /** Get the channel for a TV at the given position. */
    public int getChannel(int x, int y, int z) {
        String key = posKey(x, y, z);
        return tvChannels.getOrDefault(key, CHANNEL_VNN); // Default to VNN
    }

    /** Set the channel for a TV. */
    public void setChannel(int x, int y, int z, int channel) {
        tvChannels.put(posKey(x, y, z), Math.max(0, Math.min(NUM_CHANNELS - 1, channel)));
    }

    /** Cycle to the next channel. */
    public int nextChannel(int x, int y, int z) {
        int current = getChannel(x, y, z);
        int next = (current + 1) % NUM_CHANNELS;
        setChannel(x, y, z, next);
        return next;
    }

    /** Register a villager as watching this TV. */
    public void addViewer(int x, int y, int z, VillagerEntity villager) {
        String key = posKey(x, y, z);
        tvViewers.computeIfAbsent(key, k -> new ArrayList<>()).add(villager);
    }

    /** Remove a villager from watching. */
    public void removeViewer(int x, int y, int z, VillagerEntity villager) {
        String key = posKey(x, y, z);
        List<VillagerEntity> viewers = tvViewers.get(key);
        if (viewers != null) viewers.remove(villager);
    }

    /** Get viewers for a TV. */
    public List<VillagerEntity> getViewers(int x, int y, int z) {
        return tvViewers.getOrDefault(posKey(x, y, z), Collections.emptyList());
    }

    /** Tick the TV system - advance news stories, shopping items, etc. */
    public void tick(float dt) {
        newsStoryTimer += dt;
        if (newsStoryTimer >= NEWS_STORY_DURATION) {
            newsStoryTimer = 0;
            currentNewsStory = (currentNewsStory + 1) % vnnNewsStories.size();
        }

        shoppingTimer += dt;
        if (shoppingTimer >= SHOPPING_ITEM_DURATION) {
            shoppingTimer = 0;
            currentShoppingItem = (currentShoppingItem + 1) % shoppingItems.size();
        }
    }

    /** Get the current channel display text for rendering on TV screen. */
    public String getChannelDisplay(int channel, float worldTime) {
        switch (channel) {
            case CHANNEL_STATIC:
                return "~~~ OFF AIR ~~~\nPlease stand by...";
            case CHANNEL_SHOPPING:
                return "[VSN - Villager Shopping Network]\n" + shoppingItems.get(currentShoppingItem);
            case CHANNEL_WEATHER:
                return getWeatherDisplay(worldTime);
            case CHANNEL_VNN:
                return "[VNN - Villager News Network]\n" + vnnNewsStories.get(currentNewsStory);
            default:
                return "No Signal";
        }
    }

    /** Get channel name. */
    public String getChannelName(int channel) {
        if (channel < 0 || channel >= CHANNEL_NAMES.length) return "Unknown";
        return CHANNEL_NAMES[channel];
    }

    /** Get the current news story text. */
    public String getCurrentNewsStory() {
        return vnnNewsStories.get(currentNewsStory);
    }

    /** Generate weather display based on world time. */
    private String getWeatherDisplay(float worldTime) {
        float timeOfDay = worldTime % 1440; // 24h cycle
        String timeStr;
        if (timeOfDay < 360) timeStr = "Night ("
            + String.format("%d:%02d", (int)((timeOfDay + 360) / 60), (int)(timeOfDay % 60))
            + ")";
        else if (timeOfDay < 720) timeStr = "Morning ("
            + String.format("%d:%02d", (int)((timeOfDay + 360) / 60) % 12, (int)(timeOfDay % 60))
            + " AM)";
        else if (timeOfDay < 1080) timeStr = "Afternoon ("
            + String.format("%d:%02d", (int)((timeOfDay + 360) / 60) % 12, (int)(timeOfDay % 60))
            + " PM)";
        else timeStr = "Evening ("
            + String.format("%d:%02d", (int)((timeOfDay + 360) / 60) % 12, (int)(timeOfDay % 60))
            + " PM)";

        // Simple weather based on time and some pseudo-randomness
        String weather;
        int weatherSeed = (int)(worldTime / 240) % 10;
        if (weatherSeed < 6) weather = "Clear skies, sunny";
        else if (weatherSeed < 8) weather = "Partly cloudy";
        else weather = "Overcast, possible rain";

        return "=== WEATHER & TIME ===\n"
            + "Time: " + timeStr + "\n"
            + "Weather: " + weather + "\n"
            + "Village forecast: Safe conditions";
    }

    private static String posKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
