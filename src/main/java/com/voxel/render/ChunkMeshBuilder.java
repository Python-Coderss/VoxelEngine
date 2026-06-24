package com.voxel.render;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Builds triangle meshes from 16³ chunk section data.
 * Only extracts faces adjacent to air or non-full blocks.
 * 
 * Vertex format (10 floats per vertex = 40 bytes):
 *   posX, posY, posZ, normX, normY, normZ, texU, texV, blockId(as float bits), lightRGB(as float bits)
 * 
 * Uses direct NIO buffer writes — zero ArrayList/GC allocations during mesh building.
 * Based on Vercidium's advice: pre-allocate, pack tightly, write directly.
 */
public class ChunkMeshBuilder {

    private static final int CS = 16;

    // Face quad vertices in local space (0..16): each face has 4 corners
    private static final int[][] FACE_QUADS = {
        // -Y: 0,1,2,3  (bottom quad viewed from below)
        {0,0,0, 1,0,0, 1,0,1, 0,0,1},
        // +Y: 0,1,2,3  (top quad)
        {0,1,1, 1,1,1, 1,1,0, 0,1,0},
        // -Z: 0,1,2,3  (north quad)
        {0,0,0, 0,1,0, 1,1,0, 1,0,0},
        // +Z: 0,1,2,3  (south quad)
        {0,0,1, 1,0,1, 1,1,1, 0,1,1},
        // -X: 0,1,2,3  (west quad)
        {0,0,1, 0,1,1, 0,1,0, 0,0,0},
        // +X: 0,1,2,3  (east quad)
        {1,0,0, 1,1,0, 1,1,1, 1,0,1},
    };

    private static final float[][] FACE_NORMALS = {
        {0,-1,0}, {0,1,0}, {0,0,-1}, {0,0,1}, {-1,0,0}, {1,0,0},
    };

    // UV coords for each face quad (4 corners × 2 components) — standard 0-1 mapping
    private static final float[][] FACE_UVS = {
        {0,0, 1,0, 1,1, 0,1}, // -Y: standard
        {0,0, 1,0, 1,1, 0,1}, // +Y: standard
        {0,0, 1,0, 1,1, 0,1}, // -Z: standard
        {0,0, 1,0, 1,1, 0,1}, // +Z: standard
        {0,0, 1,0, 1,1, 0,1}, // -X: standard
        {0,0, 1,0, 1,1, 0,1}, // +X: standard
    };

    // 6 neighbor offsets: -Y, +Y, -Z, +Z, -X, +X
    private static final int[] NX = {0, 0,  0,  0, -1,  1};
    private static final int[] NY = {-1, 1, 0,  0,  0,  0};
    private static final int[] NZ = {0, 0, -1,  1,  0,  0};

    private final World world;
    private final BlockDataManager blockDataManager;

    // Pre-allocated direct buffers (sized for worst case: all 16³ blocks have all 6 faces visible)
    private static final int MAX_QUADS = CS * CS * CS * 6;
    private static final int FLOATS_PER_VERT = 10;

    private final FloatBuffer vertexBuf;
    private final IntBuffer indexBuf;

    public ChunkMeshBuilder(World world, BlockDataManager blockDataManager) {
        this.world = world;
        this.blockDataManager = blockDataManager;
        this.vertexBuf = MemoryUtil.memAllocFloat(MAX_QUADS * 4 * FLOATS_PER_VERT);
        this.indexBuf = MemoryUtil.memAllocInt(MAX_QUADS * 6);
    }

