package net.buildtheearth.terraplusplus.dataset;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// ============================================================
// DatasetManager — legacy API shell.
//
// Until 2026-11-20 this wrapped a Terrarium-backed
// HeightmapDataset (zoom-10, blocking downloads) used only
// by the old TerraPlusPlusAPI height calls. In the
// single-elevation-source switch the Terrarium source was
// removed completely (owner directive: two live height
// books mixed tiles — elevated teleport chunks, floating
// river water, slow double-downloaded coastlines), so the
// dataset classes went with it. World heights now come
// exclusively from Mapterhorn via TerraDensityFunctions.
//
// The manager stays so TerraPlusPlus/API callers keep
// compiling; height sampling through this legacy path
// honestly reports NaN ("not available") instead of
// quietly consulting a removed source.
// ============================================================
public class DatasetManager {

    private final ExecutorService loaderExecutor;

    public DatasetManager() {
        this.loaderExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Terra++-Internal-Loader");
            t.setDaemon(true);
            return t;
        });
    }

    /** Legacy API: no height dataset behind this path any more. */
    public double sampleHeight(double latitude, double longitude) {
        return Double.NaN;
    }

    public CompletableFuture<Double> sampleHeightAsync(double latitude, double longitude) {
        return CompletableFuture.supplyAsync(() -> sampleHeight(latitude, longitude), loaderExecutor);
    }

    public boolean isDataAvailable(double latitude, double longitude) {
        return false;
    }

    public CompletableFuture<Void> preloadRegion(double minLat, double minLon, double maxLat, double maxLon) {
        return CompletableFuture.completedFuture(null);
    }

    public void shutdown() {
        loaderExecutor.shutdownNow();
    }
}
