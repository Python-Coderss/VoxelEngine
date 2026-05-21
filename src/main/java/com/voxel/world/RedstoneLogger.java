package com.voxel.world;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * File-based logger for debugging the redstone system.
 * Writes to redstone_debug.log in the project root.
 * Thread-safe: uses synchronized on the PrintWriter.
 *
 * Performance: Uses a simple debounce mechanism — the same consecutive message
 * is not written more than once per second. This prevents log spam from
 * hot loops (e.g. tickLamps running 60/sec while idle).
 */
public class RedstoneLogger {
    private static PrintWriter writer;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("HH:mm:ss.SSS");
    private static boolean enabled = false;

    // Debounce: track last-written message text and when it was written.
    // If the same message repeats within DEBOUNCE_MS, skip it and count repeats.
    private static String lastMsg = "";
    private static long lastMsgTime = 0;
    private static int lastMsgRepeatCount = 0;
    private static final long DEBOUNCE_MS = 1000;  // 1 second debounce

    // Periodic flush interval
    private static long lastFlushTime = 0;
    private static final long FLUSH_INTERVAL_MS = 500;  // flush every 500ms
    private static int linesSinceLastFlush = 0;

    /** Call once at startup to open the log file. */
    public static void init() {
        try {
            String path = System.getProperty("user.dir", ".") + "/redstone_debug.log";
            writer = new PrintWriter(new FileWriter(path, false));
            enabled = true;
            lastMsg = "";
            lastMsgTime = 0;
            lastMsgRepeatCount = 0;
            log("=== Redstone logger started ===");
        } catch (IOException e) {
            System.err.println("RedstoneLogger: failed to open log file: " + e.getMessage());
            enabled = false;
        }
    }

    /** Write a message with timestamp and thread name. Thread-safe. */
    public static void log(String msg) {
        if (!enabled || writer == null) return;
        synchronized (writer) {
            // Debounce: skip if same message was written recently
            if (msg.equals(lastMsg)) {
                long now = System.currentTimeMillis();
                if (now - lastMsgTime < DEBOUNCE_MS) {
                    lastMsgRepeatCount++;
                    return;
                }
                // Time to write the repeat summary
                if (lastMsgRepeatCount > 0) {
                    writer.println(DATE_FMT.format(new Date()) + " [REPEATED x" + (lastMsgRepeatCount + 1) + "]");
                    lastMsgRepeatCount = 0;
                }
            } else {
                // Different message — flush repeat count for previous message if any
                if (lastMsgRepeatCount > 0 && !lastMsg.isEmpty()) {
                    writer.println(DATE_FMT.format(new Date()) + " [REPEATED x" + lastMsgRepeatCount + "]");
                    lastMsgRepeatCount = 0;
                }
            }

            String thread = Thread.currentThread().getName();
            writer.println(DATE_FMT.format(new Date()) + " [" + thread + "] " + msg);
            lastMsg = msg;
            lastMsgTime = System.currentTimeMillis();
            linesSinceLastFlush++;

            // Periodic flush
            long now = System.currentTimeMillis();
            if (now - lastFlushTime > FLUSH_INTERVAL_MS || linesSinceLastFlush > 100) {
                writer.flush();
                lastFlushTime = now;
                linesSinceLastFlush = 0;
            }
        }
    }

    /** Write a formatted message with position info. */
    public static void log(String tag, int x, int y, int z, String msg) {
        log(tag + " (" + x + "," + y + "," + z + ") " + msg);
    }

    /** Force flush. Call on shutdown. */
    public static void flush() {
        if (!enabled || writer == null) return;
        synchronized (writer) {
            if (lastMsgRepeatCount > 0 && !lastMsg.isEmpty()) {
                writer.println(DATE_FMT.format(new Date()) + " [REPEATED x" + lastMsgRepeatCount + "]");
                lastMsgRepeatCount = 0;
            }
            writer.flush();
            lastFlushTime = System.currentTimeMillis();
            linesSinceLastFlush = 0;
        }
    }

    /** Close the log file. */
    public static void shutdown() {
        if (writer != null) {
            log("=== Redstone logger shutting down ===");
            flush();
            synchronized (writer) {
                writer.close();
            }
            writer = null;
        }
    }
}
