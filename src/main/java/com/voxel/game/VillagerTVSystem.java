package com.voxel.game;

import com.voxel.entity.VillagerEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Villager TV — manages the channels, the broadcast clock and what each screen
 * shows. The channels, programmes, scripts and music cues themselves live in
 * {@link TvGuide} (loaded from {@code voice/tv_guide.json}); this class only
 * decides <em>what is on air right now</em>.
 *
 * <p>Eight channels ship: Off-Air test card, the Villager Shopping Network,
 * Weather &amp; Time, VNN Villager News, the Villager Music Channel, Villager
 * Geographic, Hrmm Kitchen and the Villager Sports Network. Channels 0-3 keep
 * their original numbers so saved worlds and the {@code /tv} command still
 * mean what they always did.</p>
 *
 * <p>Playback is event driven: each tick the system advances its clock and, for
 * the channel somebody is actually watching, emits a {@link TvEvent} for every
 * scripted line and music cue that came due. {@code Main} turns those into voice
 * clips and cue playback — this class never touches audio, so it stays pure and
 * unit-testable.</p>
 */
public class VillagerTVSystem {

    public static final int CHANNEL_STATIC = 0;
    public static final int CHANNEL_SHOPPING = 1;
    public static final int CHANNEL_WEATHER = 2;
    public static final int CHANNEL_VNN = 3;
    public static final int CHANNEL_MUSIC = 4;
    public static final int CHANNEL_NATURE = 5;
    public static final int CHANNEL_KITCHEN = 6;
    public static final int CHANNEL_SPORT = 7;
    /** Channels in the guide shipped with the game. */
    public static final int NUM_CHANNELS = 8;

    /** Callsigns / names, kept for tooling that predates the guide. */
    public static final String[] CHANNEL_NAMES = {
        "Static / Off-Air",
        "Villager Shopping Network",
        "Weather & Time",
        "VNN Villager News",
        "Villager Music Channel",
        "Villager Geographic",
        "Hrmm Kitchen",
        "Villager Sports Network"
    };

    /** Per-TV state: block position -> active channel. */
    private final Map<String, Integer> tvChannels = new HashMap<>();
    private java.util.function.IntConsumer channelChangeListener;

    /** Per-TV: list of villagers watching this TV. */
    private final Map<String, List<VillagerEntity>> tvViewers = new HashMap<>();

    private final TvGuide guide;

    /** Broadcast clock in seconds, advanced by {@link #tick(float)}. */
    private double clock;
    /** Channel the player is watching, or -1 when nobody is watching. */
    private int watchedChannel = -1;
    private TvGuide.Programme watchedProgramme;
    private double watchedOffset;
    private final List<TvEvent> pendingEvents = new ArrayList<>();

    public VillagerTVSystem() {
        this(TvGuide.loadDefault());
    }

    /** Build the system around an explicit guide (tests / custom schedules). */
    public VillagerTVSystem(TvGuide guide) {
        this.guide = guide == null ? TvGuide.builtin() : guide;
    }

    /** One due line and/or music cue on the watched channel. */
    public static final class TvEvent {
        public final int channel;
        public final String programme;
        public final TvGuide.Line line;
        public final TvMusicCue cue;

        TvEvent(int channel, String programme, TvGuide.Line line, TvMusicCue cue) {
            this.channel = channel;
            this.programme = programme;
            this.line = line;
            this.cue = cue;
        }

        @Override
        public String toString() {
            return "TvEvent[" + channel + " " + programme + " line="
                    + (line == null ? "-" : line.text) + " cue="
                    + (cue == null ? "-" : cue.getId()) + "]";
        }
    }

    public TvGuide getGuide() { return guide; }

    /** Number of channels the current guide defines. */
    public int channelCount() { return guide.channelCount(); }

    /** Get the channel for a TV at the given position. */
    public int getChannel(int x, int y, int z) {
        String key = posKey(x, y, z);
        return tvChannels.getOrDefault(key, CHANNEL_VNN); // Default to VNN
    }

    /** Install an optional listener for every channel assignment, including tooling/AI paths. */
    public void setChannelChangeListener(java.util.function.IntConsumer listener) {
        this.channelChangeListener = listener;
    }

    /** Set the channel for a TV. */
    public void setChannel(int x, int y, int z, int channel) {
        int selected = Math.max(0, Math.min(channelCount() - 1, channel));
        String key = posKey(x, y, z);
        Integer previous = tvChannels.put(key, selected);
        if (channelChangeListener != null && (previous == null || previous.intValue() != selected)) {
            channelChangeListener.accept(selected);
        }
    }

