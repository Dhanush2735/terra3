package io.github.opencubicchunks.cubicworldgen.api;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Interface for chunk generators that support cubic generation.
 */
public interface ICubeGenerator {
    
    /**
     * Generate a single cube.
     * @param chunk The chunk containing this cube
     * @param cubeY The Y-level of the cube
     * @param random RandomState for generation
     */
    void generateCube(ChunkAccess chunk, int cubeY, RandomState random);
    
    /**
     * Check if this generator supports cubic mode.
     */
    boolean supportsCubicGeneration();
    
    /**
     * Get the vertical range this generator operates in.
     * For infinite generators, returns Integer.MIN_VALUE to MAX_VALUE.
     */
    default int getMinGenerationY() {
        return Integer.MIN_VALUE;
    }
    
    default int getMaxGenerationY() {
        return Integer.MAX_VALUE;
    }
}
