package com.voxel.audio;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * OGG Vorbis -> {@link AudioData} decoder backed by LWJGL's stb_vorbis
 * (already shipped with {@code lwjgl-stb} for the villager voice clips).
 *
 * <p>Used by {@link MusicPlayer} so the bundled Aether soundtrack
 * ({@code assets/aether/sounds/music/*.ogg}) can join the same music library as
 * the MCSM MP3s. Decoding preserves the source channel count; callers downmix
 * with {@link AudioData#toMono()} before upload.</p>
 */
public final class OggDecoder {

    private OggDecoder() {
    }

    public static AudioData decode(File file) throws IOException {
        byte[] ogg = Files.readAllBytes(file.toPath());
        return decode(ogg);
    }

    /** Decode a complete .ogg file held in memory. */
    public static AudioData decode(byte[] ogg) throws IOException {
        if (ogg == null || ogg.length == 0) {
            throw new IOException("empty ogg data");
        }
        java.nio.ByteBuffer mem = MemoryUtil.memAlloc(ogg.length).put(ogg);
        mem.flip();
        IntBuffer error = MemoryUtil.memAllocInt(1);
        long vorbis = 0L;
        FloatBuffer samples = null;
        STBVorbisInfo info = null;
        try {
            vorbis = STBVorbis.stb_vorbis_open_memory(mem, error, null);
            if (vorbis == 0L) {
                throw new IOException("stb_vorbis could not open track (error "
                        + error.get(0) + ")");
            }
            info = STBVorbisInfo.malloc();
            STBVorbis.stb_vorbis_get_info(vorbis, info);
            int channels = Math.max(1, info.channels());
            int sampleRate = Math.max(1, info.sample_rate());

            int frames = STBVorbis.stb_vorbis_stream_length_in_samples(vorbis);
            int capacity = Math.max(frames, 4096) * channels;
            float[] pcm = new float[capacity];
            int total = 0;
            samples = MemoryUtil.memAllocFloat(Math.max(4096, frames) * channels);
            while (true) {
                samples.clear();
                int got = STBVorbis.stb_vorbis_get_samples_float_interleaved(
                        vorbis, channels, samples);
                if (got <= 0) break;
                int count = got * channels;
                if (total + count > pcm.length) {
                    pcm = Arrays.copyOf(pcm, Math.max(pcm.length * 2, total + count));
                }
                samples.position(0);
                samples.limit(count);
                samples.get(pcm, total, count);
                total += count;
            }
            if (total == 0) {
                throw new IOException("ogg track decoded to zero samples");
            }
            return new AudioData(Arrays.copyOf(pcm, total), channels, sampleRate);
        } finally {
            if (vorbis != 0L) STBVorbis.stb_vorbis_close(vorbis);
            if (info != null) info.free();
            if (samples != null) MemoryUtil.memFree(samples);
            MemoryUtil.memFree(error);
            MemoryUtil.memFree(mem);
        }
    }
}
