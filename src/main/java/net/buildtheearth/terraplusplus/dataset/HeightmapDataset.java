package net.buildtheearth.terraplusplus.dataset;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;

public abstract class HeightmapDataset {

    protected final int zoom;
    protected final Cache<Long, double[]> cache;
    private static final double[] EMPTY_TILE = new double[256 * 256];

    public HeightmapDataset(int cacheSize, ExecutorService executor) {
        this.zoom = 10; // Use Zoom 10. It is faster and reliable.
        this.cache = CacheBuilder.newBuilder()
                .maximumSize(cacheSize)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build();
    }

    // Helper to get raw data
    public double[] getTile(double lat, double lon) {
        double n = Math.pow(2, zoom);
        double tileX = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(lat);
        double mercatorY = Math.log(Math.tan(latRad) + (1.0 / Math.cos(latRad)));
        double tileY = (1.0 - mercatorY / Math.PI) / 2.0 * n;

        int tx = (int) tileX;
        int ty = (int) tileY;
        long key = (((long) tx) << 32) | (ty & 0xFFFFFFFFL);
        
        double[] data = cache.getIfPresent(key);
        if (data != null) return data;

        // FORCE DOWNLOAD (Blocking)
        // The game will pause here until download finishes or fails.
        try {
            data = loadTile(zoom, tx, ty);
            cache.put(key, data);
            return data;
        } catch (Exception e) {
            System.err.println("Terra++: Download failed for " + tx + "," + ty);
            return EMPTY_TILE;
        }
    }
    
    // Read from array
    public double readFromTile(double[] data, double lat, double lon) {
        if (data == null || data.length == 0) return 0;

        double n = Math.pow(2, zoom);
        double tileX = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(lat);
        double mercatorY = Math.log(Math.tan(latRad) + (1.0 / Math.cos(latRad)));
        double tileY = (1.0 - mercatorY / Math.PI) / 2.0 * n;
        
        int tx = (int) tileX;
        int ty = (int) tileY;
        int px = (int) ((tileX - tx) * 256);
        int py = (int) ((tileY - ty) * 256);
        
        if (px < 0) px = 0; if (px > 255) px = 255;
        if (py < 0) py = 0; if (py > 255) py = 255;
        
        int index = py * 256 + px;
        if (index >= data.length) return 0;
        return data[index];
    }
    
    public double sample(double lat, double lon) {
        return readFromTile(getTile(lat, lon), lat, lon);
    }
    
    protected abstract double[] loadTile(int zoom, int x, int y) throws Exception;
    public void cleanupCache() { cache.cleanUp(); }
    public java.util.concurrent.CompletableFuture<Void> preloadRegion(double minLat, double minLon, double maxLat, double maxLon) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    public boolean isLoaded(double lat, double lon) { return true; }
}