// ============================================================
// FILE: EsaWorldCoverIndex.java
// Source: ESA WorldCover 2021 v200 — direct S3 COG access
// Method: HTTP Range requests on Cloud Optimized GeoTIFF
// No full file download — fetches only the needed internal tile
// Coverage: Global, 3°x3° tiles, 10m resolution
// Changes: Rate limiting removed for faster S3 access
// ============================================================
package net.buildtheearth.terraplusplus.generator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Inflater;

public class EsaWorldCoverIndex {

    private static final Logger LOGGER = LogManager.getLogger("Terra++WorldCover");

    // ================================================================
    // WORLD COVER CLASSES
    // ================================================================

    public enum WorldCoverClass {
        TREE_COVER  (10,  "Tree cover"),
        SHRUBLAND   (20,  "Shrubland"),
        GRASSLAND   (30,  "Grassland"),
        CROPLAND    (40,  "Cropland"),
        BUILT_UP    (50,  "Built-up"),
        BARE        (60,  "Bare / sparse vegetation"),
        SNOW_ICE    (70,  "Snow and ice"),
        WATER       (80,  "Permanent water bodies"),
        WETLAND     (90,  "Herbaceous wetland"),
        MANGROVES   (95,  "Mangroves"),
        MOSS_LICHEN (100, "Moss and lichen"),
        UNKNOWN     (0,   "Unknown");

        public final int    value;
        public final String displayName;

        WorldCoverClass(int value, String displayName) {
            this.value       = value;
            this.displayName = displayName;
        }

        public static WorldCoverClass fromValue(int v) {
            for (WorldCoverClass wc : values())
                if (wc.value == v) return wc;
            return UNKNOWN;
        }
    }

    // ================================================================
    // S3 TILE CONSTANTS
    // ================================================================

    /**
     * ESA WorldCover 2021 v200 S3 bucket.
     * Each file covers a 3° x 3° area at 10m resolution.
     * Publicly accessible, no authentication required.
     */
    private static final String S3_BASE =
        "https://esa-worldcover.s3.eu-central-1.amazonaws.com" +
        "/v200/2021/map/ESA_WorldCover_10m_2021_v200_%s_Map.tif";

    /**
     * Each S3 tile covers exactly 3 degrees in both lat and lon.
     * Tile origin is the BOTTOM-LEFT (SW) corner of the tile.
     */
    private static final int    TILE_DEG     = 3;

    /**
     * At 10m resolution, a 3° tile has:
     * 3 * 111320 / 10 = 33396 pixels per side (approximately).
     * ESA uses exactly 33000 pixels for some tiles, 36000 for others.
     * We read this from the actual TIFF header.
     */
    private static final int    DEFAULT_PIXELS = 33000;

    /**
     * Internal COG tile size — ESA WorldCover uses 256x256 internally.
     */
    private static final int    COG_TILE_PX  = 256;

    private static final int    HTTP_TIMEOUT  = 20000;
    private static final int    MAX_RETRY     = 3;
    private static final int    DL_THREADS    = 3;

    /**
     * How many bytes to fetch for the TIFF header.
     * Must be large enough to contain the IFD with all tile offsets.
     * For WorldCover tiles with ~16,000 internal tiles, the IFD
     * can be several MB. We fetch progressively if needed.
     */
    private static final int HEADER_FETCH_BYTES = 4 * 1024 * 1024; // 4MB

    // ================================================================
    // TIFF TAG CONSTANTS
    // ================================================================

    private static final int TAG_IMAGE_WIDTH        = 256;
    private static final int TAG_IMAGE_HEIGHT       = 257;
    private static final int TAG_BITS_PER_SAMPLE    = 258;
    private static final int TAG_COMPRESSION        = 259;
    private static final int TAG_TILE_WIDTH         = 322;
    private static final int TAG_TILE_HEIGHT        = 323;
    private static final int TAG_TILE_OFFSETS       = 324;
    private static final int TAG_TILE_BYTE_COUNTS   = 325;

    private static final int COMPRESSION_DEFLATE    = 8;
    private static final int COMPRESSION_ADOBE_DEFLATE = 32946;

    // ================================================================
    // COG HEADER CACHE
    // ================================================================

    /**
     * Parsed header info for one S3 tile (one 3°x3° area).
     */
    private static class CogHeader {
        final String   tileKey;      // e.g. "N15E078"
        final int      imageWidth;
        final int      imageHeight;
        final int      tileWidth;    // internal tile width (pixels)
        final int      tileHeight;   // internal tile height (pixels)
        final int      tilesAcross;  // number of internal tiles horizontally
        final int      tilesDown;    // number of internal tiles vertically
        final long[]   tileOffsets;  // byte offset of each internal tile
        final long[]   tileByteCounts;
        final int      compression;
        final boolean  littleEndian;

