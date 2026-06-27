import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;

public class VoxelRefactor {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage:");
            System.err.println("  java VoxelRefactor -l <file> <search> [replace]");
            System.err.println("  java VoxelRefactor -la <file> <search> [replace]");
            System.err.println("  java VoxelRefactor -ra <file> <search> [replace]");
            System.exit(2);
        }
        String mode = args[0];
        Path path = Paths.get(args[1]);
        String search = args[2].replace("\r\n", "\n").replace("\r", "\n");
        String replace = (args.length > 3 ? args[3] : "").replace("\r\n", "\n").replace("\r", "\n");

        byte[] raw = Files.readAllBytes(path);
        String content = new String(raw, StandardCharsets.UTF_8);
        // Normalise line endings to LF for matching
        String normalised = content.replace("\r\n", "\n").replace("\r", "\n");

        String newContent = normalised;
        int replacements = 0;

        switch (mode) {
            case "-l": {
                int idx = normalised.indexOf(search);
                if (idx >= 0) {
                    newContent = normalised.substring(0, idx) + replace + normalised.substring(idx + search.length());
                    replacements = 1;
                }
                break;
            }
            case "-la": {
                int searchIdx = 0;
                while ((searchIdx = normalised.indexOf(search, searchIdx)) != -1) {
                    replacements++;
                    searchIdx += search.length();
                }
                newContent = normalised.replace(search, replace);
                break;
            }
            case "-ra": {
                Pattern p = Pattern.compile(search, Pattern.DOTALL);
                Matcher m = p.matcher(normalised);
                StringBuilder sb = new StringBuilder();
                int lastEnd = 0;
                while (m.find()) {
                    replacements++;
                    sb.append(normalised, lastEnd, m.start());
                    sb.append(replace);
                    lastEnd = m.end();
                }
                sb.append(normalised, lastEnd, normalised.length());
                newContent = sb.toString();
                break;
            }
            default:
                System.err.println("Unknown mode: " + mode);
                System.exit(2);
        }

        if (replacements == 0) {
            System.err.println("No matches.");
            System.exit(1);
        }

        Files.write(path, newContent.getBytes(StandardCharsets.UTF_8));
        System.out.printf("OK mode=%s replacements=%d bytes=%d->%d file=%s%n",
                mode, replacements, content.length(), newContent.length(), path);
    }
}
