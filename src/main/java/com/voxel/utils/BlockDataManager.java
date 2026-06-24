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
    public final Map<Integer, BlockData> blockRegistry = new HashMap<>();
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
     * Enum for special material effects that require custom shader logic.
     */
    public enum MaterialEffect {
        NONE(0),
        PORTAL(1),
        LIQUID(2),
        WIRE(3);

        public final int id;
        MaterialEffect(int id) { this.id = id; }
    }

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

        // Emission intensity (0 = none, 255 = maximum glow).
        public int emissive;

        // UV distortion intensity (0-255).
        public int distortion;

        // Special effect ID for the shader.
        public MaterialEffect effect = MaterialEffect.NONE;

        // Diffuse intensity (0-255, default 255).
        public int diffuse = 255;

        // Whether the block color should be modified by the biome (e.g., grass).
        public int isTintable;

        // Tint index: 0=none, 1=grass colormap, 2=foliage colormap.
        // Maps from Minecraft JSON "tintindex" values (0→1, 1→2).
        public int tintIndex;

        // Per-face tint mask: bit j (0-5) set if face j should be biome-tinted.
        // Face order: 0=down, 1=up, 2=north, 3=south, 4=west, 5=east.
        public int tintFaceMask;

        // Animated texture properties
        public boolean isAnimated;
        public int frameCount = 1;

        // Whether this block occupies the full voxel space (true for most blocks, false
        // for slabs/stairs/fences/most portals).
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
     * Registers a new block with explicit rendering properties.
     */
    public void registerBlock(int id, String name, TextureManager textureManager, String modelsDir, int transparency, int reflectivity, int diffuse) {
        registerBlock(id, name, textureManager, modelsDir);
        BlockData data = blockRegistry.get(id);
        if (data != null) {
            data.transparency = transparency;
            data.reflectivity = reflectivity;
            data.diffuse = diffuse;
        }
    }

    /**
     * Registers a new block with explicit rendering properties including emissive.
     */
    public void registerBlock(int id, String name, TextureManager textureManager, String modelsDir, int transparency, int reflectivity, int diffuse, int emissive) {
        registerBlock(id, name, textureManager, modelsDir, transparency, reflectivity, diffuse);
        BlockData data = blockRegistry.get(id);
        if (data != null) {
            data.emissive = emissive;
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
        if (blockRegistry.containsKey(id)) {
            throw new RuntimeException("Block ID collision detected! ID " + id + " is already registered to '" + blockRegistry.get(id).name + "'. Attempted to register '" + name + "'.");
        }
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
        String particle = textureMap.get("particle");

        String representativeTexture = textureMap.getOrDefault("up", top != null ? top : (end != null ? end : (all != null ? all : particle)));

        // Map common Minecraft JSON keys to the 6 face indices.
        assignFace(data, 0, textureMap.getOrDefault("down", bottom != null ? bottom : (end != null ? end : (all != null ? all : particle))),
                textureManager);
        assignFace(data, 1, representativeTexture, textureManager);
        assignFace(data, 2, textureMap.getOrDefault("north", side != null ? side : (all != null ? all : particle)), textureManager);
        assignFace(data, 3, textureMap.getOrDefault("south", side != null ? side : (all != null ? all : particle)), textureManager);
        assignFace(data, 4, textureMap.getOrDefault("west", side != null ? side : (all != null ? all : particle)), textureManager);
        assignFace(data, 5, textureMap.getOrDefault("east", side != null ? side : (all != null ? all : particle)), textureManager);

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
            data.effect = MaterialEffect.LIQUID;
        } else if (name.contains("aercloud")) {
            data.transparency = 0;
            data.reflectivity = 0;
            data.diffuse = 255;
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

        // Set isTintable from parsed tintIndex
        if (data.tintIndex > 0) {
            data.isTintable = 1;
        }

        // Set isFullBlock based on block type
        if (name.contains("slab") || name.contains("stairs") || name.contains("fence") ||
                name.contains("wall") || name.contains("door") || name.contains("trapdoor") ||
                name.contains("pane") || name.contains("carpet") || name.contains("dust") || name.contains("pressure_plate") ||
                name.contains("button") || name.contains("lever") || name.contains("torch") ||
                name.contains("rail") || name.contains("sign") || name.contains("banner") ||
                name.contains("flower") || name.contains("sapling") || name.contains("mushroom") ||
                name.contains("water") || name.contains("lava") ||
                name.contains("portal") || name.contains("aercloud") || name.contains("aerogel")) {
            data.isFullBlock = false;
        }
        if (name.contains("water") || name.contains("lava")) {
            data.effect = MaterialEffect.LIQUID;
        }
        if (name.contains("portal")) {
            data.effect = MaterialEffect.PORTAL;
            data.distortion = 20; // Default distortion for portals
        }
        if (name.contains("redstone_dust") || name.equals("redstone_wire")) {
            data.effect = MaterialEffect.WIRE;
            data.tintIndex = 0; // Redstone wire handles its own color via WIRE effect
            data.tintFaceMask = 0;
            data.isTintable = 0;
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

            // tintindex is now parsed per-face in the elements section below.

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
                                // Parse tintindex: MC 0→grass(1), MC 1→foliage(2)
                                if (face.has("tintindex")) {
                                    int ti = face.getInt("tintindex") + 1;
                                    if (ti > data.tintIndex) {
                                        data.tintIndex = ti;
                                    }
                                    // Mark this specific face as tinted in the per-face mask
                                    data.tintFaceMask |= (1 << j);
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

                // ivec4 1: (tex-X, tex+X, transparency, packed_refl_tint_full_effect_emissive)
                buffer.put(data.tex[4]);
                buffer.put(data.tex[5]);
                buffer.put(data.transparency);
                int packed = (data.reflectivity & 0xFF) | ((data.isTintable & 1) << 8)
                        | ((data.isFullBlock ? 1 : 0) << 9) 
                        | ((data.effect.id & 0xF) << 10)
                        | ((data.emissive & 0xFF) << 14)
                        | ((data.tintIndex & 0x3) << 22)
                        | ((data.tintFaceMask & 0x3F) << 24);
                buffer.put(packed);

                // ivec4 2: (albedoR, albedoG, albedoB, packedAnim_diffuse_distortion)
                buffer.put(data.albedo.getRed());
                buffer.put(data.albedo.getGreen());
                buffer.put(data.albedo.getBlue());
                int packedAnim = (data.isAnimated ? 1 : 0) | ((data.frameCount & 0x3F) << 1) 
                        | ((data.diffuse & 0xFF) << 8)
                        | ((data.distortion & 0xFF) << 16);
                buffer.put(packedAnim); // bit0: anim, bit1-6: frames, bit8-15: diffuse, bit16-23: distortion
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

    public boolean isLiquid(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null && data.effect == MaterialEffect.LIQUID;
    }

    public boolean isPortal(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null && data.effect == MaterialEffect.PORTAL;
    }

    public boolean isRedstoneWire(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null && data.effect == MaterialEffect.WIRE;
    }

    public float getHardness(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null ? data.hardness : 1.0f;
    }

    public String getPreferredTool(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null ? data.preferredTool : "hand";
    }

    /** @return true if this block emits light (emissive > 0). */
    public boolean isEmissive(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null && data.emissive > 0;
    }

    /** @return the emissive value (0-255) for the block, or 0 if unknown. */
    public int getEmissive(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null ? data.emissive : 0;
    }

    /**
     * Returns the light opacity of a block in Minecraft light units (0-15).
     * Full blocks return 16 (fully opaque), transparent blocks return partial values:
     * water=3, leaves=1, ice=3, cobweb=1, glass/portals=0.
     * Non-full blocks that don't attenuate light (torches, flowers) return 0.
     */
    public int getOpacity(int blockId) {
        if (blockId <= 0) return 0;
        BlockData data = blockRegistry.get(blockId);
        if (data == null) return 0;
        if (data.isFullBlock) return 16; // fully opaque
        if (data.effect == MaterialEffect.LIQUID) return 3; // water, lava
        if (data.effect == MaterialEffect.PORTAL) return 0; // portals don't block light
        // Non-full blocks with models (leaves, slabs, etc.) - use name as hint
        String name = (data.name != null) ? data.name.toLowerCase() : "";
        if (name.contains("leaves") || name.contains("leaf")) return 1;
        if (name.contains("ice")) return 3;
        if (name.contains("cobweb") || name.contains("web")) return 1;
        // Glass and most non-full blocks are transparent to light
        return 0;
    }

    public java.awt.Color getAlbedo(int blockId) {
        BlockData data = blockRegistry.get(blockId);
        return data != null ? data.albedo : java.awt.Color.WHITE;
    }

    /** Override tinting after registration (disable biome coloring). */
    public void setBlockTintable(int blockId, boolean tintable) {
        BlockData data = blockRegistry.get(blockId);
        if (data != null) {
            data.isTintable = tintable ? 1 : 0;
            if (!tintable) {
                data.tintIndex = 0;
                data.tintFaceMask = 0;
            }
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
