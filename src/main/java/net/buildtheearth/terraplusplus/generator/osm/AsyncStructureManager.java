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
    private final SourceCooperativeIndex  vidaIndex = SourceCooperativeIndex.getInstance();
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

            // ── VIDA tier: after OSM, before Microsoft ──
            // Gap-fill only. Uses geometric overlap because VIDA
            // footprints carry no OSM IDs.
            List<SourceCooperativeIndex.VidaBuilding> vidaAccepted =
                    new ArrayList<>();
            if (config.vidaBuildings) {
                try {
                    List<SourceCooperativeIndex.VidaBuilding> vb =
                            vidaIndex.getBuildingsForChunk(
                                    task.tileLat - 0.005, task.tileLat + 0.005,
                                    task.tileLon - 0.005, task.tileLon + 0.005);
                    if (vb != null) {
                        // Remove this dataset's positional bias against OSM
                        // before comparing. Measured per region at runtime.
                        List<FootprintAlignment.Print> vPrints =
                                new ArrayList<>(vb.size());
                        for (SourceCooperativeIndex.VidaBuilding b : vb)
                            vPrints.add(FootprintAlignment.Print.of(b));
                        SourceAlignment.Shift vShift = SourceAlignment.shiftFor(
                                "vida", task.tileLat, task.tileLon,
                                vPrints, combined);
                        List<SourceCooperativeIndex.VidaBuilding> vbShifted =
                                new ArrayList<>(vb.size());
                        for (SourceCooperativeIndex.VidaBuilding b : vb)
                            vbShifted.add(b.shifted(vShift.dLat, vShift.dLon));
                        vb = vbShifted;
                        for (SourceCooperativeIndex.VidaBuilding b : vb) {
                            if (SourceCooperativeIndex
                                    .overlapsExisting(b, combined))
                                continue;
                            // An ML footprint that lands on the carriageway has
                            // no OSM building to collide with, so every
                            // building-vs-building test above passes it and it
                            // renders as a hut in the middle of the road.
                            //
                            // This check was previously missing HERE while
                            // being present in TerraChunkGenerator. That is why
                            // road-buildings still appeared: this retry path is
                            // the one that actually places most ML buildings
                            // ("Queued retry" -> "OSM retry success" in the log),
                            // so the guard on the other path never saw them.
                            if (FootprintAlignment.sitsOnRoad(
                                    FootprintAlignment.Print.of(b), combined))
                                continue;
                            vidaAccepted.add(b);
                            combined.add(b.toOsmStructure());
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("VIDA retry failed: {}", e.getMessage());
                }
            }

            if (task.overtureResult != null) {
                // Microsoft is the lowest tier. Dedup MUST be geometric:
                // the Microsoft ML dataset carries no osm_id, so the old
                // ID-based check never matched and produced duplicate
                // buildings stacked on top of OSM/VIDA footprints.
                // NOTE: BuildingTile.buildings is an ARRAY, not a List.
                List<FootprintAlignment.Print> mPrints =
                        new ArrayList<>(task.overtureResult.buildings.length);
                for (OvertureIndex.OvertureBuilding b :
                        task.overtureResult.buildings)
                    mPrints.add(FootprintAlignment.Print.of(b));
                SourceAlignment.Shift mShift = SourceAlignment.shiftFor(
                        "overture", task.tileLat, task.tileLon,
                        mPrints, combined);

                for (OvertureIndex.OvertureBuilding raw :
                        task.overtureResult.buildings) {
                    OvertureIndex.OvertureBuilding ob =
                            raw.shifted(mShift.dLat, mShift.dLon);
                    if (overlapsAnyStructure(ob, combined))
                        continue;
                    if (SourceCooperativeIndex.overlapsVida(ob, vidaAccepted))
                        continue;
                    // Same road guard as the VIDA tier above; see the comment
                    // there for why this path in particular needs it.
                    if (FootprintAlignment.sitsOnRoad(
                            FootprintAlignment.Print.of(ob), combined))
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

    /**
     * Geometric overlap between a Microsoft footprint and any already-accepted
     * building/bridge. Mirrors TerraChunkGenerator.overlaps so the retry path
     * and the primary path agree on which Microsoft buildings to drop.
     */
    /** Microsoft rings are double[][] {lat,lon}; convert to the common form. */
    private static OsmStructureIndex.OsmNode[] msftNodes(
            OvertureIndex.OvertureBuilding m) {
        OsmStructureIndex.OsmNode[] n =
                new OsmStructureIndex.OsmNode[m.polygon.length];
        for (int i = 0; i < m.polygon.length; i++)
            n[i] = new OsmStructureIndex.OsmNode(
                    m.polygon[i][0], m.polygon[i][1]);
        return n;
    }

    private static boolean overlapsAnyStructure(
            OvertureIndex.OvertureBuilding m,
            List<OsmStructureIndex.OsmStructure> existing) {
        if (m == null || existing == null || existing.isEmpty()) return false;
        FootprintAlignment.Print cand = FootprintAlignment.Print.of(m);
        for (OsmStructureIndex.OsmStructure s : existing) {
            if (s.nodes == null || s.nodes.length < 2) continue;
            if (s.type == OsmStructureIndex.StructureType.BUILDING
                    || s.type == OsmStructureIndex.StructureType.BUILDING_PART) {
                if (!(m.maxLat < s.minLat || m.minLat > s.maxLat
                   || m.maxLon < s.minLon || m.minLon > s.maxLon)) {
                    if (s.containsPoint(m.centerLat, m.centerLon)) return true;
                    for (double[] pt : m.polygon)
                        if (s.containsPoint(pt[0], pt[1])) return true;
                    if (pointInPoly((s.minLat + s.maxLat) / 2.0,
                                    (s.minLon + s.maxLon) / 2.0, m.polygon))
                        return true;
                }
                // Near-miss: same building, ML position error.
                if (FootprintAlignment.isSameBuilding(cand,
                        FootprintAlignment.Print.of(s)))
                    return true;
                // STRICT overlap: OSM outranks Microsoft, so any shared area
                // removes the Microsoft footprint even when it is a different
                // building. Stops ML geometry clipping through OSM walls.
                if (s.isArea && s.nodes.length >= 3
                        && FootprintAlignment.footprintsIntersect(
                            msftNodes(m), m.minLat, m.maxLat, m.minLon, m.maxLon,
                            s.nodes, s.minLat, s.maxLat, s.minLon, s.maxLon))
                    return true;
            } else if (s.type == OsmStructureIndex.StructureType.BRIDGE) {
                if (m.maxLat < s.minLat || m.minLat > s.maxLat
                        || m.maxLon < s.minLon || m.minLon > s.maxLon)
                    continue;
                if (s.distanceToLine(m.centerLat, m.centerLon) < 0.00009)
                    return true;
            }
        }
        return false;
    }

    private static boolean pointInPoly(double lat, double lon, double[][] p) {
        boolean in = false;
        for (int i = 0, j = p.length - 1; i < p.length; j = i++)
            if (((p[i][0] > lat) != (p[j][0] > lat))
                    && (lon < (p[j][1] - p[i][1]) * (lat - p[i][0])
                        / (p[j][0] - p[i][0]) + p[i][1]))
                in = !in;
        return in;
    }

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