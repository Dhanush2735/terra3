// ============================================================
// FILE: OsmWaterIndex.java
// CHANGES:
//   - Replaced synchronized(rateLock)+sleep with
//     OverpassRateLimiter (one instance per endpoint)
//   - Disk cache check moved before thread pool submission
//     in getTileBlocking() and triggerFetch() — disk hits
//     never consume a rate limit slot or thread pool slot
//   - DL_THREADS increased from 2 to 4
//   - pending map cleaned up on timeout in getTileBlocking()
//   - fetchTile() no longer does disk check (done before
//     submission) — only network fetch + parse + save
//   - All prior fixes retained
// ============================================================
package net.buildtheearth.terraplusplus.generator.water;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class OsmWaterIndex {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Water/OSM");

    // ── CONSTANTS ─────────────────────────────────────────────
    private static final double TILE_DEG        = 0.05;
    private static final int    MEM_CACHE_MAX   = 512;
    private static final long   DISK_TTL_MS     =
        14L * 24 * 60 * 60 * 1000;
    private static final int    HTTP_TIMEOUT_MS = 20000;
    private static final long   TILE_WAIT_MS    = 500;
    private static final int    DL_THREADS      = 4; // increased from 2
    private static final int    MAX_CONSECUTIVE_FAILURES = 5;
    // Source-wide pause is temporary: public Overpass outages must never
    // permanently disable OSM water for the rest of a play session.
    private static final long   PAUSE_ON_FAILURES_MS = 60_000L;
    // A 4xx response generally means this particular mirror rejects our query.
    // Quarantine only that mirror and continue with the other endpoints.
    private static final long   ENDPOINT_4XX_PAUSE_MS = 5 * 60_000L;
    private static final double COASTLINE_BAND_M = 5.0;

    private static final String[] OVERPASS_ENDPOINTS = {
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    };

    // ── RATE LIMITERS ─────────────────────────────────────────
    // One token bucket per endpoint — allows parallel fetches
    // to different endpoints without any serialization.
    // Replaces the old synchronized(rateLock)+sleep pattern.
    private final OverpassRateLimiter[] endpointLimiters;

    // ── COASTLINE SEGMENT ─────────────────────────────────────

    public static class CoastlineSegment {
        public final double aLat, aLon, bLat, bLon;
        final double dLat, dLon, len2;
        final double minLat, maxLat, minLon, maxLon;

        public CoastlineSegment(double aLat, double aLon,
                double bLat, double bLon) {
            this.aLat = aLat; this.aLon = aLon;
            this.bLat = bLat; this.bLon = bLon;
            this.dLat = bLat - aLat;
            this.dLon = bLon - aLon;
            this.len2 = dLat * dLat + dLon * dLon;
            this.minLat = Math.min(aLat, bLat);
            this.maxLat = Math.max(aLat, bLat);
            this.minLon = Math.min(aLon, bLon);
            this.maxLon = Math.max(aLon, bLon);
        }

        public double signedDistMeters(double pLat,
                double pLon) {
            if (len2 < 1e-20)
                return ptDistM(pLat, pLon, aLat, aLon);
            double t = Math.max(0, Math.min(1,
                ((pLat - aLat) * dLat
                    + (pLon - aLon) * dLon) / len2));
            double dist = ptDistM(pLat, pLon,
                aLat + t * dLat, aLon + t * dLon);
            double cross =
                dLat * (pLon - aLon)
                - dLon * (pLat - aLat);
            return cross <= 0 ? dist : -dist;
        }

        public boolean mightBeNear(double lat,
                double lon, double m) {
            return lat >= minLat - m
                && lat <= maxLat + m
                && lon >= minLon - m
                && lon <= maxLon + m;
        }

        private static double ptDistM(double lat1,
                double lon1, double lat2, double lon2) {
            double mLat = 111320.0;
            double mLon = 111320.0 * Math.cos(
                Math.toRadians((lat1 + lat2) / 2.0));
            double dl = (lat2 - lat1) * mLat;
            double dn = (lon2 - lon1) * mLon;
            return Math.sqrt(dl * dl + dn * dn);
        }
    }

    public enum CoastSide {
        LAND, COASTLINE, OCEAN, UNKNOWN
    }

    // ── WATER FEATURE ─────────────────────────────────────────

    public static class WaterFeature {
        public final WaterBodyInfo.WaterType type;
        public final String name;
        public final double[][] coords;
        public final boolean isArea;
        public final int osmWidth;
        public final long osmId;
        public final double[][][] innerRings;
        public final double minLat, maxLat,
                            minLon, maxLon;

        public WaterFeature(WaterBodyInfo.WaterType type,
                String name, double[][] coords,
                boolean isArea, int osmWidth,
                long osmId, double[][][] innerRings) {
            this.type       = type;
            this.name       = name;
            this.coords     = coords;
            this.isArea     = isArea;
            this.osmWidth   = osmWidth;
            this.osmId      = osmId;
            this.innerRings = innerRings;
            double mnLat = Double.MAX_VALUE,
                   mxLat = -Double.MAX_VALUE,
                   mnLon = Double.MAX_VALUE,
                   mxLon = -Double.MAX_VALUE;
            for (double[] c : coords) {
                if (c[0] < mnLat) mnLat = c[0];
                if (c[0] > mxLat) mxLat = c[0];
                if (c[1] < mnLon) mnLon = c[1];
                if (c[1] > mxLon) mxLon = c[1];
            }
            this.minLat = mnLat; this.maxLat = mxLat;
            this.minLon = mnLon; this.maxLon = mxLon;
        }

        public WaterFeature(WaterBodyInfo.WaterType type,
                String name, double[][] coords,
                boolean isArea, int osmWidth, long osmId) {
            this(type, name, coords, isArea, osmWidth,
                osmId, new double[0][][]);
        }

        public int widthMeters() {
            return osmWidth > 0
                ? osmWidth
                : WaterBodyInfo.defaultWidth(type);
        }

        public boolean isInsideIsland(
                double lat, double lon) {
            if (innerRings == null
                    || innerRings.length == 0)
                return false;
            for (double[][] ring : innerRings)
                if (ring.length >= 3
                        && pointInPolygon(lat, lon, ring))
                    return true;
            return false;
        }

        public double getInsetMeters() {
            return WaterBodyInfo.polygonInsetMeters(type);
        }

        public double getInnerShoreBuffer() {
            return WaterBodyInfo.innerShoreBuffer(type);
        }

        public double distanceMeters(
                double lat, double lon) {
            double marginDeg =
                (widthMeters() / 2.0 + 100.0) / 111320.0;
            if (lat < minLat - marginDeg
                    || lat > maxLat + marginDeg
                    || lon < minLon - marginDeg
                    || lon > maxLon + marginDeg)
                return Double.MAX_VALUE;
            if (isArea && coords.length >= 3
                    && pointInPolygon(lat, lon, coords)) {
                if (isInsideIsland(lat, lon))
                    return distToNearestIslandEdge(
                        lat, lon) + widthMeters();
                double edgeDist =
                    edgeDistMeters(lat, lon);
                double inset = getInsetMeters();
                if (edgeDist < inset)
                    return inset - edgeDist;
                return 0;
            }
            double minD2 = Double.MAX_VALUE;
            int segCount = isArea && coords.length >= 3
                ? coords.length
                : coords.length - 1;
            for (int i = 0; i < segCount; i++) {
                int j = (i + 1) % coords.length;
                double d2 = ptSeg2(lat, lon,
                    coords[i][0], coords[i][1],
                    coords[j][0], coords[j][1]);
                if (d2 < minD2) minD2 = d2;
            }
            return degToMeters(Math.sqrt(minD2), lat);
        }

        private double distToLine(
                double lat, double lon) {
            double minD2 = Double.MAX_VALUE;
            for (int i = 0; i < coords.length - 1; i++) {
                double d2 = ptSeg2(lat, lon,
                    coords[i][0], coords[i][1],
                    coords[i+1][0], coords[i+1][1]);
                if (d2 < minD2) minD2 = d2;
            }
            return degToMeters(Math.sqrt(minD2), lat);
        }

        public double distanceToWater(
                double lat, double lon) {
            if (!isArea || coords.length < 3)
                return distanceMeters(lat, lon);
            if (pointInPolygon(lat, lon, coords)) {
                if (isInsideIsland(lat, lon))
                    return distToNearestIslandEdge(
                        lat, lon);
                double edgeDist =
                    edgeDistMeters(lat, lon);
                double inset = getInsetMeters();
                if (edgeDist < inset)
                    return inset - edgeDist;
                return 0;
            }
            return distanceMeters(lat, lon);
        }

        private double distToNearestIslandEdge(
                double lat, double lon) {
            double minDist = Double.MAX_VALUE;
            for (double[][] ring : innerRings) {
                if (ring.length < 3) continue;
                for (int i = 0; i < ring.length; i++) {
                    int j = (i + 1) % ring.length;
                    double d = degToMeters(Math.sqrt(
                        ptSeg2(lat, lon,
                            ring[i][0], ring[i][1],
                            ring[j][0], ring[j][1])),
                        lat);
                    if (d < minDist) minDist = d;
                }
            }
            return minDist;
        }

        public double edgeFactor(
                double lat, double lon) {
            return isArea
                ? areaEdgeFactor(lat, lon)
                : lineEdgeFactor(lat, lon);
        }

        private double lineEdgeFactor(
                double lat, double lon) {
            double dist = distToLine(lat, lon);
            double halfW = widthMeters() / 2.0;
            if (halfW < 1) return 0.5;
            double raw = dist / halfW;
            double deadZone = 0.3;
            if (raw <= deadZone) return 0.0;
            return Math.min(1.0,
                (raw - deadZone) / (1.0 - deadZone));
        }

        private double areaEdgeFactor(
                double lat, double lon) {
            if (coords.length < 3
                    || !pointInPolygon(lat, lon, coords))
                return 1.0;
            if (isInsideIsland(lat, lon)) return 1.0;
            double edgeDist = edgeDistMeters(lat, lon);
            double inset = getInsetMeters();
            if (edgeDist < inset) return 1.0;
            double effectiveDist = edgeDist - inset;
            double islandDist =
                distToNearestIslandEdge(lat, lon);
            if (islandDist < Double.MAX_VALUE)
                effectiveDist = Math.min(
                    effectiveDist, islandDist);
            double shoreBuffer = getInnerShoreBuffer();
            if (effectiveDist <= shoreBuffer) {
                double t = effectiveDist / shoreBuffer;
                t = t * t * (3.0 - 2.0 * t);
                return 1.0 - t;
            }
            double latSpan =
                (maxLat - minLat) * 111320.0;
            double lonSpan = (maxLon - minLon)
                * degToMeters(1.0,
                    (minLat + maxLat) / 2);
            double maxInset =
                Math.min(latSpan, lonSpan) / 2.0;
            if (maxInset < shoreBuffer + 1)
                return 0.0;
            double deepFraction = Math.min(1.0,
                (effectiveDist - shoreBuffer)
                / (maxInset - shoreBuffer));
            return Math.max(0.0,
                0.15 * (1.0 - deepFraction));
        }

        public double flowDirection(
                double lat, double lon) {
            if (coords.length < 2) return 0;
            double minD = Double.MAX_VALUE;
            int bestIdx = 0;
            for (int i = 0; i < coords.length - 1; i++) {
                double d = ptSeg2(lat, lon,
                    coords[i][0], coords[i][1],
                    coords[i+1][0], coords[i+1][1]);
                if (d < minD) { minD = d; bestIdx = i; }
            }
            return Math.atan2(
                coords[bestIdx+1][1] - coords[bestIdx][1],
                coords[bestIdx+1][0] - coords[bestIdx][0]);
        }

        public double edgeDistMeters(
                double lat, double lon) {
            double minD2 = Double.MAX_VALUE;
            for (int i = 0; i < coords.length; i++) {
                int j = (i + 1) % coords.length;
                double d2 = ptSeg2(lat, lon,
                    coords[i][0], coords[i][1],
                    coords[j][0], coords[j][1]);
                if (d2 < minD2) minD2 = d2;
            }
            return degToMeters(Math.sqrt(minD2), lat);
        }

        public static boolean pointInPolygon(double lat,
                double lon, double[][] ring) {
            boolean inside = false;
            for (int i = 0, j = ring.length - 1;
                    i < ring.length; j = i++)
                if (((ring[i][0] > lat)
                            != (ring[j][0] > lat))
                        && (lon < (ring[j][1]
                            - ring[i][1])
                            * (lat - ring[i][0])
                            / (ring[j][0] - ring[i][0])
                            + ring[i][1]))
                    inside = !inside;
            return inside;
        }

        public static double ptSeg2(double px, double py,
                double ax, double ay,
                double bx, double by) {
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            if (len2 < 1e-20) {
                double ex = px - ax, ey = py - ay;
                return ex * ex + ey * ey;
            }
            double t = Math.max(0, Math.min(1,
                ((px - ax) * dx
                    + (py - ay) * dy) / len2));
            double cx2 = ax + t * dx - px;
            double cy2 = ay + t * dy - py;
            return cx2 * cx2 + cy2 * cy2;
        }

        private static double degToMeters(
                double degDist, double lat) {
            return degDist * (111320.0
                + 111320.0 * Math.cos(
                    Math.toRadians(lat))) / 2.0;
        }
    }

    // ── WATER TILE ────────────────────────────────────────────

    public static class WaterTile {
        public final WaterFeature[] features;
        public final CoastlineSegment[] coastline;
        public final boolean valid;
        public final long fetchTimeMs;

        public WaterTile(WaterFeature[] features,
                CoastlineSegment[] coastline,
                boolean valid) {
            this.features    = features;
            this.coastline   = coastline;
            this.valid       = valid;
            this.fetchTimeMs =
                System.currentTimeMillis();
        }

        public boolean hasCoastline() {
            return coastline != null
                && coastline.length > 0;
        }

        public static final WaterTile EMPTY =
            new WaterTile(
                new WaterFeature[0],
                new CoastlineSegment[0], true);
        public static final WaterTile FAILED =
            new WaterTile(
                new WaterFeature[0],
                new CoastlineSegment[0], false);
    }

    // ── FIELDS ────────────────────────────────────────────────

    private final ConcurrentHashMap<Long, WaterTile>
        memCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long,
        CompletableFuture<WaterTile>>
        pending = new ConcurrentHashMap<>();
    private final ExecutorService dlPool;
    private final AtomicInteger endpointIdx =
        new AtomicInteger(0);
    private final AtomicInteger totalQueries =
        new AtomicInteger(0);
    private final AtomicInteger dbgCount =
        new AtomicInteger(0);
    private final Path diskDir;
    private final boolean diskOk;
    private volatile boolean enabled = true;
    /** Ensures the temporary-pause banner is emitted once per outage. */
    private final java.util.concurrent.atomic.AtomicBoolean pauseLogged =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private final long[] endpointPauseUntil;
    private volatile int consecutiveFailures = 0;
    private volatile long pauseUntilMs = 0;

    // ── CONSTRUCTOR ───────────────────────────────────────────

    public OsmWaterIndex() {
        // One rate limiter per endpoint — parallel fetches
        // to different endpoints never block each other
        this.endpointLimiters =
            new OverpassRateLimiter[
                OVERPASS_ENDPOINTS.length];
        this.endpointPauseUntil = new long[OVERPASS_ENDPOINTS.length];
        for (int i = 0;
                i < OVERPASS_ENDPOINTS.length; i++) {
            String epName = epName(
                OVERPASS_ENDPOINTS[i]);
            endpointLimiters[i] =
                OverpassRateLimiter
                    .forOsmEndpoint(epName);
        }

        this.dlPool = Executors.newFixedThreadPool(
            DL_THREADS, r -> {
                Thread t = new Thread(r,
                    "Terra++OSM-Water");
                t.setDaemon(true);
                return t;
            });

        Path dir = net.buildtheearth.terraplusplus.util.TerraCachePaths.dir("osm_water");
        boolean ok = false;
        try {
            Files.createDirectories(dir);
            ok = true;
        } catch (Exception e) {
            LOGGER.warn("OSM disk cache failed: {}",
                e.getMessage());
        }
        this.diskDir = dir;
        this.diskOk  = ok;

        LOGGER.info("OsmWaterIndex init "
            + "(unified water+coastline, "
            + "disk={}, endpoints={}, "
            + "threads={})",
            ok, OVERPASS_ENDPOINTS.length,
            DL_THREADS);
    }

    // ── COASTLINE CLASSIFICATION ──────────────────────────────

    public CoastSide classifyCoastline(
            double lat, double lon) {
        if (!enabled) return CoastSide.UNKNOWN;
        if (lat <= -85 || lat >= 85)
            return CoastSide.OCEAN;

        double bestDist  = Double.MAX_VALUE;
        double bestSigned = 0;
        boolean anyLoaded = false;
        boolean anyCoast  = false;

        for (int dlat = -1; dlat <= 1; dlat++)
            for (int dlon = -1; dlon <= 1; dlon++) {
                long key = tileKey(
                    lat + dlat * TILE_DEG,
                    lon + dlon * TILE_DEG);
                WaterTile tile = memCache.get(key);
                if (tile == null || !tile.valid)
                    continue;
                anyLoaded = true;
                if (!tile.hasCoastline()) continue;
                anyCoast = true;
                for (CoastlineSegment seg :
                        tile.coastline) {
                    if (!seg.mightBeNear(lat, lon, 0.5))
                        continue;
                    double signed =
                        seg.signedDistMeters(lat, lon);
                    double dist = Math.abs(signed);
                    if (dist < bestDist) {
                        bestDist   = dist;
                        bestSigned = signed;
                    }
                }
            }

        if (!anyLoaded || !anyCoast)
            return CoastSide.UNKNOWN;
        if (bestDist == Double.MAX_VALUE)
            return CoastSide.UNKNOWN;
        if (bestDist <= COASTLINE_BAND_M)
            return CoastSide.COASTLINE;
        return bestSigned >= 0
            ? CoastSide.LAND : CoastSide.OCEAN;
    }

    public CoastSide classifyCoastlineMemOnly(
            double lat, double lon) {
        return classifyCoastline(lat, lon);
    }

    // ── PUBLIC API ────────────────────────────────────────────

    public WaterBodyInfo query(double lat, double lon,
            double terrainElev) {
        if (!enabled || lat <= -85 || lat >= 85)
            return WaterBodyInfo.NONE;
        WaterTile tile = getTile(lat, lon);
        if (tile == null || !tile.valid)
            return WaterBodyInfo.NONE;
        if (tile.features.length == 0
                && !tile.hasCoastline())
            return WaterBodyInfo.NONE;
        return findBestMatch(tile, lat, lon,
            terrainElev);
    }

    public WaterBodyInfo queryMemOnly(double lat,
            double lon, double terrainElev) {
        if (!enabled || lat <= -85 || lat >= 85)
            return null;
        long key = tileKey(lat, lon);
        WaterTile tile = memCache.get(key);
        if (tile == null) return null;
        if (!tile.valid) return WaterBodyInfo.NONE;
        if (tile.features.length == 0
                && !tile.hasCoastline())
            return WaterBodyInfo.NONE;
        return findBestMatch(tile, lat, lon,
            terrainElev);
    }

    public boolean isConfirmedWater(
            double lat, double lon) {
        if (!enabled || lat <= -85 || lat >= 85)
            return false;
        WaterTile tile =
            memCache.get(tileKey(lat, lon));
        if (tile == null || !tile.valid
                || tile.features.length == 0)
            return false;
        for (WaterFeature f : tile.features) {
            if (!f.isArea) continue;
            if (f.type == WaterBodyInfo.WaterType.POND
                    || f.type ==
                        WaterBodyInfo.WaterType.WETLAND)
                continue;
            double margin = 0.001;
            if (lat < f.minLat - margin
                    || lat > f.maxLat + margin
                    || lon < f.minLon - margin
                    || lon > f.maxLon + margin)
                continue;
            if (f.coords.length >= 3
                    && WaterFeature.pointInPolygon(
                        lat, lon, f.coords)
                    && !f.isInsideIsland(lat, lon))
                return true;
        }
        return false;
    }

    /**
     * Blocking fetch — waits up to maxWaitMs for tile.
     *
     * Disk cache is checked HERE before submitting to
     * the thread pool — disk hits never consume a rate
     * limit slot or a thread pool thread.
     */
    public boolean getTileBlocking(double lat, double lon,
            long maxWaitMs) {
        if (!enabled || lat <= -85 || lat >= 85)
            return false;

        long key = tileKey(lat, lon);

        // Fast path — already in memory
        WaterTile tile = memCache.get(key);
        if (tile != null) return tile.valid;

        // ── DISK CHECK BEFORE THREAD POOL ─────────────
        // If on disk, load synchronously here.
        // Zero cost to rate limiter or thread pool.
        if (diskOk) {
            WaterTile disk = loadDisk(key);
            if (disk != null) {
                memCache.put(key, disk);
                // Cancel any pending future for this key
                // since we already have the data
                CompletableFuture<WaterTile> pf =
                    pending.remove(key);
                if (pf != null) pf.cancel(false);
                return disk.valid;
            }
        }

        // Not on disk — submit to thread pool
        CompletableFuture<WaterTile> future =
            pending.computeIfAbsent(key,
                k -> submitFetch(k, lat, lon));

        try {
            WaterTile r = future.get(
                maxWaitMs, TimeUnit.MILLISECONDS);
            return r != null && r.valid;
        } catch (TimeoutException e) {
            // Clean up — remove from pending so a
            // future call can retry cleanly
            pending.remove(key, future);
            return false;
        } catch (Exception e) {
            pending.remove(key, future);
            return false;
        }
    }

    /**
     * Prefetch surrounding tiles asynchronously.
     * Disk check happens before thread pool submission.
     */
    public void prefetch(double lat, double lon) {
        if (!enabled) return;
        // COLD-START BATCHING (ported from OsmStructureIndex):
        // collect genuinely missing tiles of the 3x3 area first,
        // then fetch them as ONE Overpass area request instead of
        // up to 9 rate-limited singles. On the shared public
        // endpoints the batch costs one token + one round trip —
        // measured 3-6x faster on cold arrival for structures.
        Map<Long, double[]> missing = new HashMap<>();
        for (int dlat = -1; dlat <= 1; dlat++)
            for (int dlon = -1; dlon <= 1; dlon++) {
                double tLat = lat + dlat * TILE_DEG;
                double tLon = lon + dlon * TILE_DEG;
                long key = tileKey(tLat, tLon);
                if (memCache.containsKey(key)) continue;
                if (pending.containsKey(key)) continue;

                // Disk check before submitting
                if (diskOk) {
                    WaterTile disk = loadDisk(key);
                    if (disk != null) {
                        memCache.put(key, disk);
                        continue;
                    }
                }
                missing.put(key, new double[]{
                    Math.floor(tLat / TILE_DEG) * TILE_DEG,
                    Math.floor(tLon / TILE_DEG) * TILE_DEG});
            }
        if (missing.isEmpty()) return;
        if (missing.size() == 1) {
            var e = missing.entrySet().iterator().next();
            pending.computeIfAbsent(e.getKey(),
                k -> submitFetch(k,
                    e.getValue()[0], e.getValue()[1]));
            return;
        }
        // Claim all tiles with futures so concurrent callers
        // wait on the batch instead of firing their own.
        Map<Long, CompletableFuture<WaterTile>> claimed =
            new HashMap<>();
        for (long key : missing.keySet()) {
            CompletableFuture<WaterTile> f =
                new CompletableFuture<>();
            if (pending.putIfAbsent(key, f) == null)
                claimed.put(key, f);
        }
        if (claimed.isEmpty()) return;
        dlPool.submit(() -> fetchWaterBatch(claimed, missing));
    }

    /** One area request covering every claimed tile; response
     *  split per-tile (feature bbox overlap, matching the
     *  per-tile expand margin) and cached under each key. Tiles
     *  the batch could not serve fall back to single fetch. */
    private void fetchWaterBatch(
            Map<Long, CompletableFuture<WaterTile>> claimed,
            Map<Long, double[]> origins) {
        double minLat = 90, maxLat = -90,
               minLon = 180, maxLon = -180;
        for (double[] o : origins.values()) {
            minLat = Math.min(minLat, o[0]);
            maxLat = Math.max(maxLat, o[0] + TILE_DEG);
            minLon = Math.min(minLon, o[1]);
            maxLon = Math.max(maxLon, o[1] + TILE_DEG);
        }
        double expand = TILE_DEG * 0.1;
        String bbox = String.format("%.6f,%.6f,%.6f,%.6f",
            minLat - expand, minLon - expand,
            maxLat + expand, maxLon + expand);
        String json = executeWaterQuery(bbox, 40);
        if (json == null) {
            // batch failed: single-tile fallback per claim
            for (var e : claimed.entrySet()) {
                double[] o = origins.get(e.getKey());
                WaterTile t = fetchTile(e.getKey(), o[0], o[1]);
                e.getValue().complete(t);
                pending.remove(e.getKey(), e.getValue());
            }
            return;
        }
        WaterTile all = parseResponse(json);
        int served = 0;
        for (var e : claimed.entrySet()) {
            double[] o = origins.get(e.getKey());
            WaterTile sub = all.valid
                ? subsetTile(all,
                    o[0] - expand, o[0] + TILE_DEG + expand,
                    o[1] - expand, o[1] + TILE_DEG + expand)
                : WaterTile.FAILED;
            if (sub.valid) {
                memCache.put(e.getKey(), sub);
                evictIfNeeded();
                // Disk: store the raw batch JSON under each tile
                // key. loadDisk->parseResponse replays it; the
                // extra out-of-tile features are harmless (all
                // queries distance-filter) and it keeps disk
                // replay byte-faithful to what the network said.
                if (diskOk) saveDisk(e.getKey(), json);
                served++;
            }
            e.getValue().complete(sub);
            pending.remove(e.getKey(), e.getValue());
        }
        LOGGER.info("WATER BATCH [{}]: {} tiles in one "
            + "request ({} features total)",
            bbox, served,
            all.valid ? all.features.length : -1);
    }

    /** The full water/coastline Overpass query for a bbox —
     *  shared by single-tile fetch and the cold-start batch. */
    private static String waterQueryFor(String bbox,
            int timeoutSec) {
        return "[out:json][timeout:" + timeoutSec + "];("
            + "way[\"waterway\"~"
            + "\"river|stream|canal\"]"
            + "(" + bbox + ");"
            + "way[\"waterway\"="
            + "\"riverbank\"](" + bbox + ");"
            + "way[\"natural\"=\"water\"]"
            + "(" + bbox + ");"
            + "relation[\"natural\"=\"water\"]"
            + "(" + bbox + ");"
            + "relation[\"waterway\"=\"riverbank\"]"
            + "(" + bbox + ");"
            + "relation[\"water\"~\"lake|reservoir|pond"
            + "|oxbow|basin|drain|river|canal"
            + "|ditch|moat\"](" + bbox + ");"
            + "relation[\"landuse\"=\"reservoir\"]"
            + "(" + bbox + ");"
            + "relation[\"natural\"=\"wetland\"]"
            + "(" + bbox + ");"
            + "relation[\"wetland\"~"
            + "\"swamp|marsh|bog|fen\"]"
            + "(" + bbox + ");"
            + "way[\"water\"~\"lake|reservoir|pond"
            + "|oxbow|basin|drain|river|canal"
            + "|ditch|moat\"](" + bbox + ");"
            + "way[\"landuse\"=\"reservoir\"]"
            + "(" + bbox + ");"
            + "way[\"natural\"=\"wetland\"]"
            + "(" + bbox + ");"
            + "way[\"wetland\"~"
            + "\"swamp|marsh|bog|fen\"]"
            + "(" + bbox + ");"
            + "way[\"natural\"=\"coastline\"]"
            + "(" + bbox + ");"
            + ");out body geom;";
    }

    /** Endpoint-rotating POST of a water query; null on total
     *  failure (all endpoints down/paused). Mirrors fetchTile's
     *  rotation but returns the raw JSON for batch splitting. */
    private String executeWaterQuery(String bbox,
            int timeoutSec) {
        if (System.currentTimeMillis() < pauseUntilMs)
            return null;
        String query = waterQueryFor(bbox, timeoutSec);
        totalQueries.incrementAndGet();
        int startIdx =
            endpointIdx.get() % OVERPASS_ENDPOINTS.length;
        for (int attempt = 0;
                attempt < OVERPASS_ENDPOINTS.length;
                attempt++) {
            int idx = (startIdx + attempt)
                % OVERPASS_ENDPOINTS.length;
            if (System.currentTimeMillis()
                    < endpointPauseUntil[idx]) continue;
            String ep = OVERPASS_ENDPOINTS[idx];
            OverpassRateLimiter limiter =
                endpointLimiters[idx];
            HttpURLConnection conn = null;
            boolean inFlight = false;
            try {
                limiter.acquire();
                limiter.acquireInFlight();
                inFlight = true;
                conn = (HttpURLConnection)
                    new URL(ep).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(HTTP_TIMEOUT_MS);
                conn.setReadTimeout(
                    Math.max(HTTP_TIMEOUT_MS,
                        timeoutSec * 1000 + 5000));
                conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
                conn.setRequestProperty("User-Agent",
                    "TerraPlusPlus/2.0 Minecraft");
                conn.setRequestProperty(
                    "Accept-Encoding", "gzip");
                byte[] body = ("data="
                    + URLEncoder.encode(query,
                        StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
                conn.getOutputStream().write(body);
                conn.getOutputStream().flush();
                int code = conn.getResponseCode();
                if (code == 200) {
                    String json = readResponse(conn);
                    endpointIdx.set(idx);
                    consecutiveFailures = 0;
                    return json;
                } else if (code == 429 || code == 504) {
                    limiter.penalize(
                        code == 429 ? 30_000L : 10_000L);
                } else {
                    LOGGER.warn("{} returned {} (batch)",
                        epName(ep), code);
                    limiter.penalize(8_000L);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                LOGGER.debug("water batch {}: {}",
                    epName(ep), e.getMessage());
            } finally {
                if (inFlight) limiter.releaseInFlight();
                net.buildtheearth.terraplusplus.util.HttpKeepAlive.release(conn);
            }
        }
        return null;
    }

    /** Features/coastline whose bbox overlaps the window. */
    private WaterTile subsetTile(WaterTile all,
            double aLat, double bLat,
            double aLon, double bLon) {
        List<WaterFeature> fs = new ArrayList<>();
        for (WaterFeature f : all.features)
            if (f.maxLat >= aLat && f.minLat <= bLat
                    && f.maxLon >= aLon && f.minLon <= bLon)
                fs.add(f);
        List<CoastlineSegment> cs = new ArrayList<>();
        for (CoastlineSegment c : all.coastline)
            if (c.maxLat >= aLat && c.minLat <= bLat
                    && c.maxLon >= aLon && c.minLon <= bLon)
                cs.add(c);
        return new WaterTile(
            fs.toArray(new WaterFeature[0]),
            cs.toArray(new CoastlineSegment[0]), true);
    }

    public int  getMemCacheSize()  { return memCache.size(); }
    public int  getTotalQueries()  { return totalQueries.get(); }
    public boolean isEnabled()     { return enabled; }
    public void setEnabled(boolean e) {
        this.enabled = e;
        if (e) {
            consecutiveFailures = 0;
            pauseUntilMs = 0;
            pauseLogged.set(false);
            // Reset all endpoint limiters so burst
            // capacity is restored after a pause
            for (OverpassRateLimiter lim :
                    endpointLimiters)
                lim.reset();
        }
    }
    public long getTileKeyPublic(double lat, double lon) {
        return tileKey(lat, lon);
    }
    public boolean isTileCached(long key) {
        return memCache.containsKey(key);
    }

    // ── TILE MANAGEMENT ───────────────────────────────────────

    private WaterTile getTile(double lat, double lon) {
        long key = tileKey(lat, lon);
        WaterTile cached = memCache.get(key);
        if (cached != null) return cached;

        // Disk check before triggering network fetch
        if (diskOk) {
            WaterTile disk = loadDisk(key);
            if (disk != null) {
                memCache.put(key, disk);
                return disk;
            }
        }

        CompletableFuture<WaterTile> future =
            pending.computeIfAbsent(key,
                k -> submitFetch(k, lat, lon));
        try {
            return future.get(TILE_WAIT_MS,
                TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Submit a fetch task to the thread pool.
     * The task picks the best available endpoint and
     * acquires that endpoint's rate limiter token.
     * Never does a disk check — caller does that first.
     */
    private CompletableFuture<WaterTile> submitFetch(
            long key, double lat, double lon) {
        double tileLat =
            Math.floor(lat / TILE_DEG) * TILE_DEG;
        double tileLon =
            Math.floor(lon / TILE_DEG) * TILE_DEG;
        return CompletableFuture.supplyAsync(
            () -> fetchTile(key, tileLat, tileLon),
            dlPool);
    }

    private long tileKey(double lat, double lon) {
        int tLat = (int) Math.floor(lat / TILE_DEG);
        int tLon = (int) Math.floor(lon / TILE_DEG);
        return ((long) tLat << 32)
            | (tLon & 0xFFFFFFFFL);
    }

    // ── MATCHING ──────────────────────────────────────────────

    private WaterBodyInfo findBestMatch(WaterTile tile,
            double lat, double lon,
            double terrainElev) {
        WaterFeature bestWater  = null;
        WaterFeature bestBank   = null;
        WaterFeature bestShore  = null;
        WaterFeature islandParent = null;
        double bestWaterDist  = Double.MAX_VALUE;
        double bestBankDist   = Double.MAX_VALUE;
        double bestShoreDist  = Double.MAX_VALUE;
        double islandEdgeDist = Double.MAX_VALUE;

        for (WaterFeature f : tile.features) {
            if (f.isArea
                    && f.isInsideIsland(lat, lon)) {
                double toWater =
                    f.distanceToWater(lat, lon);
                if (toWater < islandEdgeDist) {
                    islandEdgeDist = toWater;
                    islandParent   = f;
                }
                continue;
            }
            double dist =
                f.distanceMeters(lat, lon);
            double halfW = f.widthMeters() / 2.0;
            if (f.isArea) {
                if (dist == 0) {
                    if (bestWaterDist > 0
                            || bestWater == null) {
                        bestWaterDist = 0;
                        bestWater = f;
                    }
                } else if (dist < f.getInsetMeters()
                        + WaterBodyInfo
                            .defaultBankWidth(f.type)) {
                    if (dist < bestShoreDist) {
                        bestShoreDist = dist;
                        bestShore = f;
                    }
                }
            } else {
                if (dist <= halfW) {
                    if (dist < bestWaterDist) {
                        bestWaterDist = dist;
                        bestWater = f;
                    }
                } else {
                    int bankW = WaterBodyInfo
                        .defaultBankWidth(f.type);
                    double bankDist = dist - halfW;
                    if (bankDist > 0
                            && bankDist < bankW
                            && bankDist
                                < bestBankDist) {
                        bestBankDist = bankDist;
                        bestBank = f;
                    }
                }
            }
        }

        if (islandParent != null
                && bestWater == null) {
            int bankW = WaterBodyInfo
                .defaultBankWidth(islandParent.type);
            if (islandEdgeDist < bankW)
                return buildBankResult(islandParent,
                    islandEdgeDist, lat, lon,
                    terrainElev);
            return WaterBodyInfo.NONE;
        }
        if (bestWater != null)
            return buildWaterResult(bestWater,
                lat, lon, terrainElev);
        if (bestShore != null)
            return buildBankResult(bestShore,
                bestShoreDist, lat, lon, terrainElev);
        if (bestBank != null)
            return buildBankResult(bestBank,
                bestBankDist, lat, lon, terrainElev);
        return WaterBodyInfo.NONE;
    }

    private WaterBodyInfo buildWaterResult(
            WaterFeature f, double lat, double lon,
            double terrainElev) {
        int width = f.widthMeters();
        double depth = WaterBodyInfo
            .defaultDepth(f.type, width);
        double edge = f.edgeFactor(lat, lon);
        double flow = f.isArea
            ? 0 : f.flowDirection(lat, lon);
        int bankW = WaterBodyInfo
            .defaultBankWidth(f.type);
        if (dbgCount.getAndIncrement() < 50)
            LOGGER.info("OSM MATCH: {} \"{}\" "
                + "w={}m d={}m edge={} at {},{}",
                f.type, f.name, width,
                String.format("%.1f", depth),
                String.format("%.3f", edge),
                String.format("%.5f", lat),
                String.format("%.5f", lon));
        return new WaterBodyInfo(f.type,
            WaterBodyInfo.Source.OSM, true,
            terrainElev, depth, width, edge, flow,
            bankW, f.name);
    }

    private WaterBodyInfo buildBankResult(
            WaterFeature f, double bankDist,
            double lat, double lon,
            double terrainElev) {
        int bankW = WaterBodyInfo
            .defaultBankWidth(f.type);
        double flow = f.isArea
            ? 0 : f.flowDirection(lat, lon);
        return new WaterBodyInfo(f.type,
            WaterBodyInfo.Source.OSM, false,
            terrainElev, 0, 0,
            1.0 + bankDist, flow, bankW, f.name);
    }

    // ── FETCH ─────────────────────────────────────────────────

    /**
     * Network fetch only — disk check is done by callers
     * before this task is submitted to the thread pool.
     *
     * Acquires the chosen endpoint's rate limiter token
     * before sending the request. Multiple threads can
     * acquire tokens from different endpoints in parallel
     * with no serialization between them.
     */
    private WaterTile fetchTile(long key,
            double minLat, double minLon) {
        long now = System.currentTimeMillis();
        if (now < pauseUntilMs) {
            // Temporary outage/backoff. Do not cache a failed tile: it must be
            // eligible to retry when the pause expires.
            pending.remove(key);
            return WaterTile.FAILED;
        }
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            if (pauseLogged.compareAndSet(false, true))
                LOGGER.warn("Pausing OSM water for {}s after {} failures; will retry",
                    PAUSE_ON_FAILURES_MS / 1000, consecutiveFailures);
            pauseUntilMs = now + PAUSE_ON_FAILURES_MS;
            consecutiveFailures = 0;
            pending.remove(key);
            return WaterTile.FAILED;
        }

        double expand = TILE_DEG * 0.1;
        String bbox = String.format(
            "%.6f,%.6f,%.6f,%.6f",
            minLat - expand,
            minLon - expand,
            minLat + TILE_DEG + expand,
            minLon + TILE_DEG + expand);

        String query = waterQueryFor(bbox, 20);

        totalQueries.incrementAndGet();

        int startIdx =
            endpointIdx.get() % OVERPASS_ENDPOINTS.length;

        boolean attemptedEndpoint = false;
        for (int attempt = 0;
                attempt < OVERPASS_ENDPOINTS.length;
                attempt++) {
            int idx = (startIdx + attempt)
                % OVERPASS_ENDPOINTS.length;
            if (System.currentTimeMillis() < endpointPauseUntil[idx])
                continue;
            attemptedEndpoint = true;
            String ep = OVERPASS_ENDPOINTS[idx];
            OverpassRateLimiter limiter =
                endpointLimiters[idx];

            HttpURLConnection conn = null;
        boolean endpointInFlight = false;
            try {
                // Acquire token for THIS endpoint only.
                // Other threads fetching from a different
                // endpoint are not blocked here at all.
                limiter.acquire();
                limiter.acquireInFlight();
                endpointInFlight = true;

                conn = (HttpURLConnection)
                    new URL(ep).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(HTTP_TIMEOUT_MS);
                conn.setReadTimeout(HTTP_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
                conn.setRequestProperty("User-Agent",
                    "TerraPlusPlus/2.0 Minecraft");
                conn.setRequestProperty(
                    "Accept-Encoding", "gzip");

                byte[] body = ("data="
                    + URLEncoder.encode(query,
                        StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
                conn.getOutputStream().write(body);
                conn.getOutputStream().flush();

                int code = conn.getResponseCode();
                if (code == 200) {
                    String json = readResponse(conn);
                    WaterTile wt = parseResponse(json);
                    endpointIdx.set(idx);
                    consecutiveFailures = 0;
                    pauseUntilMs = 0;
                    pauseLogged.set(false);
                    memCache.put(key, wt);
                    pending.remove(key);
                    evictIfNeeded();
                    if (diskOk) saveDisk(key, json);
                    LOGGER.info(
                        "OSM [{},{}]: {} features "
                        + "{} coast segs ({})",
                        String.format("%.4f", minLat),
                        String.format("%.4f", minLon),
                        wt.features.length,
                        wt.coastline.length,
                        epName(ep));
                    return wt;
                } else if (code == 429
                        || code == 504) {
                    // Rate limited by server —
                    // reset this endpoint's limiter
                    // to back off before retrying
                    limiter.penalize(code == 429 ? 30_000L : 10_000L);
                    continue;
                } else {
                    if (code >= 400 && code < 500) {
                        endpointPauseUntil[idx] = System.currentTimeMillis()
                                + ENDPOINT_4XX_PAUSE_MS;
                        LOGGER.warn("{} returned {}; pausing this endpoint for {}s",
                                epName(ep), code, ENDPOINT_4XX_PAUSE_MS / 1000);
                    } else {
                        LOGGER.warn("{} returned {}", epName(ep), code);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending.remove(key);
                return WaterTile.FAILED;
            } catch (Exception e) {
                LOGGER.debug("{} failed: {}",
                    epName(ep), e.getMessage());
            } finally {
                net.buildtheearth.terraplusplus.util.HttpKeepAlive.release(conn);
                if (endpointInFlight) limiter.releaseInFlight();
            }
        }

        // Only count a round that actually contacted at least one endpoint.
        // If all mirrors are in their own cooldown windows, retry later without
        // escalating to a source-wide outage.
        if (attemptedEndpoint) consecutiveFailures++;
        pending.remove(key);
        return WaterTile.FAILED;
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

    private static String epName(String url) {
        if (url.contains("kumi"))    return "kumi";
        if (url.contains("mail"))    return "mail.ru";
        return "de";
    }

    // ── PARSING ───────────────────────────────────────────────

    private WaterTile parseResponse(String json) {
        try {
            JsonObject root =
                JsonParser.parseString(json)
                    .getAsJsonObject();
            JsonArray elements =
                root.getAsJsonArray("elements");
            if (elements == null
                    || elements.isEmpty())
                return WaterTile.EMPTY;

            List<WaterFeature> features =
                new ArrayList<>();
            List<CoastlineSegment> coastSegs =
                new ArrayList<>();

            for (JsonElement el : elements) {
                JsonObject obj =
                    el.getAsJsonObject();
                JsonObject tags = obj.has("tags")
                    ? obj.getAsJsonObject("tags")
                    : null;
                if (tags == null) continue;
                if ("coastline".equals(
                        getTag(tags, "natural"))) {
                    parseCoastline(obj, coastSegs);
                    continue;
                }
                WaterFeature f = parseElement(obj);
                if (f != null) features.add(f);
            }

            return new WaterTile(
                features.toArray(new WaterFeature[0]),
                coastSegs.toArray(
                    new CoastlineSegment[0]),
                true);
        } catch (Exception e) {
            LOGGER.warn("Parse failed: {}",
                e.getMessage());
            return WaterTile.FAILED;
        }
    }

    private void parseCoastline(JsonObject obj,
            List<CoastlineSegment> segments) {
        if (!obj.has("geometry")) return;
        JsonArray geom =
            obj.getAsJsonArray("geometry");
        if (geom.size() < 2) return;
        List<double[]> nodes = new ArrayList<>();
        for (int i = 0; i < geom.size(); i++) {
            JsonObject g =
                geom.get(i).getAsJsonObject();
            if (g.has("lat") && g.has("lon"))
                nodes.add(new double[]{
                    g.get("lat").getAsDouble(),
                    g.get("lon").getAsDouble()});
        }
        for (int i = 0; i < nodes.size() - 1; i++) {
            double[] a = nodes.get(i);
            double[] b = nodes.get(i + 1);
            segments.add(new CoastlineSegment(
                a[0], a[1], b[0], b[1]));
        }
    }

    private WaterFeature parseElement(JsonObject obj) {
        JsonObject tags = obj.has("tags")
            ? obj.getAsJsonObject("tags") : null;
        if (tags == null) return null;

        String waterway = getTag(tags, "waterway");
        String natural  = getTag(tags, "natural");
        String water    = getTag(tags, "water");
        String landuse  = getTag(tags, "landuse");
        String wetland  = getTag(tags, "wetland");
        String name     = getTag(tags, "name");

        WaterBodyInfo.WaterType type = null;
        boolean isArea = false;

        if (waterway != null)
            switch (waterway) {
                case "river"     ->
                    type = WaterBodyInfo.WaterType.RIVER;
                case "stream"    ->
                    type = WaterBodyInfo.WaterType.STREAM;
                case "canal"     ->
                    type = WaterBodyInfo.WaterType.CANAL;
                case "riverbank" -> {
                    type = WaterBodyInfo.WaterType.RIVER;
                    isArea = true;
                }
            }

        if (type == null && water != null) {
            isArea = true;
            type = switch (water) {
                case "lake", "oxbow" ->
                    WaterBodyInfo.WaterType.LAKE;
                case "reservoir", "basin" ->
                    WaterBodyInfo.WaterType.RESERVOIR;
                case "pond" ->
                    WaterBodyInfo.WaterType.POND;
                case "drain", "moat", "ditch" ->
                    WaterBodyInfo.WaterType.RESERVOIR;
                case "river" ->
                    WaterBodyInfo.WaterType.RIVER;
                case "canal" ->
                    WaterBodyInfo.WaterType.CANAL;
                default ->
                    WaterBodyInfo.WaterType.LAKE;
            };
        }

        if (type == null
                && "water".equals(natural)) {
            isArea = true;
            type = WaterBodyInfo.WaterType.LAKE;
        }
        if (type == null
                && "reservoir".equals(landuse)) {
            isArea = true;
            type = WaterBodyInfo.WaterType.RESERVOIR;
        }
        if (type == null
                && (wetland != null
                    || "wetland".equals(natural))) {
            isArea = true;
            type = WaterBodyInfo.WaterType.WETLAND;
        }
        if (type == null) return null;

        if ("yes".equals(getTag(tags, "area")))
            isArea = true;

        int widthTag = 0;
        String widthStr = getTag(tags, "width");
        if (widthStr != null)
            try {
                widthTag = (int) Double.parseDouble(
                    widthStr.replaceAll(
                        "[^0-9.]", ""));
            } catch (NumberFormatException ignored) {}

        long osmId = obj.has("id")
            ? obj.get("id").getAsLong() : 0;

        List<double[]> outerCoords = new ArrayList<>();
        List<double[][]> innerRingsList =
            new ArrayList<>();
        extractCoordsWithInner(obj, outerCoords,
            innerRingsList);

        if (outerCoords.size() < 2) return null;

        return new WaterFeature(type, name,
            outerCoords.toArray(new double[0][]),
            isArea, widthTag, osmId,
            innerRingsList.toArray(
                new double[0][][]));
    }

    private void extractCoordsWithInner(JsonObject obj,
            List<double[]> outerCoords,
            List<double[][]> innerRings) {
        if (obj.has("geometry")
                && !obj.has("members")) {
            JsonArray geom =
                obj.getAsJsonArray("geometry");
            for (int i = 0; i < geom.size(); i++) {
                JsonObject g =
                    geom.get(i).getAsJsonObject();
                if (g.has("lat") && g.has("lon"))
                    outerCoords.add(new double[]{
                        g.get("lat").getAsDouble(),
                        g.get("lon").getAsDouble()});
            }
            return;
        }
        if (obj.has("members")) {
            JsonArray members =
                obj.getAsJsonArray("members");
            for (JsonElement m : members) {
                JsonObject mObj =
                    m.getAsJsonObject();
                String role = mObj.has("role")
                    ? mObj.get("role").getAsString()
                    : "";
                if ("inner".equals(role)) {
                    List<double[]> rc =
                        new ArrayList<>();
                    if (mObj.has("geometry")) {
                        JsonArray geom =
                            mObj.getAsJsonArray(
                                "geometry");
                        for (int i = 0;
                                i < geom.size(); i++) {
                            JsonObject g =
                                geom.get(i)
                                .getAsJsonObject();
                            if (g.has("lat")
                                    && g.has("lon"))
                                rc.add(new double[]{
                                    g.get("lat")
                                        .getAsDouble(),
                                    g.get("lon")
                                        .getAsDouble()});
                        }
                    }
                    if (rc.size() >= 3)
                        innerRings.add(
                            rc.toArray(
                                new double[0][]));
                } else {
                    if (mObj.has("geometry")) {
                        JsonArray geom =
                            mObj.getAsJsonArray(
                                "geometry");
                        for (int i = 0;
                                i < geom.size(); i++) {
                            JsonObject g =
                                geom.get(i)
                                .getAsJsonObject();
                            if (g.has("lat")
                                    && g.has("lon"))
                                outerCoords.add(
                                    new double[]{
                                        g.get("lat")
                                        .getAsDouble(),
                                        g.get("lon")
                                        .getAsDouble()});
                        }
                    }
                }
            }
        }
    }

    private String getTag(JsonObject tags, String key) {
        if (tags.has(key)) {
            JsonElement e = tags.get(key);
            if (e.isJsonPrimitive())
                return e.getAsString();
        }
        return null;
    }

    // ── CACHE ─────────────────────────────────────────────────

    private void evictIfNeeded() {
        if (memCache.size() <= MEM_CACHE_MAX) return;
        memCache.entrySet().removeIf(
            e -> !e.getValue().valid);
        if (memCache.size() <= MEM_CACHE_MAX) return;
        List<Map.Entry<Long, WaterTile>> entries =
            new ArrayList<>(memCache.entrySet());
        entries.sort(Comparator.comparingLong(
            e -> e.getValue().fetchTimeMs));
        int toRemove = memCache.size()
            - MEM_CACHE_MAX
            + MEM_CACHE_MAX / 4;
        for (int i = 0;
                i < toRemove && i < entries.size();
                i++)
            memCache.remove(entries.get(i).getKey());
    }

    private WaterTile loadDisk(long key) {
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
            String json = Files.readString(
                file, StandardCharsets.UTF_8);
            WaterTile wt = parseResponse(json);
            if (wt.valid) return wt;
        } catch (Exception e) {
            LOGGER.debug("Disk load failed: {}",
                e.getMessage());
        }
        return null;
    }

    private void saveDisk(long key, String json) {
        try {
            Files.writeString(
                diskDir.resolve(
                    Long.toHexString(key) + ".json"),
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