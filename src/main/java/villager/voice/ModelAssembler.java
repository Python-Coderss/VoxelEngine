package villager.voice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reassembles Git-safe model parts into a complete ignored runtime bundle. */
public final class ModelAssembler {
    public static final String MANIFEST_NAME = "model-parts.manifest";
    private static final int BUFFER_SIZE = 1024 * 1024;

    private ModelAssembler() {
    }

    /**
     * Validates and assembles all model parts from {@code sourceDirectory} into
     * {@code runtimeDirectory}, then copies the small text/frontend assets.
     */
    public static Path prepare(Path sourceDirectory, Path runtimeDirectory) throws IOException {
        if (sourceDirectory == null || runtimeDirectory == null) {
            throw new IllegalArgumentException("Model directories must not be null");
        }
        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path runtime = runtimeDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IOException("Missing model part directory: " + source);
        }
        Files.createDirectories(runtime);

        List<ModelSpec> models = readManifest(source.resolve(MANIFEST_NAME));
        for (ModelSpec model : models) {
            assemble(source, runtime, model);
        }
        copyOptional(source.resolve("cmudict.dict"), runtime.resolve("cmudict.dict"));
        copyOptional(source.resolve("coqui-vctk-config.json"),
                runtime.resolve("coqui-vctk-config.json"));
        copyOptional(source.resolve("coqui-vctk-vocab.json"),
                runtime.resolve("coqui-vctk-vocab.json"));
        copyOptional(source.resolve("coqui-vctk-speaker_ids.json"),
                runtime.resolve("coqui-vctk-speaker_ids.json"));
        return runtime;
    }

    private static List<ModelSpec> readManifest(Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("Missing model parts manifest: " + manifest);
        }
        List<ModelSpec> models = new ArrayList<ModelSpec>();
        Set<String> names = new HashSet<String>();
        try (BufferedReader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\t", -1);
                if (fields.length != 4) {
                    throw new IOException("Invalid model manifest line " + lineNumber + ": " + line);
                }
                try {
                    int partCount = Integer.parseInt(fields[1]);
                    long byteCount = Long.parseLong(fields[2]);
                    if (!isAllowedModel(fields[0]) || !names.add(fields[0])
                            || partCount <= 0 || byteCount <= 0
                            || !fields[3].matches("[0-9a-fA-F]{64}")) {
                        throw new IllegalArgumentException();
                    }
                    models.add(new ModelSpec(fields[0], partCount, byteCount,
                            fields[3].toLowerCase(Locale.ROOT)));
                } catch (IllegalArgumentException error) {
                    throw new IOException("Invalid model manifest line " + lineNumber + ": " + line);
                }
            }
        }
        if (models.isEmpty()) {
            throw new IOException("Model parts manifest contains no models: " + manifest);
        }
        return models;
    }

    private static boolean isAllowedModel(String fileName) {
        return "coqui-vctk-vits.onnx".equals(fileName)
                || "vec-768-layer-12.onnx".equals(fileName)
                || "rvc-villager.onnx".equals(fileName);
    }

    private static void assemble(Path source, Path runtime, ModelSpec model) throws IOException {
        Path target = runtime.resolve(model.fileName);
        if (isValid(target, model.byteCount, model.sha256)) {
            return;
        }

        Path temporary = Files.createTempFile(runtime, model.fileName, ".tmp");
        boolean moved = false;
        try {
            MessageDigest digest = sha256();
            long total = 0L;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (OutputStream output = Files.newOutputStream(temporary)) {
                for (int index = 0; index < model.partCount; index++) {
                    Path part = source.resolve(model.fileName + ".part" + threeDigits(index));
                    if (!Files.isRegularFile(part)) {
                        throw new IOException("Missing model part: " + part);
                    }
                    try (InputStream input = Files.newInputStream(part)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            total += read;
                        }
                    }
                }
            }
            if (total != model.byteCount || !hex(digest.digest()).equals(model.sha256)) {
                throw new IOException("Model parts failed integrity check for " + model.fileName
                        + " (expected " + model.byteCount + " bytes, " + model.sha256 + ")");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static boolean isValid(Path file, long expectedBytes, String expectedSha256)
            throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) != expectedBytes) {
            return false;
        }
        return expectedSha256.equals(hash(file));
    }

    private static void copyOptional(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(target);
        }
    }

    private static String hash(Path file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        Formatter formatter = new Formatter(Locale.ROOT);
        try {
            for (byte value : bytes) {
                formatter.format("%02x", value & 0xff);
            }
            return formatter.toString();
        } finally {
            formatter.close();
        }
    }

    private static String threeDigits(int value) {
        return String.format(Locale.ROOT, "%03d", value);
    }

    private static final class ModelSpec {
        private final String fileName;
        private final int partCount;
        private final long byteCount;
        private final String sha256;

        private ModelSpec(String fileName, int partCount, long byteCount, String sha256) {
            this.fileName = fileName;
            this.partCount = partCount;
            this.byteCount = byteCount;
            this.sha256 = sha256;
        }
    }
}
