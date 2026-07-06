// ============================================================
// FILE: OhmStructureIndex.java
// CHANGES:
//   - Replaced unsynchronized AtomicLong+sleep race condition
//     with OverpassRateLimiter (single instance, OHM has one
//     endpoint — 1 req/1.5s, burst 2)
//   - Disk cache check moved before thread pool submission
//     in getStructuresForChunkBlocking() — disk hits never
//     consume a rate limit slot or thread pool thread
//   - fetchTile() no longer does disk check (done by callers)
//   - DL_THREADS increased from 2 to 3
//   - pending map cleaned up on timeout
//   - setEnabled(true) resets limiter and pauseUntilMs
//   - All prior fixes retained
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.buildtheearth.terraplusplus.generator.osm.OsmStructureIndex;
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

public class OhmStructureIndex {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Struct/OHM");

    // ── CONSTANTS ─────────────────────────────────────────────
    private static final double TILE_DEG       = 0.01;
    private static final double OVERLAP_MARGIN = 0.001;
    private static final int    MEM_CACHE_MAX  = 256;
    private static final int    HTTP_TIMEOUT_MS = 25000;
    private static final int    DL_THREADS      = 3; // increased from 2
    private static final long   DISK_TTL_MS     =
        30L * 24 * 60 * 60 * 1000;
    private static final long   TILE_WAIT_MS    = 200;
    private static final int    MAX_CONSECUTIVE_FAILURES = 10;
    private static final long   PAUSE_ON_FAILURES_MS = 60000;

    private static final String OHM_OVERPASS =
        "https://overpass-api.openhistoricalmap.org"
        + "/api/interpreter";

    // ── RATE LIMITER ──────────────────────────────────────────
    // Single token bucket for the one OHM endpoint.
    // 1 req/1.5s, burst of 2.
    // Replaces the old unsynchronized AtomicLong+sleep
    // which had a race condition between the 2 fetch threads.
    private final OverpassRateLimiter limiter =
        OverpassRateLimiter.forOhmStructures();

    // ── INNER CLASS: OHM STRUCTURE ────────────────────────────

    public static class OhmStructure {
        public final OsmStructureIndex.OsmStructure base;
        public final int    startYear;
        public final int    endYear;
        public final String startDateRaw;
        public final String endDateRaw;

        public OhmStructure(
                OsmStructureIndex.OsmStructure base,
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

        public int yearsUntilAppear(int currentYear) {
            return HistoricalDateParser
                .yearsUntilAppear(
                    startYear, currentYear, false);
        }
    }

    // ── TILE ──────────────────────────────────────────────────

    public static class OhmTile {
        public final OhmStructure[] structures;
        public final boolean        valid;
        public final long           fetchTimeMs;

        public OhmTile(OhmStructure[] structures,
                boolean valid) {
            this.structures  = structures;
            this.valid       = valid;
            this.fetchTimeMs =
                System.currentTimeMillis();
        }

        public static final OhmTile EMPTY =
            new OhmTile(new OhmStructure[0], true);
        public static final OhmTile FAILED =
            new OhmTile(new OhmStructure[0], false);
    }

    // ── FIELDS ────────────────────────────────────────────────

    private final ConcurrentHashMap<Long, OhmTile>
        memCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long,
        CompletableFuture<OhmTile>>
        pending = new ConcurrentHashMap<>();
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

    public OhmStructureIndex() {
        this.dlPool = Executors.newFixedThreadPool(
            DL_THREADS, r -> {
                Thread t = new Thread(r,
                    "Terra++OHM-Struct");
                t.setDaemon(true);
                return t;
            });

        Path dir = Paths.get(
            "terra_cache", "ohm_structures");
        boolean ok = false;
        try {
            Files.createDirectories(dir);
            ok = true;
        } catch (Exception e) {
            LOGGER.warn(
                "OHM disk cache failed: {}",
                e.getMessage());
        }
        this.diskDir = dir;
        this.diskOk  = ok;

        LOGGER.info("OhmStructureIndex init "
            + "(OHM Overpass, disk={}, "
            + "threads={})",
            ok, DL_THREADS);
    }

    // ── PUBLIC API ────────────────────────────────────────────

    /**
     * Get OHM structures for a chunk area, blocking
     * up to timeoutMs.
     *
     * Disk cache for ALL needed tiles is checked here
     * before any tile is submitted to the thread pool.
     * Only truly missing tiles trigger network fetches.
     *
     * Returns null if not all tiles loaded within
     * timeout — same contract as OsmStructureIndex.
     */
    public List<OhmStructure>
            getStructuresForChunkBlocking(
            double minLat, double maxLat,
            double minLon, double maxLon,
            TerraWorldConfig config,
            long timeoutMs) {
        if (!enabled) return List.of();
        if (System.currentTimeMillis()
                < pauseUntilMs)
            return List.of();

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
                    OhmTile disk = loadDisk(key);
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
                    maxLon + OVERLAP_MARGIN, config);

