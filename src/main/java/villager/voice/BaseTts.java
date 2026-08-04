package villager.voice;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


/** Offline base TTS. Its output is immediately converted by the custom RVC model. */
public final class BaseTts implements AutoCloseable {
    private final OfflineTts tts;

    public BaseTts(Path modelDir) {
        Path model = modelDir.resolve("base-tts.onnx");
        Path tokens = modelDir.resolve("tokens.txt");
        Path overrides = modelDir.resolve("pronunciation-overrides.tsv");
        Path lexicon = modelDir.resolve("pronunciation-lexicon.txt");
        OfflineTtsVitsModelConfig.Builder vitsBuilder = OfflineTtsVitsModelConfig.builder()
                .setModel(model.toString())
                .setTokens(tokens.toString())
                .setLengthScale(1.0f)
                // Higher stochasticity and lower duration noise reduce the
                // repeated, buzzy cadence of the old neural default while
                // keeping articulation stable for RVC conversion.
                .setNoiseScale(0.82f)
                .setNoiseScaleW(0.58f);
        // A sparse custom lexicon makes Sherpa reject every word that is not
        // explicitly listed (the first version failed on ordinary dialogue
        // such as "I am haggling you"). Keep the model's complete English
        // frontend enabled by default; the readable overrides remain available
        // as an opt-in diagnostic lexicon for callers that supply a complete
        // vocabulary.
        boolean useSparseLexicon = Boolean.getBoolean("voxel.voice.use.lexicon");
        if (useSparseLexicon && Files.isRegularFile(overrides)) {
            try {
                PronunciationLexicon.compile(overrides, tokens, lexicon);
            } catch (IOException error) {
                throw new IllegalStateException("Invalid pronunciation overrides: " + overrides,
                        error);
            }
            vitsBuilder.setLexicon(lexicon.toString());
        } else {
            vitsBuilder.setDataDir(modelDir.resolve("espeak-ng-data").toString());
        }
        OfflineTtsVitsModelConfig vits = vitsBuilder.build();
        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vits)
                .setNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2))
                .setProvider("cpu")
                .build();
        OfflineTtsConfig config = OfflineTtsConfig.builder()
                .setModel(modelConfig)
                .setMaxNumSentences(1)
                .build();
        tts = new OfflineTts(config);
    }

    public synchronized WavAudio synthesize(String text, double speed) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text must not be empty");
        }
        if (speed <= 0.0 || Double.isNaN(speed) || Double.isInfinite(speed)) {
            throw new IllegalArgumentException("Speed must be a finite value greater than zero");
        }
        GeneratedAudio audio = tts.generate(text.trim(), 0, (float) speed);
        return new WavAudio(audio.getSampleRate(), audio.getSamples());
    }

    @Override
    public void close() {
        tts.release();
    }
}
