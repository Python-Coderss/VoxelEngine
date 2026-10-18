package com.voxel.entity;

import com.voxel.World;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertSame;

/**
 * Regression: restoring a save used to rebind only enemies and Aether wildlife
 * to the dimension's world. Villagers, livestock and golems came back with a
 * null world, so their brain tick and ground movement were skipped and they
 * stood frozen. Restore now goes through the base {@link Entity#setWorld}
 * contract, which must dispatch to the owning subclass.
 */
public class EntityWorldRebindTest {

    private static final int STONE = 1;
    private static final int GROUND_Y = 63;

    private static final class FlatWorld extends World {
        FlatWorld() { super(8); }

        @Override
        public int getVoxel(int x, int y, int z) {
            return y <= GROUND_Y ? STONE : 0;
        }
    }

    @Test
    public void baseSetWorldIsASafeNoOpForEntitiesThatOwnNoWorld() {
        Entity plain = new Entity(1, new Vector3f(0.5f, 64f, 0.5f));
        plain.setWorld(new FlatWorld()); // must not throw
    }

    @Test
    public void rebindingThroughTheBaseTypeReachesEveryRestoredMob() {
        World world = new FlatWorld();
        // Held exactly as the save-restore loop holds them: as base Entity refs.
        Entity villager = new VillagerEntity(11, new Vector3f(0.5f, 64f, 0.5f), null);
        Entity golem = new IronGolemEntity(12, new Vector3f(0.5f, 64f, 0.5f), null);
        Entity cow = new CowEntity(13, new Vector3f(0.5f, 64f, 0.5f), null, null);

        Entity[] restored = {villager, golem, cow};
        for (Entity e : restored) e.setWorld(world);

        assertSame(world, ((VillagerEntity) villager).world);
        assertSame(world, ((UtilityMobEntity) golem).world);
        assertSame(world, ((FarmAnimalEntity) cow).world);
    }
}
