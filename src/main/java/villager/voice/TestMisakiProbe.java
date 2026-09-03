package villager.voice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Dev probe: phonemize each line of an input file (one sentence per line) with
 * the Java MisakiEnG2P port and write "sentence<TAB>phonemes" lines to the
 * output file. Used to diff against the Python misaki reference.
 */
public final class TestMisakiProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: TestMisakiProbe <modelDir> <in.txt> <out.txt>");
            System.exit(1);
        }
        MisakiEnG2P g2p = new MisakiEnG2P(Paths.get(args[0]));
        List<String> lines = Files.readAllLines(Paths.get(args[1]),
                StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String phonemes = g2p.phonemize(trimmed);
            out.append(trimmed).append('\t').append(phonemes).append('\n');
        }
        Files.write(Paths.get(args[2]), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + args[2]);
    }
}
