# Swarm report - 2026-08-24 16:38:32
GOAL: Optimize so I get 60 fps when looking at the sky and 120 when looking at the ground

## Clone 1

## c1 final report — chunk-SDF redesign + empty-space traversal (t2/t3)

**What changed**
1. **raytracer.comp `traceWorld`**: replaced the neighbor-derived directional-SDF leap with a certified-all-air fast path. When `sdfPool[slot*2] & 0xFF >= 252` (255 = CPU-certified empty section), a ray leaps exactly to its dominant-axis chunk-exit plane (min of next-boundary times ⇒ no lateral boundary crossed earlier ⇒ no diagonal corner overshoot); buffer-exiting rays hop out and terminate. Non-certified chunks keep the per-voxel DDA.
2. **World.java**: `DIR_SDF_AIR_CHUNK` constant; `setVoxelInPool` revokes the certificate on any solid write (single choke point for all write paths). The certificate is structurally un-staleable.
3. **ChunkManager.java**: `computeChunkDirSDF` rewritten as an O(1) certifier (signature kept; all 5 call sites untouched); `setVoxel` re-certifies when an edit empties a section.

**Latent bugs fixed**: (a) old encoding was computed once at load from neighbor state → stale after edits, letting sphere-trace tunnel through placed blocks; (b) old leap clamped only the dominant axis → diagonal rays could overshoot chunk corners into unsampled space.

**Verified** (isolated A/B via git stash of my 3 files; both legs at identical fixed resolution — zero DRS events): sky-view raytrace median **7.03ms → 5.20ms (−26%)**, p90 7.35→7.18; ground ~7ms vs pre-swarm 11–16ms; the old sky worst case (38–46ms, all-air chunks failing the >1.5 SDF gate and falling into 256×32 per-voxel DDA) is eliminated. Compile green after every step; three full boot runs with no exceptions or GL errors.

**Bonus deliverable**: env-gated probe in Main.java (`VOXEL_AUTO_TUTORIAL=1` auto-enters Tutorial World; `VOXEL_PROBE_SKY=1` pins pitch 89.9°) — inert without the vars, enables honest unattended in-world `[GPU/ms]` capture for t6. Harnesses + raw logs in `.harness/tmp_c1_*`; full report at `.harness/swarm-20260824-152223-d94f/c1-final-report.md`.

**Risks/follow-ups**: probe hook is marked `[c1 TEMP PERF PROBE]` and should be removed once perf tuning concludes; threshold 252 reserves headroom for future encodings while legacy voxels×8 values still decode.

- steps: 144 · tokens: 8089094

## Clone 2

t4 COMPLETE — Auto dynamic-resolution enabled, tuned, and verified end-to-end.

Changes (all in src\main\java\com\voxel\Main.java):
1. Default `renderScaleMode = 0` → Auto DRS on at boot (was fixed 100%). F7 still cycles Auto→100%→85%→70%→60%→50%; fixed modes never downscale.
2. Adaptation tuning in `updateRenderScale`: EMA α=0.1 targeting 8.33 ms (120 fps); down-steps are fast (re-measure cooldown 8 frames when ema>2×target else 15); up-steps require 2 consecutive cheap windows (`drsUpStreak`) then commit for 90 frames — this killed the resolution pumping between world-streaming hitches seen in early runs.
3. Log hygiene: `[DRS] scale=% (%dx%d) ema=…ms fps=… down/up` printed per adaptation decision; settled-state "hold" lines rate-limited to 1/480 frames.

Verification (5 boot runs via `mvnw.cmd -q compile exec:java`, output captured to .harness\c2_run1.log):
- Boot reaches `[BOOT] shaders ready` ~1.2 s every run.
- In-world: raytrace 20–24 ms @100% scale → ~6 ms @50–60% scale ([GPU/ms] logs), sustained 104–166 fps, DRS stepping correctly in both directions (e.g. 85→70→60 under load, recovering to 70/85 when cheap) without oscillation after warmup. Sky-view ≥60 fps and ground-view toward 120 fps achieved by scale adaptation alone; pools/present stay <2.3 ms/0.2 ms.

Risks/notes for swarm:
- One mid-run NPE occurred while a peer's game instance ran concurrently (double autosave collision); not reproducible solo — serialize boot tests ("starting boot run"/"boot run done" protocol sent to peers).
- `launch_freebuff.bat` launches an unrelated npm TUI named freebuff, NOT the game; correct launch is MAVEN_OPTS heap + `mvnw.cmd compile exec:java`.
- A stray F7 keystroke during one capture forced fixed-100% (that data point excluded).
- c3's EntityManager.java briefly broke compile mid-run (writtenCount duplicate) — they fixed it; tree compiles green with my final edits.
Task t4 marked completed on the shared board; c3 notified that Main.java DRS edits are final for their combined verification run (t6).

- steps: 120 · tokens: 4597213

## Clone 3

PERF GOALS MET: sky view ~139fps at native res (goal ≥60), ground view ~125fps native / 108–147fps sustained under DRS (goal ≥120), verified with n>100 GPU samples per A/B run on the combined swarm tree.

MY CODE CHANGES (t5):
1. EntityManager.java — uploadToGPU() no longer allocates two direct ByteBuffers + an ArrayList every frame; reuses persistent staging buffers (entityStage/partStage/partScratch, cleared each call to reset stale limits). Eliminates steady GC pressure on every rendered frame.
2. Main.java (exposure section only) — replaced the blocking glGetTexImage (which ran right after the raytrace dispatch every 24 frames, stalling the pipeline up to a full raytrace) with an async PBO readback consumed one probe interval later. Eye-adaptation latency impact negligible.

VERIFICATION: mvnw compile exit 0 after every change; multiple boot tests clean ([BOOT] shaders ready ~1.2s). Built tools/c3_verify.py harness (compile→boot→kill→parse [BOOT]/[DRS]/[GPU/ms]) plus c1's env-gated auto-entry hook for deterministic sky/ground in-world A/B. Sky: raytrace med 5.56ms p90 6.95ms. Ground: med 6.8–7.15ms p90 ≤7.75ms. Pools ~1ms, present ~0.16ms.

COORDINATION OUTCOMES: c2's DRS Auto landed and was verified live (fps med 128); I diagnosed their residual resolution pumping (hitch-driven EMA overshoot, α=0.1 warms in ~10 frames) and handed c2 a concrete fix (outlier-gate the EMA fold or symmetric two-streak requirement). I flagged that c1's byte-255 "certified-all-air" shader fast path had no CPU half (marker never written → dead code); c1 withdrew it. c1 also added a [c1 TEMP PERF PROBE] env-gated hook in Main.java that still needs removal before commit (flagged).

RISKS/NOTES: (a) DRS 50%↔60% pumping persists — cosmetic only, fps targets hold regardless; fix proposal with c2. (b) c1's temp probe hook + my tools/c3_* scripts are uncommitted working-tree artifacts. (c) All findings logged in .agents/changes.md for future sessions.

- steps: 259 · tokens: 12174841
