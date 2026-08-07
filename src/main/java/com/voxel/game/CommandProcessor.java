package com.voxel.game;

import com.voxel.world.DimensionType;

import java.util.Locale;

import com.voxel.game.GameContext.CameraMode;
import com.voxel.game.GameContext.GameMode;
import org.joml.Vector3f;


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
            case "tp": handleTp(parts); break;
            case "unstuck": handleUnstuck(); break;
            case "setuv": handleSetUv(parts); break;
            case "dimension":
            case "dim": handleDimension(parts); break;
            case "help": handleHelp(); break;
            case "list": handleList(parts); break;
            case "camera": handleCamera(parts); break;
            case "locate": handleLocate(parts); break;
            case "screenshot":
                ctx.screenshotRequested = true;
                ctx.setStatus("Screenshot saved to screenshots/");
                break;
            case "tv": handleTv(parts); break;
            case "light": handleLight(parts); break;
            default: ctx.setStatus("Unknown command: /" + command + ". Type /help for commands."); break;
        }
    }

    private void handleDimension(String[] parts) {
        if (parts.length < 2) {
            ctx.setStatus("Usage: /dimension <overworld|nether|end|aether|error502>");
            return;
        }
        String dimName = parts[1].toLowerCase(Locale.ROOT);
        DimensionType target;
        switch (dimName) {
            case "nether": target = DimensionType.NETHER; break;
            case "end": target = DimensionType.END; break;
            case "aether": target = DimensionType.AETHER; break;
            case "error502": target = DimensionType.ERROR502; break;
            default: target = DimensionType.OVERWORLD; break;
        }
        if (target == ctx.activeDimension) {
            ctx.setStatus("Already in " + target.name);
            return;
        }
        ctx.switchToDimension(target);
    }

    private void handleTp(String[] parts) {
        if (parts.length < 4) {
            ctx.setStatus("Usage: /tp <x> <y> <z>");
            return;
        }
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            ctx.player.teleport(x, y, z);
            ctx.setStatus(String.format("Teleported to %.1f, %.1f, %.1f", x, y, z));
        } catch (NumberFormatException e) {
            ctx.setStatus("Invalid coordinates. Usage: /tp <x> <y> <z>");
        }
    }

    private void handleUnstuck() {
        int raised = ctx.player.unstuck(ctx.world, ctx.blockDataManager);
        if (raised < 0) {
            ctx.setStatus("Could not get unstuck within the safety limit.");
        } else if (raised == 0) {
            ctx.setStatus("You are not stuck.");
        } else {
            ctx.setStatus("Moved up " + raised + " block" + (raised == 1 ? "" : "s") + ".");
        }
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
        // First try the canonical registry (handles deduplicated names like "flint", "water")
        String itemId = null;
        ItemDefinitions.ItemDefinition def = null;
        if (ctx.canonicalRegistry != null) {
            CanonicalRegistry.CanonicalEntry entry = ctx.canonicalRegistry.resolve(parts[1]);
            if (entry != null) {
                // Map the canonical primary block ID back to an item ID
                itemId = ctx.itemDefinitions.getBlockItemByBlockId().get(entry.primaryBlockId);
                def = itemId != null ? ctx.itemDefinitions.getDefinition(itemId) : null;
            }
        }
        // Fall back to the old resolution chain
        if (itemId == null) {
            itemId = ctx.itemDefinitions.resolveItemId(parts[1]);
            if (itemId != null) def = ctx.itemDefinitions.getDefinition(itemId);
        }
        if (itemId == null) { ctx.setStatus("Unknown item: " + parts[1]); return; }
        int amount = 1;
        if (parts.length >= 3) {
            try { amount = Math.max(1, Integer.parseInt(parts[2])); }
            catch (NumberFormatException e) { ctx.setStatus("Invalid amount: " + parts[2]); return; }
        }
        boolean added = ctx.playerInventory.addItem(itemId, amount);
        ctx.setStatus(added ? "Given " + amount + " " + def.displayName : "Inventory full");
    }

    private void handleLight(String[] parts) {
        if (parts.length >= 2 && parts[1].equalsIgnoreCase("clear")) {
            ctx.numPointLights = 0;
            ctx.setStatus("Cleared all point lights");
            return;
        }
        if (parts.length < 4) {
            ctx.setStatus("Usage: /light <r> <g> <b> - place a light that follows you (RGB 0-255, radius 32). Or /light clear");
            return;
        }
        try {
            // RGB values in 0-255 range, clamped; radius is always 32 blocks.
            float r = Math.max(0f, Math.min(255f, Float.parseFloat(parts[1]))) / 255f;
            float g = Math.max(0f, Math.min(255f, Float.parseFloat(parts[2]))) / 255f;
            float b = Math.max(0f, Math.min(255f, Float.parseFloat(parts[3]))) / 255f;
            if (ctx.numPointLights >= GameContext.MAX_POINT_LIGHTS) {
                ctx.setStatus("Max " + GameContext.MAX_POINT_LIGHTS + " point lights reached (use /light clear)");
                return;
            }
            // Place at the player's feet, radius fixed at 32 blocks
            Vector3f pos = ctx.player.getPosition();
            int i = ctx.numPointLights * 8;
            float[] d = ctx.pointLightData;
            d[i] = pos.x; d[i + 1] = pos.y; d[i + 2] = pos.z; d[i + 3] = 32.0f;
            d[i + 4] = r; d[i + 5] = g; d[i + 6] = b; d[i + 7] = 2.0f; // intensity
            ctx.numPointLights++;
            ctx.setStatus(String.format(Locale.ROOT, "Point light RGB(%.0f, %.0f, %.0f) now follows you (radius 32)",
                r * 255f, g * 255f, b * 255f));
        } catch (NumberFormatException e) {
            ctx.setStatus("Invalid number in /light args (use integers 0-255)");
        }
    }

    private void handleHelp() {
        StringBuilder sb = new StringBuilder("Available commands:");
        sb.append("\n  /help - Show this help");
        sb.append("\n  /list items - List all items");
        sb.append("\n  /list blocks - List all placeable blocks");
        sb.append("\n  /list commands - List all commands");
        sb.append("\n  /gamemode <survival|creative> - Change game mode");
        sb.append("\n  /give <item> [amount] - Give yourself an item");
        sb.append("\n  /slotclear [slot] - Clear inventory slot");
        sb.append("\n  /spawn - Teleport to spawn");
        sb.append("\n  /tp <x> <y> <z> - Teleport to coordinates");
        sb.append("\n  /unstuck - Move upward until the player is clear");
        sb.append("\n  /dimension <overworld|nether|end|aether|error502> - Switch dimension");
        sb.append("\n  /setuv <full|half|empty> <x> <y> [w] [h] - Adjust heart UVs");
        sb.append("\n  /camera <follow|orbit|fixed> - Set camera shot type");
        sb.append("\n  /locate <village> - Find nearest village");
        sb.append("\n  /tv <channel> - Change TV channel (0=Static,1=Shopping,2=Weather,3=VNN)");
        sb.append("\n  /screenshot - Save a screenshot to screenshots/");
        sb.append("\n  /light <r> <g> <b> - Place a point light (RGB 0-255, radius 32) at your position (/light clear)");
        ctx.setStatus(sb.toString());
    }

    private void handleCamera(String[] parts) {
        if (parts.length < 2) {
            ctx.setStatus("Usage: /camera <follow|orbit|fixed>");
            return;
        }
        String mode = parts[1].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "follow":
            case "third":
                ctx.cameraMode = CameraMode.THIRD_PERSON_FOLLOW;
                ctx.setStatus("Camera: third person follow");
                break;
            case "orbit":
                ctx.cameraMode = CameraMode.THIRD_PERSON_ORBIT;
                ctx.setStatus("Camera: orbit");
                break;
            case "fixed":
                ctx.cameraMode = CameraMode.THIRD_PERSON_FIXED;
                ctx.setStatus("Camera: fixed");
                break;
            default:
                ctx.setStatus("Unknown camera mode: " + mode + ". Use: follow, orbit, or fixed");
        }
    }

    private void handleList(String[] parts) {
        if (parts.length < 2) {
            ctx.setStatus("Usage: /list <items|blocks|commands>");
            return;
        }
        String category = parts[1].toLowerCase(Locale.ROOT);
        switch (category) {
            case "items":
            case "item": {
                StringBuilder sb = new StringBuilder("Items:");
                int count = 0;
                for (java.util.Map.Entry<String, ItemDefinitions.ItemDefinition> entry : ctx.itemDefinitions.getRegistry().entrySet()) {
                    sb.append("\n  ").append(entry.getKey()).append(" - ").append(entry.getValue().displayName);
                    count++;
                    if (count >= 30) { sb.append("\n  ... and more"); break; }
                }
                ctx.setStatus(sb.toString());
                break;
            }
            case "blocks":
            case "block": {
                StringBuilder sb = new StringBuilder("Placeable blocks:");
                int count = 0;
                for (java.util.Map.Entry<String, ItemDefinitions.ItemDefinition> entry : ctx.itemDefinitions.getRegistry().entrySet()) {
                    if (entry.getValue().kind == ItemDefinitions.ItemKind.BLOCK) {
                        sb.append("\n  ").append(entry.getKey());
                        count++;
                    }
                }
                sb.append("\n(").append(count).append(" total)");
                ctx.setStatus(sb.toString());
                break;
            }
            case "commands":
            case "command": {
                handleHelp();
                break;
            }
            default:
                ctx.setStatus("Unknown list category: " + category + ". Use: items, blocks, or commands");
        }
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

    private void handleLocate(String[] parts) {
        if (parts.length < 2) {
            ctx.setStatus("Usage: /locate <village>");
            return;
        }
        String structure = parts[1].toLowerCase(Locale.ROOT);
        if (structure.equals("village")) {
            if (com.voxel.world.structure.MapGenVillage.hasLastVillage()) {
                int vx = com.voxel.world.structure.MapGenVillage.getLastVillageX();
                int vy = com.voxel.world.structure.MapGenVillage.getLastVillageY();
                int vz = com.voxel.world.structure.MapGenVillage.getLastVillageZ();
                float px = ctx.player.getPosition().x;
                float pz = ctx.player.getPosition().z;
                int dist = (int)Math.sqrt((vx - px) * (vx - px) + (vz - pz) * (vz - pz));
                ctx.setStatus("Nearest village: " + vx + ", " + vy + ", " + vz + " (" + dist + " blocks away)");
            } else {
                ctx.setStatus("No village found nearby. Try exploring more!");
            }
        } else {
            ctx.setStatus("Unknown structure: " + structure + ". Available: village");
        }
    }

    private void handleTv(String[] parts) {
        if (parts.length < 2) {
            ctx.setStatus("Usage: /tv <channel> - Change TV channel (0=Static, 1=Shopping, 2=Weather, 3=VNN News)");
            return;
        }
        try {
            int channel = Integer.parseInt(parts[1]);
            if (channel < 0 || channel > 3) {
                ctx.setStatus("Invalid channel. Use 0-3: 0=Static, 1=Shopping, 2=Weather, 3=VNN News");
                return;
            }
            if (ctx.tvSystem != null) {
                // Find nearby TV and set its channel
                ctx.tvSystem.setChannel(ctx.tvBlockX, ctx.tvBlockY, ctx.tvBlockZ, channel);
                ctx.setStatus("TV channel set to: " + ctx.tvSystem.getChannelName(channel));
            } else {
                ctx.setStatus("No TV system available");
            }
        } catch (NumberFormatException e) {
            ctx.setStatus("Invalid channel number");
        }
    }
}
