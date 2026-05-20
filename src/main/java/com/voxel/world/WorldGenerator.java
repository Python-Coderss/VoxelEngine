package com.voxel.world;

/**
 * Procedural world generator (purged old noise for demo).
 * Generates a flat plain with a feature structure.
 */
public class WorldGenerator {

    public WorldGenerator(long seed) {
        // Old noise purged to remove traces of old system.
    }

    public int getHeight(int x, int z) {
        return 64; // Flat base height
    }

    public int getBlockType(int x, int y, int z, int height) {
        if (y > 80) return 0; // Air
        
        // Ground Layer
        if (y == 64) return 1; // Grass
        if (y < 64) return 2; // Stone

        // NEW DEMO FEATURE: Central God-Ray Observatory
        int dx = x - 1024;
        int dz = z - 1024;
        int distSq = dx*dx + dz*dz;
        
        // Circular tower with a "sun gap" at the top for god rays
        if (distSq < 100) {
            if (y > 64 && y < 78) {
                if (distSq > 64) return 2; // Stone outer wall
                return 0; // Hollow inside
            }
            if (y == 78) {
                // Observatory Floor for the top pool
                if (distSq < 49) return 15; // Water pool (7x7 approx)
                if (distSq < 64) return 2; // Stone rim
                // Roof with holes for god rays (further out)
                if ((x % 4 == 0) && (z % 4 == 0)) return 0; 
                return 2;
            }
            if (y == 65 && distSq < 16) return 3; // Glass floor inside
        }
        
        // Scattered transparent features
        if ((x % 64 == 0) && (z % 64 == 0) && y > 64 && y < 70) {
            return 4; // Oak Leaves floating clusters
        }
        
        return 0; // Air
    }
}
