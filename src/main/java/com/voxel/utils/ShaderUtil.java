package com.voxel.utils;

import static org.lwjgl.opengl.GL20.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ShaderUtil {
    public static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)));
    }

    public static int compileShader(String path, int type) {
        String source;
        try {
            source = readFile(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader file: " + path, e);
        }
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("Failed to compile shader (" + path + "):\n" + log);
        }
        return shader;
    }

    public static int createProgram(int... shaders) {
        int program = glCreateProgram();
        for (int shader : shaders) glAttachShader(program, shader);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new RuntimeException("Failed to link shader program:\n" + log);
        }
        for (int shader : shaders) glDetachShader(program, shader);
        return program;
    }
}
