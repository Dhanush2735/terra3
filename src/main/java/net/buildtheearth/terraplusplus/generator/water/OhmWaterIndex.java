// ============================================================
// FILE: OhmWaterIndex.java
// CHANGES:
//   - Replaced synchronized(rateLock)+sleep with
//     OverpassRateLimiter (single instance, OHM has one
//     endpoint — 1 req/2s, burst 2)
//   - Disk cache check moved before thread pool submission
//     in getTileBlocking() and prefetch() — disk hits never
//     consume a rate limit slot or thread pool thread
//   - DL_THREADS increased from 2 to 3
//   - pending map cleaned up on timeout in getTileBlocking()
//   - fetchTile() no longer does disk check (done by callers)
//   - setEnabled(true) resets limiter to restore burst capacity
//   - All prior fixes retained
// ============================================================
package net.buildtheearth.terraplusplus.generator.water;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.buildtheearth.terraplusplus.generator.historical.HistoricalDateParser;
import net.buildtheearth.terraplusplus.generator.historical.TerraTimeManager;
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

public class OhmWaterIndex {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Water/OHM");

    // ── CONSTANTS ─────────────────────────────────────────────
    private static final double TILE_DEG      = 0.05;
    private static final int    MEM_CACHE_MAX = 512;
    private static final long   DISK_TTL_MS   =
        30L * 24 * 60 * 60 * 1000;
    private static final int    HTTP_TIMEOUT_MS = 25000;
    private static final long   TILE_WAIT_MS    = 500;
    // Slow-start cure: failed-fetch memory (see OsmWaterIndex).
    private static final long   FAIL_TTL_MS     = 30_000L;
    private static final int    DL_THREADS      = 3; // increased from 2
    private static final int    MAX_CONSECUTIVE_FAILURES = 5;
    private static final long   PAUSE_ON_FAILURES_MS = 60000;

    private static final String OHM_OVERPASS =
        "https://overpass-api.openhistoricalmap.org"
        + "/api/interpreter";

    // ── RATE LIMITER ──────────────────────────────────────────
    // Single token bucket for the one OHM endpoint.
    // 1 req/2s, burst of 2 — conservative for OHM's
    // less-resourced public server.
    // Replaces the old synchronized(rateLock)+sleep pattern.
    private final OverpassRateLimiter limiter =
        OverpassRateLimiter.forOhmWater();

    // ── INNER CLASS: OHM WATER FEATURE ───────────────────────

    public static class OhmWaterFeature {
        public final OsmWaterIndex.WaterFeature base;
        public final int    startYear;
        public final int    endYear;
        public final String startDateRaw;
        public final String endDateRaw;

        public OhmWaterFeature(
                OsmWaterIndex.WaterFeature base,
                int startYear, int endYear,
                String startDateRaw,
                String endDateRaw) {
            this.base         = base;
            this.startYear    = startYear;
            this.endYear      = endYear;
            this.startDateRaw = startDateRaw;
            this.endDateRaw   = endDateRaw;
        }

        public boolean isVisibleAt(int year) {
            return HistoricalDateParser.isVisibleAt(
                startYear, endYear, year, false);
        }
    }

    // ── TILE ──────────────────────────────────────────────────

    public static class OhmWaterTile {
        public final OhmWaterFeature[] features;
        public final OsmWaterIndex.CoastlineSegment[]
            coastline;
        public final boolean valid;
        public final long    fetchTimeMs;

        public OhmWaterTile(
                OhmWaterFeature[] features,
                OsmWaterIndex.CoastlineSegment[]
                    coastline,
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

        public static final OhmWaterTile EMPTY =
            new OhmWaterTile(
                new OhmWaterFeature[0],
                new OsmWaterIndex.CoastlineSegment[0],
                true);

        public static final OhmWaterTile FAILED =
            new OhmWaterTile(
                new OhmWaterFeature[0],
                new OsmWaterIndex.CoastlineSegment[0],
                false);
    }

    // ── FIELDS ────────────────────────────────────────────────

    private final ConcurrentHashMap<Long, OhmWaterTile>
        memCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long,
        CompletableFuture<OhmWaterTile>>
        pending = new ConcurrentHashMap<>();
    // Tile key -> last failed fetch time (slow-start cure).
    private final ConcurrentHashMap<Long, Long>
        failedAt = new ConcurrentHashMap<>();

