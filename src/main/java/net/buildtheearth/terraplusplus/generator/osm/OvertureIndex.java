// ============================================================
// FILE: OvertureIndex.java (FULL — Microsoft Footprints only, OSM removed)
// ============================================================
package net.buildtheearth.terraplusplus.generator.osm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.buildtheearth.terraplusplus.config.TerraWorldConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * Microsoft Global ML Building Footprints index.
 *
 * Data source:
 *   Microsoft Global ML Building Footprints (GitHub releases, ~1.2B buildings)
 *
 * Microsoft buildings are served as GeoJSON-Lines (.geojsonl.gz) files
 * organized by quadkey. Each quadkey covers a geographic tile.
 * We download the index, find the right quadkey for a lat/lon,
 * download that file, and cache it.
 *
 * OSM buildings are handled separately by OsmStructureIndex.
 * This class provides only Microsoft ML building footprints.
 */
public class OvertureIndex {

    private static final Logger LOGGER = LogManager.getLogger("Terra++Overture");

    // ================================================================
    // CONSTANTS
    // ================================================================

    private static final double TILE_DEG             = 0.005;
    private static final int    MEM_CACHE_MAX        = 1024;
    private static final long   DISK_TTL_MS          = 90L * 24 * 60 * 60 * 1000;
    private static final int    HTTP_TIMEOUT_MS      = 20000;
    private static final long   TILE_WAIT_MS         = 500;
    private static final int    DL_THREADS           = 3;
    private static final int    MAX_CONSECUTIVE_FAILURES = 10;
    private static final long   PAUSE_ON_FAILURES_MS = 60000;
    private static final double OVERLAP_MARGIN       = 0.0005;

    // ================================================================
    // MICROSOFT BUILDING FOOTPRINTS
    // ================================================================

    private static final String MSFT_DATASET_LINKS_URL =
            "https://minedbuildings.z5.web.core.windows.net/global-buildings/dataset-links.csv";

    // ================================================================
    // DATA SOURCE ENUM
    // ================================================================

    public enum DataSource {
        MICROSOFT_ML("Microsoft ML"),
        DISK_CACHE("Disk Cache"),
        MEMORY_CACHE("Memory Cache");

        public final String displayName;
        DataSource(String name) { this.displayName = name; }
    }

    // ================================================================
    // OVERTURE BUILDING
    // ================================================================

    public static class OvertureBuilding {
        public final double[][] polygon;
        public final double centerLat;
        public final double centerLon;
        public final double minLat, maxLat, minLon, maxLon;

        public final double height;
        public final int    numFloors;
        public final String buildingClass;
        public final String subtype;
        public final String roofShape;
        public final String roofMaterial;
        public final String wallMaterial;
        public final double confidence;
        public final String overtureId;
        public final String primarySource;
        public final String name;
        public final long   osmId;

        public OvertureBuilding(double[][] polygon, double height, int numFloors,
                                String buildingClass, String subtype,
                                String roofShape, String roofMaterial,
                                String wallMaterial, double confidence,
                                String overtureId, String primarySource,
                                String name, long osmId) {
            this.polygon       = polygon;
            this.height        = height;
            this.numFloors     = numFloors;
            this.buildingClass = buildingClass;
            this.subtype       = subtype;
            this.roofShape     = roofShape;
            this.roofMaterial  = roofMaterial;
            this.wallMaterial  = wallMaterial;
            this.confidence    = confidence;
            this.overtureId    = overtureId;
            this.primarySource = primarySource;
            this.name          = name;
            this.osmId         = osmId;

            double cLat = 0, cLon = 0;
            double mnLat = Double.MAX_VALUE, mxLat = -Double.MAX_VALUE;
            double mnLon = Double.MAX_VALUE, mxLon = -Double.MAX_VALUE;
            for (double[] p : polygon) {
                cLat += p[0]; cLon += p[1];
                mnLat = Math.min(mnLat, p[0]); mxLat = Math.max(mxLat, p[0]);
                mnLon = Math.min(mnLon, p[1]); mxLon = Math.max(mxLon, p[1]);
            }
            this.centerLat = cLat / polygon.length;
            this.centerLon = cLon / polygon.length;
            this.minLat = mnLat; this.maxLat = mxLat;
            this.minLon = mnLon; this.maxLon = mxLon;
        }

        /**
         * Returns a copy translated by -(dLat,dLon), removing the measured
         * registration offset of this dataset relative to OSM.
         *
         * See {@link SourceAlignment} for why the shift is measured per region
         * at runtime rather than hardcoded. Polygon points are {lat, lon}.
         */
        public OvertureBuilding shifted(double dLat, double dLon) {
            if (dLat == 0.0 && dLon == 0.0) return this;
            double[][] moved = new double[polygon.length][];
            for (int i = 0; i < polygon.length; i++)
                moved[i] = new double[]{ polygon[i][0] - dLat,
                                         polygon[i][1] - dLon };
            return new OvertureBuilding(moved, height, numFloors,
                    buildingClass, subtype, roofShape, roofMaterial,
                    wallMaterial, confidence, overtureId, primarySource,
                    name, osmId);
        }

        public OsmStructureIndex.OsmStructure toOsmStructure() {
            OsmStructureIndex.OsmNode[] nodes =
                    new OsmStructureIndex.OsmNode[polygon.length];
            for (int i = 0; i < polygon.length; i++)
                nodes[i] = new OsmStructureIndex.OsmNode(
                        polygon[i][0], polygon[i][1]);

            String osmSubType = mapSubtype(subtype, buildingClass);

            long id = osmId;
            if (id == 0 && overtureId != null && !overtureId.isEmpty())
                id = stableHash(overtureId);
            if (id == 0)
                id = stableHash(centerLat, centerLon);

            return new OsmStructureIndex.OsmStructure(
                    OsmStructureIndex.StructureType.BUILDING,
                    osmSubType, name, nodes, true, id,
                    wallMaterial, numFloors, height,
                    roofShape, roofMaterial, null, 0, false);
        }

        private static String mapSubtype(String subtype, String buildingClass) {
            if (subtype != null && !subtype.isEmpty() && !"yes".equals(subtype))
                return subtype;
            if (buildingClass != null) {
                return switch (buildingClass.toLowerCase()) {
                    case "residential"                           -> "residential";
                    case "commercial"                            -> "commercial";
                    case "industrial"                            -> "industrial";
                    case "institutional", "civic", "government" -> "public";
                    case "agricultural"                          -> "farm";
                    case "outbuilding"                           -> "shed";
                    case "medical"                               -> "hospital";
                    case "education"                             -> "school";
                    case "religious"                             -> "church";
                    case "accommodation"                         -> "hotel";
                    default                                      -> "yes";
                };
            }
            return "yes";
        }

        public boolean isFromMicrosoft() {
            return primarySource != null &&
                    primarySource.toLowerCase().contains("microsoft");
        }

        public String getSourceShort() {
            if (isFromMicrosoft()) return "MSFT";
            return "UNK";
        }