        CogHeader(String tileKey, int imageWidth, int imageHeight,
                  int tileWidth, int tileHeight,
                  long[] tileOffsets, long[] tileByteCounts,
                  int compression, boolean littleEndian) {
            this.tileKey        = tileKey;
            this.imageWidth     = imageWidth;
            this.imageHeight    = imageHeight;
            this.tileWidth      = tileWidth;
            this.tileHeight     = tileHeight;
            this.tilesAcross    = (imageWidth  + tileWidth  - 1) / tileWidth;
            this.tilesDown      = (imageHeight + tileHeight - 1) / tileHeight;
            this.tileOffsets    = tileOffsets;
            this.tileByteCounts = tileByteCounts;
            this.compression    = compression;
            this.littleEndian   = littleEndian;
        }

        int totalTiles() { return tilesAcross * tilesDown; }
    }

    // ================================================================
    // FIELDS
    // ================================================================

    /** Cache of parsed COG headers — keyed by tile key e.g. "N15E078" */
    /**
     * Tiles the S3 bucket answered 404 for.
     *
     * ESA WorldCover has no coverage over open ocean, so tiles like S03W009 will
     * never exist. Without recording that, every subsequent chunk re-issues the
     * same request: a single session logged the identical
     * "tile [N12E081] not found" warning several hundred times, each one a real
     * HTTP round-trip on the WorldCover thread. Negative results are permanent
     * for a given tile key, so caching them is safe.
     */
    private final java.util.Set<String> missingTiles =
        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final ConcurrentHashMap<String, CogHeader> headerCache =
            new ConcurrentHashMap<>(64);
    private final ConcurrentHashMap<String, CompletableFuture<CogHeader>> pendingHeaders =
            new ConcurrentHashMap<>();

    /** Cache of decoded internal tiles — keyed by "tileKey/internalIdx" */
    private final ConcurrentHashMap<String, byte[]> internalTileCache =
            new ConcurrentHashMap<>(4096);
    private static final int INTERNAL_TILE_CACHE_MAX = 8192;

    /** Final result cache — keyed by lat/lon at ~10m resolution */
    private final ConcurrentHashMap<Long, WorldCoverClass> resultCache =
            new ConcurrentHashMap<>(32768);
    private static final int RESULT_CACHE_MAX = 65536;

    private final ExecutorService dlPool;
    private final Path            diskDir;
    private final boolean         diskOk;
    private final AtomicInteger   totalQueries  = new AtomicInteger(0);
    private final AtomicInteger   cacheHits     = new AtomicInteger(0);
    private final AtomicInteger   s3Fetches     = new AtomicInteger(0);
    private final AtomicInteger   dbgCount      = new AtomicInteger(0);
    private volatile boolean      enabled       = true;

    // ================================================================
    // CONSTRUCTOR
    // ================================================================

    public EsaWorldCoverIndex() {
        this.dlPool = Executors.newFixedThreadPool(DL_THREADS, r -> {
            Thread t = new Thread(r, "Terra++WorldCover");
            t.setDaemon(true);
            return t;
        });

        Path dir = net.buildtheearth.terraplusplus.util.TerraCachePaths.dir("worldcover");
        boolean ok = false;
        try {
            Files.createDirectories(dir);
            ok = true;
        } catch (Exception e) {
            LOGGER.warn("WorldCover disk cache init failed: {}", e.getMessage());
        }
        this.diskDir = dir;
        this.diskOk  = ok;

        LOGGER.info("EsaWorldCoverIndex init (ESA S3 COG, 3°x3° tiles, " +
                "disk={}, rate-limiting=disabled)", ok);
    }

    // ================================================================
    // PUBLIC API
    // ================================================================

