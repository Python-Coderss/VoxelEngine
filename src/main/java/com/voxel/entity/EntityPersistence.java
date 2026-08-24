package com.voxel.entity;

import com.voxel.Player;
import com.voxel.world.DimensionType;
import org.joml.Vector3f;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Serializes persistent entities to/from JSON for the world save.
 *
 * Transient entities (projectiles, orbs, the player avatar, minecarts, boss
 * fights owned by GameContext/AetherDungeonRegistry) are excluded — they are
 * either recreated by gameplay or tracked elsewhere.
 */
public final class EntityPersistence {

    private EntityPersistence() {}

    /** Export all persistable entities in the manager to a JSON array. */
    public static JSONArray export(EntityManager em) {
        JSONArray arr = new JSONArray();
        if (em == null) return arr;
        for (int i = 0; i < em.getEntityCount(); i++) {
            Entity e = em.getEntity(i);
            if (!isPersistable(e)) continue;
            JSONObject o = new JSONObject();
            o.put("type", e.getClass().getSimpleName());
            o.put("dim", e.dimension.name());
            o.put("x", e.getPosX());
            o.put("y", e.getPosY());
            o.put("z", e.getPosZ());
            o.put("yaw", e.rotation.y);
            if (e instanceof EnemyEntity && !((EnemyEntity) e).isDead()) {
                o.put("hp", ((EnemyEntity) e).getHealth());
            }
            arr.put(o);
        }
        return arr;
    }

    /**
     * Create entity objects for every entry of {@code arr} belonging to
     * {@code dim}. Caller must set world + add to the EntityManager.
     */
    public static java.util.List<Entity> createForDimension(JSONArray arr, DimensionType dim,
                                                            TextureManagerHolder tmh) {
        java.util.List<Entity> out = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (!dim.name().equals(o.optString("dim"))) continue;
            Vector3f pos = new Vector3f((float) o.getDouble("x"),
                    (float) o.getDouble("y"), (float) o.getDouble("z"));
            Entity e = create(o.getString("type"), nextId(), pos, tmh);
            if (e == null) continue;
            e.dimension = dim;
            e.rotation.y = (float) o.optDouble("yaw", 0.0);
            if (e instanceof EnemyEntity && o.has("hp")) {
                ((EnemyEntity) e).restoreHealth((float) o.getDouble("hp"));
            }
            out.add(e);
        }
        return out;
    }

    /** Simple ascending id source for restored entities. */
    private static int nextId() { return 95_000 + (idCounter++ % 5_000); }
    private static int idCounter = 0;

    public interface TextureManagerHolder {
        com.voxel.utils.TextureManager getTextureManager();
        Player getPlayer();
    }

    /** Whether this entity type is written to the save. */
    public static boolean isPersistable(Entity e) {
        return !(e instanceof PlayerEntity)
                && !(e instanceof ExperienceOrbEntity)
                && !(e instanceof ArrowEntity)
                && !(e instanceof FireballEntity)
                && !(e instanceof WitherSkullEntity)
                && !(e instanceof AetherProjectileEntity)
                && !(e instanceof EntityEnderEye)
                && !(e instanceof MinecartEntity)
                && !(e instanceof EndCrystalEntity)
                // Bosses: fight state owned by GameContext / AetherDungeonRegistry
                && !(e instanceof EnderDragonEntity)
                && !(e instanceof WitherEntity)
                // Dungeon guardians respawn via AetherDungeonRegistry spawn points
                && !(e instanceof SentryEntity)
                && !(e instanceof MimicEntity)
                && !(e instanceof ValkyrieQueenEntity)
                && !(e instanceof SunSpiritEntity)
                && !(e instanceof SliderEntity);
    }

    /** Type-name → constructor factory. Returns null for unknown types. */
    public static Entity create(String type, int id, Vector3f pos, TextureManagerHolder tmh) {
        com.voxel.utils.TextureManager tm = tmh.getTextureManager();
        Player p = tmh.getPlayer();
        switch (type) {
            // ── Passive overworld ──
            case "CowEntity":      return new CowEntity(id, pos, tm, p);
            case "PigEntity":      return new PigEntity(id, pos, tm, p);
            case "SheepEntity":    return new SheepEntity(id, pos, tm, p);
            case "ChickenEntity":  return new ChickenEntity(id, pos, tm, p);
            case "IronGolemEntity":   return new IronGolemEntity(id, pos, tm);
            case "SnowGolemEntity":   return new SnowGolemEntity(id, pos, tm);
            case "VillagerEntity":    return new VillagerEntity(id, pos, tm);
            // ── Hostile overworld ──
            case "ZombieEntity":      return new ZombieEntity(id, pos, tm, p);
            case "SkeletonEntity":    return new SkeletonEntity(id, pos, tm, p);
            case "SpiderEntity":      return new SpiderEntity(id, pos, tm, p);
            case "CreeperEntity":     return new CreeperEntity(id, pos, tm, p);
            case "BlazeEntity":       return new BlazeEntity(id, pos, tm, p);
            case "EndermanEntity":    return new EndermanEntity(id, pos, tm, p);
            case "EndermiteEntity":   return new EndermiteEntity(id, pos, tm, p);
            case "SilverfishEntity":  return new SilverfishEntity(id, pos, tm, p);
            case "ZombiePigmanEntity":return new ZombiePigmanEntity(id, pos, tm, p);
            case "MagmaCubeEntity":   return new MagmaCubeEntity(id, pos, tm, p, 2);
            // ── Aether wildlife ──
            case "AerbunnyEntity":    return new AerbunnyEntity(id, pos, tm);
            case "AerwhaleEntity":    return new AerwhaleEntity(id, pos, tm);
            case "FlyingCowEntity":   return new FlyingCowEntity(id, pos, tm);
            case "PhygEntity":        return new PhygEntity(id, pos, tm);
            case "SheepuffEntity":    return new SheepuffEntity(id, pos, tm);
            case "MoaEntity":         return new MoaEntity(id, pos, tm);
            // ── Aether hostiles ──
            case "CockatriceEntity":  return new CockatriceEntity(id, pos, tm, p);
            case "SwetEntity":        return new SwetEntity(id, pos, tm, p, false);
            case "ZephyrEntity":      return new ZephyrEntity(id, pos, tm, p);
            case "WhirlwindEntity":   return new WhirlwindEntity(id, pos, tm, p);
            case "AechorPlantEntity": return new AechorPlantEntity(id, pos, tm, p);
            default: return null;
        }
    }
}
