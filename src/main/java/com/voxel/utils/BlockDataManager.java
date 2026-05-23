package com.voxel.utils;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;

/**
 * Manages properties for each type of block (voxel) in the engine.
 * It parses Minecraft-style JSON models to determine which texture goes on
 * which face,
 * and handles uploading this property data to the GPU in a TBO (Texture Buffer
 * Object).
 */
public class BlockDataManager {
    // Stores block data indexed by their integer ID.
    private final Map<Integer, BlockData> blockRegistry = new HashMap<>();
    private final Map<String, Integer> nameToId = new HashMap<>();

    // OpenGL IDs for the Buffer and the Texture Buffer Object (TBO).
    private int tboId;
    private int textureId;

    // For block AABBs
    private int aabbTboId;
    private int aabbTextureId;
    private int infoTboId;
    private int infoTextureId;

    /**
     * Inner class representing the properties of a single block type.
     */
    public static class BlockData {
        // Texture indices for each of the 6 faces:
        // 0: -Y (Bottom), 1: +Y (Top), 2: -Z (North), 3: +Z (South), 4: -X (West), 5:
        // +X (East)
        public int[] tex = new int[6];

        // Opacity level (0 = fully opaque, 255 = fully transparent).
        public int transparency;

        // Reflection intensity (0 = matte, 255 = perfect mirror).
        public int reflectivity;

        // Whether the block color should be modified by the biome (e.g., grass).
        public int isTintable;

        // Whether this block is a liquid (water, lava).
        public boolean isLiquid;

        // Animated texture properties
        public boolean isAnimated;
        public int frameCount = 1;

        // Whether this block occupies the full voxel space (true for most blocks, false
        // for slabs/stairs/fences).
        public boolean isFullBlock;

        // The average albedo (color) of the block, extracted from its textures.
        public java.awt.Color albedo = java.awt.Color.WHITE;
        public String name = "";
        public float hardness = 1.0f;
        public String preferredTool = "hand";

        // List of AABBs for the block shape (each float[6]:
        // minx,miny,minz,maxx,maxy,maxz in 0-1 range)
        public List<float[]> aabbs = new ArrayList<>();
        // List of UVs for each face of each AABB (6 packed uints per AABB)
        public List<int[]> aabbUvs = new ArrayList<>();

        /** Initializes a block with no textures. */
        public BlockData() {
            for (int i = 0; i < 6; i++)
                tex[i] = -1;
            isFullBlock = true; // Default to full block
        }
    }

