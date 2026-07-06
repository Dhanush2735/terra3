// ============================================================
// FILE: AsyncStructureManager.java (FULL — isFromOsm removed)
// ============================================================
package net.buildtheearth.terraplusplus.generator.osm;

import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.buildtheearth.terraplusplus.generator.TerraDensityFunctions;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncStructureManager {

    private static final Logger LOGGER = LogManager.getLogger("Terra++Async");

    private static final int  RETRY_THREADS      = 2;
    private static final long INITIAL_BACKOFF_MS = 2000;
    private static final long MAX_BACKOFF_MS     = 60000;
    private static final int  MAX_RETRIES        = 10;
    private static final int  MAX_PENDING        = 500;

    private final OsmStructureIndex       osmIndex;
    private final OvertureIndex           overtureIndex;
    private final OsmStructureGenerator   structureGen;
    private final TerraDensityFunctions   tdf;
    private final TerraWorldConfig        config;
    private final StructurePlacementQueue placementQueue;

    private final ExecutorService retryPool;
    private final ConcurrentHashMap<Long, RetryTask> pendingRetries =
            new ConcurrentHashMap<>();
    private final Set<Long>     completedTiles = ConcurrentHashMap.newKeySet();
    private final AtomicInteger totalRetries   = new AtomicInteger(0);
    private final AtomicInteger totalSuccesses = new AtomicInteger(0);
    private final AtomicInteger dbgCount       = new AtomicInteger(0);

    private volatile boolean running = true;

    private static class RetryTask {
        final long         tileKey;
        final double       tileLat;
        final double       tileLon;
        final List<long[]> waitingChunks;
        int     attempts;
        long    nextRetryMs;
        long    backoffMs;
        boolean osmDone;
        boolean overtureDone;
        OsmStructureIndex.StructureTile   osmResult;
        OvertureIndex.BuildingTile        overtureResult;

        RetryTask(long tileKey, double tileLat, double tileLon,
                  int cx, int cz) {
            this.tileKey       = tileKey;
            this.tileLat       = tileLat;
            this.tileLon       = tileLon;
            this.waitingChunks = new ArrayList<>();
            this.waitingChunks.add(new long[]{cx, cz});
            this.attempts      = 0;
            this.nextRetryMs   = System.currentTimeMillis() + INITIAL_BACKOFF_MS;
            this.backoffMs     = INITIAL_BACKOFF_MS;
            this.osmDone       = false;
            this.overtureDone  = false;
        }
    }

    public AsyncStructureManager(OsmStructureIndex osmIndex,
                                  OvertureIndex overtureIndex,
                                  OsmStructureGenerator structureGen,
                                  TerraDensityFunctions tdf,
                                  TerraWorldConfig config,
                                  StructurePlacementQueue placementQueue) {
        this.osmIndex       = osmIndex;
        this.overtureIndex  = overtureIndex;
        this.structureGen   = structureGen;
        this.tdf            = tdf;
        this.config         = config;
        this.placementQueue = placementQueue;

        this.retryPool = Executors.newFixedThreadPool(RETRY_THREADS, r -> {
            Thread t = new Thread(r, "Terra++AsyncRetry");
            t.setDaemon(true);
            return t;
        });

        retryPool.submit(this::retryLoop);
        LOGGER.info("AsyncStructureManager initialized ({} retry threads)",
                RETRY_THREADS);
    }

    // ================================================================
    // PUBLIC API
    // ================================================================

    public void queueRetry(double lat, double lon, int chunkX, int chunkZ) {
        if (!running) return;
        if (pendingRetries.size() >= MAX_PENDING) evictOldest();

        long key = tileKey(lat, lon);
        if (completedTiles.contains(key)) return;

        pendingRetries.compute(key, (k, existing) -> {
            if (existing != null) {
                synchronized (existing.waitingChunks) {
                    boolean found = false;
                    for (long[] wc : existing.waitingChunks) {
                        if (wc[0] == chunkX && wc[1] == chunkZ) {
                            found = true; break;
                        }
                    }
                    if (!found)
                        existing.waitingChunks.add(new long[]{chunkX, chunkZ});
                }
                return existing;
            }
            double tileLat = Math.floor(lat / 0.01) * 0.01;
            double tileLon = Math.floor(lon / 0.01) * 0.01;
            RetryTask task = new RetryTask(key, tileLat, tileLon, chunkX, chunkZ);
            if (dbgCount.getAndIncrement() < 20) {
                LOGGER.info("Queued retry for tile [{},{}] chunk [{},{}]",
                        String.format("%.4f", tileLat),
                        String.format("%.4f", tileLon),
                        chunkX, chunkZ);
            }
            return task;
        });
    }

    public void markCompleted(double lat, double lon) {
        long key = tileKey(lat, lon);
        completedTiles.add(key);
        pendingRetries.remove(key);
    }

    public void registerDeferred(double lat, double lon,
                                  int chunkX, int chunkZ) {
        placementQueue.addDeferred(chunkX, chunkZ, lat, lon);
    }

    public int getPendingCount()   { return pendingRetries.size(); }
    public int getCompletedCount() { return completedTiles.size(); }
    public int getTotalRetries()   { return totalRetries.get(); }
    public int getTotalSuccesses() { return totalSuccesses.get(); }

    // ================================================================
    // RETRY LOOP
    // ================================================================

    private void retryLoop() {
        while (running) {
            try {
                Thread.sleep(500);
                if (!running) break;

                long now = System.currentTimeMillis();
                List<RetryTask> readyTasks = new ArrayList<>();
                for (Map.Entry<Long, RetryTask> entry :
                        pendingRetries.entrySet()) {
                    RetryTask task = entry.getValue();
                    if (now >= task.nextRetryMs && task.attempts < MAX_RETRIES)
                        readyTasks.add(task);
                }
                for (RetryTask task : readyTasks) {
                    if (!running) break;
                    processRetry(task);
                }
                pendingRetries.entrySet().removeIf(
                        e -> e.getValue().attempts >= MAX_RETRIES);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.debug("Retry loop error: {}", e.getMessage());
            }
        }
    }

    private void processRetry(RetryTask task) {
        task.attempts++;
        totalRetries.incrementAndGet();
        boolean anySuccess = false;

        // Try OSM
        if (!task.osmDone && config.hasAnyStructures()) {
            try {
                OsmStructureIndex.StructureTile osmTile =
                        osmIndex.query(task.tileLat, task.tileLon);
                if (osmTile != null && osmTile.valid) {
                    task.osmResult = osmTile;
                    task.osmDone   = true;
                    anySuccess     = true;
                    if (dbgCount.get() < 30) {
                        LOGGER.info(
                                "OSM retry success [{},{}] ({} structures, attempt {})",
                                String.format("%.4f", task.tileLat),
                                String.format("%.4f", task.tileLon),
                                osmTile.structures.length, task.attempts);
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("OSM retry failed: {}", e.getMessage());
            }
        }

        // Try Overture (Microsoft buildings)
        if (!task.overtureDone && overtureIndex != null
                && overtureIndex.isEnabled() && config.overtureBuildings) {
            try {
                OvertureIndex.BuildingTile overtureTile =
                        overtureIndex.query(task.tileLat, task.tileLon);
                if (overtureTile != null && overtureTile.valid
                        && overtureTile.buildings.length > 0) {
                    task.overtureResult = overtureTile;
                    task.overtureDone   = true;
                    anySuccess          = true;
                    if (dbgCount.get() < 30) {
                        LOGGER.info(
                                "Overture retry success [{},{}] ({} buildings, attempt {})",
                                String.format("%.4f", task.tileLat),
                                String.format("%.4f", task.tileLon),
                                overtureTile.buildings.length, task.attempts);
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Overture retry failed: {}", e.getMessage());
            }
        }

        if (anySuccess) {
            totalSuccesses.incrementAndGet();
            List<OsmStructureIndex.OsmStructure> combined = new ArrayList<>();

            if (task.osmResult != null) {
                for (OsmStructureIndex.OsmStructure s :
                        task.osmResult.structures)
                    combined.add(s);
            }

            if (task.overtureResult != null) {
                // Collect OSM building IDs already in combined
                Set<Long> osmIds = ConcurrentHashMap.newKeySet();
                for (OsmStructureIndex.OsmStructure s : combined) {
                    if (s.type == OsmStructureIndex.StructureType.BUILDING
                            && s.osmId != 0)
                        osmIds.add(s.osmId);
                }

                for (OvertureIndex.OvertureBuilding ob :
                        task.overtureResult.buildings) {
                    // Skip if this Microsoft building has an OSM ID
                    // that already exists in the OSM structures list
                    if (ob.osmId != 0 && osmIds.contains(ob.osmId))
                        continue;
                    combined.add(ob.toOsmStructure());
                }
            }

            if (!combined.isEmpty()) {
                List<long[]> chunks;
                synchronized (task.waitingChunks) {
                    chunks = new ArrayList<>(task.waitingChunks);
                }
                for (long[] chunkCoord : chunks) {
                    placementQueue.enqueue(
                            (int) chunkCoord[0], (int) chunkCoord[1],
                            combined, task.tileLat, task.tileLon);
                }
            }

            completedTiles.add(task.tileKey);
            pendingRetries.remove(task.tileKey);
        } else {
            task.backoffMs   = Math.min(task.backoffMs * 2, MAX_BACKOFF_MS);
            task.nextRetryMs = System.currentTimeMillis() + task.backoffMs;
        }
    }

    // ================================================================
    // UTILITY
    // ================================================================

    private long tileKey(double lat, double lon) {
        int tLat = (int) Math.floor(lat / 0.01);
        int tLon = (int) Math.floor(lon / 0.01);
        return ((long) tLat << 32) | (tLon & 0xFFFFFFFFL);
    }

    private void evictOldest() {
        long oldestTime = Long.MAX_VALUE;
        Long oldestKey  = null;
        for (Map.Entry<Long, RetryTask> e : pendingRetries.entrySet()) {
            long createTime = e.getValue().nextRetryMs - e.getValue().backoffMs;
            if (createTime < oldestTime) {
                oldestTime = createTime;
                oldestKey  = e.getKey();
            }
        }
        if (oldestKey != null) pendingRetries.remove(oldestKey);
    }

    public void shutdown() {
        running = false;
        retryPool.shutdown();
        try {
            if (!retryPool.awaitTermination(3, TimeUnit.SECONDS))
                retryPool.shutdownNow();
        } catch (InterruptedException e) {
            retryPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("AsyncStructureManager shutdown (retries={}, successes={})",
                totalRetries.get(), totalSuccesses.get());
    }
}