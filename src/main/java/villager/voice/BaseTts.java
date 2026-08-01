package villager.voice;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.nio.file.Path;

/** Offline base TTS. Its output is immediately converted by the custom RVC model. */
public final class BaseTts implements AutoCloseable {
    private final OfflineTts tts;

    public BaseTts(Path modelDir) {
        Path model = modelDir.resolve("base-tts.onnx");
        Path tokens = modelDir.resolve("tokens.txt");
        Path dataDir = modelDir.resolve("espeak-ng-data");
        OfflineTtsVitsModelConfig vits = OfflineTtsVitsModelConfig.builder()
                .setModel(model.toString())
                .setTokens(tokens.toString())
                .setDataDir(dataDir.toString())
                .setLengthScale(1.0f)
                .setNoiseScale(0.667f)
                .setNoiseScaleW(0.8f)
                .build();
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
