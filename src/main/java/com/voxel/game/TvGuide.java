package com.voxel.game;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Villager TV guide: a written, editable broadcast schedule.
 *
 * <p>Every channel has a callsign and an ordered list of programmes; every
 * programme has a running time and a list of scripted lines placed at an offset
 * into it. A line carries the caption the screen shows, the voice it should be
 * spoken in, and the music cue that plays under or after it.</p>
 *
 * <p>The schedule lives in {@code src/main/resources/voice/tv_guide.json} so the
 * scripts can be rewritten without touching Java. {@link #builtin()} is a small
 * last-resort schedule used only when that asset is missing, so a packaging
 * mistake can never leave the TV with nothing to broadcast.</p>
 *
 * <p>Everything here is pure data plus pure lookups — no audio device, no clock.
 * {@link VillagerTVSystem} supplies the time.</p>
 */
public final class TvGuide {

    public static final String DEFAULT_RESOURCE = "voice/tv_guide.json";
    /** Property that redirects the guide to an external file (tools/tests). */
    public static final String PATH_PROPERTY = "voxel.tv.guide";

    /** One scripted line of a programme. */
    public static final class Line {
        /** Seconds into the programme at which the line is spoken. */
        public final double at;
        /** Caption text; also the free-text hint used to pick a recording. */
        public final String text;
        /** Exact recorded clip id from {@code clips_index.json}, or null. */
        public final String clipId;
        /** {@link villager.voice.ClipScript.Topic} name used when no clip is fixed. */
        public final String topic;
        /** Delivery mood for the recorded voice. */
        public final String emotion;
        /** Playback speed for this line. */
        public final double speed;
        /** Semitone offset for this line. */
        public final double pitch;
        /** Music cue id that starts with this line, or null. */
        public final String cueId;

        Line(double at, String text, String clipId, String topic, String emotion,
             double speed, double pitch, String cueId) {
            this.at = at;
            this.text = text == null ? "" : text;
            this.clipId = clipId == null || clipId.trim().isEmpty() ? null : clipId.trim();
            this.topic = topic == null || topic.trim().isEmpty() ? null : topic.trim();
            this.emotion = emotion == null || emotion.trim().isEmpty()
                    ? "neutral" : emotion.trim().toLowerCase(Locale.ROOT);
            this.speed = speed;
            this.pitch = pitch;
            this.cueId = cueId == null || cueId.trim().isEmpty() ? null : cueId.trim();
        }

        /** True when this line should be spoken by a recorded clip. */
        public boolean isSpoken() {
            return !text.isEmpty() || clipId != null || topic != null;
        }
    }

    /** One programme in a channel's running order. */
    public static final class Programme {
        public final String name;
        /** Running time in seconds; the slot loops once this much has elapsed. */
        public final double duration;
        public final List<Line> lines;

        Programme(String name, double duration, List<Line> lines) {
            this.name = name == null ? "Untitled" : name;
            this.duration = duration;
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        }

        /** Scripted lines whose offset falls inside {@code elapsed} seconds. */
        public List<Line> linesAt(double elapsed) {
            List<Line> due = new ArrayList<>();
            for (Line line : lines) {
                if (line.at <= elapsed) due.add(line);
            }
            return due;
        }
    }

    /** A broadcast channel. */
    public static final class Channel {
        public final int id;
        public final String callsign;
        public final String name;
        public final String tagline;
        /** ARGB banner tint for the TV screen. */
        public final int tint;
        public final List<Programme> programmes;

        Channel(int id, String callsign, String name, String tagline, int tint,
                List<Programme> programmes) {
            this.id = id;
            this.callsign = callsign == null ? "" : callsign;
            this.name = name == null ? "Channel " + id : name;
            this.tagline = tagline == null ? "" : tagline;
            this.tint = tint;
            this.programmes = Collections.unmodifiableList(new ArrayList<>(programmes));
        }

        /** Total time for one pass through the running order, in seconds. */
        public double cycleSeconds() {
            double total = 0.0;
            for (Programme programme : programmes) total += programme.duration;
            return total;
        }

        /** The programme on air at {@code elapsed} seconds into the cycle. */
        public Programme programmeAt(double elapsed) {
            if (programmes.isEmpty()) return null;
            double cycle = cycleSeconds();
            double time = cycle <= 0.0 ? 0.0 : Math.floorMod((long) (elapsed * 1000.0),
                    (long) (cycle * 1000.0)) / 1000.0;
            double cursor = 0.0;
            for (Programme programme : programmes) {
                if (time < cursor + programme.duration) return programme;
                cursor += programme.duration;
            }
            return programmes.get(programmes.size() - 1);
        }

        /** Seconds elapsed inside the programme on air at {@code elapsed}. */
        public double offsetIntoProgramme(double elapsed) {
            if (programmes.isEmpty()) return 0.0;
            double cycle = cycleSeconds();
            double time = cycle <= 0.0 ? 0.0 : Math.floorMod((long) (elapsed * 1000.0),
                    (long) (cycle * 1000.0)) / 1000.0;
            double cursor = 0.0;
            for (Programme programme : programmes) {
                if (time < cursor + programme.duration) return time - cursor;
                cursor += programme.duration;
            }
            return 0.0;
        }
    }

    private final List<Channel> channels;
    private final Map<String, TvMusicCue> cues;
    private final int version;

    private TvGuide(int version, List<Channel> channels, Map<String, TvMusicCue> cues) {
        this.version = version;
        this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
        this.cues = Collections.unmodifiableMap(new HashMap<>(cues));
    }

    /** Load the packaged guide, falling back to {@link #builtin()}. */
    public static TvGuide loadDefault() {
        String configured = System.getProperty(PATH_PROPERTY);
        try {
            if (configured != null && !configured.trim().isEmpty()) {
                return parse(new String(Files.readAllBytes(Paths.get(configured)),
                        StandardCharsets.UTF_8));
            }
            Path dev = Paths.get("src", "main", "resources", "voice", "tv_guide.json");
            if (Files.isRegularFile(dev)) {
                return parse(new String(Files.readAllBytes(dev), StandardCharsets.UTF_8));
            }
            InputStream in = TvGuide.class.getResourceAsStream("/" + DEFAULT_RESOURCE);
            if (in != null) {
                try (InputStream stream = in) {
                    return parse(new String(readAll(stream), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception error) {
            System.err.println("VillagerTVSystem: could not load " + DEFAULT_RESOURCE
                    + " (" + error + "); using the built-in guide");
        }
        return builtin();
    }

    /** Parse a guide document; throws {@link IOException} on malformed input. */
    public static TvGuide parse(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("tv guide is empty");
        }
        try {
            JSONObject root = new JSONObject(json);
            int version = root.optInt("version", 1);
            if (version < 1) throw new IOException("version must be positive");

            Map<String, TvMusicCue> cues = new HashMap<>();
            JSONArray cueArray = root.optJSONArray("cues");
            if (cueArray != null) {
                for (int i = 0; i < cueArray.length(); i++) {
                    TvMusicCue cue = parseCue(cueArray.getJSONObject(i));
                    if (cues.put(cue.getId(), cue) != null) {
                        throw new IOException("duplicate cue id: " + cue.getId());
                    }
                }
            }

            JSONArray channelArray = root.getJSONArray("channels");
            if (channelArray.length() == 0) {
                throw new IOException("guide must define at least one channel");
            }
            List<Channel> channels = new ArrayList<>();
            Set<Integer> ids = new HashSet<>();
            for (int i = 0; i < channelArray.length(); i++) {
                Channel channel = parseChannel(channelArray.getJSONObject(i), cues);
                if (!ids.add(channel.id)) {
                    throw new IOException("duplicate channel id: " + channel.id);
                }
                channels.add(channel);
            }
            channels.sort((a, b) -> Integer.compare(a.id, b.id));
            // Ids must be 0..n-1 so CHANNEL_* constants and /tv stay contiguous.
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).id != i) {
                    throw new IOException("channel ids must be contiguous from 0; found "
                            + channels.get(i).id + " at position " + i);
                }
            }
            return new TvGuide(version, channels, cues);
        } catch (JSONException error) {
            throw new IOException("invalid tv guide JSON", error);
        }
    }

    private static TvMusicCue parseCue(JSONObject json) throws IOException {
        String id = json.getString("id");
        double bpm = json.optDouble("bpm", 120.0);
        float gain = (float) json.optDouble("gain", 1.0);
        JSONArray noteArray = json.getJSONArray("notes");
        if (noteArray.length() == 0) throw new IOException("cue '" + id + "' has no notes");
        int[] pitches = new int[noteArray.length()];
        double[] starts = new double[noteArray.length()];
        double[] durations = new double[noteArray.length()];
        for (int i = 0; i < noteArray.length(); i++) {
            JSONArray note = noteArray.getJSONArray(i);
            if (note.length() < 3) {
                throw new IOException("cue '" + id + "' note " + i
                        + " needs [pitch, startBeat, durationBeats]");
            }
            pitches[i] = note.getInt(0);
            starts[i] = note.getDouble(1);
            durations[i] = note.getDouble(2);
        }
        return TvMusicCue.of(id, json.optString("name", id),
                TvMusicCue.kindOf(json.optString("kind", "STING")),
                bpm, gain, pitches, starts, durations);
    }

    private static Channel parseChannel(JSONObject json, Map<String, TvMusicCue> cues)
            throws IOException {
        int id = json.getInt("id");
        JSONArray programmeArray = json.getJSONArray("programmes");
        if (programmeArray.length() == 0) {
            throw new IOException("channel " + id + " has no programmes");
        }
        List<Programme> programmes = new ArrayList<>();
        for (int i = 0; i < programmeArray.length(); i++) {
            JSONObject p = programmeArray.getJSONObject(i);
            String name = p.optString("name", "Programme " + i);
            double duration = p.optDouble("duration", 0.0);
            if (!(duration > 0.0)) {
                throw new IOException("channel " + id + " programme '" + name
                        + "' needs a positive duration");
            }
            JSONArray lineArray = p.getJSONArray("lines");
            List<Line> lines = new ArrayList<>();
            double previous = -1.0;
            for (int l = 0; l < lineArray.length(); l++) {
                JSONObject o = lineArray.getJSONObject(l);
                Line line = new Line(
                        o.optDouble("at", 0.0),
                        o.optString("text", ""),
                        o.optString("clip", null),
                        o.optString("topic", null),
                        o.optString("emotion", "neutral"),
                        o.optDouble("speed", 1.0),
                        o.optDouble("pitch", 0.0),
                        o.optString("cue", null));
                if (line.at < 0.0 || line.at > duration) {
                    throw new IOException("channel " + id + " programme '" + name
                            + "' line " + l + " falls outside the slot at " + line.at + "s");
                }
                if (line.at < previous) {
                    throw new IOException("channel " + id + " programme '" + name
                            + "' lines must be ordered by 'at' (line " + l + ")");
                }
                if (!line.isSpoken() && line.cueId == null) {
                    throw new IOException("channel " + id + " programme '" + name
                            + "' line " + l + " neither speaks nor plays a cue");
                }
                if (line.cueId != null && !cues.containsKey(line.cueId)) {
                    throw new IOException("channel " + id + " programme '" + name
                            + "' line " + l + " references unknown cue '" + line.cueId + "'");
                }
                previous = line.at;
                lines.add(line);
            }
            programmes.add(new Programme(name, duration, lines));
        }
        return new Channel(id, json.optString("callsign", "CH" + id),
                json.optString("name", null), json.optString("tagline", ""),
                parseTint(json.optString("tint", null)), programmes);
    }

    private static int parseTint(String value) {
        if (value == null || value.trim().isEmpty()) return 0xFFB0B0B0;
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            int rgb = (int) Long.parseLong(hex, 16);
            return 0xFF000000 | (rgb & 0xFFFFFF);
        } catch (NumberFormatException ignored) {
            return 0xFFB0B0B0;
        }
    }

    /** A tiny, always-available schedule used when the JSON asset is missing. */
    public static TvGuide builtin() {
        Map<String, TvMusicCue> cues = new HashMap<>();
        cues.put("vnn_theme", TvMusicCue.of("vnn_theme", "VNN Nightly Theme",
                TvMusicCue.Kind.THEME, 132.0, 0.9f,
                new int[]{67, 69, 71, 72, 71, 67},
                new double[]{0, 0.5, 1.0, 1.5, 2.5, 3.0},
                new double[]{0.5, 0.5, 0.5, 1.0, 0.5, 2.0}));
        cues.put("station_ident", TvMusicCue.of("station_ident", "Station Ident",
                TvMusicCue.Kind.IDENT, 120.0, 0.8f,
                new int[]{60, 64, 67}, new double[]{0, 0.25, 0.5},
                new double[]{0.25, 0.25, 0.75}));

        List<Programme> staticOrder = new ArrayList<>();
        staticOrder.add(new Programme("Test Card", 20.0, Arrays.asList(
                new Line(0.0, "Please stand by. Normal service will resume shortly.",
                        null, "SMALLTALK", "neutral", 1.0, 0.0, "station_ident"),
                new Line(8.0, "Hrmm. Hrmm. Testing, testing.",
                        null, "SMALLTALK", "neutral", 1.0, 0.0, null))));
        List<Programme> newsOrder = new ArrayList<>();
        newsOrder.add(new Programme("VNN Nightly", 30.0, Arrays.asList(
                new Line(0.0, "This is VNN, the Villager News Network.",
                        null, "GREETING", "happy", 1.0, 0.0, "vnn_theme"),
                new Line(6.0, "Our top story: a creeper was seen near the village walls.",
                        null, "PANIC", "scared", 1.0, 0.0, null))));
        List<Programme> shoppingOrder = new ArrayList<>();
        shoppingOrder.add(new Programme("Emerald Hour", 24.0, Arrays.asList(
                new Line(0.0, "Welcome to the Villager Shopping Network!",
                        null, "GREETING", "happy", 1.0, 0.0, "station_ident"),
                new Line(5.0, "Best trades in the village, as far as you're aware.",
                        null, "ADMIRE", "happy", 1.0, 0.0, null))));
        List<Programme> weatherOrder = new ArrayList<>();
        weatherOrder.add(new Programme("Weather Watch", 24.0, Arrays.asList(
                new Line(0.0, "Weather and time, every hour on the hour.",
                        null, "GREETING", "happy", 1.0, 0.0, "station_ident"),
                new Line(6.0, "Safe conditions. Rain later, so walk very fast.",
                        null, "SMALLTALK", "neutral", 1.0, 0.0, null))));

        List<Channel> channels = new ArrayList<>();
        channels.add(new Channel(0, "OFF", "Static / Off-Air",
                "Please stand by", 0xFF8A8A8A, staticOrder));
        channels.add(new Channel(1, "VSN", "Villager Shopping Network",
                "Deals, hrmm, now", 0xFFE0B050, shoppingOrder));
        channels.add(new Channel(2, "WX", "Weather & Time",
                "Every hour on the hour", 0xFF60A0E0, weatherOrder));
        channels.add(new Channel(3, "VNN", "VNN Villager News",
                "The testificate evening news", 0xFFC05050, newsOrder));
        return new TvGuide(1, channels, cues);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    public int getVersion() { return version; }
    public int channelCount() { return channels.size(); }
    public List<Channel> channels() { return channels; }
    public Map<String, TvMusicCue> cues() { return cues; }

    /** Channel with the given id, or null when the id is out of range. */
    public Channel channel(int id) {
        return id < 0 || id >= channels.size() ? null : channels.get(id);
    }

    /** Music cue by id, or null. */
    public TvMusicCue cue(String id) {
        return id == null ? null : cues.get(id);
    }
}
