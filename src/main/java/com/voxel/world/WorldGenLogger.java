package com.voxel.world;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * File-based logger for debugging world generation, chunk loading, and disk I/O.
 * Writes to worldgen_debug.log in the project root.
 * Thread-safe: uses synchronized on the PrintWriter.
 *
 * Unlike RedstoneLogger, this does NOT debounce — every log entry is preserved
 * because world gen events are one-shot (not hot-loop).
 */
public class WorldGenLogger {
    private static PrintWriter writer;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("HH:mm:ss.SSS");
    private static boolean enabled = false;

    private static long lastFlushTime = 0;
    private static final long FLUSH_INTERVAL_MS = 200;

    /** Call once at startup to open the log file. */
    public static void init() {
        try {
            String path = System.getProperty("user.dir", ".") + "/worldgen_debug.log";
            writer = new PrintWriter(new FileWriter(path, false));
            enabled = true;
            log("=== WorldGen logger started ===");
        } catch (IOException e) {
            System.err.println("WorldGenLogger: failed to open log file: " + e.getMessage());
            enabled = false;
        }
    }

    /** Write a message with timestamp and thread name. Thread-safe. */
    public static void log(String msg) {
        if (!enabled || writer == null) return;
        synchronized (writer) {
            String thread = Thread.currentThread().getName();
            writer.println(DATE_FMT.format(new Date()) + " [" + thread + "] " + msg);
            long now = System.currentTimeMillis();
            if (now - lastFlushTime > FLUSH_INTERVAL_MS) {
                writer.flush();
                lastFlushTime = now;
            }
        }
    }

    /** Write a formatted message with chunk position info. */
    public static void logChunk(String tag, int cx, int cy, int cz, String msg) {
        log(tag + " chunk(" + cx + "," + cy + "," + cz + ") " + msg);
    }

    /** Write a formatted message with world position info. */
    public static void logPos(String tag, int x, int y, int z, String msg) {
        log(tag + " pos(" + x + "," + y + "," + z + ") " + msg);
    }

    /** Force flush. Call on shutdown. */
    public static void flush() {
        if (!enabled || writer == null) return;
        synchronized (writer) {
            writer.flush();
        }
    }

    /** Close the log file. */
    public static void shutdown() {
        if (writer != null) {
            log("=== WorldGen logger shutting down ===");
            flush();
            synchronized (writer) {
                writer.close();
            }
            writer = null;
        }
    }
}
