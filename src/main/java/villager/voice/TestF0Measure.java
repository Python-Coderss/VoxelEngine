package villager.voice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Dev-only probe: report RMVPE F0 statistics for a set of WAV files. RMVPE is
 * the extractor the villager RVC model was trained with, so it is the right
 * ruler for "does the rendered output match the fed contour".
 */
public final class TestF0Measure {
    public static void main(String[] args) throws Exception {
        Path bank = Paths.get("dev/voice-models/rmvpe-mel.f32");
        try (RmvpePitch rmvpe = new RmvpePitch(
                Paths.get("dev/voice-models/rmvpe.onnx"), bank)) {
            for (String arg : args) {
                Path path = Paths.get(arg);
                if (!Files.isRegularFile(path)) {
                    System.out.println("MISSING " + path);
                    continue;
                }
                WavAudio wav = WavAudio.read(path);
                WavAudio at16k = wav.resampled(16000);
                float[] f0 = rmvpe.pitch(at16k.samples);
                float[] voiced = new float[f0.length];
                int count = 0;
                float min = Float.MAX_VALUE;
                float max = 0.0f;
                double sum = 0.0;
                for (float v : f0) {
                    if (v > 0.0f) {
                        voiced[count++] = v;
                        min = Math.min(min, v);
                        max = Math.max(max, v);
                        sum += v;
                    }
                }
                if (count == 0) {
                    System.out.printf("%-40s (no voiced frames)%n", path);
                    continue;
                }
                float[] sorted = Arrays.copyOf(voiced, count);
                Arrays.sort(sorted);
                float p25 = sorted[count / 4];
                float p75 = sorted[(count * 3) / 4];
                System.out.printf("%-40s voiced=%4d med=%7.1f p25=%6.1f p75=%6.1f min=%6.1f max=%6.1f%n",
                        path, count, sorted[count / 2], p25, p75, min, max);
            }
        }
    }
}