    /**
     * Build mesh for a chunk sector at given absolute chunk coordinates.
     * Writes directly to pre-allocated NIO buffers — zero GC pressure.
     */
    public MeshResult build(int absCX, int absCY, int absCZ) {
        vertexBuf.clear();
        indexBuf.clear();

        int worldX = absCX << 4;
        int worldY = absCY << 4;
        int worldZ = absCZ << 4;
        int baseIndex = 0;

        for (int ly = 0; ly < CS; ly++) {
            for (int lz = 0; lz < CS; lz++) {
                for (int lx = 0; lx < CS; lx++) {
                    int wx = worldX + lx;
                    int wy = worldY + ly;
                    int wz = worldZ + lz;

                    int blockId = world.getVoxel(wx, wy, wz);
                    if (blockId <= 0) continue;

                    boolean isFullBlock = blockDataManager.isFullBlock(blockId);
                    boolean isLiquid = blockDataManager.isLiquid(blockId);
                    boolean isPortal = blockDataManager.isPortal(blockId);

                    // Skip non-full blocks that aren't liquids or portals
                    if (!isFullBlock && !isLiquid && !isPortal) continue;

                    // Get Minecraft-style packed lightmap for this voxel (sky<<20 | block<<4)
                    int lightRGB = world.getPackedLightmap(wx, wy, wz);

                    // Check each neighbor; emit face if neighbor is transparent
                    for (int face = 0; face < 6; face++) {
                        int nx = lx + NX[face];
                        int ny = ly + NY[face];
                        int nz = lz + NZ[face];

                        int neighborId;
                        if (nx < 0 || nx >= CS || ny < 0 || ny >= CS || nz < 0 || nz >= CS) {
                            // Neighbor outside chunk section — query world
                            neighborId = world.getVoxel(worldX + nx, worldY + ny, worldZ + nz);
                        } else {
                            // Neighbor in same section — use local query
                            neighborId = world.getVoxel(worldX + nx, worldY + ny, worldZ + nz);
                        }

                        // Render face if neighbor is transparent
                        boolean renderFace = false;
                        if (neighborId <= 0) {
                            renderFace = true; // air
                        } else if (isLiquid && !blockDataManager.isLiquid(neighborId)) {
                            renderFace = true; // liquid adjacent to non-liquid
                        } else if (isPortal) {
                            renderFace = true; // portals always render
                        } else if (!blockDataManager.isFullBlock(neighborId) && !blockDataManager.isLiquid(neighborId)) {
                            renderFace = true; // adjacent to transparent non-liquid
                        }

                        if (!renderFace) continue;

                        // Emit 4 vertices + 6 indices for this face
                        int[] fq = FACE_QUADS[face];
                        float[] fn = FACE_NORMALS[face];
                        float[] fuv = FACE_UVS[face];

                        for (int v = 0; v < 4; v++) {
                            float px = (float)(fq[v*3] + (lx + 0));
                            float py = (float)(fq[v*3+1] + (ly + 0));
                            float pz = (float)(fq[v*3+2] + (lz + 0));
                            vertexBuf.put(px);
                            vertexBuf.put(py);
                            vertexBuf.put(pz);
                            vertexBuf.put(fn[0]);  vertexBuf.put(fn[1]);  vertexBuf.put(fn[2]);
                            vertexBuf.put(fuv[v*2]);  vertexBuf.put(fuv[v*2+1]);
                            vertexBuf.put(Float.intBitsToFloat(blockId));
                            vertexBuf.put(Float.intBitsToFloat(lightRGB));
                        }

                        // Two triangles: 0,1,2  and  0,2,3  (both CCW from outside)
                        indexBuf.put(baseIndex);
                        indexBuf.put(baseIndex + 1);
                        indexBuf.put(baseIndex + 2);
                        indexBuf.put(baseIndex);
                        indexBuf.put(baseIndex + 2);
                        indexBuf.put(baseIndex + 3);
                        baseIndex += 4;
                    }
                }
            }
        }

        int floatCount = vertexBuf.position();
        int indexCount = indexBuf.position();

        if (floatCount == 0) return new MeshResult(new float[0], new int[0]);

        vertexBuf.flip();
        indexBuf.flip();

        float[] vertices = new float[floatCount];
        vertexBuf.get(vertices);
        int[] indices = new int[indexCount];
        indexBuf.get(indices);

        return new MeshResult(vertices, indices);
    }



    public void delete() {
        if (vertexBuf != null) MemoryUtil.memFree(vertexBuf);
        if (indexBuf != null) MemoryUtil.memFree(indexBuf);
    }

    /** Result of building a chunk mesh. */
    public static class MeshResult {
        public final float[] vertices;
        public final int[] indices;

        public MeshResult(float[] vertices, int[] indices) {
            this.vertices = vertices;
            this.indices = indices;
        }

        public boolean isEmpty() { return vertices.length == 0; }
    }
}
