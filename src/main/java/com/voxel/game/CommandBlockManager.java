package com.voxel.game;

import com.voxel.game.GameContext.GameMode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Persistent programmable state and redstone execution for command blocks. */
public final class CommandBlockManager {
    public static final int BLOCK_COMMAND = 275;
    public static final int BLOCK_CHAIN_COMMAND = 276;
    public static final int BLOCK_REPEATING_COMMAND = 277;
    public static final int BLOCK_POWER_FRAGMENT = 278;
    public static final String POWER_FRAGMENT = "power_fragment";

    private static final int SAVE_VERSION = 1;
    private static final float REPEATING_INTERVAL = 1.0f;
    private final Map<Long, CommandBlockState> states = new ConcurrentHashMap<>();
    private int dimensionId = -1;

    public static final class CommandBlockState {
        public final int x, y, z;
        public volatile String command = "";
        public volatile boolean lastPowered;
        public volatile float repeatingTimer;

        private CommandBlockState(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL)
            | (((long) y & 0x1FFFFFL) << 21)
            | (((long) z & 0x1FFFFFL) << 42);
    }

    public void beginDimension(int id) {
        if (dimensionId == id) return;
        states.clear();
        dimensionId = id;
    }

    public CommandBlockState get(int x, int y, int z) {
        return states.get(pack(x, y, z));
    }

    public CommandBlockState getOrCreate(int x, int y, int z) {
        long key = pack(x, y, z);
        CommandBlockState state = states.get(key);
        if (state == null) {
            state = new CommandBlockState(x, y, z);
            states.put(key, state);
        }
        return state;
    }

    public void remove(int x, int y, int z) {
        states.remove(pack(x, y, z));
    }