            // Submit network fetches for missing tiles
            List<CompletableFuture<OhmTile>>
                futures = new ArrayList<>();
            for (long key : missingKeys) {
                int tLatI = (int)(key >> 32);
                int tLonI = (int) key;
                double tLat = tLatI * TILE_DEG;
                double tLon = tLonI * TILE_DEG;
                CompletableFuture<OhmTile> f =
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
                    "OHM blocking fetch: {}",
                    e.getMessage());
            }

            if (timedOut) {
                int loaded = 0;
                for (long key : missingKeys)
                    if (memCache.containsKey(key))
                        loaded++;
                if (loaded < missingKeys.size()) {
                    // Clean up futures for tiles
                    // that did not finish in time
                    for (long key : missingKeys) {
                        if (!memCache
                                .containsKey(key)) {
                            CompletableFuture<
                                OhmTile> f =
                                pending.get(key);
                            if (f != null
                                    && !f.isDone())
                                pending.remove(
                                    key, f);
                        }
                    }
                    LOGGER.debug(
                        "OHM timeout: {}/{} "
                        + "tiles — null",
                        loaded,
                        missingKeys.size());
                    return null;
                }
            }

            return collectFromKeys(neededKeys,
                minLat - OVERLAP_MARGIN,
                maxLat + OVERLAP_MARGIN,
                minLon - OVERLAP_MARGIN,
                maxLon + OVERLAP_MARGIN, config);

        } catch (Exception e) {
            LOGGER.debug(
                "OHM chunk fetch error: {}",
                e.getMessage());
            return null;
        }
    }

    public boolean areTilesLoaded(
            double minLat, double maxLat,
            double minLon, double maxLon) {
        for (long key : getTileKeysForArea(
                minLat, maxLat, minLon, maxLon))
            if (!memCache.containsKey(key))
                return false;
        return true;
    }

    public int     getCacheSize()    { return memCache.size(); }
    public int     getTotalQueries() { return totalQueries.get(); }
    public boolean isEnabled()       { return enabled; }

    public void setEnabled(boolean e) {
        this.enabled = e;
        if (e) {
            consecutiveFailures = 0;
            pauseUntilMs = 0;
            // Restore burst capacity after re-enable
            limiter.reset();
        }
    }

    // ── TILE KEYS ─────────────────────────────────────────────

    private long tileKey(double lat, double lon) {
        int tLat = (int) Math.floor(lat / TILE_DEG);
        int tLon = (int) Math.floor(lon / TILE_DEG);
        return ((long) tLat << 32)
            | (tLon & 0xFFFFFFFFL);
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

    private List<OhmStructure> collectFromKeys(
            Set<Long> keys,
            double qMinLat, double qMaxLat,
            double qMinLon, double qMaxLon,
            TerraWorldConfig config) {
        List<OhmStructure> result = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        for (long key : keys) {
            OhmTile tile = memCache.get(key);
            if (tile == null || !tile.valid
                    || tile.structures.length == 0)
                continue;
            for (OhmStructure s : tile.structures) {
                if (!isEnabled(
                        s.base.type, config))
                    continue;
                var b = s.base;
                if (b.maxLat < qMinLat
                        || b.minLat > qMaxLat
                        || b.maxLon < qMinLon
                        || b.minLon > qMaxLon)
                    continue;
                if (b.osmId != 0
                        && !seenIds.add(b.osmId))
                    continue;
                result.add(s);
            }
        }
        return result;
    }

    private boolean isEnabled(
            OsmStructureIndex.StructureType type,
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
     * Network fetch only — disk check is done by
     * getStructuresForChunkBlocking() before this task
     * is submitted to the thread pool.
     *
     * Acquires the OHM rate limiter token before
     * sending. If paused due to consecutive failures,
     * returns EMPTY immediately without consuming
     * a token.
     */
    private OhmTile fetchTile(long key,
            double minLat, double minLon) {

        if (System.currentTimeMillis()
                < pauseUntilMs) {
            pending.remove(key);
            return OhmTile.EMPTY;
        }

        if (consecutiveFailures
                >= MAX_CONSECUTIVE_FAILURES) {
            LOGGER.warn(
                "Pausing OHM struct for {}s "
                + "after {} failures",
                PAUSE_ON_FAILURES_MS / 1000,
                consecutiveFailures);
            pauseUntilMs =
                System.currentTimeMillis()
                + PAUSE_ON_FAILURES_MS;
            consecutiveFailures = 0;
            pending.remove(key);
            return OhmTile.EMPTY;
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
            + "way[\"highway\"](" + bbox + ");"
            + "way[\"railway\"~\"rail|light_rail"
            + "|subway|tram\"](" + bbox + ");"
            + "way[\"bridge\"](" + bbox + ");"
            + "way[\"barrier\"~\"wall|fence\"]"
            + "(" + bbox + ");"
            + "way[\"landuse\"~\"farmland|forest"
            + "|meadow\"](" + bbox + ");"
            + "way[\"leisure\"~\"park|garden\"]"
            + "(" + bbox + ");"
            + ");out body geom;";

        totalQueries.incrementAndGet();

        HttpURLConnection conn = null;
        try {
            // Acquire token — blocks only if we have
            // sent too many requests recently.
            // Does NOT block OSM fetch threads at all.
            limiter.acquire();

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

            byte[] body = ("data="
                + URLEncoder.encode(queryStr,
                    StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(body);
            conn.getOutputStream().flush();

            int code = conn.getResponseCode();
            if (code == 200) {
                String json = readResponse(conn);
                OhmTile tile = parseResponse(json);
                consecutiveFailures = 0;
                memCache.put(key, tile);
                pending.remove(key);
                evictIfNeeded();
                if (diskOk) saveDisk(key, json);
                if (dbgCount.getAndIncrement() < 20)
                    LOGGER.info(
                        "OHM [{},{}]: {} structures",
                        String.format(
                            "%.4f", minLat),
                        String.format(
                            "%.4f", minLon),
                        tile.structures.length);
                return tile;
            } else if (code == 429
                    || code == 504) {
                // Server-side rate limit —
                // back off by resetting limiter
                limiter.reset();
                LOGGER.debug(
                    "OHM struct rate limited ({})",
                    code);
            } else {
                LOGGER.debug(
                    "OHM returned {}", code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(key);
            return OhmTile.EMPTY;
        } catch (Exception e) {
            LOGGER.debug(
                "OHM fetch failed: {}",
                e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }

        consecutiveFailures++;
        pending.remove(key);
        return OhmTile.EMPTY;
    }

    // ── PARSING ───────────────────────────────────────────────

    private OhmTile parseResponse(String json) {
        try {
            JsonObject root =
                JsonParser.parseString(json)
                    .getAsJsonObject();
            JsonArray elements =
                root.getAsJsonArray("elements");
            if (elements == null
                    || elements.isEmpty())
                return OhmTile.EMPTY;

            List<OhmStructure> structures =
                new ArrayList<>();
            for (JsonElement el : elements) {
                OhmStructure s = parseElement(
                    el.getAsJsonObject());
                if (s != null) structures.add(s);
            }
            return new OhmTile(
                structures.toArray(
                    new OhmStructure[0]), true);
        } catch (Exception e) {
            LOGGER.warn(
                "OHM parse failed: {}",
                e.getMessage());
            return OhmTile.FAILED;
        }
    }

    private OhmStructure parseElement(
            JsonObject obj) {
        JsonObject tags = obj.has("tags")
            ? obj.getAsJsonObject("tags") : null;
        if (tags == null) return null;

        String startDateRaw =
            getTag(tags, "start_date");
        String endDateRaw =
            getTag(tags, "end_date");
        int startYear =
            HistoricalDateParser.parse(startDateRaw);
        int endYear =
            HistoricalDateParser.parse(endDateRaw);

        OsmStructureIndex.OsmStructure base =
            parseAsOsmStructure(obj, tags);
        if (base == null) return null;

        return new OhmStructure(base,
            startYear, endYear,
            startDateRaw, endDateRaw);
    }

    /**
     * Parse the element as an OSM structure.
     * Duplicates minimal parsing logic from
     * OsmStructureIndex to avoid tight coupling.
     */
    private OsmStructureIndex.OsmStructure
            parseAsOsmStructure(JsonObject obj,
            JsonObject tags) {
        long osmId = obj.has("id")
            ? obj.get("id").getAsLong() : 0;
        String objType = obj.has("type")
            ? obj.get("type").getAsString() : "";

        OsmStructureIndex.StructureType type = null;
        String subType = null;
        boolean isArea = false;

        String building = getTag(tags, "building");
        if (building != null) {
            type = OsmStructureIndex
                .StructureType.BUILDING;
            subType = building;
            isArea = true;
        }

        if (type == null) {
            String bp =
                getTag(tags, "building:part");
            if (bp != null) {
                type = OsmStructureIndex
                    .StructureType.BUILDING_PART;
                subType = bp;
                isArea = true;
            }
        }

        if (type == null) {
            String hw = getTag(tags, "highway");
            if (hw != null) {
                type = OsmStructureIndex
                    .StructureType.ROAD;
                subType = hw;
            }
        }

        if (type == null) {
            String rw = getTag(tags, "railway");
            if (rw != null) {
                type = OsmStructureIndex
                    .StructureType.RAILWAY;
                subType = rw;
            }
        }

        if (type == null) {
            String br = getTag(tags, "bridge");
            if (br != null
                    && !br.equals("no")) {
                type = OsmStructureIndex
                    .StructureType.BRIDGE;
                subType = br;
            }
        }

        if (type == null) {
            String lu = getTag(tags, "landuse");
            if (lu != null) {
                isArea = true;
                type = switch (lu) {
                    case "forest" ->
                        OsmStructureIndex
                            .StructureType.FOREST;
                    case "farmland" ->
                        OsmStructureIndex
                            .StructureType.FARMLAND;
                    default ->
                        OsmStructureIndex
                            .StructureType.MEADOW;
                };
                subType = lu;
            }
        }

        if (type == null) {
            String le = getTag(tags, "leisure");
            if (le != null) {
                isArea = true;
                type = switch (le) {
                    case "park" ->
                        OsmStructureIndex
                            .StructureType.PARK;
                    case "garden" ->
                        OsmStructureIndex
                            .StructureType.GARDEN;
                    default ->
                        OsmStructureIndex
                            .StructureType.PARK;
                };
                subType = le;
            }
        }

        if (type == null) {
            String barrier =
                getTag(tags, "barrier");
            if (barrier != null) {
                type = switch (barrier) {
                    case "wall" ->
                        OsmStructureIndex
                            .StructureType.WALL;
                    case "fence" ->
                        OsmStructureIndex
                            .StructureType.FENCE;
                    default ->
                        OsmStructureIndex
                            .StructureType.BARRIER;
                };
                subType = barrier;
            }
        }

        if (type == null) return null;

        // ── GEOMETRY ──────────────────────────────
        List<OsmStructureIndex.OsmNode> nodeList =
            new ArrayList<>();
        if ("node".equals(objType)) {
            if (obj.has("lat") && obj.has("lon"))
                nodeList.add(
                    new OsmStructureIndex.OsmNode(
                        obj.get("lat").getAsDouble(),
                        obj.get("lon")
                            .getAsDouble()));
        } else if (obj.has("geometry")) {
            JsonArray geom =
                obj.getAsJsonArray("geometry");
            for (int i = 0; i < geom.size(); i++) {
                JsonObject g =
                    geom.get(i).getAsJsonObject();
                if (g.has("lat") && g.has("lon"))
                    nodeList.add(
                        new OsmStructureIndex
                            .OsmNode(
                            g.get("lat")
                                .getAsDouble(),
                            g.get("lon")
                                .getAsDouble()));
            }
        } else if (obj.has("members")) {
            for (JsonElement m :
                    obj.getAsJsonArray("members")) {
                JsonObject mObj =
                    m.getAsJsonObject();
                String role = mObj.has("role")
                    ? mObj.get("role")
                        .getAsString() : "";
                if ("outer".equals(role)
                        || role.isEmpty()) {
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
                                nodeList.add(
                                    new OsmStructureIndex
                                        .OsmNode(
                                        g.get("lat")
                                        .getAsDouble(),
                                        g.get("lon")
                                        .getAsDouble()));
                        }
                    }
                }
            }
        }

        if (nodeList.isEmpty()) return null;
        if (type != OsmStructureIndex
                .StructureType.STREET_LAMP
                && nodeList.size() < 2) return null;

        // ── NUMERIC TAGS ──────────────────────────
        String name =
            getTag(tags, "name");
        String material =
            getTag(tags, "building:material");

        int levels = 0;
        String ls =
            getTag(tags, "building:levels");
        if (ls != null) try {
            levels = Math.max(1,
                Integer.parseInt(ls.trim()));
        } catch (NumberFormatException ignored) {}

        double height = 0;
        String hs = getTag(tags, "height");
        if (hs != null) try {
            height = Double.parseDouble(
                hs.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException ignored) {}

        // Dates are on the OhmStructure wrapper,
        // not on the base OsmStructure — pass
        // UNKNOWN_YEAR so base has neutral defaults
        return new OsmStructureIndex.OsmStructure(
            type, subType, name,
            nodeList.toArray(
                new OsmStructureIndex.OsmNode[0]),
            isArea, osmId, material, levels, height,
            getTag(tags, "roof:shape"),
            getTag(tags, "roof:material"),
            getTag(tags, "surface"),
            2, false);
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

    private OhmTile loadDisk(long key) {
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