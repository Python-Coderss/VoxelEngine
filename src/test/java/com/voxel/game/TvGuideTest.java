package com.voxel.game;

import org.junit.Test;
import villager.voice.ClipScript;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Guards the Villager TV guide: the written schedule must actually be a
 * schedule. Every channel gets a running order, every programme a real running
 * time, every scripted line a place inside its slot and a voice that exists,
 * and every music cue notes that can be rendered.
 */
public class TvGuideTest {

    private static final String GUIDE_PATH = "src/main/resources/voice/tv_guide.json";

    @Test
    public void shippedGuideLoadsAllEightChannels() {
        // loadDefault() silently falls back to the 4-channel built-in guide, so
        // asserting eight channels proves the packaged script document loaded.
        TvGuide guide = TvGuide.loadDefault();
        assertEquals("channels in the shipped guide", 8, guide.channelCount());
        for (int i = 0; i < guide.channelCount(); i++) {
            TvGuide.Channel channel = guide.channel(i);
            assertNotNull("channel " + i, channel);
            assertEquals("ids are contiguous", i, channel.id);
            assertFalse("channel " + i + " has a callsign", channel.callsign.isEmpty());
            assertFalse("channel " + i + " has a name", channel.name.isEmpty());
            assertFalse("channel " + i + " has a running order", channel.programmes.isEmpty());
        }
        assertEquals("off-air", "OFF", guide.channel(0).callsign);
        assertEquals("shopping", "VSN", guide.channel(1).callsign);
        assertEquals("weather", "WX", guide.channel(2).callsign);
        assertEquals("news", "VNN", guide.channel(3).callsign);
        assertEquals("music", "VMC", guide.channel(4).callsign);
        assertEquals("nature", "NAT", guide.channel(5).callsign);
        assertEquals("kitchen", "KITCH", guide.channel(6).callsign);
        assertEquals("sport", "SPORT", guide.channel(7).callsign);
    }

    @Test
    public void everyProgrammeIsWellFormed() {
        TvGuide guide = TvGuide.loadDefault();
        Set<String> programmeNames = new HashSet<>();
        for (TvGuide.Channel channel : guide.channels()) {
            assertTrue("channel " + channel.id + " runs for a positive time",
                    channel.cycleSeconds() > 0.0);
            for (TvGuide.Programme programme : channel.programmes) {
                assertTrue("programme '" + programme.name + "' has a running time",
                        programme.duration > 0.0);
                assertTrue("programme '" + programme.name + "' has a script",
                        !programme.lines.isEmpty());
                assertTrue("programme names are unique per channel",
                        programmeNames.add(channel.id + "/" + programme.name));
                double previous = -1.0;
                for (TvGuide.Line line : programme.lines) {
                    assertTrue("'" + programme.name + "' lines run forward",
                            line.at >= previous);
                    assertTrue("'" + programme.name + "' line fits its slot",
                            line.at >= 0.0 && line.at <= programme.duration);
                    assertTrue("each line speaks or plays a cue",
                            line.isSpoken() || line.cueId != null);
                    previous = line.at;
                }
            }
        }
    }

    @Test
    public void everyCueIsRenderableAndReferenced() {
        TvGuide guide = TvGuide.loadDefault();
        assertFalse("the guide defines music cues", guide.cues().isEmpty());
        for (TvMusicCue cue : guide.cues().values()) {
            assertTrue(cue.getNoteCount() > 0);
            assertTrue("cue '" + cue.getId() + "' has a tempo", cue.getBpm() > 0.0);
            assertTrue("cue '" + cue.getId() + "' has a length", cue.getSeconds() > 0.0);
            // The cue must survive the trip to a singable note sequence.
            assertEquals("cue '" + cue.getId() + "' keeps its notes",
                    cue.getNoteCount(), cue.toSequence().getNotes().size());
            assertFalse("cue '" + cue.getId() + "' has a cache key",
                    cue.cacheKey().isEmpty());
        }
        // Every cue named by a line must exist, and at least one line must use
        // each of the kinds the station actually needs.
        Set<String> used = new HashSet<>();
        for (TvGuide.Channel channel : guide.channels()) {
            for (TvGuide.Programme programme : channel.programmes) {
                for (TvGuide.Line line : programme.lines) {
                    if (line.cueId == null) continue;
                    TvMusicCue cue = guide.cue(line.cueId);
                    assertNotNull("line in '" + programme.name + "' names cue '"
                            + line.cueId + "'", cue);
                    used.add(cue.getId());
                }
            }
        }
        assertTrue("the schedule actually plays cues", used.size() >= 8);
    }

