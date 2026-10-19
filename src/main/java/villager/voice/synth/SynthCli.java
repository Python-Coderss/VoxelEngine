package villager.voice.synth;

import org.json.JSONObject;
import villager.voice.WavAudio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standalone command line for the parametric villager voice.
 *
 * <p>Mirrors the Python CLI's two audio-producing commands, so the same JSON
 * files drive either implementation:</p>
 *
 * <pre>
 *   # synthesise a line from text
 *   java villager.voice.synth.SynthCli render \
 *       --voiceprint src/main/resources/voice/voiceprint_villager_news.json \
 *       --text "Hrmm, would you like to trade emeralds?" \
 *       --out /tmp/villager.wav \
 *       --set source.f0_mean_hz=196 --set character.smile=0.6
 *
 *   # synthesise from a score JSON instead of text
 *   java villager.voice.synth.SynthCli render --score score.json --out out.wav
 *
 *   # re-voice an existing recording (the voice changer)
 *   java villager.voice.synth.SynthCli convert --in take.wav \
 *       --spec transform_deep_villager.json --out deep.wav
 *
 *   # dump the control surface
 *   java villager.voice.synth.SynthCli schema
 *   java villager.voice.synth.SynthCli phones
 *   java villager.voice.synth.SynthCli pronounce "testificate"
 * </pre>
 *
 * <p>No neural model, no network access, no native libraries: this is the same
 * source-filter synthesis the game runtime uses.</p>
 */
public final class SynthCli {

