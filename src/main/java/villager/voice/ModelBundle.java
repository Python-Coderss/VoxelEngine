package villager.voice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Paths and validation for the model files used by the Java runtime. */
public final class ModelBundle {
    private final Path directory;

    private ModelBundle(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static ModelBundle from(Path directory) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("Model directory must not be null");
        }
        Path source = directory.toAbsolutePath().normalize();
        if (hasCompleteBundle(source)) {
            return new ModelBundle(source);
        }
        Path runtime = Paths.get(System.getProperty("voxel.voice.runtime.models",
                "dev/voice-models"));
        Path assembled = ModelAssembler.prepare(source, runtime);
        ModelBundle bundle = new ModelBundle(assembled);
        if (!hasCompleteBundle(assembled)) {
            throw new IOException("Model assembler did not produce a complete voice bundle: "
                    + assembled);
        }
        return bundle;
    }

    public static ModelBundle defaultBundle() throws IOException {
        return from(Paths.get("models", "java"));
    }

    public Path path(String name) {
        return directory.resolve(name);
    }

    public Path directory() {
        return directory;
    }

    /** True when the Coqui VITS base (the only neural base) is present. */
    public boolean hasCoqui() {
        return Files.isRegularFile(directory.resolve("coqui-vctk-vits.onnx"))
                && Files.isRegularFile(directory.resolve("coqui-vctk-vocab.json"))
                && Files.isRegularFile(directory.resolve("cmudict.dict"));
    }

    private static boolean hasCompleteBundle(Path directory) {
        return Files.isRegularFile(directory.resolve("coqui-vctk-vits.onnx"))
                && Files.isRegularFile(directory.resolve("coqui-vctk-vocab.json"))
                && Files.isRegularFile(directory.resolve("coqui-vctk-config.json"))
                && Files.isRegularFile(directory.resolve("cmudict.dict"))
                && Files.isRegularFile(directory.resolve("vec-768-layer-12.onnx"))
                && Files.isRegularFile(directory.resolve("rvc-villager.onnx"));
    }
}
