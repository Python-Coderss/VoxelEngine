package com.voxel;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Unified file-based logger that captures ALL System.out and System.err output
 * and writes it to game.log in the project root.
 *
 * Uses a TeeOutputStream pattern: output goes to both the original stream
 * (console) and the log file. This means NO code changes are needed —
 * every System.out.println and System.err.println automatically ends up in the log.
 *
 * The tee and structured log() calls share a single FileOutputStream (append mode),
 * synchronized on the same lock, so there's no interleaving between the two paths.
 *
 * Thread-safe: uses a single synchronized block for all file writes.
 */
public class GameLogger {
    private static PrintStream originalOut;
    private static PrintStream originalErr;
    private static FileOutputStream fileOutput;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static boolean enabled = false;

    /** Call once at startup. Redirects System.out/err to also write to game.log. */
    public static void init() {
        if (enabled) return;

        originalOut = System.out;
        originalErr = System.err;

        try {
            String path = System.getProperty("user.dir", ".") + "/game.log";

            // Truncate the log file on startup
            new FileOutputStream(path, false).close();

            // Open in append mode — single handle shared by tee and log()
            fileOutput = new FileOutputStream(path, true);
            enabled = true;

            // Create tee streams that write to BOTH console AND log file
            OutputStream outTee = new TeeOutputStream(originalOut, fileOutput);
            OutputStream errTee = new TeeOutputStream(originalErr, fileOutput);

            System.setOut(new PrintStream(outTee, true));
            System.setErr(new PrintStream(errTee, true));

            log("=== GameLogger started === " + DATE_FMT.format(new Date()));
            log("Log file: " + path);
        } catch (IOException e) {
            originalOut.println("GameLogger: failed to open log file: " + e.getMessage());
            enabled = false;
        }
    }

    /**
     * Write a structured message with timestamp. Thread-safe.
     * Writes to the log file only (not console). Uses the same FileOutputStream
     * as the tee, synchronized on the same lock object.
     */
    public static void log(String msg) {
        if (!enabled || fileOutput == null) return;
        synchronized (fileOutput) {
            try {
                String line = DATE_FMT.format(new Date()) + " ["
                    + Thread.currentThread().getName() + "] " + msg + "\n";
                fileOutput.write(line.getBytes("UTF-8"));
                fileOutput.flush();
            } catch (IOException ignored) {
                // Log failure is non-fatal
            }
        }
    }

    /** Force flush the log file. */
    public static void flush() {
        if (!enabled || fileOutput == null) return;
        synchronized (fileOutput) {
            try { fileOutput.flush(); } catch (IOException ignored) {}
        }
    }

    /** Restore original streams and close log file. Call on shutdown. */
    public static void shutdown() {
        if (!enabled) return;

        log("=== GameLogger shutting down ===");

        // Restore original System.out/err
        System.setOut(originalOut);
        System.setErr(originalErr);

        // Close the shared file handle
        if (fileOutput != null) {
            synchronized (fileOutput) {
                try { fileOutput.flush(); } catch (IOException ignored) {}
                try { fileOutput.close(); } catch (IOException ignored) {}
            }
            fileOutput = null;
        }

        enabled = false;
    }

    /**
     * OutputStream that writes to both a PrintStream (console) and a
     * FileOutputStream (log file). Raw bytes pass through unchanged —
     * the PrintStream above encodes characters once, and both sides
     * receive identical bytes.
     */
    private static class TeeOutputStream extends OutputStream {
        private final PrintStream console;
        private final FileOutputStream file;

        TeeOutputStream(PrintStream console, FileOutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(int b) throws IOException {
            console.write(b);
            synchronized (file) {
                file.write(b);
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            console.write(buf, off, len);
            synchronized (file) {
                file.write(buf, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            synchronized (file) {
                file.flush();
            }
        }

        @Override
        public void close() throws IOException {
            // Don't close console — just flush file
            synchronized (file) {
                file.flush();
            }
        }
    }
}
