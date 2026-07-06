package net.buildtheearth.terraplusplus.dataset;

import net.buildtheearth.terraplusplus.TerraPlusPlus;
import net.buildtheearth.terraplusplus.config.TerraConfig;
import net.buildtheearth.terraplusplus.dataset.builtin.TerrariumHeightmapDataset;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DatasetManager {
    
    private final HeightmapDataset heightmapDataset;
    private final ExecutorService loaderExecutor;
    private final ScheduledExecutorService cacheCleanupExecutor;
    
    public DatasetManager() {
        this.loaderExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Terra++-Internal-Loader");
            t.setDaemon(true);
            return t;
        });
        
        this.cacheCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Terra++-CacheCleanup");
            t.setDaemon(true);
            return t;
        });
        
        String url = TerraConfig.TERRAIN_DATA_URL.get();
        int cacheSize = TerraConfig.TILE_CACHE_SIZE.get();
        
        this.heightmapDataset = new TerrariumHeightmapDataset(url, cacheSize, loaderExecutor);
        
        cacheCleanupExecutor.scheduleAtFixedRate(
            this::cleanupCaches, 5, 5, TimeUnit.MINUTES
        );
        
        // CHECK YOUR CONSOLE FOR THIS MESSAGE!
        System.out.println("Terra++ DEBUG: DatasetManager Started (NEW VERSION)");
    }
    
    public double sampleHeight(double latitude, double longitude) {
        return heightmapDataset.sample(latitude, longitude);
    }
    
    public CompletableFuture<Double> sampleHeightAsync(double latitude, double longitude) {
        return CompletableFuture.supplyAsync(() -> sampleHeight(latitude, longitude), loaderExecutor);
    }

    public boolean isDataAvailable(double latitude, double longitude) {
        return heightmapDataset.isLoaded(latitude, longitude);
    }

    public CompletableFuture<Void> preloadRegion(double minLat, double minLon, double maxLat, double maxLon) {
        return heightmapDataset.preloadRegion(minLat, minLon, maxLat, maxLon);
    }
    
    public HeightmapDataset getHeightmapDataset() {
        return heightmapDataset;
    }
    
    private void cleanupCaches() {
        if (heightmapDataset != null) heightmapDataset.cleanupCache();
    }
    
    public void shutdown() {
        loaderExecutor.shutdownNow();
        cacheCleanupExecutor.shutdownNow();
    }
}