    @Test
    public void everySpokenLineNamesARealVoice() {
        TvGuide guide = TvGuide.loadDefault();
        Set<String> topics = new HashSet<>();
        for (ClipScript.Topic topic : ClipScript.Topic.values()) topics.add(topic.name());
        for (TvGuide.Channel channel : guide.channels()) {
            for (TvGuide.Programme programme : channel.programmes) {
                for (TvGuide.Line line : programme.lines) {
                    if (line.topic != null) {
                        assertTrue("line in '" + programme.name + "' uses a real situation: "
                                + line.topic, topics.contains(line.topic));
                    }
                    assertTrue("line emotion is a supported delivery: " + line.emotion,
                            line.emotion.equals("neutral") || line.emotion.equals("happy")
                                    || line.emotion.equals("sad") || line.emotion.equals("angry")
                                    || line.emotion.equals("scared"));
                    assertTrue("line speed is positive", line.speed > 0.0);
                }
            }
        }
    }

    @Test
    public void slotsLoopAndReportTheirOffset() {
        TvGuide guide = TvGuide.loadDefault();
        TvGuide.Channel news = guide.channel(3);
        double cycle = news.cycleSeconds();
        assertTrue(cycle > 0.0);
        for (double t = 0.0; t < cycle; t += 3.0) {
            assertEquals("programme is periodic",
                    news.programmeAt(t), news.programmeAt(t + cycle));
            assertEquals("offset is periodic",
                    news.offsetIntoProgramme(t), news.offsetIntoProgramme(t + cycle), 0.002);
            double offset = news.offsetIntoProgramme(t);
            assertTrue("offset inside the slot", offset >= 0.0
                    && offset <= news.programmeAt(t).duration);
        }
        assertNull("out-of-range channel", guide.channel(99));
        assertNull("unknown cue", guide.cue("no_such_cue"));
    }

    @Test
    public void malformedGuidesAreRejected() {
        assertThrows("empty", "");
        assertThrows("no channels", "{\"version\":1,\"cues\":[],\"channels\":[]}");
        assertThrows("line past the end of its slot",
                "{\"channels\":[{\"id\":0,\"callsign\":\"X\",\"name\":\"X\","
                        + "\"programmes\":[{\"name\":\"P\",\"duration\":10,"
                        + "\"lines\":[{\"at\":40,\"text\":\"hi\"}]}]}]}");
        assertThrows("line names an unknown cue",
                "{\"channels\":[{\"id\":0,\"callsign\":\"X\",\"name\":\"X\","
                        + "\"programmes\":[{\"name\":\"P\",\"duration\":10,"
                        + "\"lines\":[{\"at\":0,\"text\":\"hi\",\"cue\":\"ghost\"}]}]}]}");
        assertThrows("non-contiguous channel ids",
                "{\"channels\":[{\"id\":0,\"callsign\":\"X\",\"name\":\"X\","
                        + "\"programmes\":[{\"name\":\"P\",\"duration\":10,"
                        + "\"lines\":[{\"at\":0,\"text\":\"hi\"}]}]},"
                        + "{\"id\":5,\"callsign\":\"Y\",\"name\":\"Y\","
                        + "\"programmes\":[{\"name\":\"Q\",\"duration\":10,"
                        + "\"lines\":[{\"at\":0,\"text\":\"hi\"}]}]}]}");
        assertThrows("cue with no notes",
                "{\"cues\":[{\"id\":\"a\",\"name\":\"A\",\"notes\":[]}],"
                        + "\"channels\":[{\"id\":0,\"callsign\":\"X\",\"name\":\"X\","
                        + "\"programmes\":[{\"name\":\"P\",\"duration\":10,"
                        + "\"lines\":[{\"at\":0,\"text\":\"hi\"}]}]}]}");
    }

    @Test
    public void builtinFallbackIsAlwaysValid() {
        TvGuide guide = TvGuide.builtin();
        assertTrue("fallback still broadcasts", guide.channelCount() >= 4);
        for (TvGuide.Channel channel : guide.channels()) {
            assertFalse(channel.programmes.isEmpty());
            assertTrue(channel.cycleSeconds() > 0.0);
        }
        assertNotNull("fallback keeps the news channel", guide.channel(3));
        assertTrue("fallback keeps a news theme cue", guide.cues().containsKey("vnn_theme"));
    }

    @Test
    public void theGuideFileIsPresent() {
        assumeTrue("guide asset present", new File(GUIDE_PATH).isFile());
        try {
            String json = new String(Files.readAllBytes(new File(GUIDE_PATH).toPath()),
                    StandardCharsets.UTF_8);
            assertEquals("parses to the shipped channel count",
                    8, TvGuide.parse(json).channelCount());
        } catch (Exception error) {
            throw new AssertionError("guide asset must parse: " + error, error);
        }
    }

    private static void assertThrows(String what, String json) {
        try {
            TvGuide.parse(json);
            throw new AssertionError("expected '" + what + "' to be rejected");
        } catch (java.io.IOException expected) {
            // Correct: malformed guides must not reach the broadcast path.
        }
    }
}