    /**
     * Registers a new block by parsing its JSON model and mapping textures.
     * 
     * @param id             The integer ID to assign to this block (must be > 0).
     * @param name           The name of the block (matches the model and texture
     *                       names).
     * @param textureManager The manager containing loaded textures.
     * @param modelsDir      Directory where model JSON files are located.
     */
    public void registerBlock(int id, String name, TextureManager textureManager, String modelsDir) {
        BlockData data = new BlockData();
        data.name = name;
        Map<String, String> textureMap = new HashMap<>();

        // Recursively resolve the block's model hierarchy to find all textures.
        resolveModelRecursive(name, modelsDir, textureMap, data);

        // Map collected textures from the model to the 6 physical faces.
        String all = textureMap.get("all");
        String side = textureMap.get("side");
        String end = textureMap.get("end");
        String top = textureMap.get("top");
        String bottom = textureMap.get("bottom");

        String representativeTexture = textureMap.getOrDefault("up", top != null ? top : (end != null ? end : all));

        // Map common Minecraft JSON keys to the 6 face indices.
        assignFace(data, 0, textureMap.getOrDefault("down", bottom != null ? bottom : (end != null ? end : all)),
                textureManager);
        assignFace(data, 1, representativeTexture, textureManager);
        assignFace(data, 2, textureMap.getOrDefault("north", side != null ? side : all), textureManager);
        assignFace(data, 3, textureMap.getOrDefault("south", side != null ? side : all), textureManager);
        assignFace(data, 4, textureMap.getOrDefault("west", side != null ? side : all), textureManager);
        assignFace(data, 5, textureMap.getOrDefault("east", side != null ? side : all), textureManager);

        // Calculate albedo using TextureUtils — search known texture directories
        if (representativeTexture != null) {
            String texName = representativeTexture;
            if (texName.contains("/")) {
                texName = texName.substring(texName.lastIndexOf('/') + 1);
            }
            String[] searchDirs = {
                "src/main/resources/assets/minecraft/textures/blocks",
                "src/main/resources/assets/aether/textures/block/natural",
                "src/main/resources/assets/aether/textures/block/construction",
                "src/main/resources/assets/aether/textures/block/dungeon",
                "src/main/resources/assets/aether/textures/block/utility",
                "src/main/resources/assets/aether/textures/block/miscellaneous"
            };
            for (String dir : searchDirs) {
                String texPath = dir + "/" + texName + ".png";
                if (Files.exists(Paths.get(texPath))) {
                    data.albedo = TextureUtils.getAverageColor(texPath);
                    break;
                }
            }
        }

        // Set demonstration properties based on block name.
        if (name.contains("glass")) {
            data.transparency = 150;
            data.reflectivity = 50;
        } else if (name.contains("mirror") || name.equals("diamond_block")) {
            data.reflectivity = 200;
        } else if (name.contains("water")) {
            data.transparency = 150;
            data.reflectivity = 100;
        }

        // Detect animated textures from the texture manager
        for (int faceTex : data.tex) {
            if (faceTex >= 0) {
                String texName = textureManager.getTextureNameByIndex(faceTex);
                if (texName != null) {
                    int fc = textureManager.getFrameCount(texName);
                    if (fc > 1) {
                        data.isAnimated = true;
                        data.frameCount = fc;
                        break;
                    }
                }
            }
        }

        // Set isFullBlock based on block type
        if (name.contains("slab") || name.contains("stairs") || name.contains("fence") ||
                name.contains("wall") || name.contains("door") || name.contains("trapdoor") ||
                name.contains("pane") || name.contains("carpet") || name.contains("dust") || name.contains("pressure_plate") ||
                name.contains("button") || name.contains("lever") || name.contains("torch") ||
                name.contains("rail") || name.contains("sign") || name.contains("banner") ||
                name.contains("flower") || name.contains("sapling") || name.contains("mushroom") ||
                name.contains("water") || name.contains("lava") ||
                name.contains("portal")) {
            data.isFullBlock = false;
        }
        if (name.contains("water") || name.contains("lava")) {
            data.isLiquid = true;
        }
        applyMiningDefaults(name, data);

        // Validate that we found a texture for every face.
        for (int i = 0; i < 6; i++) {
            if (data.tex[i] == -1) {
                throw new RuntimeException("Texture for block " + name + " (face " + i + ") could not be resolved!");
            }
        }

        blockRegistry.put(id, data);
        registerNameAlias(name, id);
        if (name.endsWith("_normal")) registerNameAlias(name.substring(0, name.length() - "_normal".length()), id);
        if (name.contains("grass")) registerNameAlias("grass", id);
        if (name.contains("stone")) registerNameAlias("stone", id);
        if (name.contains("glass")) registerNameAlias("glass", id);
        if (name.contains("leaves")) registerNameAlias("leaves", id);
        if (name.contains("dirt")) registerNameAlias("dirt", id);
        if (name.contains("sand")) registerNameAlias("sand", id);
    }

    private void registerNameAlias(String alias, int id) {
        nameToId.put(alias.toLowerCase(), id);
    }

    private void applyMiningDefaults(String name, BlockData data) {
        String lower = name.toLowerCase();
        if (lower.contains("stone")) {
            data.hardness = 1.6f;
            data.preferredTool = "pickaxe";
        } else if (lower.contains("glass")) {
            data.hardness = 0.4f;
            data.preferredTool = "hand";
        } else if (lower.contains("sand")) {
            data.hardness = 0.6f;
            data.preferredTool = "shovel";
        } else if (lower.contains("dirt") || lower.contains("grass")) {
            data.hardness = 0.7f;
            data.preferredTool = "shovel";
        } else if (lower.contains("leaves")) {
            data.hardness = 0.3f;
            data.preferredTool = "axe";
        }
    }

    /** Helper to resolve a texture key to its index in the TextureManager. */
    private void assignFace(BlockData data, int face, String texName, TextureManager textureManager) {
        if (texName == null)
            return;
        if (texName.startsWith("#"))
            return; // Unresolved variable reference

        // Strip path to get the texture name
        if (texName.contains("/")) {
            texName = texName.substring(texName.lastIndexOf('/') + 1);
        }
        data.tex[face] = textureManager.getTextureIndex(texName);
    }

