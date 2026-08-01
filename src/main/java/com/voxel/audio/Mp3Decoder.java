package com.voxel.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Pure-Java MP3 -> PCM decoder backed by JLayer (javazoom:jlayer:1.0.1, LGPL).
 * Decodes a full file into memory as interleaved float samples in [-1, 1].
 */
public class Mp3Decoder {

    public static AudioData decode(File file) throws Exception {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(16 * 1024 * 1024);
        int sampleRate = 44100;
        int channels = 2;

        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();
            Header header;
            try {
                while ((header = bitstream.readFrame()) != null) {
                    SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    short[] buf = sb.getBuffer();
                    int len = sb.getBufferLength();
                    for (int i = 0; i < len; i++) {
                        short s = buf[i];
                        pcm.write(s & 0xFF);
                        pcm.write((s >> 8) & 0xFF);
                    }
                    sampleRate = sb.getSampleFrequency();
                    channels = sb.getChannelCount();
                    bitstream.closeFrame();
                }
            } finally {
                try {
                    bitstream.close();
                } catch (Exception ignored) {
                }
            }
        }

        byte[] raw = pcm.toByteArray();
        int n = raw.length / 2;
        float[] samples = new float[n];
        for (int i = 0; i < n; i++) {
            short s = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
            samples[i] = s / 32768f;
        }
        return new AudioData(samples, channels, sampleRate);
    }
}
