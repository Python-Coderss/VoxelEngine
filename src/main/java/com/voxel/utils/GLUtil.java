package com.voxel.utils;

import static org.lwjgl.opengl.GL11.*;

public class GLUtil {
    public static void checkError(String label) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            String errorName;
            switch (error) {
                case GL_INVALID_ENUM:      errorName = "GL_INVALID_ENUM"; break;
                case GL_INVALID_VALUE:     errorName = "GL_INVALID_VALUE"; break;
                case GL_INVALID_OPERATION: errorName = "GL_INVALID_OPERATION"; break;
                case GL_STACK_OVERFLOW:    errorName = "GL_STACK_OVERFLOW"; break;
                case GL_STACK_UNDERFLOW:   errorName = "GL_STACK_UNDERFLOW"; break;
                case GL_OUT_OF_MEMORY:     errorName = "GL_OUT_OF_MEMORY"; break;
                case 0x0506:               errorName = "GL_INVALID_FRAMEBUFFER_OPERATION"; break;
                default:                   errorName = "UNKNOWN"; break;
            }
            System.err.println("OpenGL Error [" + label + "]: " + errorName + " (0x" + Integer.toHexString(error) + ")");
        }
    }
}