    /** Programs a command block, consuming a fragment in survival. */
    public String program(GameContext ctx, int x, int y, int z, String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.startsWith("/")) normalized = normalized.substring(1).trim();
        if (normalized.length() > 512) return "Command is too long (maximum 512 characters).";
        if (ctx.gameMode == GameMode.SURVIVAL) {
            if (!hasPowerFragment(ctx)) return "A power fragment is required to program a command block.";
            if (!isAllowedInSurvival(normalized)) {
                return "Survival command blocks only allow relative /tp, /dimension, /spawn, and /unstuck.";
            }
            if (!removePowerFragment(ctx)) return "A power fragment is required to program a command block.";
        }
        getOrCreate(x, y, z).command = normalized;
        return normalized.isEmpty() ? "Command block cleared." : "Command block programmed.";
    }

    /** Executes command blocks after redstone recalculates power levels. */
    public void tick(GameContext ctx, float dt) {
        if (ctx.redstoneManager == null || ctx.commandProcessor == null || ctx.world == null) return;
        // Discover deterministic facility consoles even before the player opens
        // them. They remain unpowered until the player supplies redstone.
        int fx = com.voxel.world.AncientBuilderFacility.FACILITY_X;
        int fz = com.voxel.world.AncientBuilderFacility.FACILITY_Z;
        for (int facilityY : com.voxel.world.AncientBuilderFacility.FACILITY_YS) {
            int fy = facilityY + 1;
            for (int dx : new int[]{-3, -2, 1, 3}) {
                int x = fx + dx;
                if (isCommandBlock(ctx.world.getVoxel(x, fy, fz))) getOrCreate(x, fy, fz);
            }
        }
        CommandBlockState[] snapshot = states.values().toArray(new CommandBlockState[0]);
        for (CommandBlockState state : snapshot) {
            int blockId = ctx.world.getVoxel(state.x, state.y, state.z);
            if (!isCommandBlock(blockId)) {
                states.remove(pack(state.x, state.y, state.z));
                continue;
            }
            if (state.command == null || state.command.trim().isEmpty()) {
                String facilityCommand = com.voxel.world.AncientBuilderFacility.defaultCommandAt(state.x, state.y, state.z);
                if (!facilityCommand.isEmpty()) state.command = facilityCommand;
                else continue;
            }

            boolean powered = ctx.redstoneManager.hasPoweredNeighbor(state.x, state.y, state.z)
                || hasDirectRedstoneSource(ctx, state.x, state.y, state.z);
            boolean risingEdge = powered && !state.lastPowered;
            boolean repeating = blockId == BLOCK_REPEATING_COMMAND;
            if (powered) state.repeatingTimer -= Math.max(0.0f, dt);
            else state.repeatingTimer = 0.0f;

            if (risingEdge || (repeating && powered && state.repeatingTimer <= 0.0f)) {
                ctx.commandProcessor.executeCommandBlock(state.command, state.x, state.y, state.z);
                state.repeatingTimer = REPEATING_INTERVAL;
                // A chain block runs immediately after a powered predecessor;
                // its own command is still gated by the same redstone signal.
                if (blockId != BLOCK_CHAIN_COMMAND) {
                    runAdjacentChain(ctx, state.x, state.y, state.z);
                }
            }
            state.lastPowered = powered;
        }
    }

    private void runAdjacentChain(GameContext ctx, int x, int y, int z) {
        int[][] directions = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {-1, 0, 0}, {0, -1, 0}, {0, 0, -1}};
        for (int[] direction : directions) {
            int cx = x + direction[0], cy = y + direction[1], cz = z + direction[2];
            if (ctx.world.getVoxel(cx, cy, cz) != BLOCK_CHAIN_COMMAND) continue;
            CommandBlockState chain = getOrCreate(cx, cy, cz);
            if (chain.command == null || chain.command.trim().isEmpty()) {
                chain.command = com.voxel.world.AncientBuilderFacility.defaultCommandAt(cx, cy, cz);
            }
            if (!chain.command.trim().isEmpty()) {
                ctx.commandProcessor.executeCommandBlock(chain.command, cx, cy, cz);
            }
            return;
        }
    }

    private static boolean hasDirectRedstoneSource(GameContext ctx, int x, int y, int z) {
        return ctx.world.getVoxel(x - 1, y, z) == 25 || ctx.world.getVoxel(x + 1, y, z) == 25
            || ctx.world.getVoxel(x, y - 1, z) == 25 || ctx.world.getVoxel(x, y + 1, z) == 25
            || ctx.world.getVoxel(x, y, z - 1) == 25 || ctx.world.getVoxel(x, y, z + 1) == 25;
    }

    public static boolean isCommandBlock(int blockId) {
        return blockId == BLOCK_COMMAND || blockId == BLOCK_CHAIN_COMMAND || blockId == BLOCK_REPEATING_COMMAND;
    }

    /** Safe subset available to survival command blocks. */
    public static boolean isAllowedInSurvival(String raw) {
        if (raw == null) return false;
        String text = raw.trim();
        if (text.startsWith("/")) text = text.substring(1).trim();
        int space = text.indexOf(' ');
        String command = (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
        return command.equals("tp") || command.equals("dimension") || command.equals("dim")
            || command.equals("spawn") || command.equals("unstuck");
    }

    public static boolean hasPowerFragment(GameContext ctx) {
        if (ctx == null || ctx.playerInventory == null) return false;
        for (int i = 0; i < ctx.playerInventory.getInventorySize(); i++) {
            ItemDefinitions.ItemStack stack = ctx.playerInventory.getSlot(i);
            if (stack != null && POWER_FRAGMENT.equals(stack.itemId) && stack.count > 0) return true;
        }
        return false;
    }

    private static boolean removePowerFragment(GameContext ctx) {
        for (int i = 0; i < ctx.playerInventory.getInventorySize(); i++) {
            ItemDefinitions.ItemStack stack = ctx.playerInventory.getSlot(i);
            if (stack != null && POWER_FRAGMENT.equals(stack.itemId) && stack.count > 0) {
                stack.count--;
                if (stack.count <= 0) ctx.playerInventory.clearSlot(i);
                return true;
            }
        }
        return false;
    }

    /** Expands command-block macros such as $(x), $(player), and $(dimension). */
    public static String expandMacros(String raw, GameContext ctx, int blockX, int blockY, int blockZ) {
        if (raw == null) return "";
        int px = ctx != null && ctx.player != null ? (int) Math.floor(ctx.player.getPosition().x) : 0;
        int py = ctx != null && ctx.player != null ? (int) Math.floor(ctx.player.getPosition().y) : 0;
        int pz = ctx != null && ctx.player != null ? (int) Math.floor(ctx.player.getPosition().z) : 0;
        String dimension = ctx != null && ctx.activeDimension != null ? ctx.activeDimension.name : "overworld";
        return raw
            .replace("$(x)", String.valueOf(blockX)).replace("${x}", String.valueOf(blockX))
            .replace("$(y)", String.valueOf(blockY)).replace("${y}", String.valueOf(blockY))
            .replace("$(z)", String.valueOf(blockZ)).replace("${z}", String.valueOf(blockZ))
            .replace("$(px)", String.valueOf(px)).replace("${px}", String.valueOf(px))
            .replace("$(py)", String.valueOf(py)).replace("${py}", String.valueOf(py))
            .replace("$(pz)", String.valueOf(pz)).replace("${pz}", String.valueOf(pz))
            .replace("$(dimension)", dimension).replace("${dimension}", dimension)
            .replace("$(player)", "player").replace("${player}", "player");
    }

    public void saveToFile(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeInt(SAVE_VERSION);
            out.writeInt(states.size());
            for (CommandBlockState state : states.values()) {
                out.writeInt(state.x); out.writeInt(state.y); out.writeInt(state.z);
                out.writeUTF(state.command == null ? "" : state.command);
                out.writeBoolean(state.lastPowered);
            }
        }
    }

    public void loadFromFile(File file) throws IOException {
        states.clear();
        if (file == null || !file.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file))))) {
            int version = in.readInt();
            if (version != SAVE_VERSION) throw new IOException("Unsupported command-block save version " + version);
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                CommandBlockState state = new CommandBlockState(x, y, z);
                state.command = in.readUTF();
                state.lastPowered = in.readBoolean();
                states.put(pack(x, y, z), state);
            }
        } catch (EOFException e) {
            states.clear();
            throw e;
        }
    }
}
