package com.voxel.utils;

import static org.lwjgl.opengl.GL11.*;

/**
 * Utility class for OpenGL-related tasks, primarily debugging.
 */
public class GLUtil {

    /**
     * Checks if any OpenGL errors have occurred and prints them to the console.
     * This is very useful for finding bugs in graphics code.
     * @param label A descriptive string to help identify where the error occurred.
     */
    public static void checkError(String label) {
        // Fetch the last error from OpenGL's internal error state.
        int error = glGetError();

        // GL_NO_ERROR means everything is fine.
        if (error != GL_NO_ERROR) {
            String errorName;
            // Map the error code to a human-readable name.
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
            // Print the error with its label and hex code.
            System.err.println("OpenGL Error [" + label + "]: " + errorName + " (0x" + Integer.toHexString(error) + ")");
        }
    }
}
