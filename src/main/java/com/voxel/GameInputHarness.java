package com.voxel;

import org.joml.Vector3f;

/**
 * Self-driving test harness for the player-grid freeze (fall-through fix).
 *
 * Launches a daemon thread at construction that waits for the real game to
 * finish booting, then teleports the player far ahead of the loaded area and
 * verifies:
 *   1. {@link com.voxel.game.GameContext#waitingForChunks} engages (freeze on).
 *   2. While frozen, the player's position is completely locked — it cannot
 *      move into the ungenerated sections (that was the fall-through bug).
 *   3. The freeze releases once the 3×3×3 grid finishes generating.
 *   4. Normal gravity/landing still works after the final teleport back to the
 *      already-generated spawn area (the freeze must not break real physics).
 *
 * Run with:  ./mvnw compile exec:java -Dexec.mainClass=com.voxel.GameInputHarness
 * Prints [HARNESS] markers and exits 0 on PASS, 1 on FAIL.
 */
public class GameInputHarness extends Main {

    private static final java.io.File LOG_FILE = new java.io.File("harness.log");

    public static void main(String[] args) {
        new GameInputHarness().run();
    }

    public GameInputHarness() {
        Thread driver = new Thread(this::driveTest, "HarnessDriver");
        driver.setDaemon(true);
        driver.start();
    }

    private void driveTest() {
        try {
            // ── Phase 0: wait for the game to boot (player + world + spawn) ──
            long bootDeadline = System.currentTimeMillis() + 180_000;
            while (System.currentTimeMillis() < bootDeadline) {
                if (ctx != null && ctx.player != null && chunkManager != null && !ctx.spawnLoading) break;
                Thread.sleep(100);
            }
            if (ctx == null || ctx.player == null || chunkManager == null) { fail("game never finished booting"); return; }
            if (ctx.spawnLoading) { fail("spawn never resolved (still spawnLoading)"); return; }

            Player p = ctx.player;
            Vector3f spawnPos = new Vector3f(p.getPosition());
            log("boot complete at " + spawnPos);
            p.setFlying(true); // terrain-safety: no fall damage / burial during teleports

            // ── Teleport far ahead twice: each must trigger the freeze ──
            for (int step = 1; step <= 2; step++) {
                Vector3f pos = p.getPosition();
                float tx = pos.x + 2048f, ty = pos.y, tz = pos.z;
                p.teleport(tx, ty, tz);
                log("step " + step + ": teleported to (" + tx + ", " + ty + ", " + tz + ")");

                // Freeze must engage.
                long engageDeadline = System.currentTimeMillis() + 30_000;
                while (System.currentTimeMillis() < engageDeadline && !ctx.waitingForChunks) {
                    Thread.sleep(5);
                }
                if (!ctx.waitingForChunks) { fail("step " + step + ": waitingForChunks never engaged after teleport"); return; }
                log("step " + step + ": freeze engaged");

                // While frozen, position must be bit-locked (no falling/walking into void).
                float frozenX = p.getPosition().x, frozenY = p.getPosition().y, frozenZ = p.getPosition().z;
                long releaseDeadline = System.currentTimeMillis() + 90_000;
                boolean released = false;
                while (System.currentTimeMillis() < releaseDeadline) {
                    Vector3f fp = p.getPosition();
                    if (fp.x != frozenX || fp.y != frozenY || fp.z != frozenZ) {
                        fail("step " + step + ": player moved while frozen: " + frozenX + "," + frozenY + "," + frozenZ
                                + " -> " + fp.x + "," + fp.y + "," + fp.z);
                        return;
                    }
                    if (p.isDead()) { fail("step " + step + ": player died while frozen"); return; }
                    if (!ctx.waitingForChunks) { released = true; break; }
                    Thread.sleep(5);
                }
                if (!released) { fail("step " + step + ": freeze never released (grid never generated)"); return; }
                log("step " + step + ": freeze released, position locked the whole time");
                Thread.sleep(2000); // let the new area stream in
            }

            // ── Sanity: physics still works on loaded terrain ──
            // Return to the original spawn (re-freezes until its grid reloads,
            // then the player drops 12 blocks onto the known-safe spawn surface).
            p.setFlying(false);
            p.teleport(spawnPos.x, spawnPos.y + 12f, spawnPos.z);
            log("physics check: returning to spawn, dropping from y=" + (spawnPos.y + 12f));
            long landDeadline = System.currentTimeMillis() + 90_000;
            while (System.currentTimeMillis() < landDeadline) {
                if (p.isDead()) { fail("physics check: player died at spawn"); return; }
                if (p.isOnGround()) break;
                Vector3f sp = p.getPosition();
                if (sp.y < spawnPos.y - 6f) {
                    fail("physics check: fell through loaded terrain to y=" + sp.y + " (spawn y=" + spawnPos.y + ")");
                    return;
                }
                Thread.sleep(50);
            }
            if (!p.isOnGround()) { fail("physics check: never landed on loaded terrain"); return; }
            log("physics check: landed at " + p.getPosition() + " — normal gravity works");

            pass("freeze holds during chunk loading, releases after generation, physics intact");
        } catch (InterruptedException e) {
            fail("interrupted: " + e);
        } catch (Throwable e) {
            e.printStackTrace();
            fail("exception: " + e);
        }
    }

    private static void log(String s) {
        String line = "[HARNESS] " + s;
        System.out.println(line);
        System.out.flush();
        try (java.io.FileWriter w = new java.io.FileWriter(LOG_FILE, true)) {
            w.write(line + "\n");
        } catch (java.io.IOException ignored) {
        }
    }

    private static void fail(String msg) {
        log("FAIL: " + msg);
        System.exit(1);
    }

    private static void pass(String msg) {
        log("PASS: " + msg);
        System.exit(0);
    }
}
