# Plan: Diegetic Crafting, Capes, Dimensions, and Animated Textures

Implement four major features: a diegetic 3D crafting system, cosmetic capes with physics, a multi-dimension system, and support for animated block textures.

## 1. Diegetic 3D Crafting System
- **Camera Perspective:** Implement `CameraMode.CRAFTING` which provides a top-down, slightly angled view (isometric style) looking at the crafting grid.
- **3D Grid:** Instead of a 2D UI, the crafting grid is physically represented. Interacting with a Crafting Table block triggers this view.
- **Model Rendering:** Items and blocks in the crafting slots are rendered as scaled-down 3D models using the existing `Entity` and `EntityManager` systems.
- **Interaction:** Map mouse clicks to the 3D grid positions to allow placing and moving items.
- **Logic:** `CraftingManager` will validate the pattern of 3D entities against the recipe registry.

## 2. Alternate Dimensions
- **Dimensions:** Support Overworld, Nether, End, and Aether.
- **World Switching:** Implement a `DimensionManager` to handle swapping `World` and `ChunkManager` instances.
- **Environmental FX:** Update `raytracer.comp` to adjust sky, fog, and lighting based on the active dimension (e.g., red fog and no sun for Nether).
- **Generation:** Unique `WorldGenerator` logic for each dimension (Nether caves, floating islands for End/Aether).

## 3. Animated Textures
- **Detection:** Update `TextureManager` to detect vertical strips (e.g., 64x2048) and load them as sequential frames in the `Texture2DArray`.
- **Metadata:** Store `frameCount` in `BlockData`.
- **Shader Implementation:** In `raytracer.comp`, use `u_Time` and `frameCount` to dynamically calculate the texture layer index.

## 4. Cape Support
- **Model Part:** Add a "cape" `ModelPart` to the `PlayerEntity`.
- **Animation Physics:** In `PlayerEntity.syncFromPlayer`, apply procedural rotations to the cape based on horizontal movement speed and vertical falling velocity (making it "fly" back or "flap").

## Verification Plan
### Automated Tests
- Validate 3D grid position mapping.
- Test recipe matching logic.

### Manual Verification
1. **Crafting:** Interact with a crafting table, verify the camera transition, and place 3D items to craft a new object.
2. **Dimensions:** Use a portal or command to switch dimensions; verify terrain and atmosphere changes.
3. **Animations:** Observe animated blocks (like water) to ensure texture frames cycle correctly.
4. **Capes:** Verify cape movement in third-person view during various player actions.
