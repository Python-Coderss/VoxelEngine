package villager.voice;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * OGG Vorbis decoding for the Villager News addon voice clips, via LWJGL's
 * stb_vorbis (already shipped in {@code lwjgl-stb}). Produces the same mono
 * float {@link WavAudio} shape the clip pipeline plays.
 */
public final class OggAudio {

    private OggAudio() {
    }

    /** Decode a complete .ogg file held in memory to mono float samples. */
    public static WavAudio read(byte[] ogg) throws IOException {
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
                throw new IOException("stb_vorbis could not open clip (error "
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
                if (got <= 0) {
                    break;
                }
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
                throw new IOException("ogg clip decoded to zero samples");
            }
            float[] mono = toMono(pcm, total, channels);
            return new WavAudio(sampleRate, mono);
        } finally {
            if (vorbis != 0L) {
                STBVorbis.stb_vorbis_close(vorbis);
            }
            if (info != null) {
                info.free();
            }
            if (samples != null) {
                MemoryUtil.memFree(samples);
            }
            MemoryUtil.memFree(error);
            MemoryUtil.memFree(mem);
        }
    }

    private static float[] toMono(float[] pcm, int total, int channels) {
        if (channels == 1) {
            return Arrays.copyOf(pcm, total);
        }
        int frames = total / channels;
        float[] mono = new float[frames];
        for (int f = 0; f < frames; f++) {
            float sum = 0f;
            for (int c = 0; c < channels; c++) {
                sum += pcm[f * channels + c];
            }
            mono[f] = sum / channels;
        }
        return mono;
    }
}
