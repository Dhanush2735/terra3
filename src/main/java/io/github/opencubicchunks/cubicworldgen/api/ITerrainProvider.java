package io.github.opencubicchunks.cubicworldgen.api;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Interface for external terrain data providers.
 * TerraPlusPlus implements this to provide real-world height data.
 */
public interface ITerrainProvider {
    
    /**
     * Sample terrain height at a block position.
     * @return The terrain height at this XZ position
     */
    double sampleHeight(int blockX, int blockZ);
    
    /**
     * Get a DensityFunction that represents this terrain.
     * Can be plugged into the noise router.
     */
    DensityFunction asDensityFunction();
    
    /**
     * Check if data is available at this position.
     * May return false if data is still loading.
     */
    boolean isDataAvailable(int blockX, int blockZ);
    
    /**
     * Request data to be loaded for a region.
     * Async operation.
     */
    void requestData(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ);
}