    private SynthCli() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            if (System.getProperty("voxel.voice.debug") != null) {
                e.printStackTrace();
            }
            System.exit(2);
        }
    }

    /** Run one command; returns a process exit code. */
    public static int run(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
            return 1;
        }
        String command = args[0];
        Map<String, String> options = parseOptions(args);

        if ("render".equals(command)) {
            return render(options);
        }
        if ("convert".equals(command)) {
            return convert(options);
        }
        if ("schema".equals(command)) {
            System.out.println(VoicePrint.defaults().toJson(2));
            return 0;
        }
        if ("phones".equals(command)) {
            System.out.println("phones: " + VillagerPhones.size());
            for (Map.Entry<String, VillagerPhones.Phone> entry
                    : VillagerPhones.all().entrySet()) {
                VillagerPhones.Phone phone = entry.getValue();
                double[] f = phone.formants(0);
                System.out.printf("  %-5s %-14s voiced=%-5s dur=%.3f amp=%.2f F=%d/%d/%d%n",
                        entry.getKey(), phone.manner, String.valueOf(phone.voiced),
                        phone.dur, phone.amp, (int) f[0], (int) f[1], (int) f[2]);
            }
            return 0;
        }
        if ("pronounce".equals(command)) {
            String text = options.containsKey("text") ? options.get("text")
                    : (args.length > 1 ? args[args.length - 1] : "");
            for (VillagerLexicon.Token token : VillagerLexicon.textToWords(text)) {
                if (token.isWord()) {
                    System.out.printf("%-16s %s%n", token.text,
                            VillagerLexicon.display(VillagerLexicon.pronounce(token.text)));
                }
            }
            return 0;
        }
        if ("stats".equals(command)) {
            System.out.println("curated dictionary entries : " + VillagerDictionary.size());
            System.out.println("corpus vocabulary entries  : " + VillagerCorpusLexicon.size());
            System.out.println("phones                     : " + VillagerPhones.size());
            System.out.println("voiceprint knobs           : "
                    + VoicePrintTables.ranges().size() + " ranges, "
                    + VoicePrintTables.enums().size() + " enums");
            System.out.println("seed voice                 : "
                    + VoicePrint.defaults().summary());
            return 0;
        }
        usage();
        return 1;
    }

    /** Synthesise a line from text or from a score JSON. */
    private static int render(Map<String, String> options) throws IOException {
        VoicePrint print = loadVoicePrint(options);
        for (Map.Entry<String, String> override : setOverrides(options).entrySet()) {
            print = print.merged(nested(override.getKey(), override.getValue()));
        }

        Path out = Paths.get(required(options, "out"));
        WavAudio audio;
        if (options.containsKey("score")) {
            String text = read(Paths.get(options.get("score")));
            audio = print == null ? null : new VillagerSynthVoice(print)
                    .renderScore(VoicePrint.jsonToMap(new JSONObject(text)));
        } else {
            String text = options.get("text");
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalArgumentException("render needs --text or --score");
            }
            Map<String, Object> delivery = new LinkedHashMap<String, Object>();
            if (options.containsKey("emotion")) {
                delivery.put("emotion", options.get("emotion"));
            }
            if (options.containsKey("speed")) {
                delivery.put("speed", Double.valueOf(options.get("speed")));
            }
            if (options.containsKey("question")) {
                delivery.put("question", Boolean.valueOf(true));
            }
            Map<String, Object> score = ProsodyScore.build(text, print,
                    delivery.isEmpty() ? null : delivery,
                    options.containsKey("seed")
                            ? Integer.valueOf(options.get("seed")) : null);
            audio = FormantSynthesizer.render(score, print, null);
            if (options.containsKey("dump-score")) {
                Files.write(Paths.get(options.get("dump-score")),
                        new JSONObject(score).toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
        audio.write(out);
        double seconds = audio.samples.length / (double) audio.sampleRate;
        System.out.printf("rendered %s%n  %.2f s at %d Hz, peak %.2f%n",
                out, Double.valueOf(seconds), Integer.valueOf(audio.sampleRate),
                Float.valueOf(villager.voice.AudioDsp.peak(audio.samples)));
        System.out.println("  " + print.effective().summary());
        return 0;
    }

    /** Re-voice an existing WAV from a transform spec. */
    private static int convert(Map<String, String> options) throws IOException {
        Path in = Paths.get(required(options, "in"));
        Path out = Paths.get(required(options, "out"));
        PsolaVoiceTransform.Spec spec = options.containsKey("spec")
                ? PsolaVoiceTransform.loadSpec(Paths.get(options.get("spec")))
                : new PsolaVoiceTransform.Spec();
        if (options.containsKey("pitch")) {
            spec.pitchSemitones = Double.parseDouble(options.get("pitch"));
        }
        if (options.containsKey("speed")) {
            spec.timeScale = Double.parseDouble(options.get("speed"));
        }
        if (options.containsKey("formant")) {
            spec.formantSemitones = Double.parseDouble(options.get("formant"));
        }
        VoicePrint target = options.containsKey("voiceprint")
                ? VoicePrint.load(Paths.get(options.get("voiceprint"))) : null;

        PsolaVoiceTransform.Report report =
                PsolaVoiceTransform.convertFile(in, out, spec, target, null);
        System.out.println("converted " + in + " -> " + out);
        System.out.println("  " + report);
        return 0;
    }

    // ---------------------------------------------------------------------
    // argument handling
    // ---------------------------------------------------------------------

    private static VoicePrint loadVoicePrint(Map<String, String> options) throws IOException {
        if (options.containsKey("voiceprint")) {
            return VoicePrint.load(Paths.get(options.get("voiceprint")));
        }
        return VillagerSynthVoice.loadDefaultVoicePrint();
    }

    /**
     * Turn repeated {@code --set dotted.path=value} arguments into an ordered
     * map, parsing each value as JSON when possible so numbers, booleans and
     * objects all work.
     */
    private static Map<String, String> setOverrides(Map<String, String> options) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String raw = options.get("set");
        if (raw == null) {
            return out;
        }
        // Options are collected as a comma-joined list so several --set flags work.
        for (String item : raw.split(",")) {
            int equals = item.indexOf('=');
            if (equals > 0) {
                out.put(item.substring(0, equals).trim(), item.substring(equals + 1).trim());
            }
        }
        return out;
    }

    /** Build a one-entry nested map from a dotted path and a raw value. */
    private static Map<String, Object> nested(String dottedPath, String rawValue) {
        Object value;
        try {
            value = JSONObject.stringToValue(rawValue);
        } catch (Exception e) {
            value = rawValue;
        }
        String[] parts = dottedPath.split("\\.");
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Map<String, Object> next = new LinkedHashMap<String, Object>();
            cursor.put(parts[i], next);
            cursor = next;
        }
        cursor.put(parts[parts.length - 1], value);
        return root;
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) {
                continue;
            }
            String key = token.substring(2);
            int equals = key.indexOf('=');
            if (equals > 0) {
                String value = key.substring(equals + 1);
                key = key.substring(0, equals);
                out.put(key, join(out.get(key), value));
                continue;
            }
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.put(key, join(out.get(key), args[i + 1]));
                i++;
            } else {
                out.put(key, "true");
            }
        }
        return out;
    }

    private static String join(String existing, String value) {
        return existing == null ? value : existing + "," + value;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("missing required --" + key);
        }
        return value;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void usage() {
        System.out.println("usage: SynthCli <command> [options]");
        System.out.println();
        System.out.println("commands:");
        System.out.println("  render    --text LINE | --score FILE --out FILE.wav"
                + " [--voiceprint FILE] [--set path=value]...");
        System.out.println("  convert   --in FILE.wav --out FILE.wav [--spec FILE]"
                + " [--pitch st] [--speed x] [--formant st]");
        System.out.println("  schema    print the default voiceprint as JSON");
        System.out.println("  phones    print the phone inventory");
        System.out.println("  pronounce --text LINE");
        System.out.println("  stats     print table sizes and the seed voice summary");
    }
}