    /**
     * Recursively parses JSON files to handle model inheritance (e.g., 'cube_all'
     * inherits from 'cube').
     */
    private void resolveModelRecursive(String modelName, String modelsDir, Map<String, String> textureMap,
            BlockData data) {
        // Strip any domain prefix (e.g., "minecraft:block/cube_all" -> "block/cube_all")
        if (modelName.contains(":")) {
            modelName = modelName.substring(modelName.lastIndexOf(':') + 1);
        }
        if (modelName.startsWith("block/"))
            modelName = modelName.substring(6);
        String jsonPath = modelsDir + "/" + modelName + ".json";

        if (!Files.exists(Paths.get(jsonPath)))
            return;

        try {
            String content = new String(Files.readAllBytes(Paths.get(jsonPath)));
            JSONObject json = new JSONObject(content);

            // Check if this model is marked as tintable (e.g., grass).
            if (content.contains("\"tintindex\"")) {
                data.isTintable = 1;
            }

            // Extract textures defined in this model.
            if (json.has("textures")) {
                JSONObject texJson = json.getJSONObject("textures");
                for (String key : texJson.keySet()) {
                    String val = texJson.getString(key);
                    // Child models override parents, so only put if key is missing.
                    if (!textureMap.containsKey(key)) {
                        // Strip domain prefix from texture references too
                        if (val.contains(":")) {
                            val = val.substring(val.lastIndexOf(':') + 1);
                        }
                        textureMap.put(key, val);
                    }
                }
            }

            // Recurse into the parent model if one is specified.
            if (json.has("parent")) {
                String parent = json.getString("parent");
                // Strip any domain prefix
                if (parent.contains(":")) {
                    parent = parent.substring(parent.lastIndexOf(':') + 1);
                }
                if (!parent.equals("block/block") && !parent.equals("block/cube") && !parent.equals("block/cube_all")
                        && !parent.equals("block/cube_column") && !parent.equals("block/cube_bottom_top")) {
                    resolveModelRecursive(parent, modelsDir, textureMap, data);
                }
            }

            // Parse elements for AABBs and UVs
            if (json.has("elements")) {
                JSONArray elements = json.getJSONArray("elements");
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject el = elements.getJSONObject(i);
                    JSONArray from = el.getJSONArray("from");
                    JSONArray to = el.getJSONArray("to");
                    float[] aabb = new float[6];
                    for (int j = 0; j < 3; j++)
                        aabb[j] = (float) from.getDouble(j) / 16.0f;
                    for (int j = 0; j < 3; j++)
                        aabb[j + 3] = (float) to.getDouble(j) / 16.0f;
                    data.aabbs.add(aabb);

                    // Parse faces for UVs
                    int[] uvs = new int[6];
                    for (int j = 0; j < 6; j++)
                        uvs[j] = packUV(0, 0, 16, 16); // Default
                    if (el.has("faces")) {
                        JSONObject faces = el.getJSONObject("faces");
                        String[] names = { "down", "up", "north", "south", "west", "east" };
                        for (int j = 0; j < 6; j++) {
                            if (faces.has(names[j])) {
                                JSONObject face = faces.getJSONObject(names[j]);
                                if (face.has("uv")) {
                                    JSONArray uv = face.getJSONArray("uv");
                                    uvs[j] = packUV(uv.getInt(0), uv.getInt(1), uv.getInt(2), uv.getInt(3));
                                }
                            }
                        }
                    }
                    data.aabbUvs.add(uvs);
                }
            }

        } catch (IOException e) {
            // Error reading file, stop recursion.
        }
    }

    private int packUV(int u1, int v1, int u2, int v2) {
        return (u1 & 0xFF) | ((v1 & 0xFF) << 8) | ((u2 & 0xFF) << 16) | ((v2 & 0xFF) << 24);
    }

    /**
     * Packs the block property data into a compact format and uploads it to the
     * GPU.
     * Each block uses 12 integers (3 ivec4 in GLSL).
     */
    public void uploadToGPU() {
        int maxId = 0;
        for (int id : blockRegistry.keySet()) {
            if (id > maxId)
                maxId = id;
        }

        // Allocate memory for the property buffer (12 ints per block).
        IntBuffer buffer = MemoryUtil.memAllocInt((maxId + 1) * 12);
        for (int i = 0; i <= maxId; i++) {
            BlockData data = blockRegistry.get(i);
            if (data != null) {
                // Layout for Shader:
                // ivec4 0: (tex-Y, tex+Y, tex-Z, tex+Z)
                buffer.put(data.tex[0]);
                buffer.put(data.tex[1]);
                buffer.put(data.tex[2]);
                buffer.put(data.tex[3]);

                // ivec4 1: (tex-X, tex+X, transparency, packed_refl_tint_full)
                buffer.put(data.tex[4]);
                buffer.put(data.tex[5]);
                buffer.put(data.transparency);
                int packed = (data.reflectivity & 0xFF) | ((data.isTintable & 1) << 8)
                        | ((data.isFullBlock ? 1 : 0) << 9) | ((data.isLiquid ? 1 : 0) << 10);
                buffer.put(packed);

                // ivec4 2: (albedoR, albedoG, albedoB, packedAnim)
                buffer.put(data.albedo.getRed());
                buffer.put(data.albedo.getGreen());
                buffer.put(data.albedo.getBlue());
                int packedAnim = (data.isAnimated ? 1 : 0) | ((data.frameCount & 0x3F) << 1);
                buffer.put(packedAnim); // Unused -> now stores animation info
            } else {
                // Fill with -1 for unused IDs.
                for (int j = 0; j < 12; j++)
                    buffer.put(-1);
            }
        }
        buffer.flip();

        // 1. Create a Generic Buffer to store the data.
        tboId = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, tboId);
        glBufferData(GL_TEXTURE_BUFFER, buffer, GL_STATIC_DRAW);

        // 2. Create a Texture Buffer Object (TBO) which interprets the buffer as a
        // texture.
        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, textureId);
        // Link the texture to the buffer with an integer format.
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32I, tboId);

        MemoryUtil.memFree(buffer);

        uploadAABBs(maxId);
    }

    private int aabbUvTboId, aabbUvTextureId;

    private void uploadAABBs(int maxId) {
        List<float[]> allAABBs = new ArrayList<>();
        List<int[]> allUVs = new ArrayList<>();
        Map<Integer, int[]> blockInfo = new HashMap<>();
        int offset = 0;
        for (int id = 0; id <= maxId; id++) {
            BlockData data = blockRegistry.get(id);
            List<float[]> aabbs = (data != null) ? data.aabbs : null;
            if (aabbs != null && !aabbs.isEmpty()) {
                blockInfo.put(id, new int[] { offset, aabbs.size() });
                for (int i = 0; i < aabbs.size(); i++) {
                    float[] aabb = aabbs.get(i);
                    allAABBs.add(new float[] { aabb[0], aabb[1], aabb[2] }); // min
                    allAABBs.add(new float[] { aabb[3], aabb[4], aabb[5] }); // max
                    allUVs.add(data.aabbUvs.get(i));
                }
                offset += aabbs.size() * 2;
            } else {
                blockInfo.put(id, new int[] { 0, 0 });
            }
        }

        // AABB data buffer
        FloatBuffer aabbBuffer = MemoryUtil.memAllocFloat(allAABBs.size() * 3);
        for (float[] v : allAABBs)
            aabbBuffer.put(v);
        aabbBuffer.flip();

        aabbTboId = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, aabbTboId);
        glBufferData(GL_TEXTURE_BUFFER, aabbBuffer, GL_STATIC_DRAW);

        aabbTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, aabbTextureId);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGB32F, aabbTboId);

        MemoryUtil.memFree(aabbBuffer);

        // UV data buffer
        IntBuffer uvBuffer = MemoryUtil.memAllocInt(allUVs.size() * 6);
        for (int[] uvs : allUVs) {
            for (int uv : uvs)
                uvBuffer.put(uv);
        }
        uvBuffer.flip();

        aabbUvTboId = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, aabbUvTboId);
        glBufferData(GL_TEXTURE_BUFFER, uvBuffer, GL_STATIC_DRAW);

        aabbUvTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, aabbUvTextureId);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_R32I, aabbUvTboId);

        MemoryUtil.memFree(uvBuffer);

        // Info buffer
        IntBuffer infoBuffer = MemoryUtil.memAllocInt((maxId + 1) * 2);
        for (int id = 0; id <= maxId; id++) {
            int[] info = blockInfo.get(id);
            infoBuffer.put(info[0]);
            infoBuffer.put(info[1]);
        }
        infoBuffer.flip();

        infoTboId = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, infoTboId);
        glBufferData(GL_TEXTURE_BUFFER, infoBuffer, GL_STATIC_DRAW);

        infoTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, infoTextureId);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RG32I, infoTboId);

        MemoryUtil.memFree(infoBuffer);
    }

    /** @return The ID of the Texture Buffer Object. */
    public int getTextureId() {
        return textureId;
    }

    /** @return The ID of the AABB Texture Buffer Object. */
    public int getAABBTextureId() {
        return aabbTextureId;
    }

    /** @return The ID of the AABB Info Texture Buffer Object. */
    public int getInfoTextureId() {
        return infoTextureId;
    }

    /** @return The ID of the AABB UV Texture Buffer Object. */
    public int getAABBUVTextureId() {
        return aabbUvTextureId;
    }

    /**
     * Checks if the block with the given ID occupies the full voxel space.
     * 
     * @param blockId The block ID to check.
     * @return True if it's a full block, false if partial (e.g., slabs, stairs).
     */
    public boolean isFullBlock(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null && data.isFullBlock;
    }

    public float getHardness(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null ? data.hardness : 1.0f;
    }

    public String getPreferredTool(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null ? data.preferredTool : "hand";
    }

    public java.awt.Color getAlbedo(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null ? data.albedo : java.awt.Color.WHITE;
    }

    /** Override isTintable flag after registration (disable biome coloring). */
    public void setBlockTintable(int blockId, boolean tintable) {
        BlockData data = blockRegistry.get(blockId);
        if (data != null) {
            data.isTintable = tintable ? 1 : 0;
        }
    }

    public String getName(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null ? data.name : "unknown";
    }

    public Integer findBlockId(String name) {
        if (name == null) return null;
        return nameToId.get(name.toLowerCase());
    }
}
