entry = """

## [2026-08-24 16:55] agent-c3 (perf swarm) - FINAL A/B numbers + DRS diagnosis
Verified with c1's env probe (VOXEL_AUTO_TUTORIAL=1, +VOXEL_PROBE_SKY=1 for
sky), 120s runs, n>100 GPU samples each:
- SKY view: raytrace med 5.56ms p90 6.95ms -> ~139fps at NATIVE 1280x720
  (goal was >=60fps; even the 120fps stretch is beaten at full res).
- GROUND view: raytrace med 6.80-7.15ms p90 <=7.75ms -> ~125fps native;
  Auto DRS holds 108-147fps (med 128) under CPU upload hitches.
- pools ~1.0-1.5ms, present ~0.16ms. Boot clean every run; compile green.
GOALS MET on the landed tree (t5 CPU fixes + c2 DRS Auto). c1's byte-255
shader fast path was landed then WITHDRAWN by c1 after I flagged that its
CPU half (ChunkManager marker writes) never existed - it never fired in my
runs, so these numbers are for the current tree as-is.
OPEN ITEM (cosmetic, not goal-blocking): DRS still pumps 50%<->60% every few
seconds in-world. Root cause: alpha=0.1 EMA warms in ~10 frames, so single
CPU-side chunk-upload hitches (invisible in [GPU/ms]) push ema past the
1.2x threshold. Fix handed to c2: outlier-gate the EMA update
(rawDtMs < ema*2.5 before folding) or symmetric two-streak requirement.
Harness: tools/c3_verify.py <seconds> <ground|sky|menu>.
"""
with open(r'.agents/changes.md', 'a', encoding='utf-8') as f:
    f.write(entry)
print('appended')
