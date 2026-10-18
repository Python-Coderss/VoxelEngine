package com.voxel.game;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The TV runtime: eight channels, a deterministic broadcast clock, and one
 * {@link VillagerTVSystem.TvEvent} per scripted line and music cue that comes
 * due on the channel a viewer is actually watching.
 */
public class VillagerTVSystemTest {

    private VillagerTVSystem tv;

    @Before
    public void setUp() {
        tv = new VillagerTVSystem(TvGuide.loadDefault());
    }

    @Test
    public void channelsAreContiguousAndNamed() {
        assertEquals("channel count", 8, tv.channelCount());
        assertEquals(VillagerTVSystem.CHANNEL_VNN, 3);
        assertEquals(VillagerTVSystem.NUM_CHANNELS, tv.channelCount());
        for (int i = 0; i < tv.channelCount(); i++) {
            assertNotNull("channel " + i, tv.getGuide().channel(i));
            assertFalse("channel " + i + " is named", tv.getChannelName(i).isEmpty());
            assertFalse("channel " + i + " has a callsign", tv.getChannelCallsign(i).isEmpty());
        }
        assertEquals("Static / Off-Air", tv.getChannelName(VillagerTVSystem.CHANNEL_STATIC));
        assertEquals("VNN Villager News", tv.getChannelName(VillagerTVSystem.CHANNEL_VNN));
        assertEquals("Hrmm Kitchen", tv.getChannelName(VillagerTVSystem.CHANNEL_KITCHEN));
        assertEquals("Villager Sports Network",
                tv.getChannelName(VillagerTVSystem.CHANNEL_SPORT));
    }

    @Test
    public void aNewTvDefaultsToVnn() {
        assertEquals(VillagerTVSystem.CHANNEL_VNN, tv.getChannel(10, 70, -10));
        tv.setChannel(10, 70, -10, VillagerTVSystem.CHANNEL_MUSIC);
        assertEquals(VillagerTVSystem.CHANNEL_MUSIC, tv.getChannel(10, 70, -10));
        // Channels stay independent per TV.
        assertEquals(VillagerTVSystem.CHANNEL_VNN, tv.getChannel(0, 0, 0));
    }

    @Test
    public void cyclingWrapsThroughEveryChannel() {
        tv.setChannel(0, 0, 0, 0);
        for (int expected = 1; expected < tv.channelCount(); expected++) {
            assertEquals("next channel", expected, tv.nextChannel(0, 0, 0));
        }
        assertEquals("wraps back to off-air", 0, tv.nextChannel(0, 0, 0));
    }

    @Test
    public void channelNumbersAreClampedToTheGuide() {
        tv.setChannel(0, 0, 0, 999);
        assertEquals("clamped to the last channel", tv.channelCount() - 1, tv.getChannel(0, 0, 0));
        tv.setChannel(0, 0, 0, -5);
        assertEquals("clamped to off-air", 0, tv.getChannel(0, 0, 0));
    }

    @Test
    public void displayShowsTheStationTheProgrammeAndTheLine() {
        String display = tv.getChannelDisplay(VillagerTVSystem.CHANNEL_VNN, 0f);
        assertTrue("callsign banner: " + display, display.contains("[VNN]"));
        assertTrue("station name: " + display, display.contains("VNN Villager News"));
        assertTrue("programme name: " + display, display.contains("Now: VNN Nightly"));
        assertTrue("scripted line: " + display,
                display.contains("Da Da Da Daaa Daa Da Da Da Daaa Da Villager News!"));
        assertTrue("music cue: " + display, display.contains("VNN Nightly Theme"));
        assertEquals("no signal", "No Signal",
                tv.getChannelDisplay(99, 0f));
    }

    @Test
    public void tuningInFiresTheCurrentLineOnce() {
        tv.setWatchedChannel(VillagerTVSystem.CHANNEL_VNN);
        List<VillagerTVSystem.TvEvent> events = tv.drainEvents();
        assertEquals("one line on tuning in", 1, events.size());
        assertEquals("VNN Nightly", events.get(0).programme);
        assertNotNull("the opening line plays a theme", events.get(0).cue);
        assertEquals("vnn_theme", events.get(0).cue.getId());
        assertTrue(tv.drainEvents().isEmpty());
    }

    @Test
    public void tickingFiresEachScriptedLineAtItsOffset() {
        tv.setWatchedChannel(VillagerTVSystem.CHANNEL_VNN);
        tv.drainEvents();

        // VNN Nightly scripts its headline 8 seconds in.
        tv.tick(8.5f);
        List<VillagerTVSystem.TvEvent> events = tv.drainEvents();
        assertEquals("one line came due", 1, events.size());
        assertEquals("Breaking News! A local citizen spots a celebrity around the "
                        + "village. Over to Villager #9.... ...Villager #9?",
                events.get(0).line.text);
        assertEquals("headline_sting", events.get(0).cue.getId());

        // Nothing new until the next scripted offset.
        tv.tick(1.0f);
        assertTrue("no line between offsets", tv.drainEvents().isEmpty());

        // Crossing three offsets at once reports all three.
        tv.tick(30f);
        events = tv.drainEvents();
        assertEquals("all intermediate lines fire", 3, events.size());
        assertFalse("each event carries its own script", 
                events.get(0).line.text.equals(events.get(1).line.text));
    }

    @Test
    public void rollingIntoTheNextProgrammeFiresItsOpening() {
        tv.setWatchedChannel(VillagerTVSystem.CHANNEL_VNN);
        tv.drainEvents();
        // VNN Nightly runs 48 seconds; Field Report follows.
        tv.tick(50f);
        List<VillagerTVSystem.TvEvent> events = tv.drainEvents();
        assertFalse("the new programme opens", events.isEmpty());
        assertEquals("Field Report: The Nether", events.get(events.size() - 1).programme);
    }

    @Test
    public void nobodyWatchingMeansNothingPlays() {
        tv.setWatchedChannel(-1);
        tv.tick(120f);
        assertTrue("silent while unwatched", tv.drainEvents().isEmpty());
        assertEquals(-1, tv.getWatchedChannel());
    }

    @Test
    public void musicCueIsOnAirOnlyForItsOwnLength() {
        tv.setWatchedChannel(VillagerTVSystem.CHANNEL_VNN);
        TvMusicCue theme = tv.getMusicCueOnAir(VillagerTVSystem.CHANNEL_VNN);
        assertNotNull("theme is on air at the top of the programme", theme);
        assertEquals("vnn_theme", theme.getId());
        long quietSeconds = (long) Math.ceil(theme.getSeconds()) + 1;
        tv.tick(quietSeconds);
        assertNull("theme stopped once it finished",
                tv.getMusicCueOnAir(VillagerTVSystem.CHANNEL_VNN));
    }

    @Test
    public void weatherChannelKeepsItsLiveTimeAndSkyStrip() {
        String morning = tv.getChannelDisplay(VillagerTVSystem.CHANNEL_WEATHER, 400f);
        assertTrue("live strip: " + morning, morning.contains("Live: Morning"));
        assertTrue("live strip names the sky: " + morning,
                morning.contains("Clear skies, sunny") || morning.contains("cloudy")
                        || morning.contains("Overcast"));
        String evening = tv.getChannelDisplay(VillagerTVSystem.CHANNEL_WEATHER, 1200f);
        assertTrue("live strip follows the world clock: " + evening,
                evening.contains("Live: Evening"));
        assertFalse("the strip changes as time passes", morning.equals(evening));
        // Other channels stay script-only.
        assertFalse(tv.getChannelDisplay(VillagerTVSystem.CHANNEL_VNN, 400f)
                .contains("Live:"));
    }

    @Test
    public void everyChannelBroadcastsAProgramme() {
        for (int channel = 0; channel < tv.channelCount(); channel++) {
            TvGuide.Programme programme = tv.getCurrentProgramme(channel);
            assertNotNull("channel " + channel + " has something on air", programme);
            assertFalse("channel " + channel + " names its programme",
                    programme.name.isEmpty());
        }
    }

    @Test
    public void tickingAdvancesTheBroadcastClock() {
        double before = tv.getClock();
        tv.tick(2.5f);
        assertEquals(before + 2.5, tv.getClock(), 0.0001);
        tv.tick(0f);
        assertEquals("a zero tick does nothing", before + 2.5, tv.getClock(), 0.0001);
    }

    @Test
    public void scriptedLinesCarryTheirOwnVoiceSetting() {
        TvGuide guide = tv.getGuide();
        TvGuide.Line line = guide.channel(VillagerTVSystem.CHANNEL_KITCHEN)
                .programmes.get(0).lines.get(0);
        assertNotNull("kitchen script lines a voice", line.topic);
        assertTrue("kitchen script lines a caption", line.text.length() > 0);
        assertTrue("kitchen script opens with a jingle", line.cueId != null);
    }
}
