package net.buildtheearth.terraplusplus.api;

import net.buildtheearth.terraplusplus.TerraPlusPlus;
import net.buildtheearth.terraplusplus.projection.GeographicProjection;
import net.buildtheearth.terraplusplus.projection.ProjectionRegistry;

import java.util.concurrent.CompletableFuture;

/**
 * Public API for Terra++.
 * Use this to access terrain data and coordinate conversion from other mods.
 */
public final class TerraPlusPlusAPI {
    
    private TerraPlusPlusAPI() {}
    
    /**
     * Convert block coordinates to geographic coordinates.
     * @return Array of [latitude, longitude] in degrees
     */
    public static double[] toGeo(double blockX, double blockZ) {
        return ProjectionRegistry.getDefault().toGeographic(blockX, blockZ);
    }
    
    /**
     * Convert block coordinates to geographic using a specific projection.
     */
    public static double[] toGeo(double blockX, double blockZ, String projectionName) {
        return ProjectionRegistry.get(projectionName)
            .orElse(ProjectionRegistry.getDefault())
            .toGeographic(blockX, blockZ);
    }
    
    /**
     * Convert geographic coordinates to block coordinates.
     * @return Array of [blockX, blockZ]
     */
    public static double[] fromGeo(double latitude, double longitude) {
        return ProjectionRegistry.getDefault().toProjected(latitude, longitude);
    }
    
    /**
     * Convert geographic coordinates to block using a specific projection.
     */
    public static double[] fromGeo(double latitude, double longitude, String projectionName) {
        return ProjectionRegistry.get(projectionName)
            .orElse(ProjectionRegistry.getDefault())
            .toProjected(latitude, longitude);
    }
    
    /**
     * Sample terrain height at block coordinates.
     * @return Height in meters above sea level
     */
    public static double sampleHeight(int blockX, int blockZ) {
        double[] geo = toGeo(blockX, blockZ);
        return TerraPlusPlus.getInstance()
            .getDatasetManager()
            .sampleHeight(geo[0], geo[1]);
    }
    
    /**
     * Sample terrain height asynchronously.
     */
    public static CompletableFuture<Double> sampleHeightAsync(int blockX, int blockZ) {
        double[] geo = toGeo(blockX, blockZ);
        return TerraPlusPlus.getInstance()
            .getDatasetManager()
            .sampleHeightAsync(geo[0], geo[1]);
    }
    
    /**
     * Check if terrain data is available for a location.
     */
    public static boolean isDataAvailable(int blockX, int blockZ) {
        double[] geo = toGeo(blockX, blockZ);
        return TerraPlusPlus.getInstance()
            .getDatasetManager()
            .isDataAvailable(geo[0], geo[1]);
    }
    
    /**
     * Request terrain data to be loaded for a region.
     */
    public static CompletableFuture<Void> preloadRegion(int minBlockX, int minBlockZ, 
                                                         int maxBlockX, int maxBlockZ) {
        double[] minGeo = toGeo(minBlockX, minBlockZ);
        double[] maxGeo = toGeo(maxBlockX, maxBlockZ);
        
        return TerraPlusPlus.getInstance()
            .getDatasetManager()
            .preloadRegion(
                Math.min(minGeo[0], maxGeo[0]),
                Math.min(minGeo[1], maxGeo[1]),
                Math.max(minGeo[0], maxGeo[0]),
                Math.max(minGeo[1], maxGeo[1])
            );
    }
    
    /**
     * Get the default projection.
     */
    public static GeographicProjection getProjection() {
        return ProjectionRegistry.getDefault();
    }
    
    /**
     * Get a projection by name.
     */
    public static GeographicProjection getProjection(String name) {
        return ProjectionRegistry.get(name).orElse(null);
    }
}