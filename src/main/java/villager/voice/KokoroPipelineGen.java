package villager.voice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dev-only evaluation harness: renders the complete villager pipeline
 * (base TTS -> RVC conversion -> natural mix -> denoise -> tilt) for both
 * neural bases, Coqui VITS and Kokoro, over identical lines and emotions.
 */
public final class KokoroPipelineGen {
    private static final class Line {
        final String text;
        final String emotion;

        Line(String text, String emotion) {
            this.text = text;
            this.emotion = emotion;
        }
    }

    private KokoroPipelineGen() {
    }

    public static void main(String[] args) throws Exception {
        Path bundleDir = Paths.get(args[0]);
        Path kokoroDir = Paths.get(args[1]);
        Path outDir = Paths.get(args[2]);
        Files.createDirectories(outDir.resolve("coqui"));
        Files.createDirectories(outDir.resolve("kokoro"));

        Line[] lines = {
                new Line("I am haggling you.", "neutral"),
                new Line("The crops failed again this season.", "sad"),
                new Line("Did you see the size of that golem?", "neutral"),
                new Line("Get away from my shop!", "angry"),
                new Line("Fine, fine. Take it for thirty emeralds.", "happy"),
        };

        System.out.println("== full pipeline: Coqui VITS base + RVC ==");
        try (VillagerSynthesizer synth = new VillagerSynthesizer(bundleDir)) {
            renderAll(synth, lines, outDir.resolve("coqui"), "");
        }

        System.out.println("== full pipeline: Kokoro base + RVC ==");
        try (KokoroTts kokoro = new KokoroTts(kokoroDir);
             VillagerSynthesizer synth = new VillagerSynthesizer(kokoro, bundleDir)) {
            renderAll(synth, lines, outDir.resolve("kokoro"), "");

            System.out.println("== full pipeline: Kokoro am_michael + RVC ==");
            kokoro.setForcedExpression("am_michael");
            renderAll(synth, lines, outDir.resolve("kokoro-am-michael"),
                    "michael_");
            kokoro.setForcedExpression(null);
        }
        System.out.println("done -> " + outDir.toAbsolutePath().normalize());
    }

    private static void renderAll(VillagerSynthesizer synth, Line[] lines,
                                  Path dir, String prefix) throws Exception {
        Map<String, String> index = new LinkedHashMap<String, String>();
        for (int i = 0; i < lines.length; i++) {
            long start = System.nanoTime();
            WavAudio audio = synth.render(lines[i].text, new SpeechOptions(
                    1.0, 0.0, 1.0, 0.0, 0.36, lines[i].emotion, 0.0, 0.0, false));
            Path out = dir.resolve(prefix + "line" + (i + 1) + ".wav");
            audio.write(out);
            index.put("line" + (i + 1), "[" + lines[i].emotion + "] " + lines[i].text);
            double seconds = (System.nanoTime() - start) / 1e9;
            System.out.println(String.format("%s  %.2fs audio in %.2fs", out,
                    audio.samples.length / (double) audio.sampleRate, seconds));
        }
        JsonLines.writeIndex(dir.resolve("index.json"), index);
    }
}
