package com.voxel.utils;

import static org.lwjgl.opengl.GL20.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Utility class for loading, compiling, and linking OpenGL shaders.
 * Shaders are small programs that run on the GPU to handle rendering.
 */
public class ShaderUtil {

    /**
     * Reads the entire content of a file into a string.
     * @param path The path to the file.
     * @return The file content as a string.
     * @throws IOException If the file cannot be read.
     */
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