    /**
     * Get WorldCover class for a lat/lon.
     * Non-blocking — returns UNKNOWN if not yet loaded.
     * Triggers async header + tile fetch in background.
     */
    public WorldCoverClass getClass(double lat, double lon) {
        if (!enabled) return WorldCoverClass.UNKNOWN;
        if (lat <= -85 || lat >= 85) return WorldCoverClass.UNKNOWN;

        totalQueries.incrementAndGet();

        // Result cache — fastest path
        long rKey = resultKey(lat, lon);
        WorldCoverClass cached = resultCache.get(rKey);
        if (cached != null) { cacheHits.incrementAndGet(); return cached; }

        // Get COG header (async, non-blocking)
        String tileKey = cogTileKey(lat, lon);
        CogHeader header = headerCache.get(tileKey);
        if (header == null) {
            triggerHeaderFetch(tileKey, lat, lon);
            return WorldCoverClass.UNKNOWN;
        }

        // Get internal tile (async, non-blocking)
        int[] pxy   = cogPixelXY(lat, lon, header);
        int   iTile = internalTileIndex(pxy[0], pxy[1], header);
        String iKey = tileKey + "/" + iTile;

        byte[] tileData = internalTileCache.get(iKey);
        if (tileData == null) {
            triggerInternalTileFetch(iKey, tileKey, header, iTile);
            return WorldCoverClass.UNKNOWN;
        }

        // Sample pixel from decompressed tile data
        WorldCoverClass wc = samplePixel(tileData, pxy[0], pxy[1], header, iTile);
        cacheResult(rKey, wc);

        if (dbgCount.getAndIncrement() < 20) {
            LOGGER.info("WorldCover [{},{}] tile={} iTile={} → {}",
                    String.format("%.4f", lat),
                    String.format("%.4f", lon),
                    tileKey, iTile, wc.displayName);
        }

        return wc;
    }

    /**
     * Blocking version — waits up to maxWaitMs.
     * Used for F3 debug.
     */
    public WorldCoverClass getClassBlocking(double lat, double lon,
                                             long maxWaitMs) {
        if (!enabled) return WorldCoverClass.UNKNOWN;
        if (lat <= -85 || lat >= 85) return WorldCoverClass.UNKNOWN;

        long rKey = resultKey(lat, lon);
        WorldCoverClass cached = resultCache.get(rKey);
        if (cached != null) return cached;

        String tileKey = cogTileKey(lat, lon);
        CogHeader header = headerCache.get(tileKey);

        if (header == null) {
            CompletableFuture<CogHeader> f = triggerHeaderFetch(tileKey, lat, lon);
            try { header = f.get(maxWaitMs, TimeUnit.MILLISECONDS); }
            catch (Exception e) { return WorldCoverClass.UNKNOWN; }
        }
        if (header == null) return WorldCoverClass.UNKNOWN;

        int[]  pxy   = cogPixelXY(lat, lon, header);
        int    iTile = internalTileIndex(pxy[0], pxy[1], header);
        String iKey  = tileKey + "/" + iTile;

        byte[] tileData = internalTileCache.get(iKey);
        if (tileData == null) {
            CompletableFuture<byte[]> f =
                    triggerInternalTileFetch(iKey, tileKey, header, iTile);
            try { tileData = f.get(maxWaitMs, TimeUnit.MILLISECONDS); }
            catch (Exception e) { return WorldCoverClass.UNKNOWN; }
        }
        if (tileData == null) return WorldCoverClass.UNKNOWN;

        WorldCoverClass wc = samplePixel(tileData, pxy[0], pxy[1], header, iTile);
        cacheResult(rKey, wc);
        return wc;
    }

    /**
     * Prefetch COG headers for tiles covering the neighborhood.
     * Called from generateTerrain() alongside OSM prefetch.
     * Only fetches the header (~4MB range request), not the full file.
     */
    public void prefetch(double lat, double lon) {
        if (!enabled) return;
        for (int dlat = -1; dlat <= 1; dlat++) {
            for (int dlon = -1; dlon <= 1; dlon++) {
                double pLat = lat + dlat * TILE_DEG;
                double pLon = lon + dlon * TILE_DEG;
                if (pLat <= -85 || pLat >= 85) continue;
                String tk = cogTileKey(pLat, pLon);
                if (!headerCache.containsKey(tk) &&
                        !pendingHeaders.containsKey(tk)) {
                    triggerHeaderFetch(tk, pLat, pLon);
                }
            }
        }
    }

    public boolean isTileLoaded(double lat, double lon) {
        return headerCache.containsKey(cogTileKey(lat, lon));
    }

    public int  getTileCacheSize()    { return headerCache.size(); }
    public int  getResultCacheSize()  { return resultCache.size(); }
    public int  getTotalQueries()     { return totalQueries.get(); }
    public int  getCacheHits()        { return cacheHits.get(); }
    public int  getS3Fetches()        { return s3Fetches.get(); }
    public boolean isEnabled()        { return enabled; }
    public void setEnabled(boolean e) { this.enabled = e; }

