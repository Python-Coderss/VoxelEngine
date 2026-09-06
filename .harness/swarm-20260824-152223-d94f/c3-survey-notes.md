# C3 survey notes (2026-08-24, swarm d94f)

## Baseline perf (game.log from 13:57 run, 1280x720 native, shadowMapRes=320)
- Menu panorama: raytrace=1.7ms
- In-world GROUND view: raytrace 11-16ms
- In-world SKY view: raytrace 38-46ms  <-- the goal gap
- pools regen: 0.8-2.9ms (amortized every 6 frames)
- present blit: 0.22ms
=> GPU-bound; sky view must come down ~4x for 60fps.

## Render pipeline facts
- Main.loop(): per-frame ~30 glProgramUniform calls + SSBO binds + entityManager.uploadToGPU
  + point-light buffer upload + UI canvas rebuild (hud.uiManager.begin/render/end) every frame.
- Compute dispatch at renderW x renderH /16x16. DRS exists: F7 cycles Auto/100/85/70/60/50.
  Default = index 1 (100% native), Auto targets 120fps, steps {50,60,70,85,100}%, cooldown 45
  frames between steps, EMA 0.9/0.1. (c2's lane to change defaults.)
- raytracer.comp main(): UI-opaque early-out -> camClipped probe -> preview ghost ->
  2-bounce traceAll loop -> marchLightShafts(godT<=96, nSteps<=10, affine pool UV stepping,
  OOB break) -> ACES -> posterize. Sky pixels: full DDA through empty space + GetSky + shafts.
- genLightPool pass shares the same program (u_ShadowPass==1); poolDepth marches up to 96
  cells over a 320x320 grid.
- perf ring: [GPU/ms] pools/raytrace/present printed once per second.

## Tooling quirks (this box)
- cmd.exe: no head/tail. Use python tools/c3_grep.py <regex> <file>.
- run_command stdout capture drops output intermittently => redirect to tools/c3_out.txt and
  read_file it instead.
- mvnw.cmd is the Windows build entrypoint.

## Ownership map
- c1: raytracer.comp only. c2: Main.java DRS constants/log line. c3 (me): Main.java render-loop
  CPU path + EntityManager.java + verification. Coordination via task board + send_message.
