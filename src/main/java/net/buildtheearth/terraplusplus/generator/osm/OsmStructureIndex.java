// ============================================================
// FILE: OsmStructureIndex.java
// CHANGES:
//   - Replaced unsynchronized AtomicLong+sleep race condition
//     with OverpassRateLimiter (one instance per endpoint)
//   - Disk cache check moved before thread pool submission
//     in getStructuresForChunkBlocking() and prefetchTiles()
//     — disk hits never consume a rate limit slot or thread
//     pool thread
//   - fetchTile() no longer does disk check (done by callers)
//   - DL_THREADS increased from 2 to 4
//   - pending map cleaned up on timeout
//   - setEnabled(true) resets all endpoint limiters
//   - All prior fixes retained (startYear/endYear fields,
//     null-on-partial-timeout, historical date parsing)
// ============================================================
package net.buildtheearth.terraplusplus.generator.osm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.buildtheearth.terraplusplus.generator.historical.HistoricalDateParser;
import net.buildtheearth.terraplusplus.generator.util.OverpassRateLimiter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class OsmStructureIndex {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Struct/OSM");

    // ── CONSTANTS ─────────────────────────────────────────────
    private static final double TILE_DEG       = 0.01;
    private static final double OVERLAP_MARGIN = 0.001;
    private static final int    MEM_CACHE_MAX  = 256;
    private static final int    HTTP_TIMEOUT_MS = 25000;
    private static final int    DL_THREADS      = 4; // increased from 2
    private static final int    MAX_CONSECUTIVE_FAILURES = 15;
    private static final long   DISK_TTL_MS     =
        30L * 24 * 60 * 60 * 1000;
    private static final long   TILE_WAIT_MS    = 200;
    private static final long   PAUSE_ON_FAILURES_MS = 30000;

    public static final double METRES_PER_BLOCK = 1.0;
    public static final double METRES_PER_FLOOR = 3.0;

    private static final String[] OVERPASS_ENDPOINTS = {
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    };

    // ── RATE LIMITERS ─────────────────────────────────────────
    // One token bucket per endpoint.
    // Replaces the old unsynchronized AtomicLong+sleep
    // which had a race condition allowing simultaneous
    // requests that violated the rate limit.
    private final OverpassRateLimiter[] endpointLimiters;

    // ── ENUMS ─────────────────────────────────────────────────

    public enum StructureType {
        BUILDING, BUILDING_PART, ROAD, RAILWAY, BRIDGE,
        WALL, FENCE, GARDEN, PARK, FARMLAND, FOREST,
        MEADOW, PARKING, FOOTWAY, CYCLEWAY, STREET_LAMP,
        BARRIER, HEDGE
    }

    public enum BridgeStructure {
        BEAM, ARCH, TRUSS, SUSPENSION, CABLE_STAYED,
        VIADUCT, PEDESTRIAN, ROPE, AQUEDUCT, MOVABLE,
        BOARDWALK, CANTILEVER, COVERED,
        LOW_WATER_CROSSING, TRESTLE, FLOATING,
        SIMPLE_SUSPENSION, HUMPBACK, CLAPPER;

        public static BridgeStructure fromBridgeTag(
                String bridgeValue) {
            if (bridgeValue == null) return null;
            return switch (
                    bridgeValue.toLowerCase().trim()) {
                case "aqueduct"           -> AQUEDUCT;
                case "boardwalk"          -> BOARDWALK;
                case "cable-stayed",
                     "cable_stayed"       -> CABLE_STAYED;
                case "cantilever"         -> CANTILEVER;
                case "covered"            -> COVERED;
                case "low_water_crossing" ->
                    LOW_WATER_CROSSING;
                case "movable"            -> MOVABLE;
                case "trestle"            -> TRESTLE;
                case "viaduct"            -> VIADUCT;
                default                   -> null;
            };
        }

        public static BridgeStructure fromStructureTag(
                String structureValue) {
            if (structureValue == null) return null;
            return switch (
                    structureValue.toLowerCase().trim()) {
                case "arch", "arch_bridge"  -> ARCH;
                case "beam", "girder",
                     "box_girder",
                     "plate_girder",
                     "slab"                 -> BEAM;
                case "truss", "through_truss",
                     "pony"                 -> TRUSS;
                case "suspension"           -> SUSPENSION;
                case "cable-stayed",
                     "cable_stayed"         -> CABLE_STAYED;
                case "floating", "pontoon"  -> FLOATING;
                case "simple-suspension",
                     "simple_suspension",
                     "hanging",
                     "swinging"             ->
                    SIMPLE_SUSPENSION;
                case "humpback",
                     "moon_bridge"          -> HUMPBACK;
                case "clapper"              -> CLAPPER;
                case "viaduct"              -> VIADUCT;
                case "aqueduct"             -> AQUEDUCT;
                case "movable", "drawbridge",
                     "swing", "bascule",
                     "lift"                 -> MOVABLE;
                case "rope", "rope_bridge"  -> ROPE;
                case "trestle"              -> TRESTLE;
                case "cantilever"           -> CANTILEVER;
                case "covered"              -> COVERED;
                case "boardwalk"            -> BOARDWALK;
                default                     -> null;
            };
        }
    }

    // ── OSM NODE ──────────────────────────────────────────────

    public static class OsmNode {
        public final double lat, lon;
        public OsmNode(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    // ── OSM STRUCTURE ─────────────────────────────────────────

    public static class OsmStructure {
        public final StructureType   type;
        public final String          subType, name,
                                     material;
        public final String          roofShape,
                                     roofMaterial,
                                     surface;
        public final String          colour,
                                     roofColour;
        public final OsmNode[]       nodes;
        /** Courtyard/island rings for area relations; empty for simple ways. */
        public OsmNode[][]           innerRings = new OsmNode[0][];
        public final boolean         isArea, isOneWay,
                                     isPart, tunnel,
                                     isRailBridge;
        public final long            osmId, parentOsmId;
        public final int             levels, lanes,
                                     layer;
        public final double          height, minHeight,
                                     roofHeight,
                                     roofDirection;
        public final double          minLat, maxLat,
                                     minLon, maxLon;
        public final BridgeStructure bridgeStructure;
        public final int             startYear;
        public final int             endYear;

        public OsmStructure(StructureType type,
                String subType, String name,
                OsmNode[] nodes, boolean isArea,
                long osmId, String material,
                int levels, double height,
                String roofShape, String roofMaterial,
                String surface, int lanes,
                boolean isOneWay, double minHeight,
                double roofHeight, double roofDirection,
                String colour, String roofColour,
                boolean isPart, long parentOsmId,
                int layer, boolean tunnel,
                boolean isRailBridge,
                BridgeStructure bridgeStructure,
                int startYear, int endYear) {
            this.type            = type;
            this.subType         = subType;
            this.name            = name;
            this.nodes           = nodes;
            this.isArea          = isArea;
            this.osmId           = osmId;
            this.material        = material;
            this.levels          = levels;
            this.height          = height;
            this.roofShape       = roofShape;
            this.roofMaterial    = roofMaterial;
            this.surface         = surface;
            this.lanes           = lanes;
            this.isOneWay        = isOneWay;
            this.minHeight       = minHeight;
            this.roofHeight      = roofHeight;
            this.roofDirection   = roofDirection;
            this.colour          = colour;
            this.roofColour      = roofColour;
            this.isPart          = isPart;
            this.parentOsmId     = parentOsmId;
            this.layer           = layer;
            this.tunnel          = tunnel;
            this.isRailBridge    = isRailBridge;
            this.bridgeStructure =
                bridgeStructure != null
                    ? bridgeStructure
                    : BridgeStructure.BEAM;
            this.startYear       = startYear;
            this.endYear         = endYear;

            double mnLat = Double.MAX_VALUE,
                   mxLat = -Double.MAX_VALUE;
            double mnLon = Double.MAX_VALUE,
                   mxLon = -Double.MAX_VALUE;
            for (OsmNode n : nodes) {
                if (n.lat < mnLat) mnLat = n.lat;
                if (n.lat > mxLat) mxLat = n.lat;
                if (n.lon < mnLon) mnLon = n.lon;
                if (n.lon > mxLon) mxLon = n.lon;
            }
            this.minLat = mnLat; this.maxLat = mxLat;
            this.minLon = mnLon; this.maxLon = mxLon;
        }

        // Backward-compat — no dates
        public OsmStructure(StructureType type,
                String subType, String name,
                OsmNode[] nodes, boolean isArea,
                long osmId, String material,
                int levels, double height,
                String roofShape, String roofMaterial,
                String surface, int lanes,
                boolean isOneWay, double minHeight,
                double roofHeight, double roofDirection,
                String colour, String roofColour,
                boolean isPart, long parentOsmId,
                int layer, boolean tunnel,
                boolean isRailBridge,
                BridgeStructure bridgeStructure) {
            this(type, subType, name, nodes, isArea,
                osmId, material, levels, height,
                roofShape, roofMaterial, surface,
                lanes, isOneWay, minHeight, roofHeight,
                roofDirection, colour, roofColour,
                isPart, parentOsmId, layer, tunnel,
                isRailBridge, bridgeStructure,
                HistoricalDateParser.UNKNOWN_YEAR,
                HistoricalDateParser.UNKNOWN_YEAR);
        }

        // Minimal — no dates, no extended fields
        public OsmStructure(StructureType type,
                String subType, String name,
                OsmNode[] nodes, boolean isArea,
                long osmId, String material,
                int levels, double height,
                String roofShape, String roofMaterial,
                String surface, int lanes,
                boolean isOneWay) {
            this(type, subType, name, nodes, isArea,
                osmId, material, levels, height,
                roofShape, roofMaterial, surface,
                lanes, isOneWay, 0, 0, 0, null, null,
                false, 0, 0, false, false,
                BridgeStructure.BEAM,
                HistoricalDateParser.UNKNOWN_YEAR,
                HistoricalDateParser.UNKNOWN_YEAR);
        }

        public boolean containsPoint(
                double lat, double lon) {
            if (lat < minLat || lat > maxLat
                    || lon < minLon || lon > maxLon)
                return false;
            if (!isArea || nodes.length < 3)
                return false;
            if (!pointInPolygon(lat, lon, nodes)) return false;
            for (OsmNode[] ring : innerRings)
                if (ring != null && ring.length >= 3
                        && pointInPolygon(lat, lon, ring))
                    return false;
            return true;
        }

        public double distanceToLine(
                double lat, double lon) {
            double minD = Double.MAX_VALUE;
            for (int i = 0; i < nodes.length - 1;
                    i++) {
                double d = ptSegDist(lat, lon,
                    nodes[i].lat, nodes[i].lon,
                    nodes[i+1].lat, nodes[i+1].lon);
                if (d < minD) minD = d;
            }
            return minD * 111320.0;
        }

        public double resolvedHeightMetres() {
            if (height > 0) return height;
            if (levels > 0)
                return levels * METRES_PER_FLOOR;
            return -1;
        }

        public int resolvedHeightBlocks() {
            double h = resolvedHeightMetres();
            return h < 0
                ? -1
                : Math.max(1, (int) Math.round(h));
        }

        public int minHeightBlocks() {
            return (int) Math.round(minHeight);
        }

        public int roofHeightBlocks() {
            if (roofHeight > 0)
                return Math.max(1,
                    (int) Math.round(roofHeight));
            if (roofShape == null) return 1;
            return switch (
                    roofShape.toLowerCase()) {
                case "pyramidal"          -> 3;
                case "gabled", "hipped"   -> 2;
                case "dome", "onion"      -> 4;
                case "mansard"            -> 3;
                default                   -> 1;
            };
        }

        public OsmNode startNode() {
            return nodes.length > 0
                ? nodes[0] : null;
        }

        public OsmNode endNode() {
            return nodes.length > 0
                ? nodes[nodes.length - 1] : null;
        }

        public boolean isVisibleAt(int year) {
            return HistoricalDateParser.isVisibleAt(
                startYear, endYear, year, true);
        }

        private static boolean pointInPolygon(
                double lat, double lon,
                OsmNode[] ring) {
            boolean inside = false;
            for (int i = 0, j = ring.length - 1;
                    i < ring.length; j = i++) {
                if (((ring[i].lat > lat)
                            != (ring[j].lat > lat))
                        && (lon < (ring[j].lon
                            - ring[i].lon)
                            * (lat - ring[i].lat)
                            / (ring[j].lat
                                - ring[i].lat)
                            + ring[i].lon))
                    inside = !inside;
            }
            return inside;
        }

        public static double ptSegDist(
                double px, double py,
                double ax, double ay,
                double bx, double by) {
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            if (len2 < 1e-20) {
                double ex = px - ax, ey = py - ay;
                return Math.sqrt(
                    ex * ex + ey * ey);
            }
            double t = Math.max(0, Math.min(1,
                ((px - ax) * dx
                    + (py - ay) * dy) / len2));
            double cx2 = ax + t * dx - px;
            double cy2 = ay + t * dy - py;
            return Math.sqrt(cx2*cx2 + cy2*cy2);
        }
    }

    // ── STRUCTURE TILE ────────────────────────────────────────

    public static class StructureTile {
        public final OsmStructure[] structures;
        public final boolean        valid;
        public final long           fetchTimeMs;

        public StructureTile(OsmStructure[] structures,
                boolean valid) {
            this.structures  = structures;
            this.valid       = valid;
            this.fetchTimeMs =
                System.currentTimeMillis();
        }

        /** A successful source response containing no structures. */
        public static final StructureTile EMPTY =
            new StructureTile(
                new OsmStructure[0], true);
        /** Source unavailable/timeout: callers must retry, never finalise empty. */
        public static final StructureTile FAILED =
            new StructureTile(
                new OsmStructure[0], false);
    }

    // ── FIELDS ────────────────────────────────────────────────

    private final ConcurrentHashMap<Long,
        StructureTile> memCache =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long,
        CompletableFuture<StructureTile>> pending =
        new ConcurrentHashMap<>();
    private final ExecutorService dlPool;
    private final AtomicInteger endpointIdx =
        new AtomicInteger(0);
    private final AtomicInteger totalQueries =
        new AtomicInteger(0);
    private final AtomicInteger dbgCount =
        new AtomicInteger(0);
    private final Path    diskDir;
    private final boolean diskOk;
    private volatile boolean enabled = true;
    private volatile int  consecutiveFailures = 0;
    private volatile long pauseUntilMs = 0;

    // ── CONSTRUCTOR ───────────────────────────────────────────

    public OsmStructureIndex() {
        // One rate limiter per endpoint — parallel
        // fetches to different endpoints never block
        // each other. Fixes the AtomicLong race that
        // could allow simultaneous requests.
        this.endpointLimiters =
            new OverpassRateLimiter[
                OVERPASS_ENDPOINTS.length];
        for (int i = 0;
                i < OVERPASS_ENDPOINTS.length; i++) {
            endpointLimiters[i] =
                OverpassRateLimiter.forOsmEndpoint(
                    epName(OVERPASS_ENDPOINTS[i]));
        }

        this.dlPool = Executors.newFixedThreadPool(
            DL_THREADS, r -> {
                Thread t = new Thread(r,
                    "Terra++OSM-Struct");
                t.setDaemon(true);
                return t;
            });

        Path dir = Paths.get(
            "terra_cache", "osm_structures");
        boolean ok = false;
        try {
            Files.createDirectories(dir);
            ok = true;
        } catch (Exception e) {
            LOGGER.warn(
                "Structure disk cache failed: {}",
                e.getMessage());
        }
        this.diskDir = dir;
        this.diskOk  = ok;

        LOGGER.info("OsmStructureIndex init "
            + "(disk={}, endpoints={}, "
            + "threads={})",
            ok, OVERPASS_ENDPOINTS.length,
            DL_THREADS);
    }

    // ── PUBLIC API ────────────────────────────────────────────

    public StructureTile query(
            double lat, double lon) {
        if (!enabled || lat <= -85 || lat >= 85)
            return StructureTile.EMPTY;
        if (System.currentTimeMillis()
                < pauseUntilMs)
            return StructureTile.FAILED;

        long key = tileKey(lat, lon);
        StructureTile cached = memCache.get(key);
        if (cached != null) return cached;

        // Disk check before thread pool
        if (diskOk) {
            StructureTile disk = loadDisk(key);
            if (disk != null) {
                memCache.put(key, disk);
                return disk;
            }
        }

        CompletableFuture<StructureTile> future =
            pending.computeIfAbsent(key, k -> {
                double tLat = Math.floor(
                    lat / TILE_DEG) * TILE_DEG;
                double tLon = Math.floor(
                    lon / TILE_DEG) * TILE_DEG;
                return CompletableFuture.supplyAsync(
                    () -> fetchTile(k, tLat, tLon),
                    dlPool);
            });
        try {
            return future.get(TILE_WAIT_MS,
                TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return StructureTile.FAILED;
        }
    }

    public List<OsmStructure> getStructuresAt(
            double lat, double lon,
            TerraWorldConfig config) {
        try {
            return collectFromKeys(
                getTileKeysForPoint(lat, lon),
                lat - 0.0005, lat + 0.0005,
                lon - 0.0005, lon + 0.0005,
                config, false);
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<OsmStructure> getStructuresForChunk(
            double minLat, double maxLat,
            double minLon, double maxLon,
            TerraWorldConfig config) {
        try {
            return collectFromKeys(
                getTileKeysForArea(
                    minLat, maxLat, minLon, maxLon),
                minLat - OVERLAP_MARGIN,
                maxLat + OVERLAP_MARGIN,
                minLon - OVERLAP_MARGIN,
                maxLon + OVERLAP_MARGIN,
                config, false);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Blocking fetch. Returns null on partial timeout.
     *
     * Disk cache for ALL needed tiles is checked here,
     * before any tile is submitted to the thread pool.
     * Only truly missing tiles trigger network fetches.
     */
    public List<OsmStructure>
            getStructuresForChunkBlocking(
            double minLat, double maxLat,
            double minLon, double maxLon,
            TerraWorldConfig config,
            long timeoutMs) {
        try {
            Set<Long> neededKeys =
                getTileKeysForArea(
                    minLat, maxLat, minLon, maxLon);

            // ── DISK CHECK FOR ALL TILES FIRST ────────
            // Done here, before thread pool submission.
            // Any tile found on disk is loaded for free
            // — no rate limit slot, no thread consumed.
            if (diskOk) {
                for (long key : neededKeys) {
                    if (memCache.containsKey(key))
                        continue;
                    StructureTile disk =
                        loadDisk(key);
                    if (disk != null)
                        memCache.put(key, disk);
                }
            }

            // Find tiles still missing after disk check
            Set<Long> missingKeys = new HashSet<>();
            for (long key : neededKeys)
                if (!memCache.containsKey(key))
                    missingKeys.add(key);

            if (missingKeys.isEmpty())
                return collectFromKeys(neededKeys,
                    minLat - OVERLAP_MARGIN,
                    maxLat + OVERLAP_MARGIN,
                    minLon - OVERLAP_MARGIN,
                    maxLon + OVERLAP_MARGIN,
                    config, false);

            // Submit network fetches for missing tiles
            List<CompletableFuture<StructureTile>>
                futures = new ArrayList<>();
            for (long key : missingKeys) {
                int tLatI = (int)(key >> 32);
                int tLonI = (int) key;
                double tLat = tLatI * TILE_DEG;
                double tLon = tLonI * TILE_DEG;
                CompletableFuture<StructureTile> f =
                    pending.computeIfAbsent(key,
                        k -> CompletableFuture
                            .supplyAsync(
                                () -> fetchTile(
                                    k, tLat, tLon),
                                dlPool));
                futures.add(f);
            }

            boolean timedOut = false;
            try {
                CompletableFuture.allOf(
                    futures.toArray(
                        new CompletableFuture[0]))
                    .get(timeoutMs,
                        TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                timedOut = true;
            } catch (Exception e) {
                LOGGER.debug(
                    "Blocking fetch error: {}",
                    e.getMessage());
            }

            if (timedOut) {
                int loaded = 0;
                for (long key : missingKeys)
                    if (memCache.containsKey(key))
                        loaded++;
                if (loaded < missingKeys.size()) {
                    // Clean up futures for keys that
                    // did not complete in time
                    for (long key : missingKeys) {
                        if (!memCache.containsKey(
                                key)) {
                            CompletableFuture<
                                StructureTile> f =
                                pending.get(key);
                            if (f != null
                                    && f.isDone()
                                    == false)
                                pending.remove(
                                    key, f);
                        }
                    }
                    if (dbgCount
                            .getAndIncrement() < 20)
                        LOGGER.info(
                            "BLOCKING TIMEOUT: "
                            + "{}/{} tiles in "
                            + "{}ms — null",
                            loaded,
                            missingKeys.size(),
                            timeoutMs);
                    return null;
                }
                if (dbgCount
                        .getAndIncrement() < 20)
                    LOGGER.info(
                        "BLOCKING TIMEOUT resolved:"
                        + " all {} tiles loaded",
                        missingKeys.size());
            }

            // A failed/missing tile is retryable source state, not proof that
            // this geographic area contains no structures.
            for (long key : neededKeys) {
                StructureTile tile = memCache.get(key);
                if (tile == null || !tile.valid)
                    return null;
            }

            return collectFromKeys(neededKeys,
                minLat - OVERLAP_MARGIN,
                maxLat + OVERLAP_MARGIN,
                minLon - OVERLAP_MARGIN,
                maxLon + OVERLAP_MARGIN,
                config, false);

        } catch (Exception e) {
            LOGGER.debug(
                "getStructuresForChunkBlocking: {}",
                e.getMessage());
            return null;
        }
    }

    public boolean areTilesLoaded(
            double minLat, double maxLat,
            double minLon, double maxLon) {
        for (long key : getTileKeysForArea(
                minLat, maxLat, minLon, maxLon)) {
            if (!memCache.containsKey(key)) {
                if (diskOk) {
                    if (!Files.exists(
                            diskDir.resolve(
                                Long.toHexString(key)
                                + ".json")))
                        return false;
                } else return false;
            }
        }
        return true;
    }

    /**
     * Prefetch tiles for an area.
     * Disk check happens before thread pool submission.
     */
    public void prefetchTiles(double minLat,
            double maxLat, double minLon,
            double maxLon) {
        for (long key : getTileKeysForArea(
                minLat, maxLat, minLon, maxLon)) {
            if (memCache.containsKey(key)) continue;
            if (pending.containsKey(key)) continue;

            // Disk check before submitting
            if (diskOk) {
                StructureTile disk = loadDisk(key);
                if (disk != null) {
                    memCache.put(key, disk);
                    continue;
                }
            }

            int tLatI = (int)(key >> 32);
            int tLonI = (int) key;
            double tLat = tLatI * TILE_DEG;
            double tLon = tLonI * TILE_DEG;
            pending.computeIfAbsent(key,
                k -> CompletableFuture.supplyAsync(
                    () -> fetchTile(k, tLat, tLon),
                    dlPool));
        }
    }

    public int     getCacheSize()    { return memCache.size(); }
    public int     getTotalQueries() { return totalQueries.get(); }
    public boolean isEnabled()       { return enabled; }

    public void setEnabled(boolean e) {
        this.enabled = e;
        if (e) {
            consecutiveFailures = 0;
            pauseUntilMs = 0;
            // Restore burst capacity on all endpoints
            for (OverpassRateLimiter lim :
                    endpointLimiters)
                lim.reset();
        }
    }

    // ── TILE KEYS ─────────────────────────────────────────────

    private long tileKey(double lat, double lon) {
        int tLat = (int) Math.floor(lat / TILE_DEG);
        int tLon = (int) Math.floor(lon / TILE_DEG);
        return ((long) tLat << 32)
            | (tLon & 0xFFFFFFFFL);
    }

    private Set<Long> getTileKeysForPoint(
            double lat, double lon) {
        Set<Long> keys = new HashSet<>();
        keys.add(tileKey(lat, lon));
        double tLat =
            Math.floor(lat / TILE_DEG) * TILE_DEG;
        double tLon =
            Math.floor(lon / TILE_DEG) * TILE_DEG;
        boolean nS =
            (lat - tLat) < OVERLAP_MARGIN;
        boolean nN =
            ((tLat + TILE_DEG) - lat) < OVERLAP_MARGIN;
        boolean nW =
            (lon - tLon) < OVERLAP_MARGIN;
        boolean nE =
            ((tLon + TILE_DEG) - lon) < OVERLAP_MARGIN;
        if (nS) keys.add(tileKey(
            lat - OVERLAP_MARGIN, lon));
        if (nN) keys.add(tileKey(
            lat + OVERLAP_MARGIN, lon));
        if (nW) keys.add(tileKey(
            lat, lon - OVERLAP_MARGIN));
        if (nE) keys.add(tileKey(
            lat, lon + OVERLAP_MARGIN));
        if (nS && nW) keys.add(tileKey(
            lat - OVERLAP_MARGIN,
            lon - OVERLAP_MARGIN));
        if (nS && nE) keys.add(tileKey(
            lat - OVERLAP_MARGIN,
            lon + OVERLAP_MARGIN));
        if (nN && nW) keys.add(tileKey(
            lat + OVERLAP_MARGIN,
            lon - OVERLAP_MARGIN));
        if (nN && nE) keys.add(tileKey(
            lat + OVERLAP_MARGIN,
            lon + OVERLAP_MARGIN));
        return keys;
    }

    private Set<Long> getTileKeysForArea(
            double minLat, double maxLat,
            double minLon, double maxLon) {
        Set<Long> keys = new HashSet<>();
        for (double tLat = Math.floor(
                (minLat - OVERLAP_MARGIN)
                / TILE_DEG) * TILE_DEG;
                tLat <= maxLat + OVERLAP_MARGIN;
                tLat += TILE_DEG)
            for (double tLon = Math.floor(
                    (minLon - OVERLAP_MARGIN)
                    / TILE_DEG) * TILE_DEG;
                    tLon <= maxLon + OVERLAP_MARGIN;
                    tLon += TILE_DEG)
                keys.add(tileKey(
                    tLat + TILE_DEG / 2,
                    tLon + TILE_DEG / 2));
        return keys;
    }

    // ── COLLECT ───────────────────────────────────────────────

    private List<OsmStructure> collectFromKeys(
            Set<Long> tileKeys,
            double qMinLat, double qMaxLat,
            double qMinLon, double qMaxLon,
            TerraWorldConfig config,
            boolean triggerFetch) {
        List<OsmStructure> result = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        for (long key : tileKeys) {
            StructureTile tile =
                memCache.get(key);
            if (tile == null && triggerFetch) {
                int tLatI = (int)(key >> 32);
                int tLonI = (int) key;
                tile = query(
                    tLatI * TILE_DEG + TILE_DEG / 2,
                    tLonI * TILE_DEG + TILE_DEG / 2);
            }
            if (tile == null || !tile.valid
                    || tile.structures.length == 0)
                continue;
            for (OsmStructure s : tile.structures) {
                if (!isEnabled(s.type, config))
                    continue;
                if (s.maxLat < qMinLat
                        || s.minLat > qMaxLat
                        || s.maxLon < qMinLon
                        || s.minLon > qMaxLon)
                    continue;
                if (s.osmId != 0
                        && !seenIds.add(s.osmId))
                    continue;
                result.add(s);
            }
        }
        return result;
    }

    private boolean isEnabled(StructureType type,
            TerraWorldConfig config) {
        return switch (type) {
            case BUILDING,
                 BUILDING_PART  -> config.buildings;
            case ROAD, FOOTWAY,
                 CYCLEWAY       -> config.roads;
            case RAILWAY        -> config.railways;
            case BRIDGE         -> config.bridges;
            case WALL, FENCE,
                 BARRIER        -> config.walls;
            case GARDEN, PARK,
                 HEDGE          -> config.gardens;
            case FARMLAND, FOREST,
                 MEADOW, PARKING -> config.landuse;
            case STREET_LAMP    -> config.streetLights;
        };
    }

    // ── FETCH ─────────────────────────────────────────────────

    /**
     * Network fetch only — disk check is done by all
     * callers before this task is submitted.
     *
     * Picks the best available endpoint and acquires
     * that endpoint's rate limiter token. Threads
     * fetching from different endpoints are never
     * blocked by each other.
     */
    private StructureTile fetchTile(long key,
            double minLat, double minLon) {

        if (System.currentTimeMillis()
                < pauseUntilMs) {
            pending.remove(key);
            return StructureTile.FAILED;
        }

        if (consecutiveFailures
                >= MAX_CONSECUTIVE_FAILURES) {
            LOGGER.warn(
                "Pausing OSM struct for {}s "
                + "after {} failures",
                PAUSE_ON_FAILURES_MS / 1000,
                consecutiveFailures);
            pauseUntilMs =
                System.currentTimeMillis()
                + PAUSE_ON_FAILURES_MS;
            consecutiveFailures = 0;
            pending.remove(key);
            return StructureTile.FAILED;
        }

        String bbox = String.format(
            "%.6f,%.6f,%.6f,%.6f",
            minLat, minLon,
            minLat + TILE_DEG,
            minLon + TILE_DEG);

        String queryStr = "[out:json][timeout:25];("
            + "way[\"building\"](" + bbox + ");"
            + "relation[\"building\"]"
            + "(" + bbox + ");"
            + "way[\"building:part\"]"
            + "(" + bbox + ");"
            + "relation[\"building:part\"]"
            + "(" + bbox + ");"
            + "way[\"highway\"](" + bbox + ");"
            + "way[\"railway\"~\"rail|light_rail"
            + "|subway|tram\"](" + bbox + ");"
            + "way[\"bridge\"](" + bbox + ");"
            // Some mapped bridges are multipolygon/route relations whose
            // member ways carry no individual bridge tag.
            + "relation[\"bridge\"](" + bbox + ");"
            + "way[\"barrier\"~\"wall|fence|hedge\"]"
            + "(" + bbox + ");"
            + "way[\"landuse\"~\"farmland|forest"
            + "|meadow|grass|orchard|vineyard"
            + "|parking\"](" + bbox + ");"
            + "relation[\"landuse\"~\"farmland"
            + "|forest|meadow|grass|orchard"
            + "|vineyard\"](" + bbox + ");"
            + "way[\"leisure\"~\"park|garden"
            + "|playground|pitch\"](" + bbox + ");"
            + "node[\"highway\"=\"street_lamp\"]"
            + "(" + bbox + ");"
            + ");out body geom;";

        totalQueries.incrementAndGet();

        int startIdx = endpointIdx.get()
            % OVERPASS_ENDPOINTS.length;

        for (int attempt = 0;
                attempt < OVERPASS_ENDPOINTS.length;
                attempt++) {
            int idx = (startIdx + attempt)
                % OVERPASS_ENDPOINTS.length;
            String ep = OVERPASS_ENDPOINTS[idx];
            OverpassRateLimiter limiter =
                endpointLimiters[idx];

            HttpURLConnection conn = null;
        boolean endpointInFlight = false;
            try {
                // Acquire token for THIS endpoint.
                // Threads using a different endpoint
                // are completely unaffected.
                limiter.acquire();
                limiter.acquireInFlight();
                endpointInFlight = true;

                conn = (HttpURLConnection)
                    new URL(ep).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(
                    HTTP_TIMEOUT_MS);
                conn.setReadTimeout(HTTP_TIMEOUT_MS);
                conn.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-"
                    + "urlencoded");
                conn.setRequestProperty(
                    "User-Agent",
                    "TerraPlusPlus/2.0 Minecraft");

                byte[] body = ("data="
                    + URLEncoder.encode(queryStr,
                        StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
                conn.getOutputStream().write(body);
                conn.getOutputStream().flush();

                int code = conn.getResponseCode();
                if (code == 200) {
                    String json =
                        readResponse(conn);
                    StructureTile st =
                        parseResponse(json);
                    endpointIdx.set(idx);
                    consecutiveFailures = 0;
                    memCache.put(key, st);
                    pending.remove(key);
                    evictIfNeeded();
                    if (diskOk)
                        saveDisk(key, json);
                    if (dbgCount
                            .getAndIncrement() < 20)
                        LOGGER.info(
                            "OSM STRUCT [{},{}]:"
                            + " {} features",
                            String.format(
                                "%.4f", minLat),
                            String.format(
                                "%.4f", minLon),
                            st.structures.length);
                    return st;
                } else if (code == 429
                        || code == 504) {
                    // Server-side rate limit —
                    // back off this endpoint
                    limiter.penalize(code == 429 ? 30_000L : 10_000L);
                    continue;
                } else {
                    LOGGER.debug(
                        "Endpoint {} returned {}",
                        idx, code);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending.remove(key);
                return StructureTile.FAILED;
            } catch (Exception e) {
                LOGGER.debug(
                    "Struct fetch failed: {}",
                    e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
                if (endpointInFlight) limiter.releaseInFlight();
            }
        }

        consecutiveFailures++;
        pending.remove(key);
        return StructureTile.FAILED;
    }

    // ── PARSING ───────────────────────────────────────────────

    private StructureTile parseResponse(String json) {
        try {
            JsonObject root =
                JsonParser.parseString(json)
                    .getAsJsonObject();
            JsonArray elements =
                root.getAsJsonArray("elements");
            if (elements == null
                    || elements.isEmpty())
                return StructureTile.EMPTY;
            List<OsmStructure> structures =
                new ArrayList<>();
            for (JsonElement el : elements) {
                JsonObject obj = el.getAsJsonObject();
                OsmStructure s = parseElement(obj);
                if (s == null) continue;
                List<OsmStructure> parts = splitRelationOuterParts(obj, s);
                if (parts.isEmpty()) structures.add(s);
                else structures.addAll(parts);
            }
            return new StructureTile(
                structures.toArray(
                    new OsmStructure[0]), true);
        } catch (Exception e) {
            LOGGER.warn(
                "Structure parse failed: {}",
                e.getMessage());
            return StructureTile.FAILED;
        }
    }

    /**
     * A multipolygon relation may contain several disconnected outer rings.
     * The old parser flattened them into one artificial polygon, drawing walls
     * across unrelated building parts. Represent each assembled outer ring as
     * an independent placement structure instead.
     */
    private List<OsmStructure> splitRelationOuterParts(
            JsonObject obj, OsmStructure base) {
        if (!base.isArea || !obj.has("members")) return List.of();
        List<List<OsmNode>> rings = new ArrayList<>();
        for (JsonElement memberEl : obj.getAsJsonArray("members")) {
            JsonObject member = memberEl.getAsJsonObject();
            String role = member.has("role") ? member.get("role").getAsString() : "";
            if (!(role.isEmpty() || "outer".equals(role)) || !member.has("geometry")) continue;
            List<OsmNode> segment = new ArrayList<>();
            for (JsonElement pointEl : member.getAsJsonArray("geometry")) {
                JsonObject point = pointEl.getAsJsonObject();
                if (point.has("lat") && point.has("lon"))
                    segment.add(new OsmNode(point.get("lat").getAsDouble(),
                            point.get("lon").getAsDouble()));
            }
            if (segment.size() >= 2) appendRingSegment(rings, segment);
        }
        if (rings.size() <= 1) return List.of();
        List<OsmStructure> parts = new ArrayList<>(rings.size());
        int part = 0;
        for (List<OsmNode> ring : rings) {
            if (ring.size() < 3) continue;
            OsmNode[] outer = ring.toArray(new OsmNode[0]);
            OsmStructure partStructure = copyWithNodes(base, outer,
                    part++ == 0 ? base.osmId : 0L);
            // Keep only courtyard rings enclosed by this particular outer
            // part. A relation with separate wings must not apply one wing's
            // courtyard to every other wing.
            List<OsmNode[]> localInner = new ArrayList<>();
            for (OsmNode[] inner : base.innerRings)
                if (inner != null && inner.length >= 3
                        && pointInRing(inner[0].lat, inner[0].lon, outer))
                    localInner.add(inner);
            partStructure.innerRings = localInner.toArray(new OsmNode[0][]);
            parts.add(partStructure);
        }
        return parts;
    }

    private static void appendRingSegment(List<List<OsmNode>> rings,
            List<OsmNode> segment) {
        for (List<OsmNode> ring : rings) {
            if (sameNode(ring.get(ring.size() - 1), segment.get(0))) {
                ring.addAll(segment.subList(1, segment.size())); return;
            }
            if (sameNode(ring.get(ring.size() - 1), segment.get(segment.size() - 1))) {
                java.util.Collections.reverse(segment);
                ring.addAll(segment.subList(1, segment.size())); return;
            }
        }
        rings.add(new ArrayList<>(segment));
    }

    private static List<OsmNode> joinLinearRelationMembers(JsonObject relation) {
        List<List<OsmNode>> chains = new ArrayList<>();
        for (JsonElement memberEl : relation.getAsJsonArray("members")) {
            JsonObject member = memberEl.getAsJsonObject();
            String role = member.has("role") ? member.get("role").getAsString() : "";
            if ("inner".equals(role) || !member.has("geometry")) continue;
            List<OsmNode> segment = new ArrayList<>();
            for (JsonElement pointEl : member.getAsJsonArray("geometry")) {
                JsonObject point = pointEl.getAsJsonObject();
                if (point.has("lat") && point.has("lon"))
                    segment.add(new OsmNode(point.get("lat").getAsDouble(),
                            point.get("lon").getAsDouble()));
            }
            if (segment.size() >= 2) appendRingSegment(chains, segment);
        }
        List<OsmNode> longest = List.of();
        for (List<OsmNode> chain : chains)
            if (chain.size() > longest.size()) longest = chain;
        return longest;
    }

    private static boolean pointInRing(double lat, double lon, OsmNode[] ring) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++)
            if (((ring[i].lat > lat) != (ring[j].lat > lat))
                    && lon < (ring[j].lon - ring[i].lon) * (lat - ring[i].lat)
                    / (ring[j].lat - ring[i].lat) + ring[i].lon)
                inside = !inside;
        return inside;
    }

    private static boolean sameNode(OsmNode a, OsmNode b) {
        return Math.abs(a.lat - b.lat) < 1e-9 && Math.abs(a.lon - b.lon) < 1e-9;
    }

    private static OsmStructure copyWithNodes(OsmStructure b, OsmNode[] nodes, long osmId) {
        OsmStructure copy = new OsmStructure(b.type, b.subType, b.name, nodes, b.isArea, osmId,
                b.material, b.levels, b.height, b.roofShape, b.roofMaterial,
                b.surface, b.lanes, b.isOneWay, b.minHeight, b.roofHeight,
                b.roofDirection, b.colour, b.roofColour, b.isPart, b.parentOsmId,
                b.layer, b.tunnel, b.isRailBridge, b.bridgeStructure,
                b.startYear, b.endYear);
        copy.innerRings = b.innerRings;
        return copy;
    }

    private OsmStructure parseElement(JsonObject obj) {
        String objType = obj.has("type")
            ? obj.get("type").getAsString() : "";
        JsonObject tags = obj.has("tags")
            ? obj.getAsJsonObject("tags") : null;
        if (tags == null) return null;
        long osmId = obj.has("id")
            ? obj.get("id").getAsLong() : 0;

        String startDateStr =
            getTag(tags, "start_date");
        String endDateStr =
            getTag(tags, "end_date");
        int startYear =
            HistoricalDateParser.parse(startDateStr);
        int endYear =
            HistoricalDateParser.parse(endDateStr);

        StructureType type = null;
        String subType = null;
        boolean isArea = false, isPart = false,
                isRailBridge = false,
                tunnel = false;
        BridgeStructure bridgeStructure = null;

        int layer = 0;
        String layerStr = getTag(tags, "layer");
        if (layerStr != null) try {
            layer = Integer.parseInt(
                layerStr.trim());
        } catch (NumberFormatException ignored) {}

        tunnel = "yes".equals(
            getTag(tags, "tunnel"));
        boolean isSubway =
            "subway".equals(
                getTag(tags, "railway"))
            || "yes".equals(
                getTag(tags, "subway"));

        String bridgeTagValue =
            getTag(tags, "bridge");
        String bridgeStructureTag =
            getTag(tags, "bridge:structure");
        boolean hasBridgeTag =
            bridgeTagValue != null
            && !bridgeTagValue.isEmpty()
            && !"no".equalsIgnoreCase(
                bridgeTagValue);

        String buildingPart =
            getTag(tags, "building:part");
        if (buildingPart != null) {
            type = StructureType.BUILDING_PART;
            subType = buildingPart;
            isArea = true; isPart = true;
        }

        if (type == null) {
            String building =
                getTag(tags, "building");
            if (building != null) {
                type = StructureType.BUILDING;
                subType = building; isArea = true;
            }
        }

        if (type == null) {
            String highway =
                getTag(tags, "highway");
            if (highway != null) {
                if ("street_lamp".equals(highway)) {
                    type = StructureType.STREET_LAMP;
                    subType = "lamp";
                } else if (
                        "footway".equals(highway)
                        || "path".equals(highway)
                        || "pedestrian"
                            .equals(highway)) {
                    type = StructureType.FOOTWAY;
                    subType = highway;
                } else if (
                        "cycleway".equals(highway)) {
                    type = StructureType.CYCLEWAY;
                    subType = highway;
                } else {
                    type = StructureType.ROAD;
                    subType = highway;
                }
                if (hasBridgeTag) {
                    type = StructureType.BRIDGE;
                    bridgeStructure =
                        resolveBridgeType(
                            bridgeTagValue,
                            bridgeStructureTag);
                }
            }
        }

        if (type == null) {
            String railway =
                getTag(tags, "railway");
            if (railway != null) {
                type = StructureType.RAILWAY;
                subType = railway;
                if (hasBridgeTag) {
                    type = StructureType.BRIDGE;
                    isRailBridge = true;
                    bridgeStructure =
                        resolveBridgeType(
                            bridgeTagValue,
                            bridgeStructureTag);
                }
                if (isSubway
                        && (tunnel || layer == 0)
                        && !isRailBridge) {
                    layer = -1; tunnel = true;
                }
            }
        }

        if (type == null && hasBridgeTag) {
            type = StructureType.BRIDGE;
            subType = "yes";
            bridgeStructure = resolveBridgeType(
                bridgeTagValue, bridgeStructureTag);
        }

        if (type == null) {
            String barrier =
                getTag(tags, "barrier");
            if (barrier != null)
                switch (barrier) {
                    case "wall"  -> {
                        type = StructureType.WALL;
                        subType = "wall";
                    }
                    case "fence" -> {
                        type = StructureType.FENCE;
                        subType = "fence";
                    }
                    case "hedge" -> {
                        type = StructureType.HEDGE;
                        subType = "hedge";
                    }
                    default      -> {
                        type = StructureType.BARRIER;
                        subType = barrier;
                    }
                }
        }

        if (type == null) {
            String landuse =
                getTag(tags, "landuse");
            if (landuse != null) {
                isArea = true;
                switch (landuse) {
                    case "farmland", "orchard",
                         "vineyard" -> {
                        type = StructureType.FARMLAND;
                        subType = landuse;
                    }
                    case "forest" -> {
                        type = StructureType.FOREST;
                        subType = "forest";
                    }
                    case "meadow", "grass" -> {
                        type = StructureType.MEADOW;
                        subType = landuse;
                    }
                    case "parking" -> {
                        type = StructureType.PARKING;
                        subType = "parking";
                    }
                    default -> {
                        type = StructureType.MEADOW;
                        subType = landuse;
                    }
                }
            }
        }

        if (type == null) {
            String leisure =
                getTag(tags, "leisure");
            if (leisure != null) {
                isArea = true;
                switch (leisure) {
                    case "park" -> {
                        type = StructureType.PARK;
                        subType = "park";
                    }
                    case "garden" -> {
                        type = StructureType.GARDEN;
                        subType = "garden";
                    }
                    case "playground" -> {
                        type = StructureType.PARK;
                        subType = "playground";
                    }
                    case "pitch" -> {
                        type = StructureType.PARK;
                        subType = "pitch";
                    }
                    default -> {
                        type = StructureType.GARDEN;
                        subType = leisure;
                    }
                }
            }
        }

        if (type == null) return null;

        // ── GEOMETRY ──────────────────────────────
        List<OsmNode> nodeList = new ArrayList<>();
        if ("node".equals(objType)) {
            if (obj.has("lat") && obj.has("lon"))
                nodeList.add(new OsmNode(
                    obj.get("lat").getAsDouble(),
                    obj.get("lon").getAsDouble()));
        } else if (obj.has("geometry")) {
            JsonArray geom =
                obj.getAsJsonArray("geometry");
            for (int i = 0; i < geom.size(); i++) {
                JsonObject g =
                    geom.get(i).getAsJsonObject();
                if (g.has("lat") && g.has("lon"))
                    nodeList.add(new OsmNode(
                        g.get("lat").getAsDouble(),
                        g.get("lon").getAsDouble()));
            }
        } else if (obj.has("members")) {
            if (!isArea) {
                // Bridges/roads represented as relations contain several way
                // segments. Join connected members instead of drawing an
                // artificial line between unrelated member endpoints.
                nodeList.addAll(joinLinearRelationMembers(obj));
            } else {
                for (JsonElement m : obj.getAsJsonArray("members")) {
                    JsonObject mObj = m.getAsJsonObject();
                    String role = mObj.has("role") ? mObj.get("role").getAsString() : "";
                    if (!("outer".equals(role) || role.isEmpty()) || !mObj.has("geometry")) continue;
                    for (JsonElement pointEl : mObj.getAsJsonArray("geometry")) {
                        JsonObject point = pointEl.getAsJsonObject();
                        if (point.has("lat") && point.has("lon"))
                            nodeList.add(new OsmNode(point.get("lat").getAsDouble(),
                                    point.get("lon").getAsDouble()));
                    }
                }
            }
        }

        List<OsmNode[]> innerRings = new ArrayList<>();
        if (obj.has("members")) {
            for (JsonElement memberEl : obj.getAsJsonArray("members")) {
                JsonObject member = memberEl.getAsJsonObject();
                String role = member.has("role") ? member.get("role").getAsString() : "";
                if (!"inner".equals(role) || !member.has("geometry")) continue;
                List<OsmNode> ring = new ArrayList<>();
                for (JsonElement pointEl : member.getAsJsonArray("geometry")) {
                    JsonObject point = pointEl.getAsJsonObject();
                    if (point.has("lat") && point.has("lon"))
                        ring.add(new OsmNode(point.get("lat").getAsDouble(),
                                point.get("lon").getAsDouble()));
                }
                if (ring.size() >= 3) innerRings.add(ring.toArray(new OsmNode[0]));
            }
        }

        if (nodeList.isEmpty()) return null;
        if (type != StructureType.STREET_LAMP
                && nodeList.size() < 2) return null;

        // ── NUMERIC TAGS ──────────────────────────
        String material =
            getTag(tags, "building:material");
        String name     = getTag(tags, "name");
        String surfaceTag = getTag(tags, "surface");
        String roofShape  =
            getTag(tags, "roof:shape");
        String roofMat  =
            getTag(tags, "roof:material");

        int levels = 0;
        String levelsStr =
            getTag(tags, "building:levels");
        if (levelsStr != null) try {
            levels = Math.max(1,
                Integer.parseInt(levelsStr.trim()));
        } catch (NumberFormatException ignored) {}

        double height = 0;
        String heightStr = getTag(tags, "height");
        if (heightStr != null) try {
            height = Double.parseDouble(
                heightStr.replaceAll(
                    "[^0-9.]", ""));
        } catch (NumberFormatException ignored) {}

        int lanes = 2;
        String lanesStr = getTag(tags, "lanes");
        if (lanesStr != null) try {
            lanes = Integer.parseInt(
                lanesStr.trim());
        } catch (NumberFormatException ignored) {}

        boolean oneWay = "yes".equals(
            getTag(tags, "oneway"));

        double minHeight = 0;
        String minHStr =
            getTag(tags, "min_height");
        if (minHStr == null)
            minHStr = getTag(tags,
                "building:min_height");
        if (minHStr != null) try {
            minHeight = Double.parseDouble(
                minHStr.replaceAll(
                    "[^0-9.]", ""));
        } catch (NumberFormatException ignored) {}
        if (minHeight == 0) {
            String minLStr = getTag(tags,
                "building:min_level");
            if (minLStr != null) try {
                minHeight = Integer.parseInt(
                    minLStr.trim())
                    * METRES_PER_FLOOR;
            } catch (
                    NumberFormatException ignored) {}
        }

        double roofHeight = 0;
        String roofHStr =
            getTag(tags, "roof:height");
        if (roofHStr != null) try {
            roofHeight = Double.parseDouble(
                roofHStr.replaceAll(
                    "[^0-9.]", ""));
        } catch (NumberFormatException ignored) {}

        double roofDir = 0;
        String roofDirStr =
            getTag(tags, "roof:direction");
        if (roofDirStr != null)
            roofDir =
                parseRoofDirection(roofDirStr);

        String colour =
            getTag(tags, "building:colour");
        if (colour == null)
            colour = getTag(tags, "colour");
        String roofColour =
            getTag(tags, "roof:colour");

        OsmStructure parsed = new OsmStructure(type, subType, name,
            nodeList.toArray(new OsmNode[0]),
            isArea, osmId, material, levels, height,
            roofShape, roofMat, surfaceTag, lanes,
            oneWay, minHeight, roofHeight, roofDir,
            colour, roofColour, isPart, 0, layer,
            tunnel, isRailBridge, bridgeStructure,
            startYear, endYear);
        parsed.innerRings = innerRings.toArray(new OsmNode[0][]);
        return parsed;
    }

    // ── HELPERS ───────────────────────────────────────────────

    private BridgeStructure resolveBridgeType(
            String bridgeTagValue,
            String bridgeStructureTag) {
        if (bridgeTagValue != null) {
            BridgeStructure p1 =
                BridgeStructure.fromBridgeTag(
                    bridgeTagValue);
            if (p1 != null) return p1;
        }
        if (bridgeStructureTag != null) {
            BridgeStructure p2 =
                BridgeStructure.fromStructureTag(
                    bridgeStructureTag);
            if (p2 != null) return p2;
        }
        return BridgeStructure.BEAM;
    }

    private static double parseRoofDirection(
            String s) {
        if (s == null || s.isEmpty()) return 0;
        s = s.trim().toUpperCase();
        return switch (s) {
            case "N"   -> 0;
            case "NNE" -> 22.5;
            case "NE"  -> 45;
            case "ENE" -> 67.5;
            case "E"   -> 90;
            case "ESE" -> 112.5;
            case "SE"  -> 135;
            case "SSE" -> 157.5;
            case "S"   -> 180;
            case "SSW" -> 202.5;
            case "SW"  -> 225;
            case "WSW" -> 247.5;
            case "W"   -> 270;
            case "WNW" -> 292.5;
            case "NW"  -> 315;
            case "NNW" -> 337.5;
            default -> {
                try {
                    yield Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    yield 0;
                }
            }
        };
    }

    private static String epName(String url) {
        if (url.contains("kumi"))  return "kumi";
        if (url.contains("mail"))  return "mail.ru";
        return "de";
    }

private String getTag(JsonObject tags,
        String key) {
    if (tags.has(key)) {
        JsonElement e = tags.get(key);
        if (e.isJsonPrimitive())
            return e.getAsString();
    }
    return null;
}

    private String readResponse(
            HttpURLConnection conn)
            throws IOException {
        StringBuilder sb = new StringBuilder(32768);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                    conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) != -1)
                sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private void evictIfNeeded() {
        if (memCache.size() <= MEM_CACHE_MAX) return;
        memCache.entrySet().removeIf(
            e -> !e.getValue().valid);
        if (memCache.size() > MEM_CACHE_MAX) {
            int toRemove =
                memCache.size() - MEM_CACHE_MAX / 2;
            var iter =
                memCache.entrySet().iterator();
            while (iter.hasNext() && toRemove > 0) {
                iter.next();
                iter.remove();
                toRemove--;
            }
        }
    }

    private StructureTile loadDisk(long key) {
        try {
            Path file = diskDir.resolve(
                Long.toHexString(key) + ".json");
            if (!Files.exists(file)) return null;
            long age = System.currentTimeMillis()
                - Files.getLastModifiedTime(file)
                    .toMillis();
            if (age > DISK_TTL_MS) {
                Files.deleteIfExists(file);
                return null;
            }
            StructureTile st = parseResponse(
                Files.readString(file,
                    StandardCharsets.UTF_8));
            return st.valid ? st : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveDisk(long key, String json) {
        try {
            Files.writeString(
                diskDir.resolve(
                    Long.toHexString(key)
                    + ".json"),
                json,
                StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        dlPool.shutdown();
        try {
            if (!dlPool.awaitTermination(
                    3, TimeUnit.SECONDS))
                dlPool.shutdownNow();
        } catch (InterruptedException e) {
            dlPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}