    /** Cycle to the next channel. */
    public int nextChannel(int x, int y, int z) {
        int current = getChannel(x, y, z);
        int next = (current + 1) % channelCount();
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
        return tvViewers.getOrDefault(posKey(x, y, z), Collections.<VillagerEntity>emptyList());
    }

    /**
     * Tune the broadcast to the channel somebody is watching, or pass -1 when
     * nobody is. Tuning in does not replay everything already broadcast: the
     * current line is fired once so the screen and the voice agree, then the
     * schedule runs forward from there.
     */
    public void setWatchedChannel(int channel) {
        int selected = channel < 0 ? -1 : Math.max(0, Math.min(channelCount() - 1, channel));
        if (selected == watchedChannel) return;
        watchedChannel = selected;
        cachedDisplayChannel = -1;
        if (watchedChannel < 0) {
            watchedProgramme = null;
            watchedOffset = 0.0;
            return;
        }
        TvGuide.Channel ch = guide.channel(watchedChannel);
        if (ch == null) {
            watchedProgramme = null;
            watchedOffset = 0.0;
            return;
        }
        watchedProgramme = ch.programmeAt(clock);
        watchedOffset = ch.offsetIntoProgramme(clock);
        if (watchedProgramme != null) {
            TvGuide.Line current = currentLine(watchedProgramme, watchedOffset);
            if (current != null) fire(watchedProgramme, current);
        }
    }

    public int getWatchedChannel() { return watchedChannel; }

    /** Seconds on the broadcast clock. */
    public double getClock() { return clock; }

    /** Take the lines and cues that came due since the last drain. */
    public List<TvEvent> drainEvents() {
        if (pendingEvents.isEmpty()) return Collections.emptyList();
        List<TvEvent> drained = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        return drained;
    }

    /** Tick the broadcast clock and collect anything that came due. */
    public void tick(float dt) {
        if (dt <= 0f) return;
        clock += dt;
        if (watchedChannel < 0) return;
        TvGuide.Channel ch = guide.channel(watchedChannel);
        if (ch == null) return;
        TvGuide.Programme programme = ch.programmeAt(clock);
        double offset = ch.offsetIntoProgramme(clock);
        if (programme == null) return;

        if (programme != watchedProgramme || offset < watchedOffset) {
            // The slot rolled over: the new programme's opening lines are due.
            watchedProgramme = programme;
            watchedOffset = 0.0;
            for (TvGuide.Line line : programme.lines) {
                if (line.at <= offset) fire(programme, line);
            }
        } else {
            for (TvGuide.Line line : programme.lines) {
                if (line.at > watchedOffset && line.at <= offset) fire(programme, line);
            }
        }
        watchedOffset = offset;
    }

    private void fire(TvGuide.Programme programme, TvGuide.Line line) {
        TvMusicCue cue = line.cueId == null ? null : guide.cue(line.cueId);
        pendingEvents.add(new TvEvent(watchedChannel, programme.name, line, cue));
    }

    private static TvGuide.Line currentLine(TvGuide.Programme programme, double offset) {
        TvGuide.Line best = null;
        for (TvGuide.Line line : programme.lines) {
            if (line.at <= offset) best = line;
        }
        return best;
    }

    /** The programme on air on a channel right now, or null. */
    public TvGuide.Programme getCurrentProgramme(int channel) {
        TvGuide.Channel ch = guide.channel(channel);
        return ch == null ? null : ch.programmeAt(clock);
    }

    /** The line the channel is currently on, or null. */
    public TvGuide.Line getCurrentLine(int channel) {
        TvGuide.Channel ch = guide.channel(channel);
        if (ch == null) return null;
        TvGuide.Programme programme = ch.programmeAt(clock);
        return programme == null ? null : currentLine(programme, ch.offsetIntoProgramme(clock));
    }

    /**
     * The music cue still sounding on a channel, or null. A cue is considered
     * on air for its own length after the line that started it.
     */
    public TvMusicCue getMusicCueOnAir(int channel) {
        TvGuide.Channel ch = guide.channel(channel);
        if (ch == null) return null;
        TvGuide.Programme programme = ch.programmeAt(clock);
        if (programme == null) return null;
        double offset = ch.offsetIntoProgramme(clock);
        TvGuide.Line best = null;
        for (TvGuide.Line line : programme.lines) {
            if (line.at <= offset && line.cueId != null) best = line;
        }
        if (best == null) return null;
        TvMusicCue cue = guide.cue(best.cueId);
        if (cue == null) return null;
        return offset - best.at <= cue.getSeconds() ? cue : null;
    }

