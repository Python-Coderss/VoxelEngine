package villager.voice;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class ModelAssemblerTest {
    @Test
    public void assemblesPartsAndRebuildsCorruptRuntimeFile() throws Exception {
        Path root = Files.createTempDirectory("voxel-model-assembler");
        Path source = root.resolve("parts");
        Path runtime = root.resolve("runtime");
        Files.createDirectories(source.resolve("espeak-ng-data"));
        try {
            byte[] base = bytes(123, 3);
            byte[] content = bytes(87, 11);
            byte[] rvc = bytes(211, 29);
            writeParts(source, "base-tts.onnx", base, 40);
            writeParts(source, "vec-768-layer-12.onnx", content, 40);
            writeParts(source, "rvc-villager.onnx", rvc, 40);
            Files.write(source.resolve("tokens.txt"), "tokens".getBytes(StandardCharsets.UTF_8));
            Files.write(source.resolve("espeak-ng-data/dict"), "data".getBytes(StandardCharsets.UTF_8));

            Path assembled = ModelAssembler.prepare(source, runtime);
            assertArrayEquals(base, Files.readAllBytes(assembled.resolve("base-tts.onnx")));
            assertArrayEquals(content, Files.readAllBytes(assembled.resolve("vec-768-layer-12.onnx")));
            assertArrayEquals(rvc, Files.readAllBytes(assembled.resolve("rvc-villager.onnx")));
            assertTrue(Files.isRegularFile(assembled.resolve("espeak-ng-data/dict")));

            Files.write(assembled.resolve("vec-768-layer-12.onnx"), new byte[]{1, 2, 3});
            ModelAssembler.prepare(source, runtime);
            assertArrayEquals(content,
                    Files.readAllBytes(assembled.resolve("vec-768-layer-12.onnx")));
        } finally {
            deleteTree(root);
        }
    }

    private static void writeParts(Path directory, String name, byte[] data, int partSize)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int count = 0;
        int offset = 0;
        while (offset < data.length) {
            int length = Math.min(partSize, data.length - offset);
            byte[] part = Arrays.copyOfRange(data, offset, offset + length);
            Files.write(directory.resolve(name + String.format(".part%03d", count)), part);
            digest.update(part);
            offset += length;
            count++;
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value & 0xff));
        }
        String manifestText = "# model parts\n" + name + "\t" + count + "\t" + data.length
                + "\t" + hex + "\n";
        Path manifestPath = directory.resolve(ModelAssembler.MANIFEST_NAME);
        if (Files.exists(manifestPath)) {
            Files.write(manifestPath,
                    (new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8)
                            + name + "\t" + count + "\t" + data.length + "\t" + hex + "\n")
                            .getBytes(StandardCharsets.UTF_8));
        } else {
            Files.write(manifestPath, manifestText.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] bytes(int length, int seed) {
        byte[] result = new byte[length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (seed + i * 13);
        }
        return result;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException error) {
                    path.toFile().deleteOnExit();
                }
            });
        }
    }
}
