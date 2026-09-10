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
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
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
        BARRIER, HEDGE,
        // ── extended OSM coverage ──
        SAND,      // natural=beach|sand
        ROCK,      // natural=bare_rock|scree|shingle, landuse=quarry
        TREE_ROW,  // natural=tree_row (linear)
        TREE,      // natural=tree (node)
        PIER,      // man_made=pier|breakwater|groyne
        TOWER,     // man_made=tower|chimney|water_tower|... (node or small area)
        PYLON,     // power=tower|pole (node)
        RUNWAY,    // aeroway=runway|taxiway|helipad
        APRON,     // aeroway=apron (area)
        POOL,      // leisure=swimming_pool (area)
        PLATFORM   // railway=platform (area or line)
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
                // taginfo: 234 ways say suspension_bridge
                // directly in the bridge tag
                case "suspension",
                     "suspension_bridge"  -> SUSPENSION;
                // 336 log bridges: felled logs -> wooden deck
                case "log_bridge"         -> BOARDWALK;
                default                   -> null;
            };
        }

        public static BridgeStructure fromStructureTag(
                String structureValue) {
            if (structureValue == null) return null;
            String v = structureValue.toLowerCase().trim();
            // taginfo: 40+ multi-values like "beam;arch" —
            // first token is the dominant structure
            int semi = v.indexOf(';');
            if (semi > 0) v = v.substring(0, semi).trim();
            return switch (v) {
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
                // taginfo additions: wooden variants (~210 uses)
                // render as plank decks
                case "log", "simple_wooden",
                     "wood", "plank"        -> BOARDWALK;
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
        /**
         * Explicit width=* tag in metres, 0 when untagged. Set
         * post-construction (like innerRings) so the many existing
         * constructor call sites stay untouched.
         */
        public double                explicitWidth = 0;
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
            // Longitude degrees shrink by cos(lat); without this
            // correction, N-S oriented ways were rendered too
            // narrow at higher latitudes (previously up to ~36%
            // at London's latitude).
            double cosLat = Math.cos(
                Math.toRadians(lat));
            double minD = Double.MAX_VALUE;
            for (int i = 0; i < nodes.length - 1;
                    i++) {
                double d = ptSegDist(
                    lat, lon * cosLat,
                    nodes[i].lat,
                    nodes[i].lon * cosLat,
                    nodes[i+1].lat,
                    nodes[i+1].lon * cosLat);
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

        Path dir = net.buildtheearth.terraplusplus.util.TerraCachePaths.dir("osm_structures_v4"); // v4: +man_made=bridge outlines
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

            // Find tiles still missing after disk check.
            // Invalid (FAILED) cached tiles count as missing so
            // a transient bad response can never permanently
            // stall the refresh pipeline.
            Set<Long> missingKeys = new HashSet<>();
            for (long key : neededKeys) {
                StructureTile t = memCache.get(key);
                if (t == null) { missingKeys.add(key); continue; }
                if (!t.valid) {
                    memCache.remove(key, t);
                    missingKeys.add(key);
                }
            }

            if (missingKeys.isEmpty())
                return collectFromKeys(neededKeys,
                    minLat - OVERLAP_MARGIN,
                    maxLat + OVERLAP_MARGIN,
                    minLon - OVERLAP_MARGIN,
                    maxLon + OVERLAP_MARGIN,
                    config, false);

            // COLD-START BATCHING: 2+ missing tiles = one
            // area request instead of N rate-limited singles.
            if (missingKeys.size() >= 2) {
                Set<Long> got =
                    fetchMissingAsBatches(missingKeys);
                missingKeys.removeAll(got);
                if (missingKeys.isEmpty())
                    return collectFromKeys(neededKeys,
                        minLat - OVERLAP_MARGIN,
                        maxLat + OVERLAP_MARGIN,
                        minLon - OVERLAP_MARGIN,
                        maxLon + OVERLAP_MARGIN,
                        config, false);
            }

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
        // Collect genuinely missing tiles (mem + disk checked
        // first: cache hits never cost a token or a thread).
        Set<Long> missing = new HashSet<>();
        for (long key : getTileKeysForArea(
                minLat, maxLat, minLon, maxLon)) {
            if (memCache.containsKey(key)) continue;
            if (pending.containsKey(key)) continue;
            if (diskOk) {
                StructureTile disk = loadDisk(key);
                if (disk != null) {
                    memCache.put(key, disk);
                    continue;
                }
            }
            missing.add(key);
        }
        if (missing.isEmpty()) return;

        if (missing.size() >= 2) {
            // Cold start: one batched area request. Register a
            // pending future per tile so concurrent callers wait
            // on the batch instead of firing their own fetches.
            Map<Long, CompletableFuture<StructureTile>> claimed =
                new HashMap<>();
            for (long key : missing) {
                CompletableFuture<StructureTile> f =
                    new CompletableFuture<>();
                if (pending.putIfAbsent(key, f) == null)
                    claimed.put(key, f);
            }
            if (claimed.isEmpty()) return;
            dlPool.submit(() -> {
                Set<Long> got = fetchMissingAsBatches(
                    claimed.keySet());
                for (var e : claimed.entrySet()) {
                    if (!got.contains(e.getKey())) {
                        // fall back to single-tile path
                        long k = e.getKey();
                        int tLatI = (int)(k >> 32);
                        int tLonI = (int) k;
                        StructureTile st = fetchTile(k,
                            tLatI * TILE_DEG,
                            tLonI * TILE_DEG);
                        e.getValue().complete(st);
                        pending.remove(k, e.getValue());
                    } else {
                        // fetchBatch completed+removed it;
                        // ensure future is resolved.
                        StructureTile st =
                            memCache.get(e.getKey());
                        if (!e.getValue().isDone())
                            e.getValue().complete(st != null
                                ? st : StructureTile.FAILED);
                        pending.remove(e.getKey(),
                            e.getValue());
                    }
                }
            });
            return;
        }

        for (long key : missing) {
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
                 BUILDING_PART,
                 TOWER          -> config.buildings;
            case ROAD, FOOTWAY,
                 CYCLEWAY       -> config.roads;
            case RAILWAY,
                 PLATFORM       -> config.railways;
            case BRIDGE, PIER   -> config.bridges;
            case WALL, FENCE,
                 BARRIER        -> config.walls;
            case GARDEN, PARK,
                 HEDGE, POOL    -> config.gardens;
            case FARMLAND, FOREST,
                 MEADOW, PARKING,
                 SAND, ROCK, TREE_ROW,
                 TREE, RUNWAY,
                 APRON          -> config.landuse;
            case STREET_LAMP,
                 PYLON          -> config.streetLights;
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
    /**
     * Full-coverage query for an arbitrary bbox. Used by both the
     * single-tile fetch and the cold-start batch fetch.
     */
    private static String buildQuery(String bbox, int timeoutSec) {
        // Full-coverage query. Every tag family the generator can render is
        // requested here. Values are whitelisted per family (an unfiltered
        // landuse/natural fetch would return huge residential/industrial
        // polygons that cannot be rendered meaningfully and would multiply
        // payload size). Combined with gzip transfer-encoding this stays
        // cheaper on the wire than the old uncompressed narrow query while
        // covering roughly twice the feature types.
        String queryStr = "[out:json][timeout:" + timeoutSec + "];("
            + "way[\"building\"](" + bbox + ");"
            + "relation[\"building\"]"
            + "(" + bbox + ");"
            + "way[\"building:part\"]"
            + "(" + bbox + ");"
            + "relation[\"building:part\"]"
            + "(" + bbox + ");"
            + "way[\"highway\"](" + bbox + ");"
            + "way[\"railway\"~\"^(rail|light_rail"
            + "|subway|tram|narrow_gauge|funicular"
            + "|monorail|platform)$\"](" + bbox + ");"
            + "way[\"bridge\"](" + bbox + ");"
            // Some mapped bridges are multipolygon/route relations whose
            // member ways carry no individual bridge tag.
            + "relation[\"bridge\"](" + bbox + ");"
            + "way[\"barrier\"~\"^(wall|fence|hedge"
            + "|retaining_wall|city_wall|guard_rail"
            + "|handrail)$\"](" + bbox + ");"
            + "way[\"landuse\"~\"^(farmland|forest"
            + "|meadow|grass|orchard|vineyard"
            + "|parking|allotments|cemetery|quarry"
            + "|recreation_ground|village_green"
            + "|plant_nursery|flowerbed"
            + "|farmyard)$\"](" + bbox + ");"
            + "relation[\"landuse\"~\"^(farmland"
            + "|forest|meadow|grass|orchard"
            + "|vineyard|allotments|cemetery"
            + "|recreation_ground"
            + "|village_green)$\"](" + bbox + ");"
            + "way[\"leisure\"~\"^(park|garden"
            + "|playground|pitch|golf_course"
            + "|recreation_ground|dog_park"
            + "|swimming_pool|track"
            + "|nature_reserve)$\"](" + bbox + ");"
            + "relation[\"leisure\"~\"^(park|garden"
            + "|golf_course|nature_reserve"
            + "|recreation_ground)$\"](" + bbox + ");"
            + "way[\"natural\"~\"^(wood|scrub|heath"
            + "|grassland|beach|sand|bare_rock"
            + "|scree|shingle|tree_row)$\"]"
            + "(" + bbox + ");"
            + "relation[\"natural\"~\"^(wood|scrub"
            + "|heath|grassland|beach"
            + "|sand)$\"](" + bbox + ");"
            + "way[\"amenity\"~\"^(parking"
            + "|marketplace|grave_yard)$\"]"
            + "(" + bbox + ");"
            + "way[\"man_made\"~\"^(pier|breakwater"
            + "|groyne|embankment|dyke|tower"
            + "|storage_tank|silo|water_tower"
            + "|chimney|lighthouse)$\"]"
            + "(" + bbox + ");"
            + "node[\"man_made\"~\"^(tower|chimney"
            + "|water_tower|lighthouse|mast"
            + "|flagpole"
            + "|communications_tower)$\"]"
            + "(" + bbox + ");"
            + "way[\"aeroway\"~\"^(runway|taxiway"
            + "|apron|helipad)$\"](" + bbox + ");"
            + "node[\"power\"~\"^(tower|pole)$\"]"
            + "(" + bbox + ");"
            + "way[\"waterway\"~\"^(dam"
            + "|weir)$\"](" + bbox + ");"
            + "node[\"highway\"=\"street_lamp\"]"
            + "(" + bbox + ");"
            + "node[\"natural\"=\"tree\"]"
            + "(" + bbox + ");"
            // CONTEXT POIs: point features that carry the IDENTITY of the
            // structure they stand inside (way 635428057 is an anonymous
            // building=transportation box; the railway=station node inside
            // it holds the name, the network and the real type). These are
            // not rendered directly — enrichFromContext() lets containing
            // buildings inherit from them.
            + "node[\"railway\"~\"^(station|halt)$\"]"
            + "(" + bbox + ");"
            + "node[\"public_transport\"=\"station\"]"
            + "(" + bbox + ");"
            + "node[\"amenity\"=\"place_of_worship\"]"
            + "(" + bbox + ");"
            + "node[\"historic\"~\"^(monument|memorial"
            + "|castle|fort|ruins)$\"]"
            + "(" + bbox + ");"
            // BRIDGE OUTLINES: man_made=bridge areas are the
            // identity carriers of major bridges (Durgam Cheruvu
            // 761604461: bridge:structure=suspension + name +
            // wikidata live on the OUTLINE; the two carriageway
            // ways 852586032/35 inside it carry only geometry).
            // Not rendered directly — enrichBridgesFromOutlines()
            // lets contained bridge ways inherit from them.
            + "way[\"man_made\"=\"bridge\"]"
            + "(" + bbox + ");"
            + "relation[\"man_made\"=\"bridge\"]"
            + "(" + bbox + ");"
            + ");out body geom;";
        return queryStr;
    }

    // ── COLD-START BATCH FETCH ────────────────────────────────
    // One Overpass request per AREA instead of per tile.
    // A 5x5-tile batch costs ONE rate-limit token and one HTTP
    // round-trip; fetched individually the same area costs 25
    // tokens = 25+ seconds of rate-limit waiting on a cold start.
    // Measured (dense Hyderabad, 0.05 deg): 1 request, ~6 MB
    // gzipped stream, ~12 s -- vs 30-80 s tile-by-tile.
    private static final int BATCH_MAX_TILES   = 30;
    private static final int BATCH_TIMEOUT_SEC = 60;
    private static final int BATCH_HTTP_READ_MS = 75000;

    /**
     * Fetch a rectangle of tiles in one Overpass request and
     * populate the per-tile caches. Returns the set of tile keys
     * successfully cached (empty on total failure).
     *
     * Correctness: a tile is a subset of the batch bbox, so the
     * batch result is a superset of every per-tile result.
     * Elements are assigned to each tile their bounding box
     * overlaps (plus OVERLAP_MARGIN) -- the same bbox test
     * collectFromKeys() applies at query time. Tiles with no
     * elements are cached as EMPTY (valid), so ocean/rural areas
     * never trigger a refetch.
     */
    private Set<Long> fetchBatch(int tLatMin, int tLatMax,
            int tLonMin, int tLonMax) {
        Set<Long> done = new HashSet<>();
        if (System.currentTimeMillis() < pauseUntilMs)
            return done;

        double minLat = tLatMin * TILE_DEG;
        double maxLat = (tLatMax + 1) * TILE_DEG;
        double minLon = tLonMin * TILE_DEG;
        double maxLon = (tLonMax + 1) * TILE_DEG;
        String bbox = String.format("%.6f,%.6f,%.6f,%.6f",
            minLat, minLon, maxLat, maxLon);
        String queryStr = buildQuery(bbox, BATCH_TIMEOUT_SEC);

        totalQueries.incrementAndGet();

        // ── OSM-BATCH-FIRST (fast-download fix, phase 2) ──────
        // The whole batch block (max 5x6 tiles = 0.05°x0.06° =
        // 0.003 deg²) fits api.osm.org's 0.25 deg² cap ~80x
        // over. One GET replaces an Overpass query that waits
        // in the busy public queue — measured 86-370s there vs
        // 1.5-7s on the API. The 50k-node cap can 400 on very
        // dense multi-tile blocks; that failure falls straight
        // through to the Overpass path below unchanged.
        String json = fetchViaOsmApi(minLat, minLon,
            maxLat, maxLon);
        if (json != null && json.length() > 40) {
            LOGGER.info("BATCH via api.osm.org "
                + "[{}..{},{}..{}]: one request",
                tLatMin, tLatMax, tLonMin, tLonMax);
        } else {
            json = null;
        }

        // Endpoint order: soonest-available first (same policy
        // as fetchTile).
        int lastGood = endpointIdx.get()
            % OVERPASS_ENDPOINTS.length;
        Integer[] order =
            new Integer[OVERPASS_ENDPOINTS.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            long wa = endpointLimiters[a].msUntilNextToken();
            long wb = endpointLimiters[b].msUntilNextToken();
            if (wa != wb) return Long.compare(wa, wb);
            return Boolean.compare(b == lastGood, a == lastGood);
        });

        for (int attempt = 0;
                attempt < OVERPASS_ENDPOINTS.length && json == null;
                attempt++) {
            int idx = order[attempt];
            OverpassRateLimiter limiter = endpointLimiters[idx];
            HttpURLConnection conn = null;
            boolean inFlight = false;
            try {
                limiter.acquire();
                limiter.acquireInFlight();
                inFlight = true;
                conn = (HttpURLConnection)
                    new URL(OVERPASS_ENDPOINTS[idx])
                        .openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(BATCH_HTTP_READ_MS);
                conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
                conn.setRequestProperty("User-Agent",
                    "TerraPlusPlus/2.0 Minecraft");
                conn.setRequestProperty(
                    "Accept-Encoding", "gzip");
                byte[] body = ("data=" + URLEncoder.encode(
                    queryStr, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
                conn.getOutputStream().write(body);
                conn.getOutputStream().flush();
                int code = conn.getResponseCode();
                if (code == 200) {
                    json = readResponse(conn);
                    endpointIdx.set(idx);
                } else if (code == 429 || code == 504) {
                    limiter.penalize(
                        code == 429 ? 30_000L : 10_000L);
                } else {
                    limiter.penalize(8_000L);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return done;
            } catch (Exception e) {
                limiter.penalize(10_000L);
                LOGGER.debug("Batch fetch failed: {}",
                    e.getMessage());
            } finally {
                net.buildtheearth.terraplusplus.util.HttpKeepAlive.release(conn);
                if (inFlight) limiter.releaseInFlight();
            }
        }
        if (json == null) {
            consecutiveFailures++;
            return done;
        }
        consecutiveFailures = 0;

        // ── Split the batch into per-tile results ──
        try {
            JsonObject root = JsonParser.parseString(json)
                .getAsJsonObject();
            JsonArray elements =
                root.getAsJsonArray("elements");

            int tilesLat = tLatMax - tLatMin + 1;
            int tilesLon = tLonMax - tLonMin + 1;
            int nTiles = tilesLat * tilesLon;
            @SuppressWarnings("unchecked")
            List<OsmStructure>[] perTile =
                new List[nTiles];
            @SuppressWarnings("unchecked")
            List<JsonElement>[] perTileJson =
                new List[nTiles];

            List<ContextPoi> batchPois = new ArrayList<>();
            List<BridgeOutline> batchOutlines = new ArrayList<>();
            if (elements != null) {
                for (JsonElement el : elements) {
                    JsonObject obj = el.getAsJsonObject();
                    ContextPoi poi = contextPoiOf(obj);
                    if (poi != null) {
                        batchPois.add(poi);
                        // The POI element MUST survive into the
                        // per-tile disk subset, or enrichment
                        // silently disappears on disk replay next
                        // session (parseResponse re-derives it
                        // from the raw JSON).
                        int pa = (int) Math.floor(
                            poi.lat / TILE_DEG);
                        int po = (int) Math.floor(
                            poi.lon / TILE_DEG);
                        for (int ta = Math.max(pa - 1, tLatMin);
                                ta <= Math.min(pa + 1, tLatMax);
                                ta++)
                            for (int to = Math.max(po - 1,
                                        tLonMin);
                                    to <= Math.min(po + 1,
                                        tLonMax); to++) {
                                int slot = (ta - tLatMin)
                                    * tilesLon + (to - tLonMin);
                                if (perTileJson[slot] == null) {
                                    perTile[slot] =
                                        new ArrayList<>();
                                    perTileJson[slot] =
                                        new ArrayList<>();
                                }
                                perTileJson[slot].add(el);
                            }
                    }
                    BridgeOutline bo = bridgeOutlineOf(obj);
                    if (bo != null) {
                        batchOutlines.add(bo);
                        // Same disk-replay rule as POIs: write the
                        // outline element into every tile its bbox
                        // touches.
                        int aLo2 = (int) Math.floor(
                            bo.minLat / TILE_DEG);
                        int aHi2 = (int) Math.floor(
                            bo.maxLat / TILE_DEG);
                        int oLo2 = (int) Math.floor(
                            bo.minLon / TILE_DEG);
                        int oHi2 = (int) Math.floor(
                            bo.maxLon / TILE_DEG);
                        for (int ta = Math.max(aLo2, tLatMin);
                                ta <= Math.min(aHi2, tLatMax);
                                ta++)
                            for (int to = Math.max(oLo2, tLonMin);
                                    to <= Math.min(oHi2, tLonMax);
                                    to++) {
                                int slot = (ta - tLatMin)
                                    * tilesLon + (to - tLonMin);
                                if (perTileJson[slot] == null) {
                                    perTile[slot] =
                                        new ArrayList<>();
                                    perTileJson[slot] =
                                        new ArrayList<>();
                                }
                                perTileJson[slot].add(el);
                            }
                    }
                    OsmStructure parsed = parseElement(obj);
                    if (parsed == null) continue;
                    List<OsmStructure> parts =
                        splitRelationOuterParts(obj, parsed);
                    List<OsmStructure> all = parts.isEmpty()
                        ? List.of(parsed) : parts;
                    // assign to every tile the bbox overlaps
                    // (margin matches point-query tolerance)
                    int aLo = (int) Math.floor(
                        (parsed.minLat - OVERLAP_MARGIN)
                            / TILE_DEG);
                    int aHi = (int) Math.floor(
                        (parsed.maxLat + OVERLAP_MARGIN)
                            / TILE_DEG);
                    int oLo = (int) Math.floor(
                        (parsed.minLon - OVERLAP_MARGIN)
                            / TILE_DEG);
                    int oHi = (int) Math.floor(
                        (parsed.maxLon + OVERLAP_MARGIN)
                            / TILE_DEG);
                    for (int ta = Math.max(aLo, tLatMin);
                            ta <= Math.min(aHi, tLatMax); ta++)
                        for (int to = Math.max(oLo, tLonMin);
                                to <= Math.min(oHi, tLonMax);
                                to++) {
                            int slot = (ta - tLatMin) * tilesLon
                                + (to - tLonMin);
                            if (perTile[slot] == null) {
                                perTile[slot] =
                                    new ArrayList<>();
                                perTileJson[slot] =
                                    new ArrayList<>();
                            }
                            perTile[slot].addAll(all);
                            perTileJson[slot].add(el);
                        }
                }
            }

            for (int ta = tLatMin; ta <= tLatMax; ta++) {
                for (int to = tLonMin; to <= tLonMax; to++) {
                    int slot = (ta - tLatMin) * tilesLon
                        + (to - tLonMin);
                    long key = ((long) ta << 32)
                        | (to & 0xFFFFFFFFL);
                    StructureTile tile;
                    if (perTile[slot] == null
                            || perTile[slot].isEmpty()) {
                        tile = StructureTile.EMPTY;
                    } else {
                        enrichFromContext(
                            perTile[slot], batchPois);
                        enrichBridgesFromOutlines(
                            perTile[slot], batchOutlines);
                        tile = new StructureTile(
                            perTile[slot].toArray(
                                new OsmStructure[0]), true);
                    }
                    memCache.put(key, tile);
                    // APPETIZER swap hook: a cold batch tile just
                    // became resident. O(1) no-op when nothing is
                    // provisional (warm start / no appetizers).
                    net.buildtheearth.terraplusplus.generator
                        .historical.ProvisionalServing
                        .notifyTileArrived(
                            ta * TILE_DEG + TILE_DEG / 2,
                            to * TILE_DEG + TILE_DEG / 2);
                    // Disk: store a per-tile Overpass-shaped
                    // JSON subset so loadDisk() replays it
                    // identically on the next cold-ish start.
                    if (diskOk) {
                        JsonObject sub = new JsonObject();
                        JsonArray arr = new JsonArray();
                        if (perTileJson[slot] != null)
                            for (JsonElement e :
                                    perTileJson[slot])
                                arr.add(e);
                        sub.add("elements", arr);
                        saveDisk(key, sub.toString());
                    }
                    CompletableFuture<StructureTile> f =
                        pending.remove(key);
                    if (f != null && !f.isDone())
                        f.complete(tile);
                    done.add(key);
                }
            }
            evictIfNeeded();
            LOGGER.info("BATCH fetch [{}..{},{}..{}]: "
                + "{} tiles in one request",
                tLatMin, tLatMax, tLonMin, tLonMax,
                done.size());
        } catch (Exception e) {
            LOGGER.warn("Batch parse failed: {}",
                e.getMessage());
        }
        return done;
    }

    /**
     * Group missing tile keys into bounded rectangles and fetch
     * each rectangle with one request. Returns keys resolved.
     */
    private Set<Long> fetchMissingAsBatches(
            Set<Long> missingKeys) {
        Set<Long> resolved = new HashSet<>();
        if (missingKeys.isEmpty()) return resolved;

        // Bounding rectangle of all missing tiles.
        int aMin = Integer.MAX_VALUE, aMax = Integer.MIN_VALUE;
        int oMin = Integer.MAX_VALUE, oMax = Integer.MIN_VALUE;
        for (long key : missingKeys) {
            int ta = (int) (key >> 32);
            int to = (int) key;
            aMin = Math.min(aMin, ta); aMax = Math.max(aMax, ta);
            oMin = Math.min(oMin, to); oMax = Math.max(oMax, to);
        }
        // Split the rectangle into <= BATCH_MAX_TILES blocks
        // (blocks of up to 5x6 tiles).
        final int BLK = 5;
        for (int a0 = aMin; a0 <= aMax; a0 += BLK) {
            for (int o0 = oMin; o0 <= oMax; o0 += BLK + 1) {
                int a1 = Math.min(a0 + BLK - 1, aMax);
                int o1 = Math.min(o0 + BLK, oMax);
                // does this block contain any missing tile?
                boolean any = false;
                for (long key : missingKeys) {
                    int ta = (int) (key >> 32);
                    int to = (int) key;
                    if (ta >= a0 && ta <= a1
                            && to >= o0 && to <= o1) {
                        any = true; break;
                    }
                }
                if (!any) continue;
                resolved.addAll(
                    fetchBatch(a0, a1, o0, o1));
            }
        }
        return resolved;
    }

    private StructureTile fetchTile(long key,
            double minLat, double minLon) {

        if (System.currentTimeMillis()
                < pauseUntilMs) {
            pending.remove(key);
            return StructureTile.FAILED;
        }

        // ── OFFICIAL OSM API FIRST (fast-download fix) ────────
        // Measured while diagnosing run 13: all 5 Overpass
        // mirrors were down/erroring (dead, 502, 504, 500@47s)
        // while api.openstreetmap.org served the same bbox in
        // 1.5s with full tags. The API has no query engine to
        // overload — it is a plain bbox dump — so it is both
        // faster and far more reliable. Overpass remains the
        // fallback below. Response is converted to Overpass
        // `out geom` shape so parseResponse/saveDisk/enrichment
        // stay byte-compatible.
        String apiJson = fetchViaOsmApi(minLat, minLon,
            minLat + TILE_DEG, minLon + TILE_DEG);
        if (apiJson != null) {
            StructureTile st = parseResponse(apiJson);
            if (st.valid) {
                consecutiveFailures = 0;
                memCache.put(key, st);
                pending.remove(key);
                evictIfNeeded();
                if (diskOk) saveDisk(key, apiJson);
                if (dbgCount.getAndIncrement() < 20)
                    LOGGER.info(
                        "OSM STRUCT [{},{}]:"
                        + " {} features (api.osm.org)",
                        String.format("%.4f", minLat),
                        String.format("%.4f", minLon),
                        st.structures.length);
                return st;
            }
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

        String queryStr = buildQuery(bbox, 25);

        totalQueries.incrementAndGet();

        // Latency-aware endpoint order: try the endpoint whose rate
        // limiter is ready soonest, tie-broken towards the last
        // successful endpoint. Prevents a cold start from queueing
        // behind an endpoint sitting in a 30s failure cooldown while
        // another endpoint is idle.
        int lastGood = endpointIdx.get()
            % OVERPASS_ENDPOINTS.length;
        Integer[] order =
            new Integer[OVERPASS_ENDPOINTS.length];
        for (int i = 0; i < order.length; i++)
            order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            long wa = endpointLimiters[a]
                .msUntilNextToken();
            long wb = endpointLimiters[b]
                .msUntilNextToken();
            if (wa != wb) return Long.compare(wa, wb);
            // prefer the endpoint that served us last
            return Boolean.compare(
                b == lastGood, a == lastGood);
        });

        for (int attempt = 0;
                attempt < OVERPASS_ENDPOINTS.length;
                attempt++) {
            int idx = order[attempt];
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
                // Dead endpoints must fail fast on a cold start;
                // slow-but-alive responses still get the full window.
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(HTTP_TIMEOUT_MS);
                conn.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-"
                    + "urlencoded");
                conn.setRequestProperty(
                    "User-Agent",
                    "TerraPlusPlus/2.0 Minecraft");
                conn.setRequestProperty(
                    "Accept-Encoding", "gzip");

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
                    // 200 + unparseable body = Overpass busy
                    // page. Retryable, never cached/saved.
                    if (!st.valid) {
                        limiter.penalize(15_000L);
                        continue;
                    }
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
                    // Unexpected status — cool this endpoint briefly
                    // so latency-aware ordering prefers healthy ones.
                    limiter.penalize(8_000L);
                    LOGGER.debug(
                        "Endpoint {} returned {}",
                        idx, code);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending.remove(key);
                return StructureTile.FAILED;
            } catch (Exception e) {
                // Connect/read failure — typically a dead or
                // overloaded endpoint. Penalize so the next tiles
                // do not each burn a connect timeout on it again.
                limiter.penalize(10_000L);
                LOGGER.debug(
                    "Struct fetch failed: {}",
                    e.getMessage());
            } finally {
                net.buildtheearth.terraplusplus.util.HttpKeepAlive.release(conn);
                if (endpointInFlight) limiter.releaseInFlight();
            }
        }

        consecutiveFailures++;
        // health signal: API AND all Overpass endpoints failed
        // for this tile — OSM is definitively struggling.
        osmLastFailAt = System.currentTimeMillis();
        pending.remove(key);
        return StructureTile.FAILED;
    }

    // ── OFFICIAL OSM API (api.openstreetmap.org) ─────────────
    // Primary structure source since the fast-download fix. The
    // /api/0.6/map call returns EVERY element in the bbox (raw
    // nodes + ways with node refs, full tags) in one cheap
    // request — no query engine, so none of Overpass's
    // overload/outage behaviour. Constraints respected here:
    // bbox area well under the 0.25 deg² cap (tiles are 0.01°),
    // 50k-node cap can 400 on very dense multi-tile boxes which
    // is why only single tiles use it; polite spacing enforced.

    // ── OSM HEALTH SIGNAL (Overture demotion) ─────────────────
    // User directive: "If osm is fast there is no need for
    // overture." Rather than deleting the appetizer (it is the
    // only cover when EVERYTHING is down), Overture's per-
    // teleport prefetch is now gated on this signal: while OSM
    // is fast and healthy, no Overture bandwidth is spent at
    // all; the appetizer machinery stays dormant and self-
    // activates only on evidence of OSM struggling.
    private static volatile long osmLastGoodDurationMs = 0;
    private static volatile long osmLastFailAt = 0;

    /** True when recent evidence says live OSM is slow or
     *  failing — the only case Overture still earns its keep. */
    public static boolean osmStruggling() {
        long now = System.currentTimeMillis();
        if (now < osmApiDownUntil) return true;          // API in cooldown
        if (now - osmLastFailAt < 60_000) return true;   // fail in last minute
        return osmLastGoodDurationMs > 3_000;            // last success was slow
    }

    private static final String OSM_API_URL =
        "https://api.openstreetmap.org/api/0.6/map.json?bbox=";
    private static final long OSM_API_SPACING_MS = 300;
    private static final Object OSM_API_GATE = new Object();
    private static long osmApiLastCall = 0;
    /** After repeated API failures, skip it for a while so tiles
     *  fall straight through to Overpass without burning a
     *  connect timeout each. */
    private static volatile long osmApiDownUntil = 0;
    private static final AtomicInteger osmApiFails =
        new AtomicInteger();

    /** Fetches a bbox from the official OSM API and converts it
     *  to Overpass `out geom` JSON (ways get inline geometry,
     *  tagged nodes kept for context-POI enrichment, relations
     *  get member geometry assembled from their ways). Returns
     *  null on any failure — caller falls back to Overpass. */
    private String fetchViaOsmApi(double minLat, double minLon,
            double maxLat, double maxLon) {
        if (System.currentTimeMillis() < osmApiDownUntil)
            return null;
        // polite spacing across all threads
        synchronized (OSM_API_GATE) {
            long wait = osmApiLastCall + OSM_API_SPACING_MS
                - System.currentTimeMillis();
            if (wait > 0) {
                try { Thread.sleep(wait); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            osmApiLastCall = System.currentTimeMillis();
        }
        HttpURLConnection conn = null;
        long fetchStart = System.currentTimeMillis();
        try {
            // API order: left,bottom,right,top (lon,lat)
            String url = OSM_API_URL + String.format(
                "%.6f,%.6f,%.6f,%.6f",
                minLon, minLat, maxLon, maxLat);
            conn = (HttpURLConnection)
                new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent",
                "TerraPlusPlus/2.0 Minecraft");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            int code = conn.getResponseCode();
            if (code != 200) {
                // 400 = node cap / bad bbox, 509 = bandwidth
                // limit; either way count it as a failure.
                if (osmApiFails.incrementAndGet() >= 3)
                    osmApiDownUntil =
                        System.currentTimeMillis() + 120_000L;
                osmLastFailAt = System.currentTimeMillis();
                LOGGER.debug("OSM API returned {}", code);
                return null;
            }
            String raw = readResponse(conn);
            osmApiFails.set(0);
            // health signal: how fast was this real fetch?
            osmLastGoodDurationMs =
                System.currentTimeMillis() - fetchStart;
            return convertApiToOutGeom(raw);
        } catch (Exception e) {
            if (osmApiFails.incrementAndGet() >= 3)
                osmApiDownUntil =
                    System.currentTimeMillis() + 120_000L;
            osmLastFailAt = System.currentTimeMillis();
            LOGGER.debug("OSM API fetch failed: {}",
                e.getMessage());
            return null;
        } finally {
            net.buildtheearth.terraplusplus.util.HttpKeepAlive.release(conn);
        }
    }

    /** API 0.6 map.json -> Overpass `out geom` shape:
     *  - every node's coords indexed; tagged nodes emitted
     *    (context POIs need them);
     *  - ways get a `geometry` array resolved from their node
     *    refs (all refs are guaranteed present in a map call);
     *  - relations get member `geometry` assembled so
     *    multipolygon buildings/bridges keep working. */
    private static String convertApiToOutGeom(String apiJson) {
        JsonObject root = JsonParser.parseString(apiJson)
            .getAsJsonObject();
        JsonArray in = root.getAsJsonArray("elements");
        if (in == null) return null;

        // pass 1: node coordinates + way index
        java.util.HashMap<Long, double[]> nodePos =
            new java.util.HashMap<>();
        java.util.HashMap<Long, JsonObject> waysById =
            new java.util.HashMap<>();
        for (JsonElement el : in) {
            JsonObject o = el.getAsJsonObject();
            String t = o.has("type")
                ? o.get("type").getAsString() : "";
            if ("node".equals(t)) {
                nodePos.put(o.get("id").getAsLong(),
                    new double[]{
                        o.get("lat").getAsDouble(),
                        o.get("lon").getAsDouble()});
            } else if ("way".equals(t)) {
                waysById.put(o.get("id").getAsLong(), o);
            }
        }

        JsonArray out = new JsonArray();
        for (JsonElement el : in) {
            JsonObject o = el.getAsJsonObject();
            String t = o.has("type")
                ? o.get("type").getAsString() : "";
            if ("node".equals(t)) {
                if (o.has("tags")) out.add(o); // context POIs
            } else if ("way".equals(t)) {
                JsonObject w = o.deepCopy();
                JsonArray geom = new JsonArray();
                if (o.has("nodes"))
                    for (JsonElement r
                            : o.getAsJsonArray("nodes")) {
                        double[] p =
                            nodePos.get(r.getAsLong());
                        if (p == null) continue;
                        JsonObject pt = new JsonObject();
                        pt.addProperty("lat", p[0]);
                        pt.addProperty("lon", p[1]);
                        geom.add(pt);
                    }
                w.add("geometry", geom);
                out.add(w);
            } else if ("relation".equals(t)) {
                JsonObject r = o.deepCopy();
                if (o.has("members")) {
                    JsonArray members = new JsonArray();
                    for (JsonElement mEl
                            : o.getAsJsonArray("members")) {
                        JsonObject m = mEl.getAsJsonObject()
                            .deepCopy();
                        if ("way".equals(m.has("type")
                                ? m.get("type").getAsString()
                                : "")
                                && m.has("ref")) {
                            JsonObject w = waysById.get(
                                m.get("ref").getAsLong());
                            if (w != null && w.has("nodes")) {
                                JsonArray geom =
                                    new JsonArray();
                                for (JsonElement nr : w
                                        .getAsJsonArray(
                                            "nodes")) {
                                    double[] p = nodePos.get(
                                        nr.getAsLong());
                                    if (p == null) continue;
                                    JsonObject pt =
                                        new JsonObject();
                                    pt.addProperty(
                                        "lat", p[0]);
                                    pt.addProperty(
                                        "lon", p[1]);
                                    geom.add(pt);
                                }
                                m.add("geometry", geom);
                            }
                        }
                        members.add(m);
                    }
                    r.add("members", members);
                }
                out.add(r);
            }
        }
        JsonObject converted = new JsonObject();
        converted.add("elements", out);
        return converted.toString();
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
            List<ContextPoi> pois = new ArrayList<>();
            List<BridgeOutline> outlines = new ArrayList<>();
            for (JsonElement el : elements) {
                JsonObject obj = el.getAsJsonObject();
                ContextPoi poi = contextPoiOf(obj);
                if (poi != null) pois.add(poi);
                BridgeOutline bo = bridgeOutlineOf(obj);
                if (bo != null) outlines.add(bo);
                OsmStructure s = parseElement(obj);
                if (s == null) continue;
                List<OsmStructure> parts = splitRelationOuterParts(obj, s);
                if (parts.isEmpty()) structures.add(s);
                else structures.addAll(parts);
            }
            int enriched = enrichFromContext(structures, pois);
            if (enriched > 0)
                LOGGER.info("CONTEXT ENRICH: {} buildings "
                    + "inherited identity from {} contained "
                    + "POIs (station/worship/historic)",
                    enriched, pois.size());
            int bEnriched = enrichBridgesFromOutlines(
                structures, outlines);
            if (bEnriched > 0)
                LOGGER.info("BRIDGE OUTLINE ENRICH: {} bridge "
                    + "ways inherited name/structure from {} "
                    + "man_made=bridge outlines",
                    bEnriched, outlines.size());
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
        copy.explicitWidth = b.explicitWidth;
        return copy;
    }

    // ── CONTEXT ENRICHMENT ────────────────────────────────────
    // A structure inherits identity from what is mapped INSIDE it.
    // Way 635428057 is an anonymous {building=transportation,
    // layer=3} box — but it contains node 7052069757
    // {railway=station, name="Secunderabad East",
    // network="Hyderabad Metro", wikidata=Q58814860}. The way's own
    // tags say almost nothing; the contained node says everything.
    // Buildings with no name / generic subtype adopt the name,
    // subtype and start_date of a context POI they contain, which
    // then flows into ALL downstream consumers for free:
    //   - StructureDateEstimator ("station" → SIG_STATION signal)
    //   - WikiDataLookup (a name to resolve → real opening year)
    //   - ParadoxMath ("station" weight 1.6, named ×1.5)
    //   - visibility filtering at time jumps

    /** Point feature that carries identity for its container. */
    private static final class ContextPoi {
        final double lat, lon;
        final String name, subType;
        final int startYear;
        ContextPoi(double lat, double lon, String name,
                String subType, int startYear) {
            this.lat = lat; this.lon = lon;
            this.name = name; this.subType = subType;
            this.startYear = startYear;
        }
    }

    /** man_made=bridge outline: the identity carrier of a major
     *  bridge. Durgam Cheruvu (761604461) holds name, wikidata
     *  and bridge:structure=suspension on the OUTLINE polygon;
     *  the carriageway ways inside carry only bridge=yes+layer.
     *  Never rendered; donates identity to contained bridges. */
    private static final class BridgeOutline {
        final OsmNode[] ring;
        final double minLat, maxLat, minLon, maxLon;
        final String name;
        final BridgeStructure structure;
        final int startYear;
        BridgeOutline(OsmNode[] ring, String name,
                BridgeStructure structure, int startYear) {
            this.ring = ring;
            this.name = name;
            this.structure = structure;
            this.startYear = startYear;
            double mnLa = 90, mxLa = -90,
                   mnLo = 180, mxLo = -180;
            for (OsmNode n : ring) {
                mnLa = Math.min(mnLa, n.lat);
                mxLa = Math.max(mxLa, n.lat);
                mnLo = Math.min(mnLo, n.lon);
                mxLo = Math.max(mxLo, n.lon);
            }
            this.minLat = mnLa; this.maxLat = mxLa;
            this.minLon = mnLo; this.maxLon = mxLo;
        }
        boolean contains(double lat, double lon) {
            if (lat < minLat || lat > maxLat
                    || lon < minLon || lon > maxLon)
                return false;
            return pointInRing(lat, lon, ring);
        }
    }

    /** Parses a man_made=bridge outline, or null. */
    private BridgeOutline bridgeOutlineOf(JsonObject obj) {
        JsonObject tags = obj.has("tags")
            ? obj.getAsJsonObject("tags") : null;
        if (tags == null) return null;
        if (!"bridge".equals(getTag(tags, "man_made")))
            return null;
        List<OsmNode> ring = new ArrayList<>();
        if (obj.has("geometry")) {
            JsonArray geom = obj.getAsJsonArray("geometry");
            for (int i = 0; i < geom.size(); i++) {
                JsonObject g = geom.get(i).getAsJsonObject();
                if (g.has("lat") && g.has("lon"))
                    ring.add(new OsmNode(
                        g.get("lat").getAsDouble(),
                        g.get("lon").getAsDouble()));
            }
        } else if (obj.has("members")) {
            for (JsonElement m : obj.getAsJsonArray("members")) {
                JsonObject mObj = m.getAsJsonObject();
                String role = mObj.has("role")
                    ? mObj.get("role").getAsString() : "";
                if (!("outer".equals(role) || role.isEmpty())
                        || !mObj.has("geometry")) continue;
                for (JsonElement p : mObj.getAsJsonArray("geometry")) {
                    JsonObject g = p.getAsJsonObject();
                    if (g.has("lat") && g.has("lon"))
                        ring.add(new OsmNode(
                            g.get("lat").getAsDouble(),
                            g.get("lon").getAsDouble()));
                }
            }
        }
        if (ring.size() < 3) return null;
        // structure type: bridge:structure preferred, else the
        // (unusual but observed) bridge:structure value on the
        // bridge tag itself
        BridgeStructure bs = null;
        String structTag = getTag(tags, "bridge:structure");
        if (structTag != null)
            bs = BridgeStructure.fromStructureTag(structTag);
        return new BridgeOutline(
            ring.toArray(new OsmNode[0]),
            getTag(tags, "name"),
            bs,
            HistoricalDateParser.parse(
                getTag(tags, "start_date")));
    }

    /** Contained bridge ways inherit name/structure/date from a
     *  man_made=bridge outline covering them. Midpoint decides
     *  containment (a carriageway may poke past the outline at
     *  the abutments). */
    private static int enrichBridgesFromOutlines(
            List<OsmStructure> structures,
            List<BridgeOutline> outlines) {
        if (outlines.isEmpty()) return 0;
        int enriched = 0;
        for (int i = 0; i < structures.size(); i++) {
            OsmStructure s = structures.get(i);
            if (s.type != StructureType.BRIDGE) continue;
            if (s.nodes.length < 2) continue;
            OsmNode mid = s.nodes[s.nodes.length / 2];
            for (BridgeOutline o : outlines) {
                if (!o.contains(mid.lat, mid.lon)) continue;
                String newName = (s.name == null
                        || s.name.isBlank())
                    && o.name != null ? o.name : s.name;
                BridgeStructure newBs =
                    (s.bridgeStructure == null
                        || s.bridgeStructure
                            == BridgeStructure.BEAM)
                    && o.structure != null
                    ? o.structure : s.bridgeStructure;
                int newStart = s.startYear
                        == HistoricalDateParser.UNKNOWN_YEAR
                        && o.startYear
                        != HistoricalDateParser.UNKNOWN_YEAR
                    ? o.startYear : s.startYear;
                boolean changed =
                    !Objects.equals(newName, s.name)
                    || newBs != s.bridgeStructure
                    || newStart != s.startYear;
                if (!changed) continue;
                OsmStructure copy = new OsmStructure(
                    s.type, s.subType, newName, s.nodes,
                    s.isArea, s.osmId, s.material,
                    s.levels, s.height, s.roofShape,
                    s.roofMaterial, s.surface, s.lanes,
                    s.isOneWay, s.minHeight, s.roofHeight,
                    s.roofDirection, s.colour, s.roofColour,
                    s.isPart, s.parentOsmId, s.layer,
                    s.tunnel, s.isRailBridge,
                    newBs, newStart, s.endYear);
                copy.innerRings = s.innerRings;
                copy.explicitWidth = s.explicitWidth;
                structures.set(i, copy);
                enriched++;
                break;
            }
        }
        return enriched;
    }

    /** Building subtypes vague enough to be overridden by a
     *  contained POI's more specific identity. */
    private static final Set<String> GENERIC_BUILDING_SUBTYPES =
        Set.of("yes", "transportation", "public", "roof");

    /** bridge=* lifecycle values: the bridge does not physically
     *  exist right now (taginfo worldwide counts in comment at
     *  the usage site). Distinct from abandoned/ruins, which
     *  still stand and are rendered. */
    private static final Set<String> LIFECYCLE_BRIDGE_VALUES =
        Set.of("razed", "construction", "proposed",
            "collapsed", "dismantled", "demolished",
            "destroyed", "removed");

    /** Parses a context POI node, or null if this element is not
     *  one. These nodes never render directly (parseElement
     *  returns null for them); they exist to donate identity.
     *  Instance method: getTag() is an instance member, and this
     *  is only called from parseResponse()/fetchBatch() anyway. */
    private ContextPoi contextPoiOf(JsonObject obj) {
        if (!"node".equals(obj.has("type")
                ? obj.get("type").getAsString() : ""))
            return null;
        if (!obj.has("lat") || !obj.has("lon")) return null;
        JsonObject tags = obj.has("tags")
            ? obj.getAsJsonObject("tags") : null;
        if (tags == null) return null;

        String railway  = getTag(tags, "railway");
        String pt       = getTag(tags, "public_transport");
        String amenity  = getTag(tags, "amenity");
        String historic = getTag(tags, "historic");

        String sub = null;
        if ("station".equals(railway) || "halt".equals(railway)
                || "station".equals(pt)) {
            sub = "station";
        } else if ("place_of_worship".equals(amenity)) {
            String rel = getTag(tags, "religion");
            sub = rel == null ? "church" : switch (rel) {
                case "christian"           -> "church";
                case "muslim"              -> "mosque";
                case "jewish"              -> "synagogue";
                case "hindu", "buddhist",
                     "jain", "sikh"        -> "temple";
                default                    -> "temple";
            };
        } else if (historic != null) {
            sub = switch (historic) {
                case "monument", "memorial" -> "monument";
                case "castle", "fort"       -> "castle";
                case "ruins"                -> "monument";
                default                     -> null;
            };
        }
        if (sub == null) return null;

        return new ContextPoi(
            obj.get("lat").getAsDouble(),
            obj.get("lon").getAsDouble(),
            getTag(tags, "name"),
            sub,
            HistoricalDateParser.parse(
                getTag(tags, "start_date")));
    }

    /** Lets anonymous/generic buildings inherit name, subtype and
     *  start_date from a context POI they geometrically contain.
     *  Replaces entries in-place; returns how many inherited. */
    private static int enrichFromContext(
            List<OsmStructure> structures,
            List<ContextPoi> pois) {
        if (pois.isEmpty()) return 0;
        int enriched = 0;
        for (int i = 0; i < structures.size(); i++) {
            OsmStructure s = structures.get(i);
            if (s.type != StructureType.BUILDING
                    || !s.isArea) continue;
            boolean nameless = s.name == null
                || s.name.isBlank();
            boolean genericSub = s.subType == null
                || GENERIC_BUILDING_SUBTYPES
                    .contains(s.subType);
            if (!nameless && !genericSub) continue;
            for (ContextPoi p : pois) {
                if (!s.containsPoint(p.lat, p.lon)) continue;
                String newName = (nameless && p.name != null)
                    ? p.name : s.name;
                String newSub = (genericSub
                        && p.subType != null)
                    ? p.subType : s.subType;
                int newStart = (s.startYear
                        == HistoricalDateParser.UNKNOWN_YEAR
                        && p.startYear
                        != HistoricalDateParser.UNKNOWN_YEAR)
                    ? p.startYear : s.startYear;
                boolean changed =
                    !Objects.equals(newName, s.name)
                    || !Objects.equals(newSub, s.subType)
                    || newStart != s.startYear;
                if (!changed) continue;
                OsmStructure copy = new OsmStructure(
                    s.type, newSub, newName, s.nodes,
                    s.isArea, s.osmId, s.material,
                    s.levels, s.height, s.roofShape,
                    s.roofMaterial, s.surface, s.lanes,
                    s.isOneWay, s.minHeight, s.roofHeight,
                    s.roofDirection, s.colour, s.roofColour,
                    s.isPart, s.parentOsmId, s.layer,
                    s.tunnel, s.isRailBridge,
                    s.bridgeStructure, newStart, s.endYear);
                copy.innerRings = s.innerRings;
                copy.explicitWidth = s.explicitWidth;
                structures.set(i, copy);
                enriched++;
                break;
            }
        }
        return enriched;
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
                bridgeTagValue)
            // TAGINFO AUDIT: lifecycle values mean the bridge
            // does NOT physically exist today (~1000 ways
            // worldwide: razed=339, construction=437,
            // proposed=127, collapsed/dismantled ~94). They were
            // rendered as real bridges. abandoned(1523)/ruins
            // still stand physically -> still rendered.
            && !LIFECYCLE_BRIDGE_VALUES.contains(
                bridgeTagValue.toLowerCase().trim());

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
            if ("platform".equals(railway)) {
                type = StructureType.PLATFORM;
                subType = railway;
                isArea = "yes".equals(
                    getTag(tags, "area"))
                    || getTag(tags, "building") != null;
            } else if (railway != null) {
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
                         "vineyard", "farmyard",
                         "allotments",
                         "plant_nursery" -> {
                        type = StructureType.FARMLAND;
                        subType = landuse;
                    }
                    case "forest" -> {
                        type = StructureType.FOREST;
                        subType = "forest";
                    }
                    case "meadow", "grass",
                         "village_green",
                         "recreation_ground",
                         "cemetery",
                         "flowerbed" -> {
                        type = StructureType.MEADOW;
                        subType = landuse;
                    }
                    case "parking" -> {
                        type = StructureType.PARKING;
                        subType = "parking";
                    }
                    case "quarry" -> {
                        type = StructureType.ROCK;
                        subType = "quarry";
                    }
                    default -> {
                        type = StructureType.MEADOW;
                        subType = landuse;
                    }
                }
            }
        }

        if (type == null) {
            String natural =
                getTag(tags, "natural");
            if (natural != null) {
                switch (natural) {
                    case "wood" -> {
                        type = StructureType.FOREST;
                        subType = "wood";
                        isArea = true;
                    }
                    case "scrub", "heath",
                         "grassland" -> {
                        type = StructureType.MEADOW;
                        subType = natural;
                        isArea = true;
                    }
                    case "beach", "sand" -> {
                        type = StructureType.SAND;
                        subType = natural;
                        isArea = true;
                    }
                    case "bare_rock", "scree",
                         "shingle" -> {
                        type = StructureType.ROCK;
                        subType = natural;
                        isArea = true;
                    }
                    case "tree_row" -> {
                        type = StructureType.TREE_ROW;
                        subType = natural;
                    }
                    case "tree" -> {
                        type = StructureType.TREE;
                        subType = natural;
                    }
                    default -> { /* unhandled natural value */ }
                }
            }
        }

        if (type == null) {
            String manMade =
                getTag(tags, "man_made");
            if (manMade != null) {
                switch (manMade) {
                    case "pier", "breakwater",
                         "groyne", "embankment",
                         "dyke" -> {
                        type = StructureType.PIER;
                        subType = manMade;
                        // piers can be mapped as areas
                        isArea = "yes".equals(
                            getTag(tags, "area"));
                    }
                    case "tower", "chimney",
                         "water_tower",
                         "lighthouse", "mast",
                         "flagpole",
                         "communications_tower",
                         "storage_tank",
                         "silo" -> {
                        type = StructureType.TOWER;
                        subType = manMade;
                    }
                    default -> { /* unhandled man_made value */ }
                }
            }
        }

        if (type == null) {
            String aeroway =
                getTag(tags, "aeroway");
            if (aeroway != null) {
                switch (aeroway) {
                    case "runway", "taxiway" -> {
                        type = StructureType.RUNWAY;
                        subType = aeroway;
                    }
                    case "apron", "helipad" -> {
                        type = StructureType.APRON;
                        subType = aeroway;
                        isArea = true;
                    }
                    default -> { /* unhandled aeroway value */ }
                }
            }
        }

        if (type == null) {
            String ww = getTag(tags, "waterway");
            if ("dam".equals(ww)
                    || "weir".equals(ww)) {
                type = StructureType.PIER;
                subType = ww;
                isArea = "yes".equals(
                    getTag(tags, "area"));
            }
        }

        if (type == null) {
            String power = getTag(tags, "power");
            if ("tower".equals(power)
                    || "pole".equals(power)) {
                type = StructureType.PYLON;
                subType = power;
            }
        }

        if (type == null) {
            String leisure =
                getTag(tags, "leisure");
            if (leisure != null) {
                isArea = true;
                switch (leisure) {
                    case "park", "playground",
                         "pitch", "dog_park",
                         "golf_course",
                         "recreation_ground",
                         "nature_reserve",
                         "track" -> {
                        type = StructureType.PARK;
                        subType = leisure;
                    }
                    case "garden" -> {
                        type = StructureType.GARDEN;
                        subType = "garden";
                    }
                    case "swimming_pool" -> {
                        type = StructureType.POOL;
                        subType = "swimming_pool";
                    }
                    default -> {
                        type = StructureType.GARDEN;
                        subType = leisure;
                    }
                }
            }
        }

        if (type == null) {
            String amenity =
                getTag(tags, "amenity");
            if (amenity != null) {
                switch (amenity) {
                    case "parking" -> {
                        type = StructureType.PARKING;
                        subType = "parking";
                        isArea = true;
                    }
                    case "marketplace" -> {
                        type = StructureType.PARK;
                        subType = "marketplace";
                        isArea = true;
                    }
                    case "grave_yard" -> {
                        type = StructureType.MEADOW;
                        subType = "grave_yard";
                        isArea = true;
                    }
                    default -> { /* unhandled amenity value */ }
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
        // Point features are valid with a single node.
        boolean pointFeature =
            type == StructureType.STREET_LAMP
            || type == StructureType.TREE
            || type == StructureType.TOWER
            || type == StructureType.PYLON;
        if (!pointFeature && nodeList.size() < 2)
            return null;

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
        // Mappers' explicit width=* beats any per-highway-type guess.
        String widthStr = getTag(tags, "width");
        if (widthStr != null) try {
            parsed.explicitWidth = Double.parseDouble(
                widthStr.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException ignored) {}
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
        InputStream raw = conn.getInputStream();
        // Overpass responses gzip ~10-15x; decompress transparently
        // when the server honoured Accept-Encoding.
        if ("gzip".equalsIgnoreCase(
                conn.getContentEncoding()))
            raw = new GZIPInputStream(raw, 8192);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(raw,
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