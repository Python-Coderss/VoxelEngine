package com.voxel.utils;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL41.*;
import static org.lwjgl.opengl.GL43.*;
import org.lwjgl.BufferUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * Utility class for loading, compiling, and linking OpenGL shaders.
 * Shaders are small programs that run on the GPU to handle rendering.
 */
public class ShaderUtil {

    /**
     * Compiled-program binary cache.
     *
     * <p>The raytracer is a large compute shader; a cold GLSL compile/link costs
     * several seconds on real drivers, which dominated boot. GL lets us dump the
     * linked program and reload it with {@code glProgramBinary}, typically in a
     * few milliseconds. The cache is keyed by the shader source hash plus the
     * GL renderer/version string, so a driver update, shader edit or different
     * GPU automatically misses instead of running a stale program.</p>
     *
     * <p>Disable with {@code -Dvoxel.shader.cache=off}; a failed load falls back
     * to a normal compile.</p>
     */
    private static final boolean CACHE_ENABLED =
            !"off".equalsIgnoreCase(System.getProperty("voxel.shader.cache", "on"));
    private static final Path CACHE_DIR = Paths.get("target", "shader-cache");
    private static boolean cacheDirReady;


    /**
     * Reads the entire content of a file into a string.
     * @param path The path to the file.
     * @return The file content as a string.
     * @throws IOException If the file cannot be read.
     */
    /** Compiles+links a vertex/fragment pair, reusing a cached binary if valid. */
    public static int createGraphicsProgramCached(String cacheName, String vertPath, String fragPath) {
        String key = sourceKey(vertPath, fragPath);
        int cached = loadProgramBinary(cacheName, key);
        if (cached != 0) return cached;
        int program = createProgram(
                compileShader(vertPath, GL_VERTEX_SHADER),
                compileShader(fragPath, GL_FRAGMENT_SHADER));
        saveProgramBinary(cacheName, key, program);
        return program;
    }

    /** Compiles+links a compute shader program, reusing a cached binary if valid. */
    public static int createComputeProgramCached(String cacheName, String path) {
        String key = sourceKey(path);
        int cached = loadProgramBinary(cacheName, key);
        if (cached != 0) return cached;
        int program = createProgram(compileShader(path, GL_COMPUTE_SHADER));
        saveProgramBinary(cacheName, key, program);
        return program;
    }

    /** SHA-256 of every shader source + the driver/renderer identity. */
    private static String sourceKey(String... paths) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String path : paths) md.update(Files.readAllBytes(Paths.get(path)));
            md.update(String.valueOf(glGetString(GL_RENDERER)).getBytes("UTF-8"));
            md.update(String.valueOf(glGetString(GL_VERSION)).getBytes("UTF-8"));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "nocache-" + System.nanoTime();
        }
    }

    private static Path cacheFile(String name, String key) {
        if (!cacheDirReady) {
            try {
                Files.createDirectories(CACHE_DIR);
            } catch (IOException ignored) {
                return null;
            }
            cacheDirReady = true;
        }
        return CACHE_DIR.resolve(name + "-" + key.substring(0, Math.min(16, key.length())) + ".bin");
    }

    private static int loadProgramBinary(String name, String key) {
        if (!CACHE_ENABLED) return 0;
        Path file = cacheFile(name, key);
        if (file == null || !Files.isRegularFile(file)) return 0;
        try {
            ByteBuffer raw = BufferUtils.createByteBuffer((int) Files.size(file));
            raw.put(Files.readAllBytes(file)).flip();
            if (raw.remaining() < 8) return 0;
            int format = raw.getInt();
            int length = raw.getInt();
            if (length <= 0 || length > raw.remaining()) return 0;
            ByteBuffer binary = raw.slice();
            binary.limit(length);
            int program = glCreateProgram();
            glProgramBinary(program, format, binary);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                glDeleteProgram(program);
                return 0;
            }
            return program;
        } catch (Throwable t) {
            // A binary from another driver/revision is not fatal: recompile.
            return 0;
        }
    }

    private static void saveProgramBinary(String name, String key, int program) {
        if (!CACHE_ENABLED) return;
        Path file = cacheFile(name, key);
        if (file == null) return;
        try {
            int length = glGetProgrami(program, GL_PROGRAM_BINARY_LENGTH);
            if (length <= 0) return;
            IntBuffer written = BufferUtils.createIntBuffer(1);
            IntBuffer format = BufferUtils.createIntBuffer(1);
            ByteBuffer binary = BufferUtils.createByteBuffer(length);
            glGetProgramBinary(program, written, format, binary);
            if (written.get(0) <= 0) return;
            binary.limit(written.get(0));
            ByteBuffer out = BufferUtils.createByteBuffer(8 + binary.remaining());
            out.putInt(format.get(0)).putInt(binary.remaining()).put(binary).flip();
            byte[] bytes = new byte[out.remaining()];
            out.get(bytes);
            Files.write(file, bytes);
        } catch (Throwable ignored) {
            // Caching is an optimization; never fail the actual program.
        }
    }

    public static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)));
    }

    /**
     * Compiles a shader from a source file.
     * @param path The path to the shader source file (e.g., .vert, .frag, .comp).
     * @param type The type of shader (e.g., GL_VERTEX_SHADER, GL_FRAGMENT_SHADER, GL_COMPUTE_SHADER).
     * @return The ID of the compiled shader.
     */
    public static int compileShader(String path, int type) {
        String source;
        try {
            source = readFile(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader file: " + path, e);
        }

        // 1. Create a shader object on the GPU
        int shader = glCreateShader(type);

        // 2. Upload the source code string to the shader object
        glShaderSource(shader, source);

        // 3. Tell the GPU to compile the shader
        glCompileShader(shader);

        // 4. Check if compilation was successful
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader); // Get the error log
            glDeleteShader(shader);
            throw new RuntimeException("Failed to compile shader (" + path + "):\n" + log);
        }

        return shader;
    }

    /**
     * Links multiple shaders together into a single shader program.
     * @param shaders Variable number of shader IDs to link.
     * @return The ID of the linked program.
     */
    public static int createProgram(int... shaders) {
        // 1. Create a program object on the GPU
        int program = glCreateProgram();

        // 2. Attach all the shaders to the program
        for (int shader : shaders) glAttachShader(program, shader);

        // 3. Link the attached shaders into a final executable program
        glLinkProgram(program);

        // 4. Check if linking was successful
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program); // Get the error log
            glDeleteProgram(program);
            throw new RuntimeException("Failed to link shader program:\n" + log);
        }

        // 5. Clean up: Detach shaders from the program after linking is done
        for (int shader : shaders) glDetachShader(program, shader);

        return program;
    }
}
