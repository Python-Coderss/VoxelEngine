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
        if (options.help) {
            System.out.println(usage());
            return;
        }
        if (options.text == null && options.lines == null) {
            throw new IllegalArgumentException("give text or --lines");
        }
        if (options.text != null && options.lines != null) {
            throw new IllegalArgumentException("use either text or --lines, not both");
        }

        VillagerSynthesizer synthesizer = new VillagerSynthesizer(options.models);
        try {
            System.out.println("loaded custom Java model bundle "
                    + options.models.toAbsolutePath().normalize());
            if (options.lines != null) {
                renderBatch(options, synthesizer);
            } else {
                synthesizer.render(options.text, options.speed, options.pitch).write(options.out);
                System.out.println(options.out);
            }
        } finally {
            synthesizer.close();
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
            synthesizer.render(entry.text, options.speed, options.pitch).write(output);
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
                + "  java -jar villager-voice.jar \"I am haggling you\" -o line.wav\n"
                + "  java -jar villager-voice.jar --lines ../lines.json --outdir voiced_lines\n\n"
                + "Options:\n"
                + "  --models DIR     custom model bundle (default: models/java)\n"
                + "  -o, --out FILE   output WAV for single-line mode\n"
                + "  --lines FILE     JSON array containing id/text entries\n"
                + "  --outdir DIR     output directory for batch mode\n"
                + "  --speed VALUE    base-TTS duration multiplier; 1.0 is normal\n"
                + "  --pitch VALUE    extra RVC pitch offset in semitones; default 0\n"
                + "  --overwrite      replace existing batch WAV files\n"
                + "  -h, --help       show this help\n\n"
                + "The generator is Java-only: Piper/VITS base TTS, ContentVec, and\n"
                + "the custom villager RVC model run in-process. No Kevin voice,\n"
                + "Python, or subprocess fallback is used.\n";
    }

    private static final class Options {
        Path out = Paths.get("villager_line.wav");
        Path lines;
        Path outdir = Paths.get("voiced_lines");
        Path models = Paths.get("models", "java");
        String text;
        double speed = 1.0;
        double pitch = 0.0;
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
                } else if ("--outdir".equals(arg)) {
                    options.outdir = Paths.get(next(args, ++i, arg));
                } else if ("--speed".equals(arg)) {
                    options.speed = parseDouble(next(args, ++i, arg), arg);
                } else if ("--pitch".equals(arg)) {
                    options.pitch = parseDouble(next(args, ++i, arg), arg);
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
            return options;
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
