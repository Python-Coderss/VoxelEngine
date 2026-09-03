package villager.voice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev-only: exercise {@link KokoroVoice} — the single-emotion samples plus
 * per-word snapshot presets — after the voice-crack fix.
 *
 * <p>Run:
 * <pre>{@code
 * java -cp target/classes:<onnxruntime>:<org.json> villager.voice.TestKokoroPreset
 * }</pre>
 */
public final class TestKokoroPreset {
    private TestKokoroPreset() {
    }

    public static void main(String[] args) throws Exception {
        Path kokoroDir = Paths.get(args.length > 0 ? args[0] : "dev/kokoro-eval");
        Path rvcSource = Paths.get(args.length > 1 ? args[1] : "models/java");
        Path presetDir = Paths.get(args.length > 2 ? args[2]
                : "samples/kokoro-voice-presets");
        Files.createDirectories(presetDir.resolve("out"));

        // Resolve the RVC bundle exactly like VillagerSynthesizer does, so
        // rmvpe.onnx / rmvpe-mel.f32 / rvc-villager-index.bin are present.
        // Without them the F0 tracking degrades audibly (crack-like pitch jumps).
        Path rvcDir = ModelBundle.from(rvcSource).directory();

        try (KokoroVoice voice = new KokoroVoice(kokoroDir, rvcDir)) {
            Map<String, String> index = new LinkedHashMap<String, String>();

            // 1. Single-emotion lines: fast path, must match the original
            //    samples/full-compare/kokoro/ renders exactly.
            String[][] lines = {
                    {"I am haggling you.", "neutral"},
                    {"The crops failed again this season.", "sad"},
                    {"Did you see the size of that golem?", "neutral"},
                    {"Get away from my shop!", "angry"},
                    {"Fine, fine. Take it for thirty emeralds.", "happy"},
            };
            for (int i = 0; i < lines.length; i++) {
                long start = System.nanoTime();
                VoiceClip clip = voice.speak(lines[i][0], lines[i][1]);
                Path out = presetDir.resolve("out/line" + (i + 1) + ".wav");
                clip.writeWav(out);
                index.put("line" + (i + 1),
                        "[" + lines[i][1] + "] " + lines[i][0]);
                System.out.printf("line%d  %.2fs audio in %.2fs%n", i + 1,
                        clip.getDurationSeconds(), (System.nanoTime() - start) / 1e9);
            }

            // 2. Per-word snapshot presets (mixed emotion, word-boundary splices).
            String[] presets = {"haggling-mixed.json", "golem-scared-tail.json"};
            for (String name : presets) {
                Path presetPath = presetDir.resolve(name);
                if (!Files.isRegularFile(presetPath)) {
                    System.out.println("skip (missing) " + presetPath);
                    continue;
                }
                long start = System.nanoTime();
                VoiceClip clip = voice.speakWithPreset(presetPath);
                Path out = presetDir.resolve("out/" + name.replace(".json", ".wav"));
                clip.writeWav(out);
                index.put(name.replace(".json", ""), "preset: " + name);
                System.out.printf("%s  %.2fs audio in %.2fs%n", out.getFileName(),
                        clip.getDurationSeconds(), (System.nanoTime() - start) / 1e9);
            }

            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : index.entrySet()) {
                if (!first) json.append(',');
                first = false;
                json.append('"').append(entry.getKey()).append("\":\"")
                        .append(entry.getValue().replace("\"", "\\\""))
                        .append('"');
            }
            json.append('}');
            Files.write(presetDir.resolve("out/index.json"),
                    json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("done -> " + presetDir.resolve("out").toAbsolutePath());
        }
    }
}