    private void stampFailure(long key) {
        if (failedAt.size() > 4096) failedAt.clear();
        failedAt.put(key, System.currentTimeMillis());
    }

    private boolean recentlyFailed(long key) {
        Long t = failedAt.get(key);
        return t != null
            && System.currentTimeMillis() - t < FAIL_TTL_MS;
    }
    private final ExecutorService dlPool;
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

    public OhmWaterIndex() {
        this.dlPool = Executors.newFixedThreadPool(
            DL_THREADS, r -> {
                Thread t = new Thread(r,
                    "Terra++OHM-Water");
                t.setDaemon(true);
                return t;
            });

        Path dir = net.buildtheearth.terraplusplus.util.TerraCachePaths.dir("ohm_water");
        boolean ok = false;
        try {
            Files.createDirectories(dir);
            ok = true;
        } catch (Exception e) {
            LOGGER.warn("OHM water disk cache "
                + "failed: {}", e.getMessage());
        }
        this.diskDir = dir;
        this.diskOk  = ok;

        LOGGER.info("OhmWaterIndex init "
            + "(OHM Overpass, disk={}, "
            + "threads={})",
            ok, DL_THREADS);
    }

    // ── PUBLIC API ────────────────────────────────────────────

    /**
     * Non-blocking query. Returns null if tile not
     * loaded yet. Returns WaterBodyInfo.NONE if no match.
     */
    public WaterBodyInfo query(double lat, double lon,
            double terrainElev) {
        if (!enabled || lat <= -85 || lat >= 85)
            return WaterBodyInfo.NONE;

        long key = tileKey(lat, lon);
        OhmWaterTile tile = memCache.get(key);
        if (tile == null) return null;

        if (!tile.valid
                || tile.features.length == 0)
            return WaterBodyInfo.NONE;

        int currentYear =
            TerraTimeManager.getCurrentYear();
        return findBestMatch(tile, lat, lon,
            terrainElev, currentYear);
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
        OhmWaterTile tile = memCache.get(key);
        if (tile != null) return tile.valid;

        // ── DISK CHECK BEFORE THREAD POOL ─────────────
        if (diskOk) {
            OhmWaterTile disk = loadDisk(key);
            if (disk != null) {
                memCache.put(key, disk);
                CompletableFuture<OhmWaterTile> pf =
                    pending.remove(key);
                if (pf != null) pf.cancel(false);
                return disk.valid;
            }
        }

        // Recent failure on record: fail fast instead of
        // paying (or launching) another doomed fetch round.
        if (recentlyFailed(key)) return false;

        // Not on disk — submit to thread pool
        CompletableFuture<OhmWaterTile> future =
            pending.computeIfAbsent(key,
                k -> submitFetch(k, lat, lon));

        try {
            OhmWaterTile r = future.get(
                maxWaitMs, TimeUnit.MILLISECONDS);
            return r != null && r.valid;
        } catch (TimeoutException e) {
            // Slow-start cure: keep the pending entry while
            // the fetch is still running — dropping it made
            // the next caller launch a duplicate download.
            if (future.isDone())
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
        for (int dlat = -1; dlat <= 1; dlat++)
            for (int dlon = -1; dlon <= 1; dlon++) {
                double tLat = lat + dlat * TILE_DEG;
                double tLon = lon + dlon * TILE_DEG;
                long key = tileKey(tLat, tLon);
                if (memCache.containsKey(key)) continue;
                if (pending.containsKey(key)) continue;

                // Disk check before submitting
                if (diskOk) {
                    OhmWaterTile disk = loadDisk(key);
                    if (disk != null) {
                        memCache.put(key, disk);
                        continue;
                    }
                }

                pending.computeIfAbsent(key,
                    k -> submitFetch(k, tLat, tLon));
            }
    }

    public boolean isTileCached(long key) {
        return memCache.containsKey(key);
    }

    public long getTileKeyPublic(double lat, double lon) {
        return tileKey(lat, lon);
    }

    public int     getMemCacheSize()  { return memCache.size(); }
    public int     getTotalQueries()  { return totalQueries.get(); }
    public boolean isEnabled()        { return enabled; }

    public void setEnabled(boolean e) {
        this.enabled = e;
        if (e) {
            consecutiveFailures = 0;
            pauseUntilMs = 0;
            // Restore burst capacity after re-enable
            limiter.reset();
        }
    }

    // ── COASTLINE CLASSIFICATION ──────────────────────────────

    public OsmWaterIndex.CoastSide classifyCoastline(
            double lat, double lon) {
        if (!enabled)
            return OsmWaterIndex.CoastSide.UNKNOWN;
        if (lat <= -85 || lat >= 85)
            return OsmWaterIndex.CoastSide.OCEAN;

        double bestDist   = Double.MAX_VALUE;
        double bestSigned = 0;
        boolean anyLoaded = false;
        boolean anyCoast  = false;

        for (int dlat = -1; dlat <= 1; dlat++)
            for (int dlon = -1; dlon <= 1; dlon++) {
                long key = tileKey(
                    lat + dlat * TILE_DEG,
                    lon + dlon * TILE_DEG);
                OhmWaterTile tile =
                    memCache.get(key);
                if (tile == null || !tile.valid)
                    continue;
                anyLoaded = true;
                if (!tile.hasCoastline()) continue;
                anyCoast = true;
                for (var seg : tile.coastline) {
                    if (!seg.mightBeNear(
                            lat, lon, 0.5))
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
            return OsmWaterIndex.CoastSide.UNKNOWN;
        if (bestDist == Double.MAX_VALUE)
            return OsmWaterIndex.CoastSide.UNKNOWN;
        if (bestDist <= 5.0)
            return OsmWaterIndex.CoastSide.COASTLINE;
        return bestSigned >= 0
            ? OsmWaterIndex.CoastSide.LAND
            : OsmWaterIndex.CoastSide.OCEAN;
    }

    // ── TILE KEY ──────────────────────────────────────────────

    private long tileKey(double lat, double lon) {
        int tLat = (int) Math.floor(lat / TILE_DEG);
        int tLon = (int) Math.floor(lon / TILE_DEG);
        return ((long) tLat << 32)
            | (tLon & 0xFFFFFFFFL);
    }

    // ── SUBMIT FETCH ──────────────────────────────────────────

    /**
     * Submit a network fetch to the thread pool.
     * Never does a disk check — callers do that first.
     */
    private CompletableFuture<OhmWaterTile> submitFetch(
            long key, double lat, double lon) {
        double tileLat =
            Math.floor(lat / TILE_DEG) * TILE_DEG;
        double tileLon =
            Math.floor(lon / TILE_DEG) * TILE_DEG;
        return CompletableFuture.supplyAsync(
            () -> {
                OhmWaterTile t =
                    fetchTile(key, tileLat, tileLon);
                if (t == null || !t.valid)
                    stampFailure(key);
                else
                    failedAt.remove(key);
                return t;
            },
            dlPool);
    }

    // ── MATCHING ──────────────────────────────────────────────

    private WaterBodyInfo findBestMatch(
            OhmWaterTile tile,
            double lat, double lon,
            double terrainElev,
            int currentYear) {

        OsmWaterIndex.WaterFeature bestWater   = null;
        OsmWaterIndex.WaterFeature bestBank    = null;
        OsmWaterIndex.WaterFeature bestShore   = null;
        OsmWaterIndex.WaterFeature islandParent = null;

        double bestWaterDist  = Double.MAX_VALUE;
        double bestBankDist   = Double.MAX_VALUE;
        double bestShoreDist  = Double.MAX_VALUE;
        double islandEdgeDist = Double.MAX_VALUE;

        for (OhmWaterFeature ohm : tile.features) {
            if (!ohm.isVisibleAt(currentYear))
                continue;

            OsmWaterIndex.WaterFeature f = ohm.base;

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
                        bestWater     = f;
                    }
                } else if (dist < f.getInsetMeters()
                        + WaterBodyInfo
                            .defaultBankWidth(f.type)) {
                    if (dist < bestShoreDist) {
                        bestShoreDist = dist;
                        bestShore     = f;
                    }
                }
            } else {
                if (dist <= halfW) {
                    if (dist < bestWaterDist) {
                        bestWaterDist = dist;
                        bestWater     = f;
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
                        bestBank     = f;
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
            OsmWaterIndex.WaterFeature f,
            double lat, double lon,
            double terrainElev) {
        int width = f.widthMeters();
        double depth = WaterBodyInfo
            .defaultDepth(f.type, width);
        double edge = f.edgeFactor(lat, lon);
        double flow = f.isArea
            ? 0 : f.flowDirection(lat, lon);
        int bankW = WaterBodyInfo
            .defaultBankWidth(f.type);
        if (dbgCount.getAndIncrement() < 20)
            LOGGER.info(
                "OHM WATER MATCH: {} \"{}\" "
                + "w={}m d={}m edge={} at {},{}",
                f.type, f.name, width,
                String.format("%.1f", depth),
                String.format("%.3f", edge),
                String.format("%.5f", lat),
                String.format("%.5f", lon));
        return new WaterBodyInfo(f.type,
            WaterBodyInfo.Source.OHM, true,
            terrainElev, depth, width, edge, flow,
            bankW, f.name);
    }

    private WaterBodyInfo buildBankResult(
            OsmWaterIndex.WaterFeature f,
            double bankDist,
            double lat, double lon,
            double terrainElev) {
        int bankW = WaterBodyInfo
            .defaultBankWidth(f.type);
        double flow = f.isArea
            ? 0 : f.flowDirection(lat, lon);
        return new WaterBodyInfo(f.type,
            WaterBodyInfo.Source.OHM, false,
            terrainElev, 0, 0,
            1.0 + bankDist, flow, bankW, f.name);
    }

    // ── FETCH ─────────────────────────────────────────────────

    /**
     * Network fetch only — disk check is done by callers
     * before this task is submitted to the thread pool.
     *
     * Acquires the OHM rate limiter token before sending.
     * If paused due to consecutive failures, returns EMPTY
     * immediately without consuming a token.
     */
    private OhmWaterTile fetchTile(long key,
            double minLat, double minLon) {

        if (System.currentTimeMillis() < pauseUntilMs) {
            pending.remove(key);
            return OhmWaterTile.EMPTY;
        }

        if (consecutiveFailures
                >= MAX_CONSECUTIVE_FAILURES) {
            LOGGER.warn(
                "Pausing OHM water for {}s "
                + "after {} failures",
                PAUSE_ON_FAILURES_MS / 1000,
                consecutiveFailures);
            pauseUntilMs =
                System.currentTimeMillis()
                + PAUSE_ON_FAILURES_MS;
            consecutiveFailures = 0;
            pending.remove(key);
            return OhmWaterTile.EMPTY;
        }

        double expand = TILE_DEG * 0.1;
        String bbox = String.format(
            "%.6f,%.6f,%.6f,%.6f",
            minLat - expand,
            minLon - expand,
            minLat + TILE_DEG + expand,
            minLon + TILE_DEG + expand);

        String query = "[out:json][timeout:25];("
            + "way[\"waterway\"~"
            + "\"river|stream|canal\"]"
            + "(" + bbox + ");"
            + "way[\"waterway\"=\"riverbank\"]"
            + "(" + bbox + ");"
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

        totalQueries.incrementAndGet();

        HttpURLConnection conn = null;
        boolean endpointInFlight = false;
        try {
            // Acquire token — blocks only if we have
            // sent too many requests recently.
            // Does NOT block other threads fetching
            // OSM tiles in parallel.
            limiter.acquire();
                limiter.acquireInFlight();
                endpointInFlight = true;

            conn = (HttpURLConnection)
                new URL(OHM_OVERPASS).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type",
                "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent",
                "TerraPlusPlus/2.0 Minecraft OHM");
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
                OhmWaterTile tile =
                    parseResponse(json);
                consecutiveFailures = 0;
                memCache.put(key, tile);
                pending.remove(key);
                evictIfNeeded();
                if (diskOk) saveDisk(key, json);
                if (dbgCount.get() < 20)
                    LOGGER.info(
                        "OHM [{},{}]: {} features "
                        + "{} coast segs",
                        String.format(
                            "%.4f", minLat),
                        String.format(
                            "%.4f", minLon),
                        tile.features.length,
                        tile.coastline.length);
                return tile;
            } else if (code == 429
                    || code == 504) {
                // Server-side rate limit —
                // back off by resetting limiter
                limiter.penalize(code == 429 ? 30_000L : 10_000L);
                LOGGER.debug(
                    "OHM water rate limited ({})",
                    code);
            } else {
                LOGGER.debug(
                    "OHM water returned {}", code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(key);
            return OhmWaterTile.EMPTY;
        } catch (Exception e) {
            LOGGER.debug(
                "OHM water fetch failed: {}",
                e.getMessage());
        } finally {
            net.buildtheearth.terraplusplus.util.HttpKeepAlive.release(conn);
            if (endpointInFlight) limiter.releaseInFlight();
        }

        consecutiveFailures++;
        pending.remove(key);
        return OhmWaterTile.EMPTY;
    }

    // ── PARSING ───────────────────────────────────────────────

    private OhmWaterTile parseResponse(String json) {
        try {
            JsonObject root =
                JsonParser.parseString(json)
                    .getAsJsonObject();
            JsonArray elements =
                root.getAsJsonArray("elements");
            if (elements == null
                    || elements.isEmpty())
                return OhmWaterTile.EMPTY;

            List<OhmWaterFeature> features =
                new ArrayList<>();
            List<OsmWaterIndex.CoastlineSegment>
                coastSegs = new ArrayList<>();

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

                OhmWaterFeature f =
                    parseElement(obj, tags);
                if (f != null) features.add(f);
            }

            return new OhmWaterTile(
                features.toArray(
                    new OhmWaterFeature[0]),
                coastSegs.toArray(
                    new OsmWaterIndex
                        .CoastlineSegment[0]),
                true);

        } catch (Exception e) {
            LOGGER.warn(
                "OHM water parse failed: {}",
                e.getMessage());
            return OhmWaterTile.FAILED;
        }
    }

    private void parseCoastline(JsonObject obj,
            List<OsmWaterIndex.CoastlineSegment>
                segments) {
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
            segments.add(
                new OsmWaterIndex.CoastlineSegment(
                    a[0], a[1], b[0], b[1]));
        }
    }

    private OhmWaterFeature parseElement(
            JsonObject obj, JsonObject tags) {

        String startDateRaw =
            getTag(tags, "start_date");
        String endDateRaw =
            getTag(tags, "end_date");
        int startYear =
            HistoricalDateParser.parse(startDateRaw);
        int endYear =
            HistoricalDateParser.parse(endDateRaw);

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
                    type = WaterBodyInfo
                        .WaterType.RIVER;
                case "stream"    ->
                    type = WaterBodyInfo
                        .WaterType.STREAM;
                case "canal"     ->
                    type = WaterBodyInfo
                        .WaterType.CANAL;
                case "riverbank" -> {
                    type = WaterBodyInfo
                        .WaterType.RIVER;
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

        List<double[]> outerCoords =
            new ArrayList<>();
        List<double[][]> innerRingsList =
            new ArrayList<>();
        extractCoordsWithInner(obj, outerCoords,
            innerRingsList);

        if (outerCoords.size() < 2) return null;

        OsmWaterIndex.WaterFeature base =
            new OsmWaterIndex.WaterFeature(
                type, name,
                outerCoords.toArray(
                    new double[0][]),
                isArea, widthTag, osmId,
                innerRingsList.toArray(
                    new double[0][][]));

        return new OhmWaterFeature(base,
            startYear, endYear,
            startDateRaw, endDateRaw);
    }

    private void extractCoordsWithInner(
            JsonObject obj,
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
                    ? mObj.get("role")
                        .getAsString() : "";
                if ("inner".equals(role)) {
                    List<double[]> rc =
                        new ArrayList<>();
                    if (mObj.has("geometry")) {
                        JsonArray geom =
                            mObj.getAsJsonArray(
                                "geometry");
                        for (int i = 0;
                                i < geom.size();
                                i++) {
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
                                i < geom.size();
                                i++) {
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

    private String getTag(JsonObject tags,
            String key) {
        if (tags.has(key)) {
            JsonElement e = tags.get(key);
            if (e.isJsonPrimitive())
                return e.getAsString();
        }
        return null;
    }

    // ── IO ────────────────────────────────────────────────────

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
        if (memCache.size() <= MEM_CACHE_MAX)
            return;
        memCache.entrySet().removeIf(
            e -> !e.getValue().valid);
        if (memCache.size() > MEM_CACHE_MAX) {
            int toRemove =
                memCache.size() - MEM_CACHE_MAX / 2;
            var iter =
                memCache.entrySet().iterator();
            while (iter.hasNext()
                    && toRemove > 0) {
                iter.next();
                iter.remove();
                toRemove--;
            }
        }
    }

    private OhmWaterTile loadDisk(long key) {
        try {
            Path file = diskDir.resolve(
                Long.toHexString(key) + ".json");
            if (!Files.exists(file)) return null;
            long age =
                System.currentTimeMillis()
                - Files.getLastModifiedTime(file)
                    .toMillis();
            if (age > DISK_TTL_MS) {
                Files.deleteIfExists(file);
                return null;
            }
            return parseResponse(
                Files.readString(file,
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
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