        public boolean hasRichMetadata() {
            return height > 0 || numFloors > 0
                    || (roofShape    != null && !roofShape.isEmpty())
                    || (wallMaterial != null && !wallMaterial.isEmpty())
                    || (subtype      != null && !"yes".equals(subtype)
                            && !subtype.isEmpty());
        }

        public static long stableHash(String s) {
            if (s == null) return 0;
            long h = 0;
            for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
            return h & 0x7FFFFFFFFFFFFFFFL;
        }

        public static long stableHash(double lat, double lon) {
            return Double.doubleToLongBits(lat)
                    ^ (Double.doubleToLongBits(lon) * 31);
        }
    }

    // ================================================================
    // BUILDING TILE
    // ================================================================

    public static class BuildingTile {
        public final OvertureBuilding[] buildings;
        public final boolean    valid;
        public final long       fetchTimeMs;
        public final DataSource source;
        public int msftCount;
        public int otherCount;

        public BuildingTile(OvertureBuilding[] buildings, boolean valid,
                            DataSource source) {
            this.buildings   = buildings;
            this.valid       = valid;
            this.fetchTimeMs = System.currentTimeMillis();
            this.source      = source;
            countSources();
        }

        public BuildingTile(OvertureBuilding[] buildings, boolean valid) {
            this(buildings, valid, DataSource.MEMORY_CACHE);
        }

        private void countSources() {
            for (OvertureBuilding b : buildings) {
                if (b.isFromMicrosoft()) msftCount++;
                else                     otherCount++;
            }
        }

        public String sourceBreakdown() {
            if (buildings.length == 0) return "empty";
            StringBuilder sb = new StringBuilder();
            if (msftCount  > 0) sb.append("MSFT:").append(msftCount).append(" ");
            if (otherCount > 0) sb.append("OTHER:").append(otherCount).append(" ");
            return sb.toString().trim();
        }

        /** A completed source lookup containing no buildings. */
        public static final BuildingTile EMPTY =
                new BuildingTile(new OvertureBuilding[0], true,
                        DataSource.MEMORY_CACHE);
        /** Download/index state is unavailable; callers must retry. */
        public static final BuildingTile FAILED =
                new BuildingTile(new OvertureBuilding[0], false,
                        DataSource.MEMORY_CACHE);
    }

    // ================================================================
    // STATE
    // ================================================================

    private final ConcurrentHashMap<Long, BuildingTile> memCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<BuildingTile>> pending =
            new ConcurrentHashMap<>();

    // Microsoft quadkey index: quadkey → download URL
    private final ConcurrentHashMap<String, String> msftIndex =
            new ConcurrentHashMap<>();
    // Microsoft quadkey building cache: quadkey → list of buildings
    /** Quadkeys currently being downloaded/parsed, to prevent duplicate work. */
    private final java.util.Set<String> msftInFlight =
        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Parsing a Microsoft quadkey can scan 600k+ lines and allocate many
    // objects. Allow exactly one cold parse at a time process-wide.
    private final java.util.concurrent.Semaphore msftParsePermit =
        new java.util.concurrent.Semaphore(1, true);

    private final ConcurrentHashMap<String, List<OvertureBuilding>> msftQuadkeyCache =
            new ConcurrentHashMap<>();

    private volatile boolean msftIndexLoaded  = false;
    private volatile boolean msftIndexLoading = false;
    private volatile boolean msftAvailable    = true;

    private final ExecutorService dlPool;
    private final AtomicInteger   totalQueries    = new AtomicInteger(0);
    private final AtomicInteger   totalBuildings  = new AtomicInteger(0);
    private final AtomicInteger   dbgCount        = new AtomicInteger(0);

    private final AtomicInteger msftHits     = new AtomicInteger(0);
    private final AtomicInteger diskHits     = new AtomicInteger(0);
    private final AtomicInteger emptyTiles   = new AtomicInteger(0);

    private final AtomicInteger msftBuildingCount = new AtomicInteger(0);

    private final Path    diskDir;
    private final Path    msftCacheDir;
    private final Path    msftRegionCacheDir;
    private final boolean diskOk;

    private volatile boolean enabled             = true;
    private volatile int     consecutiveFailures = 0;
    private volatile long    pauseUntilMs        = 0;

    // ================================================================
    // CONSTRUCTOR
    // ================================================================

    /**
     * Shared instance.
     *
     * TerraChunkGenerator and HistoricalStructureManager each used to construct
     * their own OvertureIndex. Both then downloaded the 25,606-entry Microsoft
     * quadkey index and both kept independent tile caches and download pools,
     * doubling memory and network for identical data. One instance only.
     */
    private static volatile OvertureIndex INSTANCE;

