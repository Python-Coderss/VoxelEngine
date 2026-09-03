package villager.voice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * RVC feature-index retrieval ("index rate") over the villager training
 * embeddings. The faiss IVFFlat index is pre-flattened offline into a simple
 * binary (centroids + per-list vector blocks); at runtime each ContentVec
 * frame finds its nearest centroid list, then the closest stored embedding in
 * that list, and blends it into the frame. This pulls converted features
 * toward real Dan Lloyd speech segments, which tightens the timbre beyond
 * what the generator alone manages.
 */
public final class FeatureIndex {
    private static final int MAGIC = 0x58495652; // 'RVIX' little-endian
    private final int dimension;
    private final int listCount;
    private FloatBuffer centroids;
    private final int[] listStart;
    private final int[] listLength;
    private FloatBuffer vectors;
    private final float[] scratch;

    private FeatureIndex(int dimension, int listCount) {
        this.dimension = dimension;
        this.listCount = listCount;
        this.listStart = new int[listCount];
        this.listLength = new int[listCount];
        this.scratch = new float[dimension];
    }

    public static FeatureIndex load(Path bin) throws IOException {
        long bytes = Files.size(bin);
        FileChannel channel = FileChannel.open(bin);
        try {
            ByteBuffer head = channel.map(FileChannel.MapMode.READ_ONLY, 0, bytes)
                    .order(ByteOrder.LITTLE_ENDIAN);
            if (head.getInt() != MAGIC || head.getInt() != 1) {
                throw new IOException(bin + ": not an RVIX v1 feature index");
            }
            int dimension = head.getInt();
            int listCount = head.getInt();
            FeatureIndex index = new FeatureIndex(dimension, listCount);
            int centroidFloats = dimension * listCount;
            head.position(16 + centroidFloats * 4);
            long total = 0;
            for (int i = 0; i < listCount; i++) {
                index.listLength[i] = head.getInt();
                index.listStart[i] = (int) total;
                total += index.listLength[i];
            }
            int vectorStart = 16 + centroidFloats * 4 + listCount * 4;

            ByteBuffer centroidView = channel.map(
                    FileChannel.MapMode.READ_ONLY, 0, bytes).order(ByteOrder.LITTLE_ENDIAN);
            centroidView.position(16);
            index.centroids = centroidView.slice()
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();

            ByteBuffer mapView = channel.map(FileChannel.MapMode.READ_ONLY, 0, bytes)
                    .order(ByteOrder.LITTLE_ENDIAN);
            mapView.position(vectorStart);
            index.vectors = mapView.slice()
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            return index;
        } finally {
            channel.close();
        }
    }

    public int dimension() {
        return dimension;
    }

    /**
     * Blend retrieved training embeddings into ContentVec frames in place.
     *
     * @param rate blend strength, 0 disables; ~0.7 mirrors the web UI default
     */
    public void apply(float[][] content, double rate) {
        if (rate <= 0.0) {
            return;
        }
        double keep = Math.max(0.0, 1.0 - rate);
        for (float[] frame : content) {
            if (frame.length != dimension) {
                return; // unexpected shape; leave content untouched
            }
            normalizeInto(frame, scratch);
            int bestList = 0;
            double bestScore = -Double.MAX_VALUE;
            for (int list = 0; list < listCount; list++) {
                double score = dot(centroids, (long) list * dimension, scratch);
                if (score > bestScore) {
                    bestScore = score;
                    bestList = list;
                }
            }
            int start = listStart[bestList];
            int length = listLength[bestList];
            int bestVector = -1;
            bestScore = -Double.MAX_VALUE;
            for (int i = 0; i < length; i++) {
                double score = dot(vectors, (long) (start + i) * dimension, scratch);
                if (score > bestScore) {
                    bestScore = score;
                    bestVector = start + i;
                }
            }
            if (bestVector < 0) {
                continue;
            }
            vectors.position(bestVector * dimension);
            for (int j = 0; j < dimension; j++) {
                frame[j] = (float) (frame[j] * keep + vectors.get() * rate);
            }
        }
    }

    private static void normalizeInto(float[] frame, float[] out) {
        double norm = 0.0;
        for (int j = 0; j < frame.length; j++) {
            norm += frame[j] * (double) frame[j];
        }
        norm = Math.sqrt(norm);
        double scale = norm > 1e-9 ? 1.0 / norm : 0.0;
        for (int j = 0; j < frame.length; j++) {
            out[j] = (float) (frame[j] * scale);
        }
    }

    private static double dot(FloatBuffer bank, long offsetElements, float[] vector) {
        int base = (int) offsetElements;
        double sum = 0.0;
        for (int j = 0; j < vector.length; j++) {
            sum += bank.get(base + j) * (double) vector[j];
        }
        return sum;
    }
}
