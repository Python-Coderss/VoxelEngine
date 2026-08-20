package com.voxel.entity;

import com.voxel.utils.FixedPoint;
import com.voxel.world.DimensionType;
import org.joml.Vector3f;
import org.joml.Vector2f;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Base class for all entities in the voxel engine.
 * Entities are composed of multiple ModelParts, similar to Minecraft.
 * 
 * Position uses 64-bit fixed-point (56-bit integer + 8-bit fractional)
 * to maintain precision at extreme coordinates (Far Lands).
 */
public class Entity {
    public int id;
    
    // Fixed-point position (8 fractional bits: 1/256 ≈ 0.004 blocks resolution)
    protected long posX, posY, posZ;
    protected long prevX, prevY, prevZ;  // previous-frame position for interpolation
    
    public Vector3f rotation; // x=pitch, y=yaw, z=roll
    public List<ModelPart> parts;
    /** The dimension this entity currently belongs to. */
    public DimensionType dimension = DimensionType.OVERWORLD;

    // Tint color (RGB multiplier, 1,1,1 = no tint) and amount (0-1)
    public Vector3f tintColor = new Vector3f(1.0f, 1.0f, 1.0f);
    public float tintAmount = 0.0f;

    // ── Animation system ──
    private Animation currentAnimation = null;
    private String currentAnimName = null;
    private float animTime = 0.0f;
    private boolean animPlaying = false;
    private Map<String, Vector3f> basePartOffsets = new java.util.LinkedHashMap<>();
    private Map<String, Vector3f> basePartRotations = new java.util.LinkedHashMap<>();

    public Entity(int id, Vector3f position) {
        this.id = id;
        this.posX = FixedPoint.fromFloat(position.x);
        this.posY = FixedPoint.fromFloat(position.y);
        this.posZ = FixedPoint.fromFloat(position.z);
        this.prevX = posX;
        this.prevY = posY;
        this.prevZ = posZ;
        this.parts = new ArrayList<>();
        this.rotation = new Vector3f(0, 0, 0);
    }

    // ── Fixed-point position accessors ────────────────────────────────
    
    /** Raw fixed-point X coordinate. */
    public long getFixedX() { return posX; }
    public long getFixedY() { return posY; }
    public long getFixedZ() { return posZ; }
    public long getFixedPrevX() { return prevX; }
    public long getFixedPrevY() { return prevY; }
    public long getFixedPrevZ() { return prevZ; }
    
    /** Float position (for distance/velocity calculations where small deltas are fine). */
    public float getPosX() { return FixedPoint.toFloat(posX); }
    public float getPosY() { return FixedPoint.toFloat(posY); }
    public float getPosZ() { return FixedPoint.toFloat(posZ); }
    
    /** Full position as Vector3f. */
    public Vector3f getPosition() {
        return new Vector3f(FixedPoint.toFloat(posX), FixedPoint.toFloat(posY), FixedPoint.toFloat(posZ));
    }
    
    /** Set position from floats (e.g., from Player). */
    public void setPosition(Vector3f p) {
        this.posX = FixedPoint.fromFloat(p.x);
        this.posY = FixedPoint.fromFloat(p.y);
        this.posZ = FixedPoint.fromFloat(p.z);
    }
    
    /** Set position from doubles. */
    public void setPositionD(double x, double y, double z) {
        this.posX = FixedPoint.fromDouble(x);
        this.posY = FixedPoint.fromDouble(y);
        this.posZ = FixedPoint.fromDouble(z);
    }
    
    /** Set position from longs (copy another entity's fixed-point). */
    public void setFixedPos(long x, long y, long z) {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
    }
    
    /** Add a delta in world units. */
    public void addPosition(float dx, float dy, float dz) {
        posX += FixedPoint.fromFloat(dx);
        posY += FixedPoint.fromFloat(dy);
        posZ += FixedPoint.fromFloat(dz);
    }
    
    /** Add a fixed-point delta directly. */
    public void addFixed(long dx, long dy, long dz) {
        posX += dx;
        posY += dy;
        posZ += dz;
    }
    
    /** Snapshot current position to prevX/Y/Z for interpolation. */
    public void snapshotPrev() {
        prevX = posX;
        prevY = posY;
        prevZ = posZ;
    }