    // ================================================================
    // TILE KEY MATH
    // ================================================================

    /**
     * Build the ESA S3 tile key for a lat/lon.
     *
     * The tile covers [tileLat, tileLat+3] x [tileLon, tileLon+3].
     * Origin is the BOTTOM-LEFT (SW) corner.
     *
     * Examples:
     *   lat=17.44, lon=78.54  → N15E078  (SW corner: 15N, 78E)
     *   lat=51.5,  lon=-0.1   → N51W003  (SW corner: 51N, 3W)
     *   lat=-2.5,  lon=30.5   → S03E030  (SW corner: 3S, 30E)
     */
    public static String cogTileKey(double lat, double lon) {
        int tileLat = (int) Math.floor(lat / TILE_DEG) * TILE_DEG;
        int tileLon = (int) Math.floor(lon / TILE_DEG) * TILE_DEG;

        String latStr = (tileLat >= 0)
                ? String.format("N%02d", tileLat)
                : String.format("S%02d", Math.abs(tileLat));
        String lonStr = (tileLon >= 0)
                ? String.format("E%03d", tileLon)
                : String.format("W%03d", Math.abs(tileLon));

        return latStr + lonStr;
    }

    public static String cogTileUrl(String tileKey) {
        return String.format(S3_BASE, tileKey);
    }

    // ================================================================
    // PIXEL COORDINATE MATH
    // ================================================================

    /**
     * Convert lat/lon to pixel coordinates within the COG image.
     * The image origin (0,0) is the TOP-LEFT (NW) corner.
     * Y increases downward (south).
     */
    public static int[] cogPixelXY(double lat, double lon, CogHeader h) {
        int tileLat = tileOriginLat(lat);
        int tileLon = tileOriginLon(lon);

        double fracX = (lon - tileLon) / TILE_DEG;
        double fracY = ((tileLat + TILE_DEG) - lat) / TILE_DEG;

        int px = (int) Math.min(fracX * h.imageWidth,  h.imageWidth  - 1);
        int py = (int) Math.min(fracY * h.imageHeight, h.imageHeight - 1);

        return new int[]{ px, py };
    }

    private static int tileOriginLat(double lat) {
        return (int) Math.floor(lat / TILE_DEG) * TILE_DEG;
    }

    private static int tileOriginLon(double lon) {
        return (int) Math.floor(lon / TILE_DEG) * TILE_DEG;
    }

    /**
     * Get the index of the internal COG tile that contains pixel (px, py).
     */
    public static int internalTileIndex(int px, int py, CogHeader h) {
        int tx = px / h.tileWidth;
        int ty = py / h.tileHeight;
        return ty * h.tilesAcross + tx;
    }

    // ================================================================
    // PIXEL SAMPLING
    // ================================================================

    private WorldCoverClass samplePixel(byte[] tileData,
                                         int px, int py,
                                         CogHeader h, int iTile) {
        int tx   = (iTile % h.tilesAcross) * h.tileWidth;
        int ty   = (iTile / h.tilesAcross) * h.tileHeight;
        int lpx  = px - tx;
        int lpy  = py - ty;

        int tw   = Math.min(h.tileWidth,  h.imageWidth  - tx);
        int th   = Math.min(h.tileHeight, h.imageHeight - ty);
        lpx = Math.max(0, Math.min(tw - 1, lpx));
        lpy = Math.max(0, Math.min(th - 1, lpy));

        int idx = lpy * tw + lpx;
        if (idx < 0 || idx >= tileData.length) return WorldCoverClass.UNKNOWN;

        int classVal = tileData[idx] & 0xFF;
        return WorldCoverClass.fromValue(classVal);
    }

    // ================================================================
    // ASYNC FETCH — HEADER
    // ================================================================

    private CompletableFuture<CogHeader> triggerHeaderFetch(String tileKey,
                                                             double lat,
                                                             double lon) {
        // Known-absent upstream: never re-request. Without this the same tile
        // is fetched once per chunk query, forever.
        if (missingTiles.contains(tileKey))
            return CompletableFuture.completedFuture(null);
        return pendingHeaders.computeIfAbsent(tileKey, k ->
            CompletableFuture.supplyAsync(() -> {
                CogHeader h = loadHeader(tileKey);
                if (h != null) headerCache.put(tileKey, h);
                pendingHeaders.remove(tileKey);
                return h;
            }, dlPool)
        );
    }

    // ================================================================
    // ASYNC FETCH — INTERNAL TILE
    // ================================================================

