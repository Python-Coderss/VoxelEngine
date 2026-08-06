package villager.voice;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MidiNoteSequenceTest {
    @Test
    public void roundTripsNotesAndTempo() throws Exception {
        MidiNoteSequence source = new MidiNoteSequence(480, 130.0, Arrays.asList(
                new MidiNoteSequence.Note(72, 0, 240, 100, 0, "da"),
                new MidiNoteSequence.Note(76, 480, 480, 90, 0, "da")));
        Path file = Files.createTempFile("villager-news", ".mid");
        try {
            source.save(file);
            MidiNoteSequence loaded = MidiNoteSequence.load(file);
            assertEquals(480, loaded.getResolution());
            assertEquals(130.0, loaded.getBpm(), 0.01);
            assertEquals(2, loaded.getNotes().size());
            assertEquals(72, loaded.getNotes().get(0).getPitch());
            assertEquals(480, loaded.getNotes().get(1).getStartTick());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void midiConvertsToMonophonicIntro() {
        MidiNoteSequence midi = new MidiNoteSequence(480, 130.0, Arrays.asList(
                new MidiNoteSequence.Note(48, 0, 480, 90, 0, "bass"),
                new MidiNoteSequence.Note(72, 0, 240, 100, 0, "melody"),
                new MidiNoteSequence.Note(76, 480, 480, 100, 0, "melody")));
        VillagerNewsIntro intro = VillagerNewsIntro.fromMidi(midi);
        assertEquals(2, intro.getNotes().size());
        assertEquals("C5", intro.getNotes().get(0).getPitchName());
        assertEquals("E5", intro.getNotes().get(1).getPitchName());
        assertTrue(intro.getBpm() > 0.0);
    }
}