    /** Interpolated position for smooth rendering between logic ticks. */
    public Vector3f getInterpolatedPosition(float partialTicks) {
        return new Vector3f(
            FixedPoint.lerpToFloat(prevX, posX, partialTicks),
            FixedPoint.lerpToFloat(prevY, posY, partialTicks),
            FixedPoint.lerpToFloat(prevZ, posZ, partialTicks)
        );
    }

    public void loadModel(String path, com.voxel.utils.TextureManager textureManager) {
        java.util.Map<String, ModelPart> loadedParts = new java.util.LinkedHashMap<>();
        loadModelRecursive(path, textureManager, loadedParts);
        this.parts.clear();
        this.parts.addAll(loadedParts.values());
        storeBasePose(); // Store initial model pose for animation blending
    }

    private int resolveTextureIndex(String texName, com.voxel.utils.TextureManager textureManager) {
        return textureManager.getEntityTextureIndex(texName);
    }

    private void loadModelRecursive(String path, com.voxel.utils.TextureManager textureManager, java.util.Map<String, ModelPart> loadedParts) {
        try {
            String content = new String(Files.readAllBytes(Paths.get(path)));
            JSONObject json = new JSONObject(content);

            // 1. Handle Parent Inheritance
            if (json.has("parent")) {
                String parentPath = json.getString("parent");
                // Resolve relative path if necessary
                if (!parentPath.startsWith("src/")) {
                    java.nio.file.Path currentPath = Paths.get(path);
                    parentPath = currentPath.getParent().resolve(parentPath).toString();
                }
                loadModelRecursive(parentPath, textureManager, loadedParts);
            }

            // 2. Load/Override Parts
            if (json.has("parts")) {
                JSONArray partsArray = json.getJSONArray("parts");
                for (int i = 0; i < partsArray.length(); i++) {
                    JSONObject p = partsArray.getJSONObject(i);
                    String name = p.getString("name");
                    
                    ModelPart part = loadedParts.get(name);
                    
                    if (part == null) {
                        // New part - requires basic fields
                        JSONArray from = p.getJSONArray("from");
                        JSONArray size = p.getJSONArray("size");
                        String texName = p.getString("texture");
                        int texIdx = resolveTextureIndex(texName, textureManager);
                        
                        part = new ModelPart(
                            name,
                            new Vector3f((float)from.getDouble(0), (float)from.getDouble(1), (float)from.getDouble(2)),
                            new Vector3f((float)size.getDouble(0), (float)size.getDouble(1), (float)size.getDouble(2)),
                            texIdx
                        );
                        
                        if (p.has("uv")) {
                            JSONArray uv = p.getJSONArray("uv");
                            part.uvOrigin.set((float)uv.getDouble(0), (float)uv.getDouble(1));
                        }
                        
                        loadedParts.put(name, part);
                    } else {
                        // Override existing part properties
                        if (p.has("from")) {
                            JSONArray from = p.getJSONArray("from");
                            part.offset.set((float)from.getDouble(0), (float)from.getDouble(1), (float)from.getDouble(2));
                        }
                        if (p.has("size")) {
                            JSONArray size = p.getJSONArray("size");
                            part.size.set((float)size.getDouble(0), (float)size.getDouble(1), (float)size.getDouble(2));
                        }
                        if (p.has("texture")) {
                            part.textureIndex = resolveTextureIndex(p.getString("texture"), textureManager);
                        }
                        if (p.has("uv")) {
                            JSONArray uv = p.getJSONArray("uv");
                            part.uvOrigin.set((float)uv.getDouble(0), (float)uv.getDouble(1));
                        }
                    }

                    // Optional/Overrideable fields
                    if (p.has("absolute_offset")) {
                        JSONArray absOff = p.getJSONArray("absolute_offset");
                        part.absoluteOffset.set((float)absOff.getDouble(0), (float)absOff.getDouble(1), (float)absOff.getDouble(2));
                    }
                    
                    if (p.has("rotation")) {
                        JSONArray rot = p.getJSONArray("rotation");
                        part.rotation.set((float)rot.getDouble(0), (float)rot.getDouble(1), (float)rot.getDouble(2));
                    }

                    if (p.has("uv_size")) {
                        JSONArray uvSize = p.getJSONArray("uv_size");
                        part.uvSize.set((float)uvSize.getDouble(0), (float)uvSize.getDouble(1), (float)uvSize.getDouble(2));
                    }

                    if (p.has("texture_mapping")) {
                        String mapping = p.getString("texture_mapping");
                        if ("cuboid_atlas".equals(mapping)) {
                            part.textureMapping = ModelPart.TEXTURE_MAPPING_CUBOID_ATLAS;
                        } else {
                            part.textureMapping = ModelPart.TEXTURE_MAPPING_PLANAR;
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load entity model: " + path);
        }
    }

    public void addPart(ModelPart part) {
        parts.add(part);
    }

    public void update(float dt) {
        // Update animation if playing
        if (animPlaying && currentAnimation != null) {
            animTime += dt;
            float duration = currentAnimation.getDuration();
            if (duration > 0 && animTime >= duration) {
                if (currentAnimation.isLooping()) {
                    animTime -= duration;
                } else {
                    animTime = duration;
                    animPlaying = false;
                }
            }
            // Sample and apply animation to parts
            Map<String, Animation.Keyframe> sample = currentAnimation.sample(animTime);
            for (ModelPart part : parts) {
                Animation.Keyframe kf = sample.get(part.name);
                if (kf != null) {
                    // Apply animation offset on top of base pose
                    Vector3f baseOff = basePartOffsets.get(part.name);
                    if (baseOff != null) {
                        part.offset.set(baseOff).add(kf.offset);
                    } else {
                        part.offset.set(kf.offset);
                    }
                    Vector3f baseRot = basePartRotations.get(part.name);
                    if (baseRot != null) {
                        part.rotation.set(baseRot).add(kf.rotation);
                    } else {
                        part.rotation.set(kf.rotation);
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Animation API
    // ════════════════════════════════════════════════════════════════

    /** Play an animation by name. Clears any current animation. */
    public void playAnimation(Animation anim) {
        // Store base pose before playing
        storeBasePose();
        this.currentAnimation = anim;
        this.currentAnimName = anim != null ? anim.getName() : null;
        this.animTime = 0.0f;
        this.animPlaying = anim != null;
    }

    /** Play an animation, blending from the current state. */
    public void playAnimationBlended(Animation anim) {
        playAnimation(anim);
    }

    /** Stop the current animation and restore base pose. */
    public void stopAnimation() {
        this.animPlaying = false;
        this.currentAnimation = null;
        this.currentAnimName = null;
        this.animTime = 0.0f;
        restoreBasePose();
    }

    /** Whether an animation is currently playing. */
    public boolean isAnimating() { return animPlaying; }

    /** Get the name of the current animation, or null. */
    public String getCurrentAnimName() { return currentAnimName; }

    /** Get animation progress [0,1]. */
    public float getAnimProgress() {
        if (currentAnimation == null || currentAnimation.getDuration() <= 0) return 0;
        return Math.min(1.0f, animTime / currentAnimation.getDuration());
    }

    public void storeBasePose() {
        basePartOffsets.clear();
        basePartRotations.clear();
        for (ModelPart part : parts) {
            basePartOffsets.put(part.name, new Vector3f(part.offset));
            basePartRotations.put(part.name, new Vector3f(part.rotation));
        }
    }

    private void restoreBasePose() {
        for (ModelPart part : parts) {
            Vector3f baseOff = basePartOffsets.get(part.name);
            if (baseOff != null) part.offset.set(baseOff);
            Vector3f baseRot = basePartRotations.get(part.name);
            if (baseRot != null) part.rotation.set(baseRot);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Model Swapping API (for dynamic model changes during animations)
    // ════════════════════════════════════════════════════════════════

    /** Replace the current model with a new one. Clears animation state. */
    public void swapModel(String newModelPath, com.voxel.utils.TextureManager textureManager) {
        stopAnimation();
        loadModel(newModelPath, textureManager);
    }

    /**
     * Replace a single part's texture by name.
     * Useful for texture swaps in animations (e.g., blinking, mouth movement).
     */
    public void setPartTexture(String partName, int newTextureIndex) {
        for (ModelPart part : parts) {
            if (part.name.equals(partName)) {
                part.textureIndex = newTextureIndex;
                return;
            }
        }
    }

    /**
     * Find a part by name.
     */
    public ModelPart findPart(String name) {
        for (ModelPart part : parts) {
            if (part.name.equals(name)) return part;
        }
        return null;
    }
}