    private final ConcurrentHashMap<String, CompletableFuture<byte[]>>
            pendingInternalTiles = new ConcurrentHashMap<>();

    private CompletableFuture<byte[]> triggerInternalTileFetch(
            String iKey, String tileKey, CogHeader header, int iTile) {
        return pendingInternalTiles.computeIfAbsent(iKey, k ->
            CompletableFuture.supplyAsync(() -> {
                byte[] data = fetchInternalTile(tileKey, header, iTile);
                if (data != null) {
                    if (internalTileCache.size() > INTERNAL_TILE_CACHE_MAX)
                        internalTileCache.clear();
                    internalTileCache.put(iKey, data);
                }
                pendingInternalTiles.remove(iKey);
                return data;
            }, dlPool)
        );
    }

    // ================================================================
    // COG HEADER LOADING
    // ================================================================

    /**
     * Load and parse the TIFF header from the S3 file.
     * Uses HTTP Range request to fetch only the first HEADER_FETCH_BYTES.
     * Rate limiting removed — S3 has no rate limit on public buckets.
     */
    private CogHeader loadHeader(String tileKey) {
        // Check disk cache first
        if (diskOk) {
            CogHeader cached = loadHeaderFromDisk(tileKey);
            if (cached != null) return cached;
        }

        String url = cogTileUrl(tileKey);

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(HTTP_TIMEOUT);
                conn.setReadTimeout(HTTP_TIMEOUT);
                conn.setRequestProperty("User-Agent",
                        "TerraPlusPlus/2.0 Minecraft WorldCover");
                conn.setRequestProperty("Range",
                        "bytes=0-" + (HEADER_FETCH_BYTES - 1));

                int code = conn.getResponseCode();
                if (code == 200 || code == 206) {
                    byte[] data;
                    try (InputStream is = conn.getInputStream()) {
                        data = is.readAllBytes();
                    }
                    CogHeader h = parseTiffHeader(tileKey, data);
                    if (h != null) {
                        LOGGER.info("WorldCover header [{}]: {}x{} px, "
                                + "intTile={}x{}, total={} tiles",
                                tileKey, h.imageWidth, h.imageHeight,
                                h.tileWidth, h.tileHeight, h.totalTiles());
                        if (diskOk) saveHeaderToDisk(tileKey, h);
                        s3Fetches.incrementAndGet();
                        return h;
                    }
                } else if (code == 404) {
                    // Record permanently; this tile does not exist upstream.
                    if (missingTiles.add(tileKey))
                        LOGGER.warn("WorldCover tile [{}] not found on S3 (404)"
                                + " - suppressing further requests", tileKey);
                    return null;
                } else {
                    LOGGER.warn("WorldCover header [{}] HTTP {}", tileKey, code);
                }
            } catch (Exception e) {
                LOGGER.debug("WorldCover header [{}] attempt {}: {}",
                        tileKey, attempt, e.getMessage());
            } finally {
                net.buildtheearth.terraplusplus.util.HttpKeepAlive.release(conn);
            }

