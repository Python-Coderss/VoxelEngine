package com.voxel.game;

import com.voxel.world.DimensionType;

import java.util.Locale;

import com.voxel.game.GameContext.CameraMode;
import com.voxel.game.GameContext.GameMode;


/**
 * Parses and executes slash commands (gamemode, give, spawn, dimension, etc.).
 */
public class CommandProcessor {
    @FunctionalInterface
    public interface CommandCallback {
        void onCommand(String command, String[] parts, String status);
        default void setStatus(String msg) {}
    }

    private final GameContext ctx;

    public CommandProcessor(GameContext ctx) {
        this.ctx = ctx;
    }

    public void execute(String raw) {
        if (raw.isEmpty()) return;
        String commandText = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] parts = commandText.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return;

        String command = parts[0].toLowerCase(Locale.ROOT);
        switch (command) {
            case "gamemode": handleGamemode(parts); break;
            case "give": handleGive(parts); break;
            case "slotclear": handleSlotClear(parts); break;
            case "spawn":
                ctx.player.respawn();
                ctx.setStatus("Teleported to spawn.");
                break;
            case "setuv": handleSetUv(parts); break;
            case "dimension":
            case "dim": handleDimension(parts); break;
            default: ctx.setStatus("Unknown command: /" + command); break;
        }
    }

    private void handleDimension(String[] parts) {
        if (parts.length < 2) {
            ctx.setStatus("Usage: /dimension <overworld|nether|end|aether>");
            return;
        }
        String dimName = parts[1].toLowerCase(Locale.ROOT);
        DimensionType target;
        switch (dimName) {
            case "nether": target = DimensionType.NETHER; break;
            case "end": target = DimensionType.END; break;
            case "aether": target = DimensionType.AETHER; break;
            default: target = DimensionType.OVERWORLD; break;
        }
        if (target == ctx.activeDimension) {
            ctx.setStatus("Already in " + target.name);
            return;
        }
        ctx.switchToDimension(target);
    }

    private void handleSetUv(String[] parts) {
        if (parts.length < 4) {
            ctx.setStatus("Usage: /setuv <full|half|empty> <x> <y> [w] [h]");
            return;
        }
        try {
            String type = parts[1].toLowerCase(Locale.ROOT);
            float x = Float.parseFloat(parts[2]);
            float y = Float.parseFloat(parts[3]);
            float w = parts.length > 4 ? Float.parseFloat(parts[4]) : 9;
            float h = parts.length > 5 ? Float.parseFloat(parts[5]) : 9;
            org.joml.Vector4f uv = new org.joml.Vector4f(x, y, w, h);
            if (type.equals("full")) ctx.uvHeartFull = uv;
            else if (type.equals("half")) ctx.uvHeartHalf = uv;
            else if (type.equals("empty")) ctx.uvHeartEmpty = uv;
            else { ctx.setStatus("Invalid heart type: " + type); return; }
            ctx.setStatus("Updated " + type + " UVs: " + x + "," + y + " (" + w + "x" + h + ")");
        } catch (Exception e) {
            ctx.setStatus("Error parsing values.");
        }
    }

    private void handleGamemode(String[] parts) {
        if (parts.length < 2) { ctx.setStatus("Usage: /gamemode <survival|creative>"); return; }
        String value = parts[1].toLowerCase(Locale.ROOT);
        if (value.equals("survival") || value.equals("s")) {
            ctx.gameMode = GameMode.SURVIVAL;
            ctx.player.setFlying(false);
            ctx.setStatus("Gamemode set to survival");
        } else if (value.equals("creative") || value.equals("c")) {
            ctx.gameMode = GameMode.CREATIVE;
            ctx.player.setFlying(true);
            ctx.setStatus("Gamemode set to creative");
        } else {
            ctx.setStatus("Invalid gamemode: " + parts[1]);
        }
    }

    private void handleGive(String[] parts) {
        if (parts.length < 2) { ctx.setStatus("Usage: /give <item> [amount]"); return; }
        String itemId = ctx.itemDefinitions.resolveItemId(parts[1]);
        if (itemId == null) { ctx.setStatus("Unknown item: " + parts[1]); return; }
        ItemDefinitions.ItemDefinition def = ctx.itemDefinitions.getDefinition(itemId);
        int amount = 1;
        if (parts.length >= 3) {
            try { amount = Math.max(1, Integer.parseInt(parts[2])); }
            catch (NumberFormatException e) { ctx.setStatus("Invalid amount: " + parts[2]); return; }
        }
        boolean added = ctx.playerInventory.addItem(itemId, amount);
        ctx.setStatus(added ? "Given " + amount + " " + def.displayName : "Inventory full");
    }

    private void handleSlotClear(String[] parts) {
        int slotIndex = ctx.playerInventory.getSelectedSlot();
        if (parts.length >= 2) {
            try { slotIndex = Integer.parseInt(parts[1]) - 1; }
            catch (NumberFormatException e) { ctx.setStatus("Invalid slot: " + parts[1]); return; }
        }
        if (slotIndex < 0 || slotIndex >= ctx.playerInventory.getInventorySize()) {
            ctx.setStatus("Slot out of range. Use 1-" + ctx.playerInventory.getInventorySize());
            return;
        }
        ctx.playerInventory.clearSlot(slotIndex);
        ctx.setStatus("Cleared slot " + (slotIndex + 1));
    }
}
