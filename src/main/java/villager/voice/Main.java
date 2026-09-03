package villager.voice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command-line interface for the Java-only custom villager voice. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(usage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("setup error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("runtime error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.mode != null) {
            System.setProperty("voxel.voice.mode",
                    options.mode.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (options.help) {
            System.out.println(usage());
            return;
        }
        if (options.midiEditor) {
            MidiIntroEditor.launch(options.models);
            return;
        }
        if (options.editor) {
            VoiceEditor.launch(options.models);
            return;
        }
        if (options.dialogueEditor) {
            DialogueEditor.launch(options.models);
            return;
        }
        if (!options.newsIntro && !options.baseOnly
                && options.text == null && options.lines == null) {
            throw new IllegalArgumentException(
                    "give text, --lines, --news-intro, or --editor");
        }
        if (options.newsIntro && (options.text != null || options.lines != null)) {
            throw new IllegalArgumentException("--news-intro cannot be combined with text or --lines");
        }
        if (options.text != null && options.lines != null) {
            throw new IllegalArgumentException("use either text or --lines, not both");
        }
        if (options.baseOnly && options.text == null && options.lines == null) {
            throw new IllegalArgumentException("--base-only needs text or --lines");
        }
        if (options.baseOnly) {
            renderBaseOnly(options);
            return;
        }

        VillagerSynthesizer synthesizer = new VillagerSynthesizer(options.models);
        try {
            System.out.println("voice mode: "
                    + synthesizer.getMode().name().toLowerCase(java.util.Locale.ROOT));
            if (synthesizer.getMode() == VoiceMode.NEURAL) {
                System.out.println("voice backend: Coqui VCTK VITS + RVC (Java ONNX, no Python)");
                System.out.println("model bundle: "
                        + options.models.toAbsolutePath().normalize());
            }
            if (options.newsIntro) {
                VillagerNewsIntro intro = VillagerNewsIntro.loadDefault();
                intro.render(synthesizer, options.voiceOptions()).write(options.out);
                System.out.println("intro: " + intro.getTitle());
                System.out.println("source: " + intro.getSourceUrl());
                System.out.println(options.out);
            } else if (options.lines != null) {
                renderBatch(options, synthesizer);
            } else {
                synthesizer.render(options.text, options.voiceOptions()).write(options.out);
                System.out.println(options.out);
            }
        } finally {
            synthesizer.close();
        }
    }

    /**
     * Render the raw Coqui VITS base voice — the exact audio that feeds the
     * RVC conversion — without loading any RVC model. Useful for auditing and
     * tuning the base: whatever sounds wrong here will sound wrong after RVC.
     */
    private static void renderBaseOnly(Options options) throws Exception {
        CoquiVitsTts baseTts = new CoquiVitsTts(
                ModelBundle.from(options.models).directory());
        try {
            SpeechOptions voiceOptions = options.voiceOptions();
            if (options.lines != null) {
                Files.createDirectories(options.outdir);
                List<JsonLines.Entry> entries = JsonLines.read(options.lines);
                Map<String, String> index = new LinkedHashMap<String, String>();
                for (JsonLines.Entry entry : entries) {
                    String fileId = safeFileName(entry.id);
                    WavAudio audio = baseTts.synthesize(entry.text,
                            voiceOptions.getEffectiveSpeed(), voiceOptions.getEmotion());
                    AudioDsp.normalizePeak(audio.samples, 0.9f);
                    Path output = options.outdir.resolve(fileId + ".wav");
                    audio.write(output);
                    index.put(fileId, entry.text);
                    System.out.println("ok " + fileId + " (base)");
                }
                JsonLines.writeIndex(options.outdir.resolve("index.json"), index);
                System.out.println(entries.size() + " lines -> " + options.outdir
                        + " (base TTS, no RVC)");
            } else {
                WavAudio audio = baseTts.synthesize(options.text,
                        voiceOptions.getEffectiveSpeed(), voiceOptions.getEmotion());
                AudioDsp.normalizePeak(audio.samples, 0.9f);
                audio.write(options.out);
                System.out.println(options.out + " (base TTS, no RVC)");
            }
        } finally {
            baseTts.close();
        }
    }

    private static void renderBatch(Options options, VillagerSynthesizer synthesizer) throws Exception {
        Files.createDirectories(options.outdir);
        List<JsonLines.Entry> entries = JsonLines.read(options.lines);
        Map<String, String> index = new LinkedHashMap<String, String>();
        for (JsonLines.Entry entry : entries) {
            String fileId = safeFileName(entry.id);
            Path output = options.outdir.resolve(fileId + ".wav");
            if (Files.exists(output) && !options.overwrite) {
                System.out.println("skip (exists) " + fileId);
                index.put(fileId, entry.text);
                continue;
            }
            synthesizer.render(entry.text, options.voiceOptions()).write(output);
            index.put(fileId, entry.text);
            System.out.println("ok " + fileId);
        }
        JsonLines.writeIndex(options.outdir.resolve("index.json"), index);
        System.out.println(entries.size() + " lines -> " + options.outdir);
    }

    private static String safeFileName(String value) {
        String result = value == null ? "line" : value.replaceAll("[^A-Za-z0-9_\\-]+", "_");
        result = result.replaceAll("_+", "_");
        return result.length() == 0 ? "line" : result;
    }

    private static String usage() {
        return "Usage:\n"
                + "  java -cp <classpath> villager.voice.Main \"I am haggling you\" -o line.wav\n"
                + "  java -cp <classpath> villager.voice.Main --lines lines.json --outdir voiced_lines\n"
                + "  java -cp <classpath> villager.voice.Main --editor\n"
                + "  java -cp <classpath> villager.voice.Main --midi-editor\n\n"
                + "Options:\n"
                + "  --models DIR     custom model bundle (default: models/java)\n"
                + "  -o, --out FILE   output WAV for single-line mode\n"
                + "  --lines FILE     JSON array containing id/text entries\n"
                + "  --news-intro     render the editable Villager News intro asset\n"
                + "  --outdir DIR     output directory for batch mode\n"
                + "  --speed VALUE    Coqui VITS duration multiplier; 1.0 is normal\n"
                + "  --pitch VALUE    extra RVC pitch offset in semitones; default 0\n"
                + "  --volume VALUE   output gain, 1.0 is unchanged\n"
                + "  --tone VALUE     mood from -1.0 serious to +1.0 joking\n"
                + "  --natural VALUE  natural VITS mix from 0.0 to 0.5\n"
                + "  --emotion NAME   neutral, happy, sad, angry, or scared\n"
                + "  --singing VALUE  singing expression from 0.0 (speech) to 1.0\n"
                + "  --sarcasm VALUE  dry/deadpan delivery from 0.0 to 1.0\n"
                + "  --question       use a rising interrogative ending\n"
                + "  --base-only      render the raw Coqui VITS base voice (no RVC)\n"
                + "  --mode MODE      neural (default, Coqui) or reference (exact transcript clips)\n"
                + "  --editor         open the standalone voice preset editor\n"
                + "  --midi-editor    open the Villager News piano-roll MIDI editor\n"
                + "  --dialogue-editor open the dialogue metadata catalog editor\n"
                + "  --overwrite      replace existing batch WAV files\n"
                + "  -h, --help       show this help\n";
    }

    private static final class Options {
        Path out = Paths.get("villager_line.wav");
        Path lines;
        Path outdir = Paths.get("voiced_lines");
        Path models = Paths.get("models", "java");
        String text;
        double speed = 1.0;
        double pitch = 0.0;
        double volume = 1.0;
        double tone = 0.0;
        double natural = 0.36;
        String emotion = "neutral";
        double singing = 0.0;
        double sarcasm = 0.0;
        boolean question;
        VoiceMode mode;
        boolean editor;
        boolean midiEditor;
        boolean dialogueEditor;
        boolean newsIntro;
        boolean baseOnly;
        boolean overwrite;
        boolean help;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("-h".equals(arg) || "--help".equals(arg)) {
                    options.help = true;
                } else if ("--models".equals(arg)) {
                    options.models = Paths.get(next(args, ++i, arg));
                } else if ("-o".equals(arg) || "--out".equals(arg)) {
                    options.out = Paths.get(next(args, ++i, arg));
                } else if ("--lines".equals(arg)) {
                    options.lines = Paths.get(next(args, ++i, arg));
                } else if ("--news-intro".equals(arg)) {
                    options.newsIntro = true;
                } else if ("--outdir".equals(arg)) {
                    options.outdir = Paths.get(next(args, ++i, arg));
                } else if ("--speed".equals(arg)) {
                    options.speed = parseDouble(next(args, ++i, arg), arg);
                } else if ("--pitch".equals(arg)) {
                    options.pitch = parseDouble(next(args, ++i, arg), arg);
                } else if ("--volume".equals(arg)) {
                    options.volume = parseDouble(next(args, ++i, arg), arg);
                } else if ("--tone".equals(arg)) {
                    options.tone = parseDouble(next(args, ++i, arg), arg);
                } else if ("--natural".equals(arg)) {
                    options.natural = parseDouble(next(args, ++i, arg), arg);
                } else if ("--emotion".equals(arg)) {
                    options.emotion = next(args, ++i, arg);
                } else if ("--singing".equals(arg)) {
                    options.singing = parseDouble(next(args, ++i, arg), arg);
                } else if ("--sarcasm".equals(arg)) {
                    options.sarcasm = parseDouble(next(args, ++i, arg), arg);
                } else if ("--question".equals(arg)) {
                    options.question = true;
                } else if ("--base-only".equals(arg)) {
                    options.baseOnly = true;
                } else if ("--mode".equals(arg)) {
                    options.mode = VoiceMode.parse(next(args, ++i, arg));
                } else if ("--editor".equals(arg)) {
                    options.editor = true;
                } else if ("--midi-editor".equals(arg)) {
                    options.midiEditor = true;
                } else if ("--dialogue-editor".equals(arg)) {
                    options.dialogueEditor = true;
                } else if ("--overwrite".equals(arg)) {
                    options.overwrite = true;
                } else if (arg.startsWith("-")) {
                    throw new IllegalArgumentException("unknown option: " + arg);
                } else if (options.text == null) {
                    options.text = arg;
                } else {
                    throw new IllegalArgumentException("only one text argument is allowed");
                }
            }
            if (options.speed <= 0.0 || Double.isNaN(options.speed)
                    || Double.isInfinite(options.speed)) {
                throw new IllegalArgumentException("--speed must be a finite value greater than zero");
            }
            if (Double.isNaN(options.pitch) || Double.isInfinite(options.pitch)) {
                throw new IllegalArgumentException("--pitch must be finite");
            }
            if (options.volume < 0.0 || options.volume > 2.0
                    || Double.isNaN(options.volume) || Double.isInfinite(options.volume)) {
                throw new IllegalArgumentException("--volume must be between 0 and 2");
            }
            if (options.tone < -1.0 || options.tone > 1.0
                    || Double.isNaN(options.tone) || Double.isInfinite(options.tone)) {
                throw new IllegalArgumentException("--tone must be between -1 and 1");
            }
            if (options.natural < 0.0 || options.natural > 0.5
                    || Double.isNaN(options.natural) || Double.isInfinite(options.natural)) {
                throw new IllegalArgumentException("--natural must be between 0 and 0.5");
            }
            if (options.singing < 0.0 || options.singing > 1.0
                    || Double.isNaN(options.singing) || Double.isInfinite(options.singing)) {
                throw new IllegalArgumentException("--singing must be between 0 and 1");
            }
            if (options.sarcasm < 0.0 || options.sarcasm > 1.0
                    || Double.isNaN(options.sarcasm) || Double.isInfinite(options.sarcasm)) {
                throw new IllegalArgumentException("--sarcasm must be between 0 and 1");
            }
            return options;
        }

        SpeechOptions voiceOptions() {
            return new SpeechOptions(speed, pitch, volume, tone, natural, emotion,
                    singing, sarcasm, question);
        }

        private static String next(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[index];
        }

        private static double parseDouble(String value, String option) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " needs a number: " + value);
            }
        }
    }
}