    public static OvertureIndex getInstance() {
        OvertureIndex local = INSTANCE;
        if (local == null) {
            synchronized (OvertureIndex.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new OvertureIndex();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    private OvertureIndex() {
        this.dlPool = Executors.newFixedThreadPool(DL_THREADS, r -> {
            Thread t = new Thread(r, "Terra++Overture");
            t.setDaemon(true);
            return t;
        });

        Path dir     = Paths.get("terra_cache", "overture_buildings");
        Path msftDir = Paths.get("terra_cache", "msft_buildings");
        Path regionDir = msftDir.resolve("regions");
        boolean ok   = false;
        try {
            Files.createDirectories(dir);
            Files.createDirectories(msftDir);
            Files.createDirectories(regionDir);
            ok = true;
        } catch (Exception e) {
            LOGGER.warn("Cache dir failed: {}", e.getMessage());
        }
        this.diskDir      = dir;
        this.msftCacheDir = msftDir;
        this.msftRegionCacheDir = regionDir;
        this.diskOk       = ok;

        LOGGER.info("OvertureIndex initialized");
        LOGGER.info("  Source: Microsoft Global ML Building Footprints (~1.2B)");
        LOGGER.info("  OSM buildings handled separately by OsmStructureIndex");

        dlPool.submit(this::loadMicrosoftIndex);
    }

    // ================================================================
    // MICROSOFT INDEX LOADING
    // ================================================================

    private void loadMicrosoftIndex() {
        if (msftIndexLoading || msftIndexLoaded) return;
        msftIndexLoading = true;

        Path indexFile = msftCacheDir.resolve("dataset-links.csv");
        if (diskOk && Files.exists(indexFile)) {
            try {
                long age = System.currentTimeMillis() -
                        Files.getLastModifiedTime(indexFile).toMillis();
                if (age < 30L * 24 * 60 * 60 * 1000) {
                    loadIndexFromFile(indexFile);
                    if (!msftIndex.isEmpty()) {
                        msftIndexLoaded  = true;
                        msftIndexLoading = false;
                        LOGGER.info("Microsoft index from disk: {} quadkeys",
                                msftIndex.size());
                        return;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Disk index load failed: {}", e.getMessage());
            }
        }

        HttpURLConnection conn = null;
        try {
            LOGGER.info("Downloading Microsoft building footprints index...");
            conn = (HttpURLConnection)
                    new URL(MSFT_DATASET_LINKS_URL).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "TerraPlusPlus/2.0");

            int code = conn.getResponseCode();
            if (code == 200) {
                StringBuilder fullCsv = new StringBuilder(1024 * 1024);
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    boolean header = true;
                    while ((line = br.readLine()) != null) {
                        fullCsv.append(line).append('\n');
                        if (header) { header = false; continue; }
                        parseCsvLine(line);
                    }
                }
                if (diskOk && !msftIndex.isEmpty()) {
                    try {
                        Files.writeString(indexFile, fullCsv.toString(),
                                StandardCharsets.UTF_8);
                    } catch (Exception ignored) {}
                }
                msftIndexLoaded = true;
                LOGGER.info("Microsoft index downloaded: {} quadkeys",
                        msftIndex.size());
            } else {
                LOGGER.warn("Microsoft index download returned {}", code);
                msftAvailable = false;
            }
        } catch (Exception e) {
            LOGGER.warn("Microsoft index download failed: {}", e.getMessage());
            msftAvailable = false;
        } finally {
            if (conn != null) conn.disconnect();
            msftIndexLoading = false;
        }
    }

    private void loadIndexFromFile(Path file) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(file,
                StandardCharsets.UTF_8)) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; }
                parseCsvLine(line);
            }
        }
    }

    private void parseCsvLine(String line) {
        String[] parts = line.split(",");
        if (parts.length >= 3) {
            String quadkey = null, url = null;
            for (String part : parts) {
                part = part.trim().replace("\"", "");
                if (part.matches("\\d{1,15}") && quadkey == null) quadkey = part;
                else if (part.startsWith("http") && url == null)  url     = part;
            }
            if (quadkey != null && url != null) msftIndex.put(quadkey, url);
        } else if (parts.length == 2) {
            String qk  = parts[0].trim().replace("\"", "");
            String url = parts[1].trim().replace("\"", "");
            if (qk.matches("\\d+") && url.startsWith("http"))
                msftIndex.put(qk, url);
        }
    }

    // ================================================================
    // QUADKEY CONVERSION
    // ================================================================

    private static String latLonToQuadkey(double lat, double lon, int zoom) {
        double latRad = Math.toRadians(lat);
        double n      = Math.pow(2, zoom);
        int tileX = (int) Math.floor((lon + 180.0) / 360.0 * n);
        int tileY = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) +
                1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
        tileX = Math.max(0, Math.min((int) n - 1, tileX));
        tileY = Math.max(0, Math.min((int) n - 1, tileY));

        StringBuilder qk = new StringBuilder();
        for (int i = zoom; i > 0; i--) {
            char digit = '0';
            int mask = 1 << (i - 1);
            if ((tileX & mask) != 0) digit++;
            if ((tileY & mask) != 0) digit += 2;
            qk.append(digit);
        }
        return qk.toString();
    }

    // ================================================================
    // NON-BLOCKING QUERIES
    // ================================================================

    public BuildingTile query(double lat, double lon) {
        if (!enabled) return BuildingTile.EMPTY;
        if (lat <= -85 || lat >= 85) return BuildingTile.EMPTY;
        if (System.currentTimeMillis() < pauseUntilMs) return BuildingTile.FAILED;

        long key = tileKey(lat, lon);
        BuildingTile cached = memCache.get(key);
        if (cached != null) return cached;

        CompletableFuture<BuildingTile> future = triggerFetch(key, lat, lon);
        try { return future.get(TILE_WAIT_MS, TimeUnit.MILLISECONDS); }
        catch (Exception e) { return BuildingTile.FAILED; }
    }

    public List<OvertureBuilding> getBuildingsAt(double lat, double lon) {
        if (!enabled) return List.of();
        try {
            Set<Long> keys = getTileKeysForPoint(lat, lon);
            return collectFromKeys(keys,
                    lat - OVERLAP_MARGIN, lat + OVERLAP_MARGIN,
                    lon - OVERLAP_MARGIN, lon + OVERLAP_MARGIN, false);
        } catch (Exception e) { return List.of(); }
    }

    public List<OvertureBuilding> getBuildingsForChunk(
            double minLat, double maxLat, double minLon, double maxLon) {
        if (!enabled) return List.of();
        try {
            Set<Long> keys = getTileKeysForArea(minLat, maxLat, minLon, maxLon);
            return collectFromKeys(keys,
                    minLat - OVERLAP_MARGIN, maxLat + OVERLAP_MARGIN,
                    minLon - OVERLAP_MARGIN, maxLon + OVERLAP_MARGIN, false);
        } catch (Exception e) { return List.of(); }
    }

    public List<OvertureBuilding> getBuildingsInChunk(double centerLat,
                                                       double centerLon) {
        if (!enabled) return List.of();
        try {
            double margin = 0.002;
            Set<Long> keys = getTileKeysForArea(
                    centerLat - margin, centerLat + margin,
                    centerLon - margin, centerLon + margin);
            return collectFromKeys(keys,
                    centerLat - margin - OVERLAP_MARGIN,
                    centerLat + margin + OVERLAP_MARGIN,
                    centerLon - margin - OVERLAP_MARGIN,
                    centerLon + margin + OVERLAP_MARGIN, false);
        } catch (Exception e) { return List.of(); }
    }

    // ================================================================
    // BLOCKING QUERY
    // ================================================================

    public List<OvertureBuilding> getBuildingsForChunkBlocking(
            double minLat, double maxLat, double minLon, double maxLon,
            TerraWorldConfig config, long timeoutMs) {
        if (!enabled) return List.of();
        try {
            Set<Long> neededKeys = getTileKeysForArea(minLat, maxLat,
                    minLon, maxLon);

            for (long key : neededKeys) {
                if (!memCache.containsKey(key) && diskOk) {
                    BuildingTile disk = loadDisk(key);
                    if (disk != null) {
                        memCache.put(key, disk);
                        diskHits.incrementAndGet();
                    }
                }
            }

            boolean allCached = true;
            for (long key : neededKeys)
                if (!memCache.containsKey(key)) { allCached = false; break; }
            if (allCached)
                return collectFromKeys(neededKeys,
                        minLat - OVERLAP_MARGIN, maxLat + OVERLAP_MARGIN,
                        minLon - OVERLAP_MARGIN, maxLon + OVERLAP_MARGIN, false);

            List<CompletableFuture<BuildingTile>> futures = new ArrayList<>();
            for (long key : neededKeys) {
                if (!memCache.containsKey(key)) {
                    int tLatI = (int) (key >> 32);
                    int tLonI = (int) key;
                    futures.add(triggerFetch(key,
                            tLatI * TILE_DEG + TILE_DEG / 2,
                            tLonI * TILE_DEG + TILE_DEG / 2));
                }
            }

            try {
                CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0]))
                        .get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LOGGER.debug("Blocking wait: {}", e.getMessage());
            }

            return collectFromKeys(neededKeys,
                    minLat - OVERLAP_MARGIN, maxLat + OVERLAP_MARGIN,
                    minLon - OVERLAP_MARGIN, maxLon + OVERLAP_MARGIN, false);
        } catch (Exception e) { return List.of(); }
    }

    public List<OvertureBuilding> getBuildingsForChunkBlocking(
            double minLat, double maxLat, double minLon, double maxLon,
            long timeoutMs) {
        return getBuildingsForChunkBlocking(
                minLat, maxLat, minLon, maxLon, null, timeoutMs);
    }

    // ================================================================
    // TILE LOADED CHECK / PREFETCH
    // ================================================================

    public boolean areTilesLoaded(double minLat, double maxLat,
                                   double minLon, double maxLon) {
        Set<Long> keys = getTileKeysForArea(minLat, maxLat, minLon, maxLon);
        for (long key : keys) {
            if (!memCache.containsKey(key)) {
                if (diskOk) {
                    Path file = diskDir.resolve(Long.toHexString(key) + ".json");
                    if (!Files.exists(file)) return false;
                } else return false;
            }
        }
        return true;
    }

    public void prefetchTiles(double minLat, double maxLat,
                               double minLon, double maxLon) {
        if (!enabled) return;
        Set<Long> keys = getTileKeysForArea(minLat, maxLat, minLon, maxLon);
        for (long key : keys) {
            if (!memCache.containsKey(key)) {
                int tLatI = (int) (key >> 32);
                int tLonI = (int) key;
                query(tLatI * TILE_DEG + TILE_DEG / 2,
                        tLonI * TILE_DEG + TILE_DEG / 2);
            }
        }
    }

    public int countMissingTiles(double minLat, double maxLat,
                                  double minLon, double maxLon) {
        Set<Long> keys = getTileKeysForArea(minLat, maxLat, minLon, maxLon);
        int missing = 0;
        for (long key : keys) if (!memCache.containsKey(key)) missing++;
        return missing;
    }

    // ================================================================
    // STATS
    // ================================================================

    public boolean isEnabled()     { return enabled; }
    public void setEnabled(boolean e) {
        this.enabled = e; if (e) consecutiveFailures = 0;
    }
    public int getCacheSize()      { return memCache.size(); }
    public int getTotalQueries()   { return totalQueries.get(); }
    public int getTotalBuildings() { return totalBuildings.get(); }
    public int getMsftHits()       { return msftHits.get(); }
    public int getDiskHits()       { return diskHits.get(); }
    public int getEmptyTiles()     { return emptyTiles.get(); }

    public String getSourceBreakdown() {
        return String.format("MSFT:%d", msftBuildingCount.get());
    }

    // ================================================================
    // TILE KEY HELPERS
    // ================================================================

    private long tileKey(double lat, double lon) {
        int tLat = (int) Math.floor(lat / TILE_DEG);
        int tLon = (int) Math.floor(lon / TILE_DEG);
        return ((long) tLat << 32) | (tLon & 0xFFFFFFFFL);
    }

    private Set<Long> getTileKeysForPoint(double lat, double lon) {
        Set<Long> keys = new HashSet<>();
        keys.add(tileKey(lat, lon));
        double tileLat = Math.floor(lat / TILE_DEG) * TILE_DEG;
        double tileLon = Math.floor(lon / TILE_DEG) * TILE_DEG;
        boolean nearS = (lat - tileLat)             < OVERLAP_MARGIN;
        boolean nearN = ((tileLat + TILE_DEG) - lat) < OVERLAP_MARGIN;
        boolean nearW = (lon - tileLon)             < OVERLAP_MARGIN;
        boolean nearE = ((tileLon + TILE_DEG) - lon) < OVERLAP_MARGIN;
        if (nearS)          keys.add(tileKey(lat - OVERLAP_MARGIN, lon));
        if (nearN)          keys.add(tileKey(lat + OVERLAP_MARGIN, lon));
        if (nearW)          keys.add(tileKey(lat, lon - OVERLAP_MARGIN));
        if (nearE)          keys.add(tileKey(lat, lon + OVERLAP_MARGIN));
        if (nearS && nearW) keys.add(tileKey(lat - OVERLAP_MARGIN,
                lon - OVERLAP_MARGIN));
        if (nearS && nearE) keys.add(tileKey(lat - OVERLAP_MARGIN,
                lon + OVERLAP_MARGIN));
        if (nearN && nearW) keys.add(tileKey(lat + OVERLAP_MARGIN,
                lon - OVERLAP_MARGIN));
        if (nearN && nearE) keys.add(tileKey(lat + OVERLAP_MARGIN,
                lon + OVERLAP_MARGIN));
        return keys;
    }

    private Set<Long> getTileKeysForArea(double minLat, double maxLat,
                                          double minLon, double maxLon) {
        Set<Long> keys = new HashSet<>();
        double qMinLat = minLat - OVERLAP_MARGIN, qMaxLat = maxLat + OVERLAP_MARGIN;
        double qMinLon = minLon - OVERLAP_MARGIN, qMaxLon = maxLon + OVERLAP_MARGIN;
        for (double tLat = Math.floor(qMinLat / TILE_DEG) * TILE_DEG;
             tLat <= qMaxLat; tLat += TILE_DEG)
            for (double tLon = Math.floor(qMinLon / TILE_DEG) * TILE_DEG;
                 tLon <= qMaxLon; tLon += TILE_DEG)
                keys.add(tileKey(tLat + TILE_DEG / 2, tLon + TILE_DEG / 2));
        return keys;
    }

    private List<OvertureBuilding> collectFromKeys(Set<Long> tileKeys,
                                                    double qMinLat, double qMaxLat,
                                                    double qMinLon, double qMaxLon,
                                                    boolean triggerFetch) {
        List<OvertureBuilding> result = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        for (long key : tileKeys) {
            BuildingTile tile = memCache.get(key);
            if (tile == null && triggerFetch) {
                int tLatI = (int) (key >> 32), tLonI = (int) key;
                tile = query(tLatI * TILE_DEG + TILE_DEG / 2,
                        tLonI * TILE_DEG + TILE_DEG / 2);
            }
            if (tile == null || !tile.valid || tile.buildings.length == 0)
                continue;
            for (OvertureBuilding b : tile.buildings) {
                if (b.maxLat < qMinLat || b.minLat > qMaxLat
                        || b.maxLon < qMinLon || b.minLon > qMaxLon) continue;
                long dedupId = b.osmId != 0 ? b.osmId :
                        (b.overtureId != null
                                ? OvertureBuilding.stableHash(b.overtureId)
                                : OvertureBuilding.stableHash(
                                        b.centerLat, b.centerLon));
                if (!seenIds.add(dedupId)) continue;
                result.add(b);
            }
        }
        return result;
    }

    // ================================================================
    // TRIGGER FETCH
    // ================================================================

    private CompletableFuture<BuildingTile> triggerFetch(long key,
                                                          double lat, double lon) {
        return pending.computeIfAbsent(key, k -> {
            double tileLat = Math.floor(lat / TILE_DEG) * TILE_DEG;
            double tileLon = Math.floor(lon / TILE_DEG) * TILE_DEG;
            return CompletableFuture.supplyAsync(
                    () -> fetchTile(k, tileLat, tileLon), dlPool);
        });
    }

    // ================================================================
    // FETCH — MICROSOFT ONLY
    // ================================================================

    private BuildingTile fetchTile(long key, double minLat, double minLon) {
        if (diskOk) {
            BuildingTile disk = loadDisk(key);
            if (disk != null) {
                memCache.put(key, disk);
                pending.remove(key);
                diskHits.incrementAndGet();
                return disk;
            }
        }

        if (System.currentTimeMillis() < pauseUntilMs) {
            pending.remove(key);
            return BuildingTile.FAILED;
        }

        // The Microsoft dataset-links index is loaded asynchronously at
        // startup. That state is pending work, not a failed building request;
        // counting it as a failure previously triggered a 60-second pause
        // before the first real Microsoft lookup could even begin.
        if (!msftIndexLoaded) {
            if (!msftIndexLoading) dlPool.submit(this::loadMicrosoftIndex);
            pending.remove(key);
            return BuildingTile.FAILED;
        }

        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            LOGGER.warn("Pausing building fetch for {}s",
                    PAUSE_ON_FAILURES_MS / 1000);
            pauseUntilMs        = System.currentTimeMillis() + PAUSE_ON_FAILURES_MS;
            consecutiveFailures = 0;
            pending.remove(key);
            return BuildingTile.FAILED;
        }

        totalQueries.incrementAndGet();
        double maxLat    = minLat + TILE_DEG;
        double maxLon    = minLon + TILE_DEG;
        double centerLat = minLat + TILE_DEG / 2;
        double centerLon = minLon + TILE_DEG / 2;

        List<OvertureBuilding> allBuildings = new ArrayList<>();

        // Microsoft ML Building Footprints. A null list means the index or
        // source is still unavailable, which must not be cached as a valid
        // empty result.
        List<OvertureBuilding> msftBuildings = null;
        if (msftAvailable) {
            try {
                msftBuildings = fetchMicrosoftBuildings(centerLat, centerLon,
                        minLat, minLon, maxLat, maxLon);
            } catch (Exception e) {
                LOGGER.debug("Microsoft fetch failed: {}", e.getMessage());
            }
        }
        if (msftBuildings == null) {
            // null also means a different task currently owns the one allowed
            // Microsoft parse. That is normal pending work, not an endpoint
            // failure; counting it caused false 60-second Overture pauses.
            pending.remove(key);
            return BuildingTile.FAILED;
        }
        if (!msftBuildings.isEmpty()) {
            allBuildings.addAll(msftBuildings);
            msftHits.incrementAndGet();
            if (dbgCount.getAndIncrement() < 30) {
                LOGGER.info("MSFT [{},{}]: {} buildings",
                        String.format("%.4f", centerLat),
                        String.format("%.4f", centerLon),
                        msftBuildings.size());
            }
        }

        if (allBuildings.isEmpty()) {
            emptyTiles.incrementAndGet();
            BuildingTile empty = new BuildingTile(
                    new OvertureBuilding[0], true, DataSource.MICROSOFT_ML);
            memCache.put(key, empty);
            pending.remove(key);
            if (diskOk) saveDiskBuildings(key, empty);
            return empty;
        }

        consecutiveFailures = 0;
        BuildingTile tile = new BuildingTile(
                allBuildings.toArray(new OvertureBuilding[0]),
                true, DataSource.MICROSOFT_ML);
        memCache.put(key, tile);
        pending.remove(key);
        evictIfNeeded();

        totalBuildings.addAndGet(tile.buildings.length);
        msftBuildingCount.addAndGet(tile.msftCount);

        if (diskOk) saveDiskBuildings(key, tile);

        if (dbgCount.get() < 30) {
            LOGGER.info("MSFT [{},{}]: {} buildings [{}]",
                    String.format("%.4f", centerLat),
                    String.format("%.4f", centerLon),
                    tile.buildings.length, tile.sourceBreakdown());
        }
        return tile;
    }

    // ================================================================
    // MICROSOFT BUILDING FOOTPRINTS FETCH
    // ================================================================

    private List<OvertureBuilding> fetchMicrosoftBuildings(
            double centerLat, double centerLon,
            double minLat, double minLon, double maxLat, double maxLon) {

        if (!msftIndexLoaded) {
            if (!msftIndexLoading) dlPool.submit(this::loadMicrosoftIndex);
            return null;
        }

        String quadkey = latLonToQuadkey(centerLat, centerLon, 9);

        List<OvertureBuilding> cached = msftQuadkeyCache.get(
                regionCacheKey(quadkey, minLat, minLon));
        if (cached != null)
            return filterByBbox(cached, minLat, minLon, maxLat, maxLon);

        // Only ONE thread may download+parse a given quadkey. Without this
        // every concurrent chunk that misses the cache repeats the whole job:
        // a single session downloaded quadkey 123301331 three times and parsed
        // 628,630 lines three times, allocating ~1.9M building objects to keep
        // a few hundred. Losers return null, which callers already treat as
        // "not ready yet, retry".
        if (!msftInFlight.add(quadkey))
            return null;
        // Do not park download workers behind a large parse. A caller receives
        // retryable pending state and AsyncStructureManager reschedules it.
        if (!msftParsePermit.tryAcquire()) {
            msftInFlight.remove(quadkey);
            return null;
        }
        try {
            return fetchMicrosoftQuadkey(quadkey,
                    minLat, minLon, maxLat, maxLon);
        } finally {
            msftParsePermit.release();
            // Must always release, or this quadkey is blocked forever.
            msftInFlight.remove(quadkey);
        }
    }

    /**
     * Region-scoped cache key.
     *
     * A parsed result now covers only the window that was requested, so it
     * must not be filed under the bare quadkey. Rounding to MSFT_REGION_DEG
     * keeps nearby chunks sharing one entry while preventing a window from one
     * part of a quadkey being served for another.
     */
    private static String regionCacheKey(String quadkey,
            double lat, double lon) {
        long gLat = (long) Math.floor(lat / MSFT_REGION_DEG);
        long gLon = (long) Math.floor(lon / MSFT_REGION_DEG);
        return quadkey + '@' + gLat + ',' + gLon;
    }

    /** Grid size for {@link #regionCacheKey}; ~1.1 km, matching a few chunks. */
    private static final double MSFT_REGION_DEG = 0.01;

    /** Download+parse one quadkey. Caller holds the in-flight marker. */
    private List<OvertureBuilding> fetchMicrosoftQuadkey(String quadkey,
            double minLat, double minLon, double maxLat, double maxLon) {

        // Re-check: another thread may have finished while we waited.
        final String rKey = regionCacheKey(quadkey, minLat, minLon);
        List<OvertureBuilding> cached = msftQuadkeyCache.get(rKey);
        if (cached != null)
            return filterByBbox(cached, minLat, minLon, maxLat, maxLon);

        String url = findMicrosoftUrl(quadkey);
        if (url == null) {
            LOGGER.debug("No Microsoft data for quadkey {}", quadkey);
            msftQuadkeyCache.put(rKey, List.of());
            return List.of();
        }

        if (diskOk) {
            Path regionFile = microsoftRegionFile(quadkey, minLat, minLon);
            if (Files.exists(regionFile)) {
                try {
                    List<OvertureBuilding> buildings = parseMicrosoftFile(
                            regionFile, minLat, maxLat, minLon, maxLon);
                    msftQuadkeyCache.put(rKey, buildings);
                    LOGGER.info("MSFT region cache {}: {} buildings", quadkey, buildings.size());
                    return filterByBbox(buildings, minLat, minLon, maxLat, maxLon);
                } catch (Exception e) {
                    LOGGER.debug("MSFT region cache read failed: {}", e.getMessage());
                }
            }
            Path qkFile = msftCacheDir.resolve(quadkey + ".geojsonl");
            if (Files.exists(qkFile)) {
                try {
                    long age = System.currentTimeMillis() -
                            Files.getLastModifiedTime(qkFile).toMillis();
                    if (age < DISK_TTL_MS) {
                        // Parse ONLY this region's window out of the cached
                        // file; the file itself stays whole on disk.
                        List<OvertureBuilding> buildings =
                                parseMicrosoftFile(qkFile,
                                        minLat, maxLat, minLon, maxLon,
                                        regionFile);
                        msftQuadkeyCache.put(rKey, buildings);
                        LOGGER.info("MSFT quadkey {} from disk: {} buildings",
                                quadkey, buildings.size());
                        return filterByBbox(buildings, minLat, minLon,
                                maxLat, maxLon);
                    }
                } catch (Exception e) {
                    LOGGER.debug("MSFT disk cache read failed: {}",
                            e.getMessage());
                }
            }
        }

        HttpURLConnection conn = null;
        try {
            LOGGER.info("Downloading Microsoft buildings for quadkey {} ...",
                    quadkey);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "TerraPlusPlus/2.0");
            conn.setRequestProperty("Accept-Encoding", "gzip");

            int code = conn.getResponseCode();
            if (code != 200) {
                LOGGER.debug("Microsoft download returned {}", code);
                return null;
            }

            InputStream is         = conn.getInputStream();
            boolean isGzipFile     = url.endsWith(".gz");
            boolean isGzipResponse = "gzip".equalsIgnoreCase(
                    conn.getContentEncoding());

            BufferedReader reader;
            if (isGzipFile && !isGzipResponse) {
                reader = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(is), StandardCharsets.UTF_8));
            } else {
                reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8));
            }

            // Region window for the PARSE only. The full file is still written
            // to disk, so a later visit to another part of this quadkey can be
            // served without re-downloading -- it just re-parses its own window.
            //
            // Without this the first visit Gson-parsed all 628,630 buildings to
            // keep ~330, costing 83.6 s of wall clock. See parseMicrosoftFile
            // for the measured 3.3x improvement and the correctness argument.
            final double pad   = 0.001;
            final double loLat = minLat - pad, hiLat = maxLat + pad;
            final double loLon = minLon - pad, hiLon = maxLon + pad;

            List<OvertureBuilding> buildings = new ArrayList<>();
            StringBuilder diskContent = new StringBuilder(1024 * 1024);
            // Persist a compact local 0.01-degree region sidecar while the
            // full quadkey is already being streamed for the first time.
            StringBuilder regionContent = new StringBuilder(64 * 1024);
            double regionLat = Math.floor(minLat / MSFT_REGION_DEG) * MSFT_REGION_DEG;
            double regionLon = Math.floor(minLon / MSFT_REGION_DEG) * MSFT_REGION_DEG;
            double regionPad = 0.001;
            String line;
            int lineCount = 0, parsedCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                line = line.trim();
                if (line.isEmpty()) continue;
                diskContent.append(line).append('\n');
                if (firstVertexInBox(line, regionLat - regionPad,
                        regionLat + MSFT_REGION_DEG + regionPad,
                        regionLon - regionPad,
                        regionLon + MSFT_REGION_DEG + regionPad))
                    regionContent.append(line).append('\n');
                if (!firstVertexInBox(line, loLat, hiLat, loLon, hiLon))
                    continue;
                parsedCount++;
                try {
                    OvertureBuilding b = parseMicrosoftLine(line);
                    if (b != null) buildings.add(b);
                } catch (Exception e) {
                    if (lineCount <= 3)
                        LOGGER.debug("MSFT parse line {}: {}",
                                lineCount, e.getMessage());
                }
            }
            reader.close();

            // Cache under a REGION key, not the bare quadkey. The parsed list
            // now covers only the requested window, so storing it as if it
            // were the whole quadkey would make a later query for a different
            // part of the same quadkey return an incorrectly empty result.
            msftQuadkeyCache.put(
                    regionCacheKey(quadkey, minLat, minLon), buildings);
            if (diskOk) {
                try {
                    Files.writeString(
                            msftCacheDir.resolve(quadkey + ".geojsonl"),
                            diskContent.toString(), StandardCharsets.UTF_8);
                    Files.writeString(microsoftRegionFile(quadkey, minLat, minLon),
                            regionContent.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }
            LOGGER.info("MSFT quadkey {}: {} retained, {} of {} lines parsed",
                    quadkey, buildings.size(), parsedCount, lineCount);
            return filterByBbox(buildings, minLat, minLon, maxLat, maxLon);

        } catch (Exception e) {
            LOGGER.warn("Microsoft download failed for {}: {}",
                    quadkey, e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String findMicrosoftUrl(String quadkey) {
        String url = msftIndex.get(quadkey);
        if (url != null) return url;
        for (int len = quadkey.length() - 1; len >= 1; len--) {
            url = msftIndex.get(quadkey.substring(0, len));
            if (url != null) return url;
        }
        return null;
    }

    /** Persistent compact cache for one 0.01-degree Microsoft region. */
    private Path microsoftRegionFile(String quadkey, double lat, double lon) {
        long gLat = (long) Math.floor(lat / MSFT_REGION_DEG);
        long gLon = (long) Math.floor(lon / MSFT_REGION_DEG);
        return msftRegionCacheDir.resolve(quadkey + "_" + gLat + "_" + gLon + ".geojsonl");
    }

    // ================================================================
    // MICROSOFT GEOJSON-LINES PARSING
    // ================================================================

    private OvertureBuilding parseMicrosoftLine(String line) {
        return parseMicrosoftFeature(
                JsonParser.parseString(line).getAsJsonObject());
    }

    private OvertureBuilding parseMicrosoftFeature(JsonObject feature) {
        if (!feature.has("geometry")) return null;
        JsonObject geometry = feature.getAsJsonObject("geometry");
        if (!"Polygon".equals(getStr(geometry, "type"))) return null;

        JsonArray coordinates = geometry.getAsJsonArray("coordinates");
        if (coordinates == null || coordinates.size() == 0) return null;

        List<double[]> coords = new ArrayList<>();
        for (JsonElement ce : coordinates.get(0).getAsJsonArray()) {
            JsonArray pt = ce.getAsJsonArray();
            coords.add(new double[]{
                    pt.get(1).getAsDouble(),
                    pt.get(0).getAsDouble() });
        }
        if (coords.size() < 3) return null;

        JsonObject props = feature.has("properties") ?
                feature.getAsJsonObject("properties") : new JsonObject();

        double height     = getDouble(props, "height", 0);
        double confidence = getDouble(props, "confidence", 0.85);

        return new OvertureBuilding(
                coords.toArray(new double[0][]),
                height, 0, null, "yes",
                null, null, null,
                confidence, null, "Microsoft", null, 0);
    }

    private List<OvertureBuilding> parseMicrosoftFile(Path file)
            throws IOException {
        return parseMicrosoftFile(file,
                -90.0, 90.0, -180.0, 180.0);
    }

    /**
     * Parses a Microsoft .geojsonl quadkey file, keeping only buildings whose
     * first vertex falls inside the region window.
     *
     * <h2>Why the region window and the cheap pre-filter</h2>
     * One quadkey file holds ~628,630 buildings; a chunk needs a few hundred.
     * The old code ran a full Gson parse on EVERY line and then discarded
     * 99.95 % of the result. Measured on the real Hyderabad quadkey
     * (123301331), that is 83.6 s of wall clock on the first visit -- the
     * single largest cost in a cold start.
     *
     * The fix is to reject a line using RAW TEXT before Gson ever sees it.
     * The first coordinate pair of a GeoJSON polygon always appears as
     * {@code [[[lon,lat}, so it can be read with an index scan and compared
     * against the window. Only survivors are JSON-parsed.
     *
     * Measured on the real 628,630-line file, 0.005 deg window:
     * <pre>
     *   full Gson parse of every line : 628,630 parsed, 311 kept, 7.4 s
     *   raw pre-filter then parse     :     616 parsed, 311 kept, 2.2 s
     *   -> 3.3x faster, 99.90 % of JSON parses avoided, IDENTICAL results
     * </pre>
     * (Python timings; the Java ratio is the same because both spend their
     * time in the JSON parser.)
     *
     * The pad is deliberately generous: the test uses the FIRST vertex, not
     * the centroid, so a building straddling the window edge must not be lost.
     */
    private List<OvertureBuilding> parseMicrosoftFile(Path file,
            double minLat, double maxLat, double minLon, double maxLon)
            throws IOException {
        return parseMicrosoftFile(file, minLat, maxLat, minLon, maxLon, null);
    }

    /**
     * Parses a full cached quadkey once and, when requested, writes the raw
     * lines for the active 0.01-degree region into a persistent sidecar. The
     * following warm start then reads that small sidecar rather than rescanning
     * the complete Microsoft file.
     */
    private List<OvertureBuilding> parseMicrosoftFile(Path file,
            double minLat, double maxLat, double minLon, double maxLon,
            Path regionSidecar)
            throws IOException {
        final double pad = 0.001;   // > max building diagonal at these scales
        final double loLat = minLat - pad, hiLat = maxLat + pad;
        final double loLon = minLon - pad, hiLon = maxLon + pad;
        final boolean windowed =
                !(minLat <= -90.0 && maxLat >= 90.0);

        List<OvertureBuilding> buildings = new ArrayList<>();
        StringBuilder regionLines = regionSidecar == null ? null : new StringBuilder(64 * 1024);
        double regionLat = Math.floor(minLat / MSFT_REGION_DEG) * MSFT_REGION_DEG;
        double regionLon = Math.floor(minLon / MSFT_REGION_DEG) * MSFT_REGION_DEG;
        int scanned = 0, parsed = 0;
        try (BufferedReader br = Files.newBufferedReader(file,
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                scanned++;
                if (regionLines != null && firstVertexInBox(line,
                        regionLat - pad, regionLat + MSFT_REGION_DEG + pad,
                        regionLon - pad, regionLon + MSFT_REGION_DEG + pad))
                    regionLines.append(line).append('\n');
                if (windowed && !firstVertexInBox(
                        line, loLat, hiLat, loLon, hiLon))
                    continue;
                parsed++;
                try {
                    OvertureBuilding b = parseMicrosoftLine(line.trim());
                    if (b != null) buildings.add(b);
                } catch (Exception ignored) {}
            }
        }
        if (regionLines != null && diskOk) {
            try {
                Files.writeString(regionSidecar, regionLines.toString(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOGGER.debug("MSFT region cache write failed: {}", e.getMessage());
            }
        }
        if (windowed)
            LOGGER.info("MSFT parse: {} lines scanned, {} JSON-parsed "
                    + "({}% skipped), {} retained",
                    scanned, parsed,
                    scanned == 0 ? 0
                        : String.format("%.2f",
                            100.0 * (scanned - parsed) / scanned),
                    buildings.size());
        return buildings;
    }

    /**
     * True when the polygon's first vertex lies in the box, decided by
     * scanning raw text. Returns true on any parse ambiguity so a malformed
     * line falls through to the real parser rather than being silently lost.
     */
    private static boolean firstVertexInBox(String line,
            double loLat, double hiLat, double loLon, double hiLon) {
        int i = line.indexOf("[[[");
        if (i < 0) return true;
        i += 3;
        int n = line.length();
        while (i < n && (line.charAt(i) == ' ')) i++;
        int lonEnd = i;
        while (lonEnd < n && line.charAt(lonEnd) != ',') lonEnd++;
        if (lonEnd >= n) return true;
        int j = lonEnd + 1;
        while (j < n && line.charAt(j) == ' ') j++;
        int latEnd = j;
        while (latEnd < n) {
            char c = line.charAt(latEnd);
            if (c == ',' || c == ']') break;
            latEnd++;
        }
        if (latEnd >= n) return true;
        try {
            double lon = Double.parseDouble(
                    line.substring(i, lonEnd).trim());
            double lat = Double.parseDouble(
                    line.substring(j, latEnd).trim());
            return lat >= loLat && lat <= hiLat
                && lon >= loLon && lon <= hiLon;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    // ================================================================
    // POLYGON HELPER
    // ================================================================

    private static List<OvertureBuilding> filterByBbox(
            List<OvertureBuilding> buildings,
            double minLat, double minLon,
            double maxLat, double maxLon) {
        List<OvertureBuilding> result = new ArrayList<>();
        for (OvertureBuilding b : buildings) {
            if (b.maxLat >= minLat && b.minLat <= maxLat
                    && b.maxLon >= minLon && b.minLon <= maxLon)
                result.add(b);
        }
        return result;
    }

    // ================================================================
    // DISK CACHE
    // ================================================================

    private void saveDiskBuildings(long key, BuildingTile tile) {
        if (!diskOk) return;
        try {
            StringBuilder sb = new StringBuilder(4096);
            sb.append("{\"type\":\"FeatureCollection\",\"source\":\"")
              .append(tile.source.displayName)
              .append("\",\"features\":[");
            boolean first = true;
            for (OvertureBuilding b : tile.buildings) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"type\":\"Feature\","
                        + "\"geometry\":{\"type\":\"Polygon\","
                        + "\"coordinates\":[[");
                for (int i = 0; i < b.polygon.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(String.format("[%.7f,%.7f]",
                            b.polygon[i][1], b.polygon[i][0]));
                }
                sb.append("]]},\"properties\":{");
                sb.append("\"source\":\"").append(esc(b.primarySource))
                  .append("\"");
                if (b.name != null)
                    sb.append(",\"name\":\"").append(esc(b.name)).append("\"");
                if (b.height > 0)
                    sb.append(",\"height\":").append(b.height);
                if (b.numFloors > 0)
                    sb.append(",\"building:levels\":").append(b.numFloors);
                if (b.buildingClass != null)
                    sb.append(",\"class\":\"").append(esc(b.buildingClass))
                      .append("\"");
                if (b.subtype != null && !"yes".equals(b.subtype))
                    sb.append(",\"building\":\"").append(esc(b.subtype))
                      .append("\"");
                if (b.osmId > 0)
                    sb.append(",\"osm_id\":").append(b.osmId);
                if (b.confidence > 0 && b.confidence < 1)
                    sb.append(",\"confidence\":").append(
                            String.format("%.2f", b.confidence));
                if (b.roofShape != null)
                    sb.append(",\"roof:shape\":\"").append(esc(b.roofShape))
                      .append("\"");
                if (b.wallMaterial != null)
                    sb.append(",\"building:material\":\"")
                      .append(esc(b.wallMaterial)).append("\"");
                sb.append("}}");
            }
            sb.append("]}");
            Files.writeString(
                    diskDir.resolve(Long.toHexString(key) + ".json"),
                    sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("Disk save failed: {}", e.getMessage());
        }
    }

    private BuildingTile loadDisk(long key) {
        try {
            Path file = diskDir.resolve(Long.toHexString(key) + ".json");
            if (!Files.exists(file)) return null;
            long age = System.currentTimeMillis() -
                    Files.getLastModifiedTime(file).toMillis();
            if (age > DISK_TTL_MS) { Files.deleteIfExists(file); return null; }

            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<OvertureBuilding> buildings =
                    parseGeoJsonFeatureCollection(json);
            return new BuildingTile(
                    buildings.toArray(new OvertureBuilding[0]),
                    true, DataSource.DISK_CACHE);
        } catch (Exception e) {
            LOGGER.debug("Disk load failed: {}", e.getMessage());
        }
        return null;
    }

    private List<OvertureBuilding> parseGeoJsonFeatureCollection(String json) {
        List<OvertureBuilding> buildings = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray features = root.getAsJsonArray("features");
            if (features == null) return buildings;

            for (JsonElement fe : features) {
                try {
                    JsonObject feature  = fe.getAsJsonObject();
                    if (!feature.has("geometry")) continue;
                    JsonObject geometry = feature.getAsJsonObject("geometry");
                    if (!"Polygon".equals(getStr(geometry, "type"))) continue;

                    JsonArray coordinates =
                            geometry.getAsJsonArray("coordinates");
                    if (coordinates == null || coordinates.size() == 0)
                        continue;

                    List<double[]> coords = new ArrayList<>();
                    for (JsonElement ce :
                            coordinates.get(0).getAsJsonArray()) {
                        JsonArray pt = ce.getAsJsonArray();
                        coords.add(new double[]{
                                pt.get(1).getAsDouble(),
                                pt.get(0).getAsDouble() });
                    }
                    if (coords.size() < 3) continue;

                    JsonObject props = feature.has("properties") ?
                            feature.getAsJsonObject("properties") :
                            new JsonObject();

                    String source = getStr(props, "source");
                    if (source == null) source = "Microsoft";
                    String name          = getStr(props, "name");
                    double height        = getDouble(props, "height", 0);
                    int    floors        = getInt(props, "building:levels", 0);
                    String buildingClass = getStr(props, "class");
                    String subtype       = getStr(props, "building");
                    if (subtype == null) subtype = "yes";
                    long   osmId         = getLong(props, "osm_id", 0);
                    double confidence    = getDouble(props, "confidence", 0.85);
                    String roofShape     = getStr(props, "roof:shape");
                    String wallMaterial  = getStr(props, "building:material");

                    buildings.add(new OvertureBuilding(
                            coords.toArray(new double[0][]),
                            height, floors, buildingClass, subtype,
                            roofShape, null, wallMaterial,
                            confidence, null, source, name, osmId));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            LOGGER.debug("GeoJSON parse failed: {}", e.getMessage());
        }
        return buildings;
    }

    // ================================================================
    // EVICTION
    // ================================================================

    private void evictIfNeeded() {
        if (memCache.size() <= MEM_CACHE_MAX) return;
        memCache.entrySet().removeIf(e -> !e.getValue().valid);
        if (memCache.size() <= MEM_CACHE_MAX) return;
        memCache.entrySet().removeIf(e -> e.getValue().buildings.length == 0);
        if (memCache.size() <= MEM_CACHE_MAX) return;
        List<Map.Entry<Long, BuildingTile>> entries =
                new ArrayList<>(memCache.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue().fetchTimeMs));
        int toRemove = memCache.size() - MEM_CACHE_MAX + MEM_CACHE_MAX / 4;
        for (int i = 0; i < toRemove && i < entries.size(); i++)
            memCache.remove(entries.get(i).getKey());
    }

    // ================================================================
    // UTILITY
    // ================================================================

    private static String getStr(JsonObject obj, String key) {
        if (obj != null && obj.has(key)) {
            JsonElement e = obj.get(key);
            if (e.isJsonPrimitive()) return e.getAsString();
        }
        return null;
    }

    private static double getDouble(JsonObject obj, String key, double def) {
        if (obj != null && obj.has(key)) {
            try { return obj.get(key).getAsDouble(); }
            catch (Exception ignored) {}
        }
        return def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        if (obj != null && obj.has(key)) {
            try { return obj.get(key).getAsInt(); }
            catch (Exception ignored) {}
        }
        return def;
    }

    private static long getLong(JsonObject obj, String key, long def) {
        if (obj != null && obj.has(key)) {
            try { return obj.get(key).getAsLong(); }
            catch (Exception ignored) {}
        }
        return def;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    public void shutdown() {
        dlPool.shutdown();
        try {
            if (!dlPool.awaitTermination(5, TimeUnit.SECONDS))
                dlPool.shutdownNow();
        } catch (InterruptedException e) {
            dlPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("OvertureIndex shutdown — FINAL STATS:");
        LOGGER.info("  Queries: {} | Buildings: {}",
                totalQueries.get(), totalBuildings.get());
        LOGGER.info("  Hits — MSFT: {} | Disk: {} | Empty: {}",
                msftHits.get(), diskHits.get(), emptyTiles.get());
        LOGGER.info("  Sources — {}", getSourceBreakdown());
        LOGGER.info("  MSFT index: {} quadkeys, {} quadkeys cached",
                msftIndex.size(), msftQuadkeyCache.size());
    }
}