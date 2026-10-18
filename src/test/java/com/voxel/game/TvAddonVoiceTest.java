package com.voxel.game;

import org.junit.Test;
import villager.voice.ClipIndex;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Guards the Villager TV's voice source.
 *
 * <p>The station is only licensed to broadcast one collection: the Villager
 * News 1.0 Add-On recordings the index marks {@code kind="addon"}. These tests
 * pin that down from both ends — the runtime view must exclude every other
 * collection, and every scripted line must be pinned to a take from the addon
 * collection (with the caption being what that take actually says).</p>
 */
public class TvAddonVoiceTest {

    private static final String INDEX_PATH = "src/main/resources/voice/clips_index.json";

    private static ClipIndex addonOnly() throws java.io.IOException {
        assumeTrue("clip index present", new File(INDEX_PATH).isFile());
        return ClipIndex.loadDefault().restrictedToKind("addon");
    }

    @Test
    public void theAddonViewExcludesEveryOtherCollection() throws Exception {
        assumeTrue("clip index present", new File(INDEX_PATH).isFile());
        ClipIndex full = ClipIndex.loadDefault();
        ClipIndex addon = full.restrictedToKind("addon");

        assertTrue("the addon collection is indexed", addon.size() > 1000);
        assertTrue("the index also holds another collection, which must be excluded",
                full.size() > addon.size());
        for (ClipIndex.Clip clip : addon.clips()) {
            assertEquals("addon view holds only addon clips", "addon", clip.kind);
        }
        // Splitting by kind partitions the index: every clip lands in exactly
        // one view, so the addon view cannot be leaking or inventing recordings.
        int sum = 0;
        for (Map.Entry<String, Integer> kind : full.kindCounts().entrySet()) {
            ClipIndex view = full.restrictedToKind(kind.getKey());
            assertEquals(kind.getKey() + " view size", kind.getValue().intValue(), view.size());
            sum += view.size();
        }
        assertEquals("the kind views partition the index", full.size(), sum);
        assertTrue("at least one other collection really exists",
                full.kindCounts().size() > 1);
    }

    @Test
    public void everyScriptedLineIsPinnedToAnAddonRecording() throws Exception {
        ClipIndex addon = addonOnly();
        TvGuide guide = TvGuide.loadDefault();
        int lines = 0;
        for (TvGuide.Channel channel : guide.channels()) {
            for (TvGuide.Programme programme : channel.programmes) {
                for (TvGuide.Line line : programme.lines) {
                    if (line.text.isEmpty()) continue;
                    lines++;
                    assertNotNull("line in '" + programme.name + "' is pinned to a take: "
                            + line.text, line.clipId);
                    ClipIndex.Clip clip = clipById(addon, line.clipId);
                    assertNotNull("pinned clip '" + line.clipId + "' is an addon recording",
                            clip);
                    assertEquals("pinned clip stays in the licensed collection",
                            "addon", clip.kind);
                    assertFalse("pinned clip has a recording behind it", clip.isVocalization());
                }
            }
        }
        assertTrue("the guide scripts a full schedule", lines >= 80);
    }

    @Test
    public void everyCaptionIsARealRecordingOfThatLine() throws Exception {
        ClipIndex addon = addonOnly();
        TvGuide guide = TvGuide.loadDefault();
        for (TvGuide.Channel channel : guide.channels()) {
            for (TvGuide.Programme programme : channel.programmes) {
                for (TvGuide.Line line : programme.lines) {
                    if (line.text.isEmpty()) continue;
                    // exact() compares normalized transcripts, so a hit means the
                    // collection really does say this line.
                    ClipIndex.Clip spoken = addon.exact(line.text);
                    assertNotNull("the addon collection says this caption: " + line.text, spoken);
                    ClipIndex.Clip pinned = clipById(addon, line.clipId);
                    assertNotNull("the pinned take exists", pinned);
                    // Resolving the pinned take's own transcript must land on the
                    // same clip, which holds exactly when the two transcripts are
                    // the same words -- the caption is not a paraphrase.
                    assertEquals("what is heard is what is captioned: " + line.text,
                            spoken.id, addon.exact(pinned.text).id);
                }
            }
        }
    }

    @Test
    public void theSameTakeIsNotTheOnlyVoiceOnTheStation() throws Exception {
        ClipIndex addon = addonOnly();
        TvGuide guide = TvGuide.loadDefault();
        Set<String> used = new HashSet<>();
        for (TvGuide.Channel channel : guide.channels()) {
            for (TvGuide.Programme programme : channel.programmes) {
                for (TvGuide.Line line : programme.lines) {
                    if (line.clipId != null) used.add(line.clipId);
                }
            }
        }
        assertTrue("the station draws on many separate takes", used.size() >= 40);
        for (String id : used) {
            assertNotNull("every used take belongs to the addon collection",
                    clipById(addon, id));
        }
    }

    private static ClipIndex.Clip clipById(ClipIndex index, String id) {
        if (id == null) return null;
        for (ClipIndex.Clip clip : index.clips()) {
            if (clip.id.equals(id)) return clip;
        }
        return null;
    }
}