            if (attempt < MAX_RETRY) {
                try { Thread.sleep(800L * attempt); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        LOGGER.warn("WorldCover header [{}] failed after {} attempts",
                tileKey, MAX_RETRY);
        return null;
    }

    // ================================================================
    // INTERNAL TILE FETCHING
    // ================================================================

    /**
     * Fetch and decompress a specific internal 256x256 tile block.
     * Uses HTTP Range request to fetch only the compressed bytes
     * for this one internal tile — typically 5-50KB.
     * Rate limiting removed — S3 has no rate limit on public buckets.
     */
    private byte[] fetchInternalTile(String tileKey,
                                      CogHeader header, int iTile) {
        if (iTile < 0 || iTile >= header.tileOffsets.length) return null;

        long offset    = header.tileOffsets[iTile];
        long byteCount = header.tileByteCounts[iTile];

        if (offset == 0 || byteCount == 0) {
            return new byte[header.tileWidth * header.tileHeight];
        }

        // Check disk cache
        if (diskOk) {
            byte[] cached = loadInternalTileFromDisk(tileKey, iTile);
            if (cached != null) return cached;
        }

        String url = cogTileUrl(tileKey);

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(HTTP_TIMEOUT);
                conn.setReadTimeout(HTTP_TIMEOUT);
                conn.setRequestProperty("User-Agent",
                        "TerraPlusPlus/2.0 Minecraft WorldCover");
                conn.setRequestProperty("Range",
                        "bytes=" + offset + "-" + (offset + byteCount - 1));

                int code = conn.getResponseCode();
                if (code == 200 || code == 206) {
                    byte[] compressed;
                    try (InputStream is = conn.getInputStream()) {
                        compressed = is.readAllBytes();
                    }

                    byte[] decompressed = decompressDeflate(compressed,
                            header.tileWidth * header.tileHeight);
                    if (decompressed != null) {
                        s3Fetches.incrementAndGet();
                        if (diskOk)
                            saveInternalTileToDisk(tileKey, iTile, decompressed);
                        return decompressed;
                    }
                } else {
                    LOGGER.debug("WorldCover iTile [{}/{}] HTTP {}",
                            tileKey, iTile, code);
                }
            } catch (Exception e) {
                LOGGER.debug("WorldCover iTile [{}/{}] attempt {}: {}",
                        tileKey, iTile, attempt, e.getMessage());
            } finally {
                net.buildtheearth.terraplusplus.util.HttpKeepAlive.release(conn);
            }

            if (attempt < MAX_RETRY) {
                try { Thread.sleep(500L * attempt); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        return null;
    }

    // ================================================================
    // TIFF HEADER PARSING
    // ================================================================

    /**
     * Parse a TIFF/BigTIFF header to extract tile offsets and byte counts.
     */
    private CogHeader parseTiffHeader(String tileKey, byte[] data) {
        try {
            if (data.length < 8) return null;

            boolean le = (data[0] == 'I' && data[1] == 'I');
            ByteOrder order = le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            ByteBuffer buf = ByteBuffer.wrap(data).order(order);

            int magic = buf.getShort(2) & 0xFFFF;
            boolean bigTiff = (magic == 43);

            if (bigTiff) {
                return parseBigTiffHeader(tileKey, buf, le);
            } else if (magic == 42) {
                return parseStandardTiffHeader(tileKey, buf, le);
            } else {
                LOGGER.warn("WorldCover [{}]: unknown TIFF magic {}",
                        tileKey, magic);
                return null;
            }
        } catch (Exception e) {
            LOGGER.warn("WorldCover [{}] parse failed: {}",
                    tileKey, e.getMessage());
            return null;
        }
    }

    private CogHeader parseStandardTiffHeader(String tileKey,
                                               ByteBuffer buf,
                                               boolean le) {
        int ifdOffset = buf.getInt(4);
        if (ifdOffset < 0 || ifdOffset >= buf.capacity() - 2) return null;

        int numEntries = buf.getShort(ifdOffset) & 0xFFFF;
        int entryStart = ifdOffset + 2;

        int imageWidth = 0, imageHeight = 0;
        int tileWidth  = COG_TILE_PX, tileHeight = COG_TILE_PX;
        int compression = COMPRESSION_DEFLATE;
        long[] tileOffsets = null, tileByteCounts = null;

        for (int i = 0; i < numEntries; i++) {
            int pos = entryStart + i * 12;
            if (pos + 12 > buf.capacity()) break;

            int    tag      = buf.getShort(pos)     & 0xFFFF;
            int    type     = buf.getShort(pos + 2) & 0xFFFF;
            long   count    = buf.getInt(pos + 4)   & 0xFFFFFFFFL;
            int    valPos   = pos + 8;

            switch (tag) {
                case TAG_IMAGE_WIDTH  -> imageWidth  = readTiffValue(buf, type, valPos, count);
                case TAG_IMAGE_HEIGHT -> imageHeight = readTiffValue(buf, type, valPos, count);
                case TAG_TILE_WIDTH   -> tileWidth   = readTiffValue(buf, type, valPos, count);
                case TAG_TILE_HEIGHT  -> tileHeight  = readTiffValue(buf, type, valPos, count);
                case TAG_COMPRESSION  -> compression = readTiffValue(buf, type, valPos, count);
                case TAG_TILE_OFFSETS ->
                        tileOffsets = readLongArray(buf, type, valPos, count);
                case TAG_TILE_BYTE_COUNTS ->
                        tileByteCounts = readLongArray(buf, type, valPos, count);
            }
        }

        if (tileOffsets == null || tileByteCounts == null
                || imageWidth == 0 || imageHeight == 0) {
            LOGGER.warn("WorldCover [{}]: missing required TIFF tags", tileKey);
            return null;
        }

        return new CogHeader(tileKey, imageWidth, imageHeight,
                tileWidth, tileHeight,
                tileOffsets, tileByteCounts, compression, le);
    }

    private CogHeader parseBigTiffHeader(String tileKey,
                                          ByteBuffer buf,
                                          boolean le) {
        long ifdOffset = buf.getLong(8);
        if (ifdOffset < 0 || ifdOffset >= buf.capacity() - 8) return null;

        long numEntries = buf.getLong((int) ifdOffset);
        long entryStart = ifdOffset + 8;

        int imageWidth = 0, imageHeight = 0;
        int tileWidth = COG_TILE_PX, tileHeight = COG_TILE_PX;
        int compression = COMPRESSION_DEFLATE;
        long[] tileOffsets = null, tileByteCounts = null;

        for (long i = 0; i < numEntries; i++) {
            long pos = entryStart + i * 20;
            if (pos + 20 > buf.capacity()) break;

            int  tag   = buf.getShort((int) pos)     & 0xFFFF;
            int  type  = buf.getShort((int) pos + 2) & 0xFFFF;
            long count = buf.getLong((int) pos + 4);
            int  valPos = (int) pos + 12;

            switch (tag) {
                case TAG_IMAGE_WIDTH  -> imageWidth  = readTiffValue(buf, type, valPos, count);
                case TAG_IMAGE_HEIGHT -> imageHeight = readTiffValue(buf, type, valPos, count);
                case TAG_TILE_WIDTH   -> tileWidth   = readTiffValue(buf, type, valPos, count);
                case TAG_TILE_HEIGHT  -> tileHeight  = readTiffValue(buf, type, valPos, count);
                case TAG_COMPRESSION  -> compression = readTiffValue(buf, type, valPos, count);
                case TAG_TILE_OFFSETS ->
                        tileOffsets = readLongArray(buf, type, valPos, count);
                case TAG_TILE_BYTE_COUNTS ->
                        tileByteCounts = readLongArray(buf, type, valPos, count);
            }
        }

        if (tileOffsets == null || tileByteCounts == null
                || imageWidth == 0 || imageHeight == 0) {
            LOGGER.warn("WorldCover [{}]: missing BigTIFF tags", tileKey);
            return null;
        }

        return new CogHeader(tileKey, imageWidth, imageHeight,
                tileWidth, tileHeight,
                tileOffsets, tileByteCounts, compression, le);
    }

    private static int readTiffValue(ByteBuffer buf, int type,
                                      int valPos, long count) {
        try {
            return switch (type) {
                case 1  -> buf.get(valPos) & 0xFF;
                case 3  -> buf.getShort(valPos) & 0xFFFF;
                case 4  -> buf.getInt(valPos);
                case 16 -> (int) buf.getLong(valPos);
                default -> buf.getShort(valPos) & 0xFFFF;
            };
        } catch (Exception e) { return 0; }
    }

    private static long[] readLongArray(ByteBuffer buf, int type,
                                         int valPos, long count) {
        try {
            int n = (int) Math.min(count, 100_000L);
            long[] result = new long[n];

            int dataPos = valPos;
            int valueSize = switch (type) {
                case 1, 2 -> 1;
                case 3     -> 2;
                case 4, 9  -> 4;
                case 16,17 -> 8;
                default    -> 4;
            };

            if (count > 1 || (count == 1 && valueSize > 4)) {
                long offset;
                if (valueSize <= 4) {
                    offset = buf.getInt(valPos) & 0xFFFFFFFFL;
                } else {
                    offset = buf.getLong(valPos);
                }
                if (offset < 0 || offset >= buf.capacity()) return null;
                dataPos = (int) offset;
            }

            for (int i = 0; i < n; i++) {
                int p = dataPos + i * valueSize;
                if (p + valueSize > buf.capacity()) break;
                result[i] = switch (type) {
                    case 1  -> buf.get(p) & 0xFF;
                    case 3  -> buf.getShort(p) & 0xFFFF;
                    case 4  -> buf.getInt(p)   & 0xFFFFFFFFL;
                    case 16 -> buf.getLong(p);
                    default -> buf.getInt(p)   & 0xFFFFFFFFL;
                };
            }
            return result;
        } catch (Exception e) {
            LOGGER.debug("readLongArray failed: {}", e.getMessage());
            return null;
        }
    }

    // ================================================================
    // DECOMPRESSION
    // ================================================================

    /**
     * Decompress Deflate-compressed internal tile data.
     * ESA WorldCover uses zlib Deflate (compression=8).
     */
    private static byte[] decompressDeflate(byte[] compressed,
                                              int expectedSize) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(compressed);
            byte[] out = new byte[expectedSize * 2];
            int n = inf.inflate(out);
            inf.end();
            if (n <= 0) return null;
            return Arrays.copyOf(out, n);
        } catch (Exception e) {
            try {
                Inflater inf = new Inflater(true);
                inf.setInput(compressed);
                byte[] out = new byte[expectedSize * 2];
                int n = inf.inflate(out);
                inf.end();
                if (n <= 0) return null;
                return Arrays.copyOf(out, n);
            } catch (Exception e2) {
                LOGGER.debug("Deflate failed: {}", e2.getMessage());
                return null;
            }
        }
    }

