package villager.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Dev-only: print RMS/peak/duration of a 16-bit PCM WAV, or compare two. */
public final class TestWavCompare {
    private TestWavCompare() {
    }

    public static void main(String[] args) throws Exception {
        float[] a = readPcm(Paths.get(args[0]));
        if (args.length < 2) {
            System.out.printf("%s: dur=%.2fs rms=%.4f peak=%.3f%n",
                    args[0], a.length / 24000.0, rms(a), peak(a));
            return;
        }
        float[] b = readPcm(Paths.get(args[1]));
        double rmsA = rms(a), rmsB = rms(b);
        System.out.printf("%s: dur=%.2fs rms=%.4f peak=%.3f%n",
                args[0], a.length / 24000.0, rmsA, peak(a));
        System.out.printf("%s: dur=%.2fs rms=%.4f peak=%.3f%n",
                args[1], b.length / 24000.0, rmsB, peak(b));
        System.out.printf("rms delta: %.2f%%%n", 100.0 * Math.abs(rmsA - rmsB)
                / Math.max(rmsA, rmsB));
    }

    private static float[] readPcm(java.nio.file.Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        // Find the data chunk (WAV may have extra chunks before data).
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = new String(bytes, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = buffer.getInt(pos + 4);
            if ("data".equals(id)) {
                int offset = pos + 8;
                int count = Math.min(size, bytes.length - offset) / 2;
                float[] out = new float[count];
                for (int i = 0; i < count; i++) {
                    out[i] = buffer.getShort(offset + i * 2) / 32768.0f;
                }
                return out;
            }
            pos += 8 + size + (size & 1);
        }
        throw new IllegalStateException("no data chunk: " + path);
    }

    private static double rms(float[] x) {
        double sum = 0;
        for (float v : x) sum += v * v;
        return Math.sqrt(sum / Math.max(1, x.length));
    }

    private static float peak(float[] x) {
        float p = 0;
        for (float v : x) p = Math.max(p, Math.abs(v));
        return p;
    }
}
