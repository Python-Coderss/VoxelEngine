package com.voxel.audio;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Minimal 16-bit PCM WAV writer (little-endian RIFF). */
public class WavIO {

    public static void write(File file, AudioData audio) throws IOException {
        int frameCount = audio.frameCount();
        int blockAlign = audio.channels * 2;
        int dataSize = frameCount * blockAlign;
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeBytes("RIFF");
            writeIntLE(out, 36 + dataSize);
            out.writeBytes("WAVE");
            out.writeBytes("fmt ");
            writeIntLE(out, 16);
            writeShortLE(out, 1); // PCM
            writeShortLE(out, audio.channels);
            writeIntLE(out, audio.sampleRate);
            writeIntLE(out, audio.sampleRate * blockAlign);
            writeShortLE(out, blockAlign);
            writeShortLE(out, 16);
            out.writeBytes("data");
            writeIntLE(out, dataSize);
            for (int i = 0; i < audio.samples.length; i++) {
                int s = (int) (audio.samples[i] * 32767f);
                if (s > 32767) s = 32767;
                if (s < -32768) s = -32768;
                out.writeByte(s & 0xFF);
                out.writeByte((s >> 8) & 0xFF);
            }
        }
    }

    private static void writeIntLE(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xFF);
        out.writeByte((v >> 8) & 0xFF);
        out.writeByte((v >> 16) & 0xFF);
        out.writeByte((v >> 24) & 0xFF);
    }

    private static void writeShortLE(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xFF);
        out.writeByte((v >> 8) & 0xFF);
    }
}