    /** Banner tint for a channel (ARGB), so each station reads differently. */
    public int getChannelTint(int channel) {
        TvGuide.Channel ch = guide.channel(channel);
        return ch == null ? 0xFFB0B0B0 : ch.tint;
    }

    private int cachedDisplayChannel = -1;
    private double cachedDisplaySecond = Double.NaN;
    private int cachedWorldMinute = Integer.MIN_VALUE;
    private String cachedDisplay = "";

    /** Get the current channel display text for rendering on the TV screen. */
    public String getChannelDisplay(int channel, float worldTime) {
        // The HUD asks for this every frame; only rebuild when the broadcast
        // second (or, for the live strip, the world minute) ticks over so a wall
        // of screens cannot thrash the string builder.
        double second = Math.floor(clock);
        int minute = (int) (worldTime / 60f);
        if (channel == cachedDisplayChannel && second == cachedDisplaySecond
                && minute == cachedWorldMinute) {
            return cachedDisplay;
        }
        TvGuide.Channel ch = guide.channel(channel);
        String text;
        if (ch == null) {
            text = "No Signal";
        } else {
            StringBuilder out = new StringBuilder();
            out.append('[').append(ch.callsign).append("] ").append(ch.name);
            TvGuide.Programme programme = ch.programmeAt(clock);
            if (programme != null) {
                out.append('\n').append("Now: ").append(programme.name);
                TvGuide.Line line = currentLine(programme, ch.offsetIntoProgramme(clock));
                if (line != null && !line.text.isEmpty()) {
                    out.append('\n').append(line.text);
                }
            } else {
                out.append('\n').append(ch.tagline);
            }
            TvMusicCue cue = getMusicCueOnAir(channel);
            if (cue != null) {
                out.append('\n').append("♪ ").append(cue.getName());
            }
            // The weather station keeps a live data strip under its script, so
            // the channel still tells you the time and the sky right now.
            if (ch.id == CHANNEL_WEATHER) {
                out.append('\n').append(liveWeatherStrip(worldTime));
            }
            text = out.toString();
        }
        cachedDisplayChannel = channel;
        cachedDisplaySecond = second;
        cachedWorldMinute = minute;
        cachedDisplay = text;
        return text;
    }

    /** Present-tense time and sky, derived from the world clock. */
    static String liveWeatherStrip(float worldTime) {
        float timeOfDay = ((worldTime % 1440f) + 1440f) % 1440f;
        int hours24 = (int) (timeOfDay / 60f);
        int minutes = (int) (timeOfDay % 60f);
        String period = hours24 < 6 ? "Night"
                : hours24 < 12 ? "Morning"
                : hours24 < 18 ? "Afternoon" : "Evening";
        int hour12 = hours24 % 12;
        if (hour12 == 0) hour12 = 12;
        String meridiem = hours24 < 12 ? "AM" : "PM";
        int seed = (int) (worldTime / 240f) % 10;
        String sky = seed < 6 ? "Clear skies, sunny"
                : seed < 8 ? "Partly cloudy" : "Overcast, possible rain";
        return String.format(java.util.Locale.ROOT, "Live: %s %d:%02d %s | %s",
                period, hour12, minutes, meridiem, sky);
    }

    /** Get channel name. */
    public String getChannelName(int channel) {
        TvGuide.Channel ch = guide.channel(channel);
        if (ch != null) return ch.name;
        // Fall back to the shipped names for out-of-range ids so tools still
        // print something sensible for a stale channel number.
        if (channel >= 0 && channel < CHANNEL_NAMES.length) return CHANNEL_NAMES[channel];
        return "Unknown";
    }

    /** Callsign for a channel, e.g. {@code VNN}. */
    public String getChannelCallsign(int channel) {
        TvGuide.Channel ch = guide.channel(channel);
        return ch == null ? "---" : ch.callsign;
    }

    /** Get the current news story text. */
    public String getCurrentNewsStory() {
        TvGuide.Line line = getCurrentLine(CHANNEL_VNN);
        return line == null ? "" : line.text;
    }

    private static String posKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
