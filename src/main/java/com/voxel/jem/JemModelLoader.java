package com.voxel.jem;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JemModelLoader {
    private static final Path CEM_ROOT = Paths.get("src/main/resources/models/assets/minecraft/optifine/cem");
    private static final Path MODEL_ROOT = Paths.get("src/main/resources/models");
    private static final Path MODEL_ASSET_ROOT = MODEL_ROOT.resolve("assets");
    private static final Path ENTITY_TEXTURE_ROOT = Paths.get("src/main/resources/assets/minecraft/textures/entity");
    private static final float EPSILON = 1.0e-4f;

    private static final Map<Path, JemModel> CACHE = new HashMap<>();
    private static final Map<Path, TextureImage> TEXTURE_CACHE = new HashMap<>();
    private static final List<EntityPartMetadata> PART_METADATA = new ArrayList<>();
    private static final List<TextureImage> UNIQUE_TEXTURES = new ArrayList<>();

    private JemModelLoader() {
    }

    public static List<EntityPartMetadata> getPartMetadata() {
        return PART_METADATA;
    }

    public static List<TextureImage> getUniqueTextures() {
        return UNIQUE_TEXTURES;
    }

    public static JemEntityInstance loadEntity(String modelNameOrPath) throws IOException {
        return new JemEntityInstance(loadModel(modelNameOrPath));
    }

    public static JemModel loadModel(String modelNameOrPath) throws IOException {
        Path path = resolveJemPath(modelNameOrPath);
        JemModel cached = CACHE.get(path);
        if (cached != null) {
            return cached;
        }

        JSONObject root = readJson(path);
        JSONArray models = root.getJSONArray("models");
        Map<String, JSONObject> resolvedById = new HashMap<>();
        List<JemPartDefinition> parts = new ArrayList<>();
        TextureImage texture = loadTexture(root, path);
        Vector3f textureSize = readVector3(root.optJSONArray("textureSize"), 64.0f);

        for (int i = 0; i < models.length(); i++) {
            JSONObject resolved = resolveModelDefinition(models.getJSONObject(i), resolvedById, path.getParent());
            String id = resolved.optString("id", "");
            if (!id.isEmpty()) {
                resolvedById.put(id, resolved);
            }

            parts.addAll(buildPartDefinitions(resolved, texture, textureSize));
        }

        JemModel model = new JemModel(stripExtension(path.getFileName().toString()), parts);
        CACHE.put(path, model);
        return model;
    }

    private static List<JemPartDefinition> buildPartDefinitions(JSONObject model, TextureImage texture, Vector3f textureSize) {
        List<JemPartDefinition> partDefinitions = new ArrayList<>();
        float renderScale = (float) model.optDouble("scale", 1.0);
        if (renderScale <= 0.0f) {
            return partDefinitions;
        }

        String partName = model.getString("part");
        AxisSettings axis = AxisSettings.from(model.optString("invertAxis", ""));
        Vector3f origin = axis.applyTranslate(readVector3(model.optJSONArray("translate"), 0.0f));
        Vector3f rotationDegrees = axis.applyRotation(readVector3(model.optJSONArray("rotate"), 0.0f));
        Quaternionf baseRotation = new Quaternionf().rotateXYZ(
                (float) Math.toRadians(rotationDegrees.x),
                (float) Math.toRadians(rotationDegrees.y),
                (float) Math.toRadians(rotationDegrees.z));
        Vector3f baseScale = new Vector3f(renderScale);

        List<VoxelBox> boxes = collectBoxesForPart(model, texture, textureSize);
        for (VoxelBox box : boxes) {
            JemPartDefinition part = createPartDefinition(partName, origin, baseRotation, baseScale, box);
            if (part != null) {
                partDefinitions.add(part);
            }
        }
        return partDefinitions;
    }

    private static JemPartDefinition createPartDefinition(String partName, Vector3f origin, Quaternionf baseRotation,
            Vector3f baseScale, VoxelBox box) {
        Bounds bounds = computeBounds(box);
        bounds.ensureNonZeroAxes();

        EntityPartMetadata meta = new EntityPartMetadata();
        meta.textureIdx = UNIQUE_TEXTURES.indexOf(box.texture);
        if (meta.textureIdx == -1 && box.texture != null) {
            meta.textureIdx = UNIQUE_TEXTURES.size();
            UNIQUE_TEXTURES.add(box.texture);
        }

        Vector3f size = new Vector3f(box.max).sub(box.min);
        for (int face = 0; face < 6; face++) {
            FaceSample fs = box.getFaceUVs(face, size);
            meta.uvs[face].set(
                (box.textureOffset.x + fs.u) / box.textureSize.x,
                (box.textureOffset.y + fs.v) / box.textureSize.y,
                fs.faceWidth / box.textureSize.x,
                fs.faceHeight / box.textureSize.y
            );
        }

        int blockId = PART_METADATA.size();
        PART_METADATA.add(meta);

        return new JemPartDefinition(partName, origin, baseRotation, baseScale, bounds.min, bounds.max, blockId);
    }

    private static List<VoxelBox> collectBoxesForPart(JSONObject model, TextureImage texture, Vector3f textureSize) {
        List<VoxelBox> boxes = new ArrayList<>();
        AxisSettings axis = AxisSettings.from(model.optString("invertAxis", ""));
        collectBoxes(model, new Matrix4f(), axis, boxes, texture, textureSize);
        collectChildren(model, new Matrix4f(), boxes, texture, textureSize);
        return boxes;
    }

    private static void collectChildren(JSONObject parent, Matrix4f parentTransform, List<VoxelBox> boxes,
            TextureImage inheritedTexture, Vector3f textureSize) {
        if (parent.has("submodel")) {
            JSONObject child = parent.getJSONObject("submodel");
            Matrix4f childTransform = new Matrix4f(parentTransform).mul(nodeTransform(child));
            collectNodeRecursive(child, childTransform, boxes, inheritedTexture, textureSize);
        }

        JSONArray children = parent.optJSONArray("submodels");
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.getJSONObject(i);
            Matrix4f childTransform = new Matrix4f(parentTransform).mul(nodeTransform(child));
            collectNodeRecursive(child, childTransform, boxes, inheritedTexture, textureSize);
        }
    }

    private static void collectNodeRecursive(JSONObject node, Matrix4f currentTransform, List<VoxelBox> boxes,
            TextureImage inheritedTexture, Vector3f textureSize) {
        AxisSettings axis = AxisSettings.from(node.optString("invertAxis", ""));
        TextureImage texture = inheritedTexture;
        String textureRef = node.optString("texture", "");
        if (!textureRef.isEmpty()) {
            texture = loadTexture(textureRef);
        }
        collectBoxes(node, currentTransform, axis, boxes, texture, textureSize);
        collectChildren(node, currentTransform, boxes, texture, textureSize);
    }

    private static void collectBoxes(JSONObject node, Matrix4f transform, AxisSettings axis, List<VoxelBox> boxes,
            TextureImage texture, Vector3f textureSize) {
        JSONArray boxArray = node.optJSONArray("boxes");
        boolean mirrorU = node.optString("mirrorTexture", "").indexOf('u') >= 0;
        boolean mirrorV = node.optString("mirrorTexture", "").indexOf('v') >= 0;
        if (boxArray != null) {
            for (int i = 0; i < boxArray.length(); i++) {
                VoxelBox box = createVoxelBox(boxArray.getJSONObject(i), transform, axis, texture, textureSize, mirrorU, mirrorV);
                if (box != null) {
                    boxes.add(box);
                }
            }
        }

        JSONArray spriteArray = node.optJSONArray("sprites");
        if (spriteArray == null) {
            return;
        }
        for (int i = 0; i < spriteArray.length(); i++) {
            VoxelBox box = createVoxelBox(spriteArray.getJSONObject(i), transform, axis, texture, textureSize, mirrorU, mirrorV);
            if (box != null) {
                boxes.add(box);
            }
        }
    }

    private static VoxelBox createVoxelBox(JSONObject boxJson, Matrix4f transform, AxisSettings axis,
            TextureImage texture, Vector3f textureSize, boolean mirrorU, boolean mirrorV) {
        JSONArray coordinates = boxJson.optJSONArray("coordinates");
        if (coordinates == null || coordinates.length() < 6) {
            return null;
        }

        float x = (float) coordinates.getDouble(0);
        float y = (float) coordinates.getDouble(1);
        float z = (float) coordinates.getDouble(2);
        float width = (float) coordinates.getDouble(3);
        float height = (float) coordinates.getDouble(4);
        float depth = (float) coordinates.getDouble(5);

        Vector3f inflate = readInflate(boxJson);

        if (axis.invertX) {
            x = -x - width;
        }
        if (axis.invertY) {
            y = -y - height;
        }
        if (axis.invertZ) {
            z = -z - depth;
        }

        Vector3f min = new Vector3f(x - inflate.x, y - inflate.y, z - inflate.z);
        Vector3f size = new Vector3f(
                width + inflate.x * 2.0f,
                height + inflate.y * 2.0f,
                depth + inflate.z * 2.0f);

        Vector3f textureOffset = readVector3(boxJson.optJSONArray("textureOffset"), 0.0f);
        return new VoxelBox(transform, min, size, texture, textureSize, textureOffset, mirrorU, mirrorV);
    }

    private static Bounds computeBounds(VoxelBox box) {
        Bounds bounds = new Bounds();
        Vector3f corner = new Vector3f();
        for (int mask = 0; mask < 8; mask++) {
            corner.set(
                    (mask & 1) == 0 ? box.min.x : box.max.x,
                    (mask & 2) == 0 ? box.min.y : box.max.y,
                    (mask & 4) == 0 ? box.min.z : box.max.z);
            box.transform.transformPosition(corner);
            bounds.include(corner);
        }
        return bounds;
    }

    private static int[] voxelize(VoxelBox box, Bounds bounds, Vector3f voxelScale) {
        int[] voxelData = new int[16 * 16 * 16];
        Vector3f sample = new Vector3f();

        for (int x = 0; x < 16; x++) {
            sample.x = bounds.min.x + (x + 0.5f) * voxelScale.x;
            for (int y = 0; y < 16; y++) {
                sample.y = bounds.min.y + (y + 0.5f) * voxelScale.y;
                for (int z = 0; z < 16; z++) {
                    sample.z = bounds.min.z + (z + 0.5f) * voxelScale.z;
                    int material = sampleMaterial(box, sample);
                    if ((material >>> 24) != 0) {
                        voxelData[x + y * 16 + z * 256] = material;
                    }
                }
            }
        }

        return voxelData;
    }

    private static int sampleMaterial(VoxelBox box, Vector3f sample) {
        Vector3f local = new Vector3f(sample);
        local.set(sample);
        box.inverseTransform.transformPosition(local);
        if (box.contains(local)) {
            int rgba = box.sampleRgba(local);
            if ((rgba >>> 24) != 0) {
                return rgba;
            }
        }
        return 0;
    }

    private static JSONObject resolveModelDefinition(JSONObject raw, Map<String, JSONObject> resolvedById, Path baseDir) throws IOException {
        JSONObject resolved = new JSONObject();

        String baseId = raw.optString("baseId", "");
        if (!baseId.isEmpty()) {
            JSONObject base = resolvedById.get(baseId);
            if (base == null) {
                throw new IOException("Unknown baseId '" + baseId + "'");
            }
            resolved = deepCopy(base);
        }

        String externalModel = raw.optString("model", "");
        if (!externalModel.isEmpty()) {
            Path modelPath = resolveModelReference(baseDir, externalModel, ".jpm");
            resolved = mergeJson(resolved, readJson(modelPath));
        }

        resolved = mergeJson(resolved, raw);
        resolved.remove("baseId");
        resolved.remove("model");
        return resolved;
    }

    private static JSONObject mergeJson(JSONObject base, JSONObject override) {
        JSONObject merged = deepCopy(base);
        for (String key : override.keySet()) {
            Object value = override.get(key);
            if (value instanceof JSONObject && merged.has(key) && merged.get(key) instanceof JSONObject) {
                merged.put(key, mergeJson(merged.getJSONObject(key), (JSONObject) value));
            } else if (value instanceof JSONObject) {
                merged.put(key, deepCopy((JSONObject) value));
            } else if (value instanceof JSONArray) {
                merged.put(key, new JSONArray(value.toString()));
            } else {
                merged.put(key, value);
            }
        }
        return merged;
    }

    private static JSONObject deepCopy(JSONObject object) {
        return new JSONObject(object.toString());
    }

    private static JSONObject readJson(Path path) throws IOException {
        return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    private static TextureImage loadTexture(JSONObject root, Path jemPath) throws IOException {
        String textureRef = root.optString("texture", "");
        if (!textureRef.isEmpty()) {
            return loadTexture(textureRef);
        }

        String modelName = stripExtension(jemPath.getFileName().toString());
        Path direct = ENTITY_TEXTURE_ROOT.resolve(modelName).resolve(modelName + ".png");
        if (Files.exists(direct)) {
            return loadTexture(direct.toAbsolutePath().normalize());
        }

        Path flat = ENTITY_TEXTURE_ROOT.resolve(modelName + ".png");
        if (Files.exists(flat)) {
            return loadTexture(flat.toAbsolutePath().normalize());
        }

        return null;
    }

    private static TextureImage loadTexture(String textureRef) {
        Path resolved = resolveTextureReference(textureRef);
        return loadTexture(resolved);
    }

    private static TextureImage loadTexture(Path texturePath) {
        Path normalized = texturePath.toAbsolutePath().normalize();
        TextureImage cached = TEXTURE_CACHE.get(normalized);
        if (cached != null) {
            return cached;
        }
        try {
            BufferedImage image = ImageIO.read(normalized.toFile());
            if (image == null) {
                throw new IOException("Unsupported image format");
            }
            TextureImage texture = new TextureImage(image);
            TEXTURE_CACHE.put(normalized, texture);
            return texture;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load entity texture " + normalized, e);
        }
    }

    private static Path resolveJemPath(String modelNameOrPath) {
        Path candidate = Paths.get(modelNameOrPath);
        if (Files.exists(candidate)) {
            return candidate.toAbsolutePath().normalize();
        }

        String fileName = modelNameOrPath.endsWith(".jem") ? modelNameOrPath : modelNameOrPath + ".jem";
        return CEM_ROOT.resolve(fileName).toAbsolutePath().normalize();
    }

    private static Path resolveModelReference(Path baseDir, String reference, String defaultExtension) {
        if (reference.startsWith("/assets/")) {
            return MODEL_ROOT.resolve(reference.substring(1)).toAbsolutePath().normalize();
        }

        if (reference.indexOf(':') >= 0) {
            String[] parts = reference.split(":", 2);
            String relative = ensureExtension(parts[1], defaultExtension);
            return MODEL_ASSET_ROOT.resolve(parts[0]).resolve(relative).toAbsolutePath().normalize();
        }

        return baseDir.resolve(ensureExtension(reference, defaultExtension)).toAbsolutePath().normalize();
    }

    private static Path resolveTextureReference(String reference) {
        if (reference.startsWith("/assets/")) {
            return MODEL_ROOT.resolve(reference.substring(1)).toAbsolutePath().normalize();
        }
        if (reference.indexOf(':') >= 0) {
            String[] parts = reference.split(":", 2);
            return Paths.get("src/main/resources/assets").resolve(parts[0]).resolve(parts[1] + ".png").toAbsolutePath().normalize();
        }
        if (reference.endsWith(".png")) {
            Path direct = CEM_ROOT.resolve(reference);
            if (Files.exists(direct)) {
                return direct.toAbsolutePath().normalize();
            }
        }
        Path entityRelative = ENTITY_TEXTURE_ROOT.resolve(reference);
        if (Files.exists(entityRelative)) {
            return entityRelative.toAbsolutePath().normalize();
        }
        Path nested = ENTITY_TEXTURE_ROOT.resolve(reference + ".png");
        if (Files.exists(nested)) {
            return nested.toAbsolutePath().normalize();
        }
        return CEM_ROOT.resolve(reference).toAbsolutePath().normalize();
    }

    private static String ensureExtension(String path, String defaultExtension) {
        return path.endsWith(defaultExtension) ? path : path + defaultExtension;
    }

    private static String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private static Matrix4f nodeTransform(JSONObject node) {
        AxisSettings axis = AxisSettings.from(node.optString("invertAxis", ""));
        Vector3f translate = axis.applyTranslate(readVector3(node.optJSONArray("translate"), 0.0f));
        Vector3f rotateDegrees = axis.applyRotation(readVector3(node.optJSONArray("rotate"), 0.0f));
        float scale = (float) node.optDouble("scale", 1.0);

        return new Matrix4f()
                .translate(translate)
                .rotateXYZ(
                        (float) Math.toRadians(rotateDegrees.x),
                        (float) Math.toRadians(rotateDegrees.y),
                        (float) Math.toRadians(rotateDegrees.z))
                .scale(scale);
    }

    private static Vector3f readVector3(JSONArray array, float defaultValue) {
        if (array == null) {
            return new Vector3f(defaultValue, defaultValue, defaultValue);
        }

        return new Vector3f(
                array.length() > 0 ? (float) array.getDouble(0) : defaultValue,
                array.length() > 1 ? (float) array.getDouble(1) : defaultValue,
                array.length() > 2 ? (float) array.getDouble(2) : defaultValue);
    }

    private static Vector3f readInflate(JSONObject boxJson) {
        JSONArray sizesAdd = boxJson.optJSONArray("sizesAdd");
        if (sizesAdd != null && sizesAdd.length() >= 3) {
            return new Vector3f(
                    (float) sizesAdd.getDouble(0),
                    (float) sizesAdd.getDouble(1),
                    (float) sizesAdd.getDouble(2));
        }

        float sizeAdd = (float) boxJson.optDouble("sizeAdd", 0.0);
        return new Vector3f(sizeAdd, sizeAdd, sizeAdd);
    }

    private static final class AxisSettings {
        private final boolean invertX;
        private final boolean invertY;
        private final boolean invertZ;

        private AxisSettings(boolean invertX, boolean invertY, boolean invertZ) {
            this.invertX = invertX;
            this.invertY = invertY;
            this.invertZ = invertZ;
        }

        static AxisSettings from(String value) {
            return new AxisSettings(value.indexOf('x') >= 0, value.indexOf('y') >= 0, value.indexOf('z') >= 0);
        }

        Vector3f applyTranslate(Vector3f vector) {
            if (invertX) {
                vector.x = -vector.x;
            }
            if (invertY) {
                vector.y = -vector.y;
            }
            if (invertZ) {
                vector.z = -vector.z;
            }
            return vector;
        }

        Vector3f applyRotation(Vector3f vector) {
            if (invertX) {
                vector.x = -vector.x;
            }
            if (invertY) {
                vector.y = -vector.y;
            }
            if (invertZ) {
                vector.z = -vector.z;
            }
            return vector;
        }
    }

    private static final class VoxelBox {
        private final Matrix4f transform;
        private final Matrix4f inverseTransform;
        private final Vector3f min;
        private final Vector3f max;
        private final TextureImage texture;
        private final Vector3f textureSize;
        private final Vector3f textureOffset;
        private final boolean mirrorU;
        private final boolean mirrorV;

        private VoxelBox(Matrix4f transform, Vector3f min, Vector3f size, TextureImage texture,
                Vector3f textureSize, Vector3f textureOffset, boolean mirrorU, boolean mirrorV) {
            this.transform = new Matrix4f(transform);
            this.inverseTransform = new Matrix4f(transform).invert();
            this.min = new Vector3f(min);
            this.max = new Vector3f(min).add(size);
            this.texture = texture;
            this.textureSize = new Vector3f(textureSize);
            this.textureOffset = new Vector3f(textureOffset);
            this.mirrorU = mirrorU;
            this.mirrorV = mirrorV;
        }

        private boolean contains(Vector3f point) {
            return point.x >= min.x - EPSILON && point.x <= max.x + EPSILON
                    && point.y >= min.y - EPSILON && point.y <= max.y + EPSILON
                    && point.z >= min.z - EPSILON && point.z <= max.z + EPSILON;
        }

        private int sampleRgba(Vector3f point) {
            if (texture == null) {
                return packRgba(255, 255, 255, 255);
            }

            Vector3f size = new Vector3f(max).sub(min);
            FaceSample face = pickFace(point, size);
            float texU = textureOffset.x + face.u;
            float texV = textureOffset.y + face.v;

            if (mirrorU) {
                texU = textureOffset.x + face.faceWidth - face.u;
            }
            if (mirrorV) {
                texV = textureOffset.y + face.faceHeight - face.v;
            }

            return texture.sample(texU / textureSize.x, texV / textureSize.y);
        }

        private FaceSample getFaceUVs(int face, Vector3f size) {
            float x = size.x;
            float y = size.y;
            float z = size.z;

            FaceSample fs;
            if (face == 0) fs = new FaceSample(z, 0, z, y); // -X
            else if (face == 1) fs = new FaceSample(z + x, 0, z, y); // +X
            else if (face == 2) fs = new FaceSample(z, z, x, z); // -Y
            else if (face == 3) fs = new FaceSample(z + x, 0, x, y); // +Y
            else if (face == 4) fs = new FaceSample(z + x + z, 0, x, y); // -Z
            else fs = new FaceSample(z, 0, x, y); // +Z

            if (mirrorU) fs.u = fs.faceWidth - fs.u;
            if (mirrorV) fs.v = fs.faceHeight - fs.v;
            return fs;
        }

        private FaceSample pickFace(Vector3f point, Vector3f size) {
            float dxMin = point.x - min.x;
            float dxMax = max.x - point.x;
            float dyMin = point.y - min.y;
            float dyMax = max.y - point.y;
            float dzMin = point.z - min.z;
            float dzMax = max.z - point.z;

            int face = 0;
            float best = dxMin;
            if (dxMax < best) { best = dxMax; face = 1; }
            if (dyMin < best) { best = dyMin; face = 2; }
            if (dyMax < best) { best = dyMax; face = 3; }
            if (dzMin < best) { best = dzMin; face = 4; }
            if (dzMax < best) { face = 5; }

            float localX = point.x - min.x;
            float localY = point.y - min.y;
            float localZ = point.z - min.z;
            float x = size.x;
            float y = size.y;
            float z = size.z;

            FaceSample fs;
            if (face == 0) fs = new FaceSample(z - localZ, localY, z, y);
            else if (face == 1) fs = new FaceSample(z + x + localZ, localY, z, y);
            else if (face == 2) fs = new FaceSample(z + localX, z - localZ, x, z);
            else if (face == 3) fs = new FaceSample(z + x + localX, localY, x, y);
            else if (face == 4) fs = new FaceSample(z + x + z + (x - localX), localY, x, y);
            else fs = new FaceSample(z + localX, localY, x, y);

            if (mirrorU) fs.u = fs.faceWidth - fs.u;
            if (mirrorV) fs.v = fs.faceHeight - fs.v;
            return fs;
        }
    }

    private static final class FaceSample {
        private float u;
        private float v;
        private final float faceWidth;
        private final float faceHeight;

        private FaceSample(float u, float v, float faceWidth, float faceHeight) {
            this.u = u;
            this.v = v;
            this.faceWidth = faceWidth;
            this.faceHeight = faceHeight;
        }
    }

    private static final class Bounds {
        private final Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        private final Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);

        private void include(Vector3f point) {
            min.min(point);
            max.max(point);
        }

        private void ensureNonZeroAxes() {
            ensureAxis(0);
            ensureAxis(1);
            ensureAxis(2);
        }

        private void ensureAxis(int axis) {
            float minValue = axis == 0 ? min.x : axis == 1 ? min.y : min.z;
            float maxValue = axis == 0 ? max.x : axis == 1 ? max.y : max.z;
            if (maxValue - minValue >= EPSILON) {
                return;
            }

            if (axis == 0) {
                min.x -= 0.5f;
                max.x += 0.5f;
            } else if (axis == 1) {
                min.y -= 0.5f;
                max.y += 0.5f;
            } else {
                min.z -= 0.5f;
                max.z += 0.5f;
            }
        }
    }

    public static final class TextureImage {
        private final int width;
        private final int height;
        private final int[] argb;

        private TextureImage(BufferedImage image) {
            width = image.getWidth();
            height = image.getHeight();
            argb = new int[width * height];
            image.getRGB(0, 0, width, height, argb, 0, width);
        }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int[] getArgb() { return argb; }

        private int sample(float u, float v) {
            float wrappedU = clamp01(u);
            float wrappedV = clamp01(v);
            int x = Math.min(width - 1, Math.max(0, (int) (wrappedU * (width - 1) + 0.5f)));
            int y = Math.min(height - 1, Math.max(0, (int) (wrappedV * (height - 1) + 0.5f)));
            int color = argb[x + y * width];
            int a = (color >>> 24) & 0xFF;
            int r = (color >>> 16) & 0xFF;
            int g = (color >>> 8) & 0xFF;
            int b = color & 0xFF;
            return packRgba(r, g, b, a);
        }
    }

    private static int packRgba(int r, int g, int b, int a) {
        return (r & 0xFF) | ((g & 0xFF) << 8) | ((b & 0xFF) << 16) | ((a & 0xFF) << 24);
    }

    private static float clamp01(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        return value;
    }
}