    // ================================================================
    // DISK CACHE — HEADER
    // ================================================================

    private void saveHeaderToDisk(String tileKey, CogHeader h) {
        try {
            Path file = diskDir.resolve("hdr_" + tileKey + ".bin");
            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(
                            Files.newOutputStream(file)))) {
                dos.writeInt(h.imageWidth);
                dos.writeInt(h.imageHeight);
                dos.writeInt(h.tileWidth);
                dos.writeInt(h.tileHeight);
                dos.writeInt(h.compression);
                dos.writeBoolean(h.littleEndian);
                dos.writeInt(h.tileOffsets.length);
                for (long v : h.tileOffsets)      dos.writeLong(v);
                for (long v : h.tileByteCounts)   dos.writeLong(v);
            }
        } catch (Exception e) {
            LOGGER.debug("Header disk save [{}] failed: {}",
                    tileKey, e.getMessage());
        }
    }

    private CogHeader loadHeaderFromDisk(String tileKey) {
        try {
            Path file = diskDir.resolve("hdr_" + tileKey + ".bin");
            if (!Files.exists(file)) return null;
            try (DataInputStream dis = new DataInputStream(
                    new BufferedInputStream(
                            Files.newInputStream(file)))) {
                int imageWidth   = dis.readInt();
                int imageHeight  = dis.readInt();
                int tileWidth    = dis.readInt();
                int tileHeight   = dis.readInt();
                int compression  = dis.readInt();
                boolean le       = dis.readBoolean();
                int n            = dis.readInt();
                if (n <= 0 || n > 1_000_000) return null;
                long[] offsets   = new long[n];
                long[] byteCnts  = new long[n];
                for (int i = 0; i < n; i++) offsets[i]  = dis.readLong();
                for (int i = 0; i < n; i++) byteCnts[i] = dis.readLong();
                LOGGER.debug("WorldCover header [{}] loaded from disk", tileKey);
                return new CogHeader(tileKey, imageWidth, imageHeight,
                        tileWidth, tileHeight, offsets, byteCnts,
                        compression, le);
            }
        } catch (Exception e) {
            LOGGER.debug("Header disk load [{}] failed: {}",
                    tileKey, e.getMessage());
            return null;
        }
    }

    // ================================================================
    // DISK CACHE — INTERNAL TILES
    // ================================================================

    private void saveInternalTileToDisk(String tileKey, int iTile,
                                         byte[] data) {
        try {
            Path dir = diskDir.resolve(tileKey);
            Files.createDirectories(dir);
            Files.write(dir.resolve(iTile + ".raw"), data);
        } catch (Exception e) {
            LOGGER.debug("iTile disk save [{}/{}] failed: {}",
                    tileKey, iTile, e.getMessage());
        }
    }

    private byte[] loadInternalTileFromDisk(String tileKey, int iTile) {
        try {
            Path file = diskDir.resolve(tileKey).resolve(iTile + ".raw");
            if (!Files.exists(file)) return null;
            return Files.readAllBytes(file);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================
    // RESULT CACHE
    // ================================================================

    private void cacheResult(long rKey, WorldCoverClass wc) {
        if (resultCache.size() > RESULT_CACHE_MAX) resultCache.clear();
        resultCache.put(rKey, wc);
    }

    private static long resultKey(double lat, double lon) {
        long rLat = Math.round(lat * 10000.0);
        long rLon = Math.round(lon * 10000.0);
        return rLat * 36000001L + rLon;
    }

    // ================================================================
    // SHUTDOWN
    // ================================================================

    public void shutdown() {
        dlPool.shutdown();
        try {
            if (!dlPool.awaitTermination(3, TimeUnit.SECONDS))
                dlPool.shutdownNow();
        } catch (InterruptedException e) {
            dlPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("EsaWorldCoverIndex shutdown "
                + "(queries={} hits={} s3Fetches={} headers={} iTiles={})",
                totalQueries.get(), cacheHits.get(), s3Fetches.get(),
                headerCache.size(), internalTileCache.size());
    }
}