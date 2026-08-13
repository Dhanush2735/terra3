// ============================================================
// FILE: AsyncStructureManager.java (FULL — isFromOsm removed)
// ============================================================
package net.buildtheearth.terraplusplus.generator.osm;

import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.buildtheearth.terraplusplus.generator.TerraDensityFunctions;
import net.buildtheearth.terraplusplus.generator.historical.OhmStructureIndex;
import net.buildtheearth.terraplusplus.generator.historical.HistoricalDateParser;
import net.buildtheearth.terraplusplus.generator.historical.StructureDateEstimator;
import net.buildtheearth.terraplusplus.generator.historical.TerraTimeManager;
import net.buildtheearth.terraplusplus.generator.historical.WikiDataLookup;

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

    private final OhmStructureIndex       ohmIndex;
    private final OsmStructureIndex       osmIndex;
    private final SourceCooperativeIndex  vidaIndex = SourceCooperativeIndex.getInstance();
    private final OvertureIndex           overtureIndex;
    private final OsmStructureGenerator   structureGen;
    private final TerraDensityFunctions   tdf;
    private final TerraWorldConfig        config;
    private final StructurePlacementQueue placementQueue;
    private final WikiDataLookup wikiData = WikiDataLookup.getInstance();

    private final ExecutorService retryPool;
    private final ConcurrentHashMap<Long, RetryTask> pendingRetries =
            new ConcurrentHashMap<>();
    private final Set<Long>     completedTiles = ConcurrentHashMap.newKeySet();
    /** Accepted hierarchy result reusable by historical refresh passes. */
    private static final class CompletedResult {
        final int year;
        final List<OsmStructureIndex.OsmStructure> structures;
        CompletedResult(int year, List<OsmStructureIndex.OsmStructure> structures) {
            this.year = year;
            this.structures = List.copyOf(structures);
        }
    }
    private final ConcurrentHashMap<Long, CompletedResult> completedResults = new ConcurrentHashMap<>();
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
        boolean ohmDone;
        boolean osmDone;
        boolean vidaDone;
        boolean overtureDone;
        OhmStructureIndex.OhmTile          ohmResult;
        OsmStructureIndex.StructureTile   osmResult;
        List<SourceCooperativeIndex.VidaBuilding> vidaResult;
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
            this.ohmDone       = false;
            this.osmDone       = false;
            this.vidaDone      = false;
            this.overtureDone  = false;
        }
    }

    public AsyncStructureManager(OhmStructureIndex ohmIndex,
                                  OsmStructureIndex osmIndex,
                                  OvertureIndex overtureIndex,
                                  OsmStructureGenerator structureGen,
                                  TerraDensityFunctions tdf,
                                  TerraWorldConfig config,
                                  StructurePlacementQueue placementQueue) {
        this.ohmIndex       = ohmIndex;
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
        CompletedResult completed = completedResults.get(key);
        if (completed != null && completed.year == TerraTimeManager.getCurrentYear()) return;
        // A time jump changes visibility. Discard only this tile's prior
        // accepted result and let the normal hierarchy resolve it again.
        if (completed != null) {
            completedResults.remove(key, completed);
            completedTiles.remove(key);
        }

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

    /**
     * Return the accepted current-year hierarchy result for one chunk bounds,
     * or null while the source tile is pending/retryable.
     */
    public List<OsmStructureIndex.OsmStructure> getCompletedStructures(
            double lat, double lon, double minLat, double maxLat,
            double minLon, double maxLon) {
        CompletedResult result = completedResults.get(tileKey(lat, lon));
        if (result == null || result.year != TerraTimeManager.getCurrentYear()) return null;
        List<OsmStructureIndex.OsmStructure> out = new ArrayList<>();
        final double margin = 0.00025;
        for (OsmStructureIndex.OsmStructure s : result.structures)
            if (!(s.maxLat < minLat - margin || s.minLat > maxLat + margin
                    || s.maxLon < minLon - margin || s.minLon > maxLon + margin))
                out.add(s);
        return out;
    }

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
                    if (now >= task.nextRetryMs)
                        readyTasks.add(task);
                }
                for (RetryTask task : readyTasks) {
                    if (!running) break;
                    processRetry(task);
                }
                // Do not discard a tile merely because public source data was
                // unavailable ten times. defer() backs it off to a bounded
                // interval, preserving eventual correctness after an outage.

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

        // Tier 1: date-aware OHM. It must reach a terminal result before
        // OSM is considered, otherwise an OHM outage becomes an OSM duplicate.
        if (!task.ohmDone && config.ohmEnabled && ohmIndex != null && ohmIndex.isEnabled()) {
            try {
                OhmStructureIndex.OhmTile tile = ohmIndex.query(task.tileLat, task.tileLon);
                if (tile != null && tile.valid) {
                    task.ohmResult = tile;
                    task.ohmDone = true;
                }
            } catch (Exception e) {
                LOGGER.debug("OHM retry failed: {}", e.getMessage());
            }
        } else if (!config.ohmEnabled || ohmIndex == null || !ohmIndex.isEnabled()) {
            task.ohmDone = true;
        }
        if (!task.ohmDone) { defer(task); return; }

        // Tier 2: OSM
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

        // Strict hierarchy: lower tiers cannot run until the OSM state is
        // terminal. A retryable OSM failure must never look like an OSM gap.
        if (!task.osmDone) {
            defer(task);
            return;
        }

        // Google/VIDA is tier 3. It is requested only after OSM has produced
        // terminal data (which may legitimately be empty).
        if (config.vidaBuildings && !task.vidaDone) {
            // VIDA is an expensive gap-fill source. In a tile already covered
            // by dense OHM/OSM building geometry, fetching tens of thousands
            // of ML footprints provides little value and dominates cold start.
            if (!needsVidaGapFill(task)) {
                task.vidaResult = List.of();
                task.vidaDone = true;
                LOGGER.debug("VIDA skipped [{},{}]: OHM/OSM building coverage is dense",
                        String.format("%.4f", task.tileLat),
                        String.format("%.4f", task.tileLon));
            } else try {
                List<SourceCooperativeIndex.VidaBuilding> vb =
                        vidaIndex.getBuildingsForChunk(
                                task.tileLat - 0.005, task.tileLat + 0.005,
                                task.tileLon - 0.005, task.tileLon + 0.005);
                if (vb == null) {
                    defer(task);
                    return;
                }
                task.vidaResult = vb;
                task.vidaDone = true;
            } catch (Exception e) {
                LOGGER.debug("VIDA stage failed: {}", e.getMessage());
                defer(task);
                return;
            }
        } else if (!config.vidaBuildings) {
            task.vidaDone = true;
        }

        // Microsoft/Ovt is tier 4. It starts only after the VIDA result is
        // terminal, preventing cold-start OSM + VIDA + MSFT fan-out.
        if (!task.vidaDone) {
            defer(task);
            return;
        }
        boolean overtureRequired = overtureIndex != null
                && overtureIndex.isEnabled() && config.overtureBuildings;
        if (!task.overtureDone && overtureRequired) {
            try {
                OvertureIndex.BuildingTile overtureTile =
                        overtureIndex.query(task.tileLat, task.tileLon);
                // A valid empty response is terminal data, not a failed
                // request. Retrying it forever caused needless cold/warm work.
                if (overtureTile != null && overtureTile.valid) {
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

        if (!overtureRequired) task.overtureDone = true;
        if (!task.overtureDone) {
            defer(task);
            return;
        }

        // All enabled tiers have now reached terminal source states. Completion
        // must not depend on whether a response happened in this retry pass.
        if (task.ohmDone && task.osmDone && task.vidaDone && task.overtureDone) {
            totalSuccesses.incrementAndGet();
            List<OsmStructureIndex.OsmStructure> combined = new ArrayList<>();
            java.util.Set<Long> ohmIds = new java.util.HashSet<>();
            List<StructureDateEstimator.AnchorPoint> dateAnchors = new ArrayList<>();
            int year = TerraTimeManager.getCurrentYear();
            if (task.ohmResult != null) {
                for (OhmStructureIndex.OhmStructure s : task.ohmResult.structures) {
                    if (!s.isVisibleAt(year)) continue;
                    combined.add(s.base);
                    if (s.startYear != HistoricalDateParser.UNKNOWN_YEAR)
                        dateAnchors.add(StructureDateEstimator.AnchorPoint.fromOhm(s));
                    if (s.base.osmId != 0) ohmIds.add(s.base.osmId);
                }
            }
            if (task.osmResult != null) {
                for (OsmStructureIndex.OsmStructure s : task.osmResult.structures) {
                    // OHM shares OSM IDs where it represents the same feature.
                    if (s.osmId != 0 && ohmIds.contains(s.osmId)) continue;
                    if (s.startYear != HistoricalDateParser.UNKNOWN_YEAR)
                        dateAnchors.add(StructureDateEstimator.AnchorPoint.fromOsm(s));
                    if (config.timeEnabled && !HistoricalDateParser.isVisibleAt(
                            s.startYear, s.endYear, year, true)) continue;
                    combined.add(s);
                }
            }

            // ── VIDA tier: after OSM, before Microsoft ──
            // Gap-fill only. Uses geometric overlap because VIDA
            // footprints carry no OSM IDs.
            List<SourceCooperativeIndex.VidaBuilding> vidaAccepted =
                    new ArrayList<>();
            if (config.vidaBuildings && task.vidaResult != null) {
                    List<SourceCooperativeIndex.VidaBuilding> vb = task.vidaResult;
                    {
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
                            if (config.timeEnabled) {
                                int start = StructureDateEstimator.estimate(
                                        b.centerLat, b.centerLon,
                                        OsmStructureIndex.StructureType.BUILDING,
                                        null, dateAnchors);
                                if (!HistoricalDateParser.isVisibleAt(start,
                                        HistoricalDateParser.UNKNOWN_YEAR,
                                        TerraTimeManager.getCurrentYear(), false))
                                    continue;
                            }
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
                    if (config.timeEnabled) {
                        int start = StructureDateEstimator.estimate(
                                ob.centerLat, ob.centerLon,
                                OsmStructureIndex.StructureType.BUILDING,
                                ob.subtype, dateAnchors);
                        // Never block the retry workers on Wikidata. Named
                        // landmarks are prefetched; later retries use the
                        // cached answer when it has arrived.
                        if (ob.name != null && !ob.name.isBlank()) {
                            wikiData.prefetch(ob.name, ob.centerLat, ob.centerLon);
                            WikiDataLookup.WikiResult wiki = wikiData.lookupCached(ob.name);
                            if (wiki.found()) start = wiki.year;
                        }
                        if (!HistoricalDateParser.isVisibleAt(start,
                                HistoricalDateParser.UNKNOWN_YEAR,
                                TerraTimeManager.getCurrentYear(), false))
                            continue;
                    }
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

            completedResults.put(task.tileKey, new CompletedResult(
                    TerraTimeManager.getCurrentYear(), combined));
            if (!combined.isEmpty()) {
                List<long[]> chunks;
                synchronized (task.waitingChunks) {
                    chunks = new ArrayList<>(task.waitingChunks);
                }
                int queuedChunks = 0;
                int skippedChunks = 0;
                int localReferences = 0;
                for (long[] chunkCoord : chunks) {
                    int chunkX = (int) chunkCoord[0];
                    int chunkZ = (int) chunkCoord[1];
                    List<OsmStructureIndex.OsmStructure> local =
                            filterStructuresForChunk(combined, chunkX, chunkZ);
                    if (local.isEmpty()) {
                        skippedChunks++;
                        continue;
                    }
                    localReferences += local.size();
                    queuedChunks++;
                    placementQueue.enqueue(chunkX, chunkZ, local,
                            task.tileLat, task.tileLon);
                }
                LOGGER.info("AsyncStruct tile [{},{}]: combined={} waiting={} queued={} "
                                + "skipped={} localRefs={}",
                        String.format("%.4f", task.tileLat),
                        String.format("%.4f", task.tileLon),
                        combined.size(), chunks.size(), queuedChunks,
                        skippedChunks, localReferences);
            }

            completedTiles.add(task.tileKey);
            pendingRetries.remove(task.tileKey);
        } else {
            defer(task);
        }
    }

    /**
     * Decide whether tier-3 VIDA can add useful coverage. A 4x4 grid over the
     * 0.01-degree async tile is marked by expanded OHM/OSM building bounds.
     * If at least 14 cells are covered, use the authoritative mapped geometry
     * already available and avoid an expensive VIDA cold fetch.
     */
    private boolean needsVidaGapFill(RetryTask task) {
        final int grid = 4;
        final double cell = 0.01 / grid;
        final double margin = 0.00045; // ~50 m building service radius
        boolean[][] covered = new boolean[grid][grid];
        int count = 0;
        List<OsmStructureIndex.OsmStructure> candidates = new ArrayList<>();
        if (task.ohmResult != null) {
            int year = TerraTimeManager.getCurrentYear();
            for (OhmStructureIndex.OhmStructure s : task.ohmResult.structures)
                if (s.isVisibleAt(year)) candidates.add(s.base);
        }
        if (task.osmResult != null)
            for (OsmStructureIndex.OsmStructure s : task.osmResult.structures)
                candidates.add(s);
        for (OsmStructureIndex.OsmStructure s : candidates) {
            if (s.type != OsmStructureIndex.StructureType.BUILDING
                    && s.type != OsmStructureIndex.StructureType.BUILDING_PART)
                continue;
            int minX = Math.max(0, Math.min(grid - 1,
                    (int) Math.floor((s.minLat - margin - task.tileLat) / cell)));
            int maxX = Math.max(0, Math.min(grid - 1,
                    (int) Math.floor((s.maxLat + margin - task.tileLat) / cell)));
            int minZ = Math.max(0, Math.min(grid - 1,
                    (int) Math.floor((s.minLon - margin - task.tileLon) / cell)));
            int maxZ = Math.max(0, Math.min(grid - 1,
                    (int) Math.floor((s.maxLon + margin - task.tileLon) / cell)));
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                if (!covered[x][z]) { covered[x][z] = true; count++; }
        }
        return count < 14;
    }

    /**
     * Restrict a source-tile result to the structures which can affect one
     * Minecraft chunk. This prevents a dense 0.01-degree tile from handing the
     * same thousands of structures to every subscribed chunk.
     */
    private List<OsmStructureIndex.OsmStructure> filterStructuresForChunk(
            List<OsmStructureIndex.OsmStructure> all, int chunkX, int chunkZ) {
        if (all == null || all.isEmpty() || tdf == null) return List.of();
        int minX = chunkX << 4, minZ = chunkZ << 4;
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
        int[][] corners = {{minX, minZ}, {minX + 15, minZ},
                {minX, minZ + 15}, {minX + 15, minZ + 15}};
        for (int[] corner : corners) {
            double[] geo = tdf.toGeo(corner[0], corner[1]);
            if (geo == null || !Double.isFinite(geo[0]) || !Double.isFinite(geo[1]))
                return List.of();
            // TerraDensityFunctions returns {longitude, latitude}.
            minLon = Math.min(minLon, geo[0]); maxLon = Math.max(maxLon, geo[0]);
            minLat = Math.min(minLat, geo[1]); maxLat = Math.max(maxLat, geo[1]);
        }
        // Include wide roads/bridges that can place just beyond the nominal
        // geometry bounds while keeping the filter strongly chunk-local.
        final double margin = 0.00025; // roughly 28 metres latitude-wise
        List<OsmStructureIndex.OsmStructure> local = new ArrayList<>();
        for (OsmStructureIndex.OsmStructure structure : all) {
            if (structure == null) continue;
            if (structure.maxLat < minLat - margin || structure.minLat > maxLat + margin
                    || structure.maxLon < minLon - margin || structure.minLon > maxLon + margin)
                continue;
            local.add(structure);
        }
        return local;
    }

    /** Requeue a source tile with bounded exponential backoff. */
    private void defer(RetryTask task) {
        task.backoffMs = Math.min(task.backoffMs * 2, MAX_BACKOFF_MS);
        task.nextRetryMs = System.currentTimeMillis() + task.backoffMs;
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