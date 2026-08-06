package com.voxel.audio;

import org.junit.Test;
import villager.voice.SpeechOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DialogueCatalogTest {
    @Test
    public void roundTripsVoiceMetadata() throws Exception {
        Path file = Files.createTempFile("dialogue-catalog", ".json");
        try {
            SpeechOptions options = new SpeechOptions(0.9, 2.0, 1.2, 0.3,
                    0.2, "sad", 0.75, 0.65, true);
            DialogueLine line = new DialogueLine("test_line", "Hello, friend.",
                    "FARMER", "EVENING", 4, options);
            new DialogueCatalog(Arrays.asList(line)).save(file);

            DialogueCatalog loaded = DialogueCatalog.load(file);
            assertEquals(1, loaded.getLines().size());
            DialogueLine result = loaded.getLines().get(0);
            assertEquals("test_line", result.getId());
            assertEquals("Hello, friend.", result.getText());
            assertEquals("FARMER", result.getProfession());
            assertEquals("EVENING", result.getPeriod());
            assertEquals(4, result.getVariant());
            assertEquals("sad", result.getOptions().getEmotion());
            assertEquals(2.0, result.getOptions().getPitchSemitones(), 0.000001);
            assertEquals(0.75, result.getOptions().getSinging(), 0.000001);
            assertEquals(0.65, result.getOptions().getSarcasm(), 0.000001);
            assertTrue(result.getOptions().isQuestion());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void packagedCatalogProvidesEditableDefaults() {
        DialogueCatalog catalog = DialogueCatalog.loadDefault();
        assertNotNull(catalog);
        assertTrue("packaged dialogue metadata should be available",
                !catalog.getLines().isEmpty());
    }

    @Test
    public void exactContextWinsOverWildcardDefaults() {
        DialogueLine global = new DialogueLine("global", "Global", "*", "*", 0,
                SpeechOptions.DEFAULT);
        DialogueLine period = new DialogueLine("period", "Night", "*", "NIGHT", 0,
                SpeechOptions.DEFAULT);
        DialogueLine exact = new DialogueLine("exact", "Farmer night?", "FARMER", "NIGHT", 0,
                new SpeechOptions(1.0, 0.0, 1.0, 0.0, 0.36, "neutral", 0.0, 0.2, true));
        DialogueCatalog catalog = new DialogueCatalog(Arrays.asList(global, period, exact));
        assertEquals("exact", catalog.choose("FARMER", "NIGHT", 0).getId());
    }

    @Test
    public void explicitQuestionFalseOverridesPunctuation() throws Exception {
        Path file = Files.createTempFile("dialogue-question", ".json");
        try {
            Files.write(file, ("[{\"id\":\"explicit\",\"text\":\"Really?\","
                    + "\"profession\":\"FARMER\",\"period\":\"DAY\","
                    + "\"voice\":{\"question\":false}}]").getBytes("UTF-8"));
            DialogueLine line = DialogueCatalog.load(file).choose("FARMER", "DAY", 0);
            assertTrue(line != null);
            assertTrue(!line.getOptions().isQuestion());
            assertTrue(!line.getOptions().allowsAutomaticQuestionDetection());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void emptyCatalogDoesNotSelectAnything() {
        assertEquals(null, DialogueCatalog.empty().choose("FARMER", "DAY", 0));
        assertTrue(DialogueCatalog.empty().getLines().isEmpty());
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsMalformedCatalog() throws Exception {
        Path file = Files.createTempFile("dialogue-catalog-bad", ".json");
        try {
            Files.write(file, "{not an array}".getBytes("UTF-8"));
            DialogueCatalog.load(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

}
