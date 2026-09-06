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
 * frame finds its nearest centroid list, then the top-8 closest stored
 * embeddings in that list, and blends their weighted mean into the frame.
 * This pulls converted features toward real Dan Lloyd speech segments, which
 * tightens the timbre beyond what the generator alone manages.
 */
public final class FeatureIndex {
    private static final int MAGIC = 0x58495652; // 'RVIX' little-endian
    /** Weighted-mean neighbourhood size (RVC semantics; exact-search k). */
    private static final int TOP_K = 8;
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
     * <p>Retrieval is a top-8 weighted mean (weights 1/d^2 over cosine
     * distance) instead of a single-nearest copy: one fixed vector per frame
     * quantized the delivery onto individual training segments, which surfaced
     * as a metallic/gargly edge in the converted audio. A weighted mean tracks
     * the speaker's feature space smoothly, matching the web UI's faiss
     * retrieval with {@code k=8}.</p>
     *
     * @param rate   blend strength, 0 disables; the RVC "index rate"
     *               (0.5-0.75 suits the short villager dataset)
     * @param voiced optional per-frame voicing flags; when supplied, unvoiced
     *               frames (consonants, breaths, pauses) keep their original
     *               ContentVec features — the RVC "protect" semantics that
     *               stops retrieval from smearing frication into hoarseness.
     *               May be null when no F0 track is available.
     */
    public void apply(float[][] content, double rate, boolean[] voiced) {
        if (rate <= 0.0) {
            return;
        }
        double keep = Math.max(0.0, 1.0 - rate);
        for (int frameIndex = 0; frameIndex < content.length; frameIndex++) {
            float[] frame = content[frameIndex];
            if (frame.length != dimension) {
                return; // unexpected shape; leave content untouched
            }
            if (voiced != null && frameIndex < voiced.length && !voiced[frameIndex]) {
                continue; // protect: consonants and breaths stay unblended
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
            int neighbourCount = Math.min(TOP_K, length);
            if (neighbourCount <= 0) {
                continue;
            }
            // Descending top-K by cosine similarity (insertion into a fixed
            // sorted list; K is tiny so this beats a full sort).
            double[] bestScores = new double[neighbourCount];
            int[] bestVectors = new int[neighbourCount];
            java.util.Arrays.fill(bestScores, -Double.MAX_VALUE);
            java.util.Arrays.fill(bestVectors, -1);
            for (int i = 0; i < length; i++) {
                double score = dot(vectors, (long) (start + i) * dimension, scratch);
                if (score > bestScores[neighbourCount - 1]) {
                    int p = neighbourCount - 1;
                    while (p > 0 && bestScores[p - 1] < score) {
                        bestScores[p] = bestScores[p - 1];
                        bestVectors[p] = bestVectors[p - 1];
                        p--;
                    }
                    bestScores[p] = score;
                    bestVectors[p] = start + i;
                }
            }
            // RVC weighting: 1/d^2 where d is the distance the search ranked
            // by (here cosine). Clamp so an exact match cannot dominate to
            // infinity and reduce the neighbourhood back to a single copy.
            double weightSum = 0.0;
            double[] weights = new double[neighbourCount];
            for (int i = 0; i < neighbourCount; i++) {
                if (bestVectors[i] < 0) {
                    continue;
                }
                double distance = Math.max(1.0e-4, 1.0 - bestScores[i]);
                weights[i] = 1.0 / (distance * distance);
                weightSum += weights[i];
            }
            if (weightSum <= 0.0) {
                continue;
            }
            for (int j = 0; j < dimension; j++) {
                double blended = 0.0;
                for (int i = 0; i < neighbourCount; i++) {
                    if (weights[i] <= 0.0) {
                        continue;
                    }
                    blended += weights[i]
                            * vectors.get(bestVectors[i] * dimension + j);
                }
                frame[j] = (float) (frame[j] * keep
                        + (blended / weightSum) * rate);
            }
        }
    }

    /** Convenience overload when no voicing information is available. */
    public void apply(float[][] content, double rate) {
        apply(content, rate, null);
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
