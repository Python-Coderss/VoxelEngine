package com.voxel.world.structure;

import com.voxel.World;
import com.voxel.biome.Biome;
import com.voxel.biome.BiomeProvider;
import java.util.Random;

/**
 * Coordinates generation of all structures (villages, mineshafts, temples, etc.)
 * across the world. Ported from Minecraft 1.12.2 structure system.
 */
public class MapGenStructure {

    private final MapGenVillage villageGen = new MapGenVillage();
    private final MapGenMineshaft mineshaftGen = new MapGenMineshaft();

    private final long worldSeed;

    public MapGenStructure(long worldSeed) {
        this.worldSeed = worldSeed;
    }

    /**
     * Attempts to generate structures for the given chunk.
     * Called during chunk decoration.
     */
    public void generateStructures(World world, int cx, int cz, BiomeProvider biomeProvider) {
        Random rand = new Random(worldSeed + (long)cx * 341873128712L + (long)cz * 132897987541L);

        // Villages generate in plains, savanna, desert, taiga
        Biome biome = biomeProvider.getBiome(cx << 4, cz << 4);
        if (biome != null && isVillageBiome(biome)) {
            villageGen.generate(world, cx, cz, rand);
        }

        // Mineshafts generate in any biome underground
        mineshaftGen.generate(world, cx, cz, rand);
    }

    private boolean isVillageBiome(Biome biome) {
        return biome instanceof com.voxel.biome.BiomePlains
            || biome instanceof com.voxel.biome.BiomeSavanna
            || biome instanceof com.voxel.biome.BiomeDesert
            || biome instanceof com.voxel.biome.BiomeTaiga
            || biome.name.toLowerCase().contains("plains")
            || biome.name.toLowerCase().contains("savanna")
            || biome.name.toLowerCase().contains("desert")
            || biome.name.toLowerCase().contains("taiga");
    }
}
