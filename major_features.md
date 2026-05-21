# Plan: Crafting, Capes, Dimensions, and Animated Textures

Implement four major features: a crafting system, cosmetic capes with movement physics, a multi-dimension system, and support for animated block textures.

## 1. Animated Textures
- **Metadata:** Update `BlockDataManager.BlockData` to include `isAnimated` and `frameCount`.
- **Texture Loading:** Modify `TextureManager` to detect vertical strips (height > 64) and load each 64x64 frame into sequential layers of the `Texture2DArray`.
- **Shader:** Update `raytracer.comp` to calculate the current frame using `u_Time` and the stored metadata, offsetting the texture index accordingly.

## 2. Cape Support
- **Model:** Add a `cape` `ModelPart` to `PlayerEntity`.
- **Physics:** In `PlayerEntity.syncFromPlayer`, apply procedural rotation to the cape based on horizontal speed and vertical movement.
- **Rendering:** Register and load a `cape.png` in `TextureManager`.

## 3. Crafting System
- **Registry:** Create a `CraftingManager` class to store 2x2 and 3x3 recipes.
- **UI:** Add 4 input slots and 1 result slot to the inventory UI in `Main.java`.
- **Logic:** Handle item movement into crafting slots and automatic result generation when a valid pattern is matched.

## 4. Alternate Dimensions
- **Dimension Manager:** Create a system to manage multiple `World` and `ChunkManager` instances for Overworld, Nether, End, and Aether.
- **Generation:**
    - **Nether:** Cave-like structure with roof and floor.
    - **End/Aether:** Sparse floating islands.
- **Rendering:** Update `raytracer.comp` to change sky color, fog, and lighting based on the current dimension.

## Verification Plan
### Automated Tests
- Test recipe matching logic in `CraftingManager`.
- Test dimension switching consistency (ensuring player position and world data sync correctly).

### Manual Verification
1. **Crafting:** Open inventory, place items in the 2x2 grid, and verify the result slot populates correctly.
2. **Capes:** Switch to third-person view and move around to see the cape swaying.
3. **Dimensions:** Use a command to switch dimensions and verify the new terrain and atmosphere.
4. **Animations:** Place an animated block (e.g., water) and verify the texture cycles.
