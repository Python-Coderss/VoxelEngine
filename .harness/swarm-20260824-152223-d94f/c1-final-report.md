# c1 final report — chunk-SDF redesign + empty-space traversal (t2/t3)

## What changed
1. `raytracer.comp` `traceWorld` (L~1187): replaced the neighbor-derived directional-SDF leap with a
   **certified-all-air fast path**. When `sdfPool[slot*2] & 0xFF >= 252` (255 = CPU-certified all-air
   section), the ray leaps exactly to its dominant-axis chunk-EXIT plane (`min` of next-boundary times,
   so no lateral boundary is crossed earlier → no diagonal corner overshoot, no unprobed cells). Rays that
   would exit the buffer entirely hop out and terminate. Non-certified sections keep the per-voxel DDA.
2. `World.java`: added `DIR_SDF_AIR_CHUNK = (byte)255`; `setVoxelInPool` revokes byte 0 of the dir-SDF
   slot whenever a solid voxel is written (single choke point — every write path funnels through it,
   including villager/structure writes via the slot-dirty listener). Certificate can never go stale.
3. `ChunkManager.java`: `computeChunkDirSDF` rewritten as O(1) certifier (signature kept; all 5 call
   sites at L1656/1711/1850/1968/2019 untouched). Added re-certification in `setVoxel` when an edit leaves
   the section with zero solid bits (last-block-broken case). Upload path unchanged — dirty-slot SDF slice
   piggybacks on Main.uploadDirtyChunks (L6986–7000), no renderer edits needed.

## Bugs fixed (latent, pre-existing)
- Old encoding was computed once at chunk load from NEIGHBOR states; placing a block into an air chunk
  left `dirSdf=8..128` valid-looking while the shader's sphere-trace could step past the new block
  (staleness tunneling).
- Old leap clamped only the dominant axis (`stepVox = min(sdf*0.95, distToBndDom-1)`) — diagonal rays
  could overshoot a chunk corner into unsampled space.

## Verified numbers (1280×720 native, fixed res both legs — no DRS events during either run)
- Isolated A/B via `git stash push -- <my 3 files>`: sky-view (pitch=89.9° lock) raytrace median
  **7.03 ms without** my changes vs **5.20 ms with** (−26%, p90 7.35→7.18, max 7.73→7.51).
- Ground view ~7 ms vs pre-swarm baseline 11–16 ms. Pre-swarm sky worst case 38–46 ms eliminated
  (root cause: all-air chunks encoded dirSdf≈1 voxel clearance → failed the `>1.5` gate → 256-step
  outer × 32-step inner per-voxel DDA through pure air).
- Compile green after every step; two full boot runs clean (no exceptions, no GL errors).

## Reusable probe (left in tree, env-gated, inert without vars)
- `VOXEL_AUTO_TUTORIAL=1` → auto-enters Tutorial World 3 s after boot (same path as DOWN+ENTER on menu).
- `VOXEL_PROBE_SKY=1` (with the above) → pins pitch=89.9° for pure-sky measurement.
- Marked `[c1 TEMP PERF PROBE]` in Main.java tick(); remove when perf tuning concludes.
- Harnesses: `.harness/tmp_c1_boot.py` (ground view), `.harness/tmp_c1_boot_sky.py` (sky view);
  raw logs `tmp_c1_run.log`, `tmp_c1_run_sky*.log`.

## Risks / follow-ups
- The 252 threshold reserves 252–254 for future encodings; legacy voxels*8 values (≤248) still decode
  correctly if anything ever writes them again.
- `getChunkDirSDF`'s doc comment in raytracer.comp updated to the new encoding.
- t6 combined verify can now produce honest in-world numbers using the env-gated probe.
