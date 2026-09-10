// ============================================================
// FILE: OvertureOsmIndex.java
// THE REAL OVERTURE — OSM-derived planet data from Overture
// Maps' PMTiles releases, read via HTTP range requests.
//
// (The class formerly named OvertureIndex never read Overture;
// it reads Microsoft's quadkey files and is now correctly named
// MicrosoftIndex. THIS class is the genuine article.)
//
// Purpose (user-specified appetizer plan, option 1):
//   COLD START: download BOTH Overture(OSM) and live OSM.
//   Overture serves buildings/roads/water within ~1s as
//   TEMPORARY data until live OSM lands and the area becomes
//   self-sustaining (disk-cached OSM). Trust hierarchy inside
//   the appetizer phase: Overture(OSM) > VIDA(Google) > MSFT.
//
// Why PMTiles and not GeoParquet: Overture publishes per-theme
// PMTiles alongside parquet. Verified live from this sandbox:
//   buildings.pmtiles: v3, INTERNAL+TILE COMPRESSION = GZIP,
//   tile type MVT, maxzoom 14, HTTP 206 range reads OK.
// GZIP = java.util.zip, zero new dependencies. One z14 tile
// covers ~2.4 km — one or two range reads per structure tile.
//
// Read path per tile (all bounded, all cached):
//   header (127 B, once/theme) -> root directory (gzip varint,
//   once/theme) -> [leaf directory if entry.runLength==0] ->
//   tile bytes -> gunzip -> hand-parsed MVT (protobuf subset)
//   -> OsmStructure conversion.
//
// Design rules (project doctrine):
//   - bounded blocking only; every network call has timeouts
//   - missing/failed = PENDING (never cached as empty)
//   - mem + disk caches, raw bytes on disk (replayable)
//   - every stage self-checks and logs (OVERTURE prefix)
// ============================================================
package net.buildtheearth.terraplusplus.generator.osm;

import net.buildtheearth.terraplusplus.generator.historical.HistoricalDateParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public final class OvertureOsmIndex {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++OvertureOSM");

    /** Pinned release; bump deliberately after testing. */
    private static final String RELEASE = "2026-08-19.0";
    private static final String BASE_URL =
        "https://overturemaps-extras-us-west-2.s3.us-west-2."
        + "amazonaws.com/tiles/" + RELEASE + "/";

    private static final int Z = 14;          // native maxzoom
    private static final int HTTP_TIMEOUT_MS = 8000;
    private static final long DISK_TTL_MS =
        60L * 24 * 60 * 60 * 1000;            // release-pinned

    public enum Theme {
        BUILDINGS("buildings"),
        TRANSPORTATION("transportation"),
        BASE("base");
        final String file;
        Theme(String f) { this.file = f; }
    }

    // ── SINGLETON ─────────────────────────────────────────────
    private static volatile OvertureOsmIndex instance;
    public static OvertureOsmIndex getInstance() {
        if (instance == null)
            synchronized (OvertureOsmIndex.class) {
                if (instance == null)
                    instance = new OvertureOsmIndex();
            }
        return instance;
    }

    // per-theme PMTiles metadata (header + root dir), lazy
    private static final class ThemeState {
        volatile long rootOff, rootLen, leafOff, tileOff;
        volatile List<long[]> rootDir;   // [tileId, runLen, off, len]
        volatile boolean failed;
    }
    private final Map<Theme, ThemeState> themes =
        new ConcurrentHashMap<>();

    /** parsed-features cache per (theme,z,x,y) */
    private final ConcurrentHashMap<String,
        List<OsmStructureIndex.OsmStructure>> memCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> pending =
        new ConcurrentHashMap<>();

    private final Path diskDir;
    private final boolean diskOk;
    private final ExecutorService dlPool;

    private OvertureOsmIndex() {
        Path dir = net.buildtheearth.terraplusplus.util.TerraCachePaths.dir("overture_osm_" + RELEASE.replace(".", "_"));
        boolean ok = false;
        try { Files.createDirectories(dir); ok = true; }
        catch (Exception e) {
            LOGGER.warn("OVERTURE cache dir failed: {}",
                e.getMessage());
        }
        this.diskDir = dir;
        this.diskOk = ok;
        this.dlPool = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "Terra++OvertureOSM");
            t.setDaemon(true);
            return t;
        });
        LOGGER.info("OvertureOsmIndex init (release={}, "
            + "z={}, disk={}) — OSM-derived planet tiles via "
            + "HTTP range reads", RELEASE, Z, ok);
    }

    public void shutdown() {
        dlPool.shutdown();
        try {
            if (!dlPool.awaitTermination(3, TimeUnit.SECONDS))
                dlPool.shutdownNow();
        } catch (InterruptedException e) {
            dlPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ══════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════

    /** Fire-and-forget warmup for a destination.
     *  WIDENED (run-10 fix): the old ±0.01° box (~1.1km) could
     *  straddle a z14 tile border (~2.2km/tile) and leave the
     *  player's actual tile cold. Now warms the full 3x3 tile
     *  neighborhood around the destination — 9 tiles x 2 themes,
     *  all parallel on the pool. BASE skipped (never converted:
     *  water belongs to the water indexes). */
    public void prefetch(double lat, double lon) {
        long c = tileKeyXY(lat, lon);
        int cx = (int) (c >> 32), cy = (int) c;
        int max = (1 << Z) - 1;
        int fired = 0;
        for (Theme t : APPETIZER_THEMES)
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++) {
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x > max || y > max)
                        continue;
                    String ck = t.file + "/" + x + "/" + y;
                    if (memCache.containsKey(ck)) continue;
                    if (pending.putIfAbsent(ck, true) != null)
                        continue;
                    final int fx = x, fy = y;
                    final Theme ft = t;
                    fired++;
                    dlPool.submit(() -> {
                        try { loadTile(ft, fx, fy); }
                        finally { pending.remove(ck); }
                    });
                }
        if (fired > 0)
            LOGGER.info("OVERTURE prefetch [{},{}]: {} tiles "
                + "dispatched (3x3 neighborhood, 2 themes)",
                String.format("%.4f", lat),
                String.format("%.4f", lon), fired);
    }

    /** Themes the appetizer actually serves. BASE is excluded:
     *  convert() skips it entirely (water is owned by the water
     *  indexes), so fetching it here burned budget on tiles we
     *  threw away — and a BASE failure could nuke a plate that
     *  was already full (run-10 starvation contributor). */
    private static final Theme[] APPETIZER_THEMES = {
        Theme.BUILDINGS, Theme.TRANSPORTATION };

    /** Bounded-blocking fetch of appetizer structures for a
     *  bbox: buildings + roads/rails, already converted to
     *  OsmStructure.
     *
     *  PARTIAL-SERVING REWRITE (run-10 fix): the old loop was
     *  serial and all-or-nothing — one missing tile returned
     *  null even when buildings were already resident. Now:
     *   1. dispatch ALL missing tiles to the download pool in
     *      parallel (themes fetch concurrently too — fix 3);
     *   2. wait until everything landed or the deadline hits;
     *   3. plate whatever is resident. Buildings without roads
     *      is a fine appetizer; nothing at all returns null
     *      (never fabricates emptiness).
     *  Self-check: logs one machine-parseable line per call. */
    public List<OsmStructureIndex.OsmStructure>
            getStructuresForArea(double minLat, double maxLat,
            double minLon, double maxLon, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        long deadline = t0 + timeoutMs;
        List<Long> keys = tilesForArea(minLat, maxLat,
            minLon, maxLon);

        // 1. parallel dispatch of every missing tile
        List<java.util.concurrent.Future<?>> waits =
            new ArrayList<>();
        for (Theme t : APPETIZER_THEMES)
            for (long key : keys) {
                int x = (int) (key >> 32), y = (int) key;
                String ck = t.file + "/" + x + "/" + y;
                if (memCache.containsKey(ck)) continue;
                if (pending.putIfAbsent(ck, true) != null)
                    continue; // someone else is fetching it
                waits.add(dlPool.submit(() -> {
                    try { loadTile(t, x, y); }
                    finally { pending.remove(ck); }
                }));
            }

        // 2. bounded wait: all landed, or deadline
        for (var f : waits) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                f.get(left,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // timeout / fetch failure: plate what we have
            }
        }

        // 3. plate everything resident right now
        List<OsmStructureIndex.OsmStructure> out =
            new ArrayList<>();
        int tilesHit = 0, tilesMiss = 0;
        for (Theme t : APPETIZER_THEMES)
            for (long key : keys) {
                int x = (int) (key >> 32), y = (int) key;
                List<OsmStructureIndex.OsmStructure> tile =
                    memCache.get(t.file + "/" + x + "/" + y);
                if (tile == null) { tilesMiss++; continue; }
                tilesHit++;
                for (var s : tile)
                    if (s.maxLat >= minLat && s.minLat <= maxLat
                            && s.maxLon >= minLon
                            && s.minLon <= maxLon)
                        out.add(s);
            }
        long spent = System.currentTimeMillis() - t0;
        if (tilesHit == 0) {
            // 0ms budget = resident-only poll from the engine,
            // possibly every tick per pending chunk: rate-limit
            // the miss line or it floods the log (first 10 only).
            if (timeoutMs > 0 || starveLogged.incrementAndGet() <= 10)
                LOGGER.info("OVERTURE area [{},{}]: 0/{} tiles "
                    + "resident after {}ms — nothing to serve",
                    String.format("%.4f", (minLat + maxLat) / 2),
                    String.format("%.4f", (minLon + maxLon) / 2),
                    tilesHit + tilesMiss, spent);
            return null; // genuinely nothing: caller falls back
        }
        // RUN-13 FIX (spam): resident-but-empty results (ocean
        // spawn, empty bbox) were logged on EVERY 0ms engine
        // poll — ~150 identical lines at world start. Empty +
        // polled = first 10 only; real serves always log.
        if (out.isEmpty() && timeoutMs == 0) {
            if (emptyLogged.incrementAndGet() <= 10)
                LOGGER.info("OVERTURE area [{},{}]: 0 structures "
                    + "from {}/{} tiles (empty area; further "
                    + "identical polls muted)",
                    String.format("%.4f", (minLat + maxLat) / 2),
                    String.format("%.4f", (minLon + maxLon) / 2),
                    tilesHit, tilesHit + tilesMiss);
        } else {
            LOGGER.info("OVERTURE area [{},{}]: {} structures from "
                + "{}/{} tiles in {}ms{}",
                String.format("%.4f", (minLat + maxLat) / 2),
                String.format("%.4f", (minLon + maxLon) / 2),
                out.size(), tilesHit, tilesHit + tilesMiss, spent,
                tilesMiss > 0 ? " (partial serve)" : "");
        }
        return out;
    }

    public int getCacheSize() { return memCache.size(); }

    // ══════════════════════════════════════════════════════════
    // PMTILES PLUMBING
    // ══════════════════════════════════════════════════════════

    private List<long[]> rootFor(Theme t) {
        ThemeState st = themes.computeIfAbsent(t,
            k -> new ThemeState());
        if (st.failed) return null;
        if (st.rootDir != null) return st.rootDir;
        synchronized (st) {
            if (st.rootDir != null) return st.rootDir;
            try {
                byte[] hdr = httpRange(t, 0, 126);
                if (hdr == null || hdr.length < 127
                        || hdr[0] != 'P') {
                    st.failed = true;
                    LOGGER.warn("OVERTURE header FAIL "
                        + "theme={}", t.file);
                    return null;
                }
                st.rootOff = le64(hdr, 8);
                st.rootLen = le64(hdr, 16);
                st.leafOff = le64(hdr, 40);
                st.tileOff = le64(hdr, 56);
                byte[] rootGz = httpRange(t, st.rootOff,
                    st.rootOff + st.rootLen - 1);
                if (rootGz == null) {
                    st.failed = true; return null;
                }
                st.rootDir = parseDirectory(gunzip(rootGz));
                LOGGER.info("OVERTURE root dir theme={}: {} "
                    + "entries ({} B) — header self-check PASS",
                    t.file, st.rootDir.size(), st.rootLen);
                return st.rootDir;
            } catch (Exception e) {
                st.failed = true;
                LOGGER.warn("OVERTURE root dir theme={} "
                    + "failed: {}", t.file, e.getMessage());
                return null;
            }
        }
    }

    /** Loads + parses one tile; caches; null on any failure
     *  (retryable — nothing poisoned). */
    private List<OsmStructureIndex.OsmStructure> loadTile(
            Theme t, int x, int y) {
        String ck = t.file + "/" + x + "/" + y;
        List<OsmStructureIndex.OsmStructure> hit =
            memCache.get(ck);
        if (hit != null) return hit;
        try {
            byte[] mvt = null;
            Path f = diskDir.resolve(
                t.file + "_" + x + "_" + y + ".mvt");
            if (diskOk && Files.exists(f)
                    && System.currentTimeMillis()
                        - Files.getLastModifiedTime(f)
                            .toMillis() < DISK_TTL_MS)
                mvt = Files.readAllBytes(f);
            if (mvt == null) {
                ThemeState st = themes.get(t) != null
                    ? themes.get(t) : null;
                List<long[]> root = rootFor(t);
                if (root == null) return null;
                st = themes.get(t);
                long tid = tileId(Z, x, y);
                long[] e = findEntry(root, tid);
                // leaf directory hop (runLength==0)
                int hops = 0;
                while (e != null && e[1] == 0 && hops++ < 3) {
                    byte[] leafGz = httpRange(t,
                        st.leafOff + e[2],
                        st.leafOff + e[2] + e[3] - 1);
                    if (leafGz == null) return null;
                    e = findEntry(
                        parseDirectory(gunzip(leafGz)), tid);
                }
                if (e == null) {
                    // genuinely empty tile (ocean): cache empty
                    // — this IS a valid answer from a resident
                    // directory, not a network failure.
                    List<OsmStructureIndex.OsmStructure> none =
                        List.of();
                    memCache.put(ck, none);
                    return none;
                }
                byte[] gz = httpRange(t, st.tileOff + e[2],
                    st.tileOff + e[2] + e[3] - 1);
                if (gz == null) return null;
                mvt = gunzip(gz);
                if (diskOk)
                    try { Files.write(f, mvt); }
                    catch (Exception ex) {
                        LOGGER.warn("OVERTURE disk write "
                            + "FAIL {}: {}", f, ex.getMessage());
                    }
            }
            List<OsmStructureIndex.OsmStructure> parsed =
                parseMvt(t, mvt, x, y);
            memCache.put(ck, parsed);
            if (memCache.size() % 25 == 1)
                LOGGER.info("OVERTURE tile {}/{}/{}: {} "
                    + "features (cache={} tiles)",
                    t.file, x, y, parsed.size(),
                    memCache.size());
            return parsed;
        } catch (Exception e) {
            // RUN-10: failures were invisible at debug level —
            // zero tiles loaded all session and the log said
            // nothing. First 10 failures at WARN with reason.
            if (failLogged.incrementAndGet() <= 10)
                LOGGER.warn("OVERTURE tile {}/{}/{} FAIL: {} {}",
                    t.file, x, y,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }
    private final java.util.concurrent.atomic.AtomicInteger
        failLogged =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger
        starveLogged =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger
        emptyLogged =
            new java.util.concurrent.atomic.AtomicInteger();

    private byte[] httpRange(Theme t, long from, long to) {
        // KEEP-ALIVE FIX: never call disconnect() — it closes
        // the pooled socket, forcing a fresh TLS handshake
        // (~600-900ms from IN to us-west-2) on EVERY range
        // request. Fully reading + closing the stream instead
        // lets the JVM reuse the connection: only the first
        // request per host pays the handshake.
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                BASE_URL + t.file + ".pmtiles")
                .openConnection();
            c.setConnectTimeout(HTTP_TIMEOUT_MS);
            c.setReadTimeout(HTTP_TIMEOUT_MS);
            c.setRequestProperty("Range",
                "bytes=" + from + "-" + to);
            c.setRequestProperty("User-Agent",
                "TerraPlusPlus/2.0");
            if (c.getResponseCode() != 206) {
                // drain the error body so the socket is clean
                // for reuse instead of being torn down
                try (InputStream es = c.getErrorStream()) {
                    if (es != null) es.readAllBytes();
                } catch (Exception ignored) { }
                return null;
            }
            try (InputStream is = c.getInputStream()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ── PMTiles v3 primitives ────────────────────────────────
    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--)
            v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    private static byte[] gunzip(byte[] in) throws Exception {
        try (InputStream is = new GZIPInputStream(
                new ByteArrayInputStream(in))) {
            ByteArrayOutputStream bos =
                new ByteArrayOutputStream(in.length * 4);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0)
                bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** varint-decoded PMTiles directory:
     *  entries = [tileId, runLength, offset, length]. */
    private static List<long[]> parseDirectory(byte[] d) {
        int[] p = {0};
        long n = varint(d, p);
        long[][] ent = new long[(int) n][4];
        long tid = 0;
        for (int i = 0; i < n; i++) {
            tid += varint(d, p);
            ent[i][0] = tid;
        }
        for (int i = 0; i < n; i++)
            ent[i][1] = varint(d, p);
        for (int i = 0; i < n; i++)
            ent[i][3] = varint(d, p);
        long lastOff = 0, lastLen = 0;
        for (int i = 0; i < n; i++) {
            long v = varint(d, p);
            ent[i][2] = v == 0
                ? lastOff + lastLen : v - 1;
            lastOff = ent[i][2];
            lastLen = ent[i][3];
        }
        List<long[]> out = new ArrayList<>((int) n);
        for (long[] e : ent) out.add(e);
        return out;
    }

    /** binary search incl. run-length coverage. */
    private static long[] findEntry(List<long[]> dir,
            long tid) {
        int lo = 0, hi = dir.size() - 1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            long[] e = dir.get(m);
            if (tid < e[0]) hi = m - 1;
            else if (e[1] == 0 ? (m + 1 < dir.size()
                    && tid >= dir.get(m + 1)[0])
                    : tid >= e[0] + e[1]) lo = m + 1;
            else return e;
        }
        return null;
    }

    private static long varint(byte[] d, int[] p) {
        long v = 0; int s = 0;
        while (true) {
            byte b = d[p[0]++];
            v |= (long) (b & 0x7F) << s;
            if ((b & 0x80) == 0) return v;
            s += 7;
        }
    }

    /** PMTiles tileId: zoom-cumulative base + Hilbert d. */
    private static long tileId(int z, int x, int y) {
        long acc = 0;
        for (int i = 0; i < z; i++)
            acc += 1L << (2 * i);
        long n = 1L << z, rx, ry, d = 0;
        long tx = x, ty = y;
        for (long s = n / 2; s > 0; s /= 2) {
            rx = (tx & s) > 0 ? 1 : 0;
            ry = (ty & s) > 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ ry);
            // rotate
            if (ry == 0) {
                if (rx == 1) {
                    tx = s - 1 - tx;
                    ty = s - 1 - ty;
                }
                long tmp = tx; tx = ty; ty = tmp;
            }
        }
        return acc + d;
    }

    // ── web-mercator tiling ──────────────────────────────────
    private static long tileKeyXY(double lat, double lon) {
        int n = 1 << Z;
        int x = (int) Math.floor((lon + 180) / 360 * n);
        double lr = Math.toRadians(lat);
        int y = (int) Math.floor((1 - Math.log(Math.tan(lr)
            + 1 / Math.cos(lr)) / Math.PI) / 2 * n);
        x = Math.max(0, Math.min(n - 1, x));
        y = Math.max(0, Math.min(n - 1, y));
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    private static List<Long> tilesForArea(double minLat,
            double maxLat, double minLon, double maxLon) {
        long a = tileKeyXY(maxLat, minLon); // top-left
        long b = tileKeyXY(minLat, maxLon); // bottom-right
        int x0 = (int) (a >> 32), y0 = (int) a;
        int x1 = (int) (b >> 32), y1 = (int) b;
        List<Long> keys = new ArrayList<>();
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                keys.add(((long) x << 32)
                    | (y & 0xFFFFFFFFL));
        return keys;
    }

    // ══════════════════════════════════════════════════════════
    // MVT (Mapbox Vector Tile) — minimal hand parser
    // ══════════════════════════════════════════════════════════

    private List<OsmStructureIndex.OsmStructure> parseMvt(
            Theme theme, byte[] d, int tx, int ty) {
        List<OsmStructureIndex.OsmStructure> out =
            new ArrayList<>();
        int[] p = {0};
        while (p[0] < d.length) {
            long tag = varint(d, p);
            int field = (int) (tag >> 3), wt = (int) (tag & 7);
            if (field == 3 && wt == 2) {          // Layer
                int len = (int) varint(d, p);
                parseLayer(theme, d, p[0], p[0] + len,
                    tx, ty, out);
                p[0] += len;
            } else skip(d, p, wt);
        }
        return out;
    }

    private void parseLayer(Theme theme, byte[] d, int start,
            int end, int tx, int ty,
            List<OsmStructureIndex.OsmStructure> out) {
        int[] p = {start};
        String name = "";
        int extent = 4096;
        List<String> keys = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        List<int[]> featureSpans = new ArrayList<>();
        while (p[0] < end) {
            long tag = varint(d, p);
            int f = (int) (tag >> 3), wt = (int) (tag & 7);
            int len;
            switch (f) {
                case 1 -> { len = (int) varint(d, p);
                    name = new String(d, p[0], len);
                    p[0] += len; }
                case 2 -> { len = (int) varint(d, p);
                    featureSpans.add(
                        new int[]{p[0], p[0] + len});
                    p[0] += len; }
                case 3 -> { len = (int) varint(d, p);
                    keys.add(new String(d, p[0], len));
                    p[0] += len; }
                case 4 -> { len = (int) varint(d, p);
                    vals.add(parseValue(d, p[0], p[0] + len));
                    p[0] += len; }
                case 5 -> extent = (int) varint(d, p);
                default -> skip(d, p, wt);
            }
        }
        for (int[] span : featureSpans)
            parseFeature(theme, name, d, span[0], span[1],
                keys, vals, extent, tx, ty, out);
    }

    private static Object parseValue(byte[] d, int start,
            int end) {
        int[] p = {start};
        while (p[0] < end) {
            long tag = varint(d, p);
            int f = (int) (tag >> 3), wt = (int) (tag & 7);
            switch (f) {
                case 1 -> { int len = (int) varint(d, p);
                    String s = new String(d, p[0], len);
                    return s; }
                case 4 -> { return varint(d, p); }
                case 5 -> { return varint(d, p); }
                case 6 -> { long z = varint(d, p);
                    return (z >>> 1) ^ -(z & 1); }
                case 7 -> { return varint(d, p) != 0; }
                case 3 -> { long bits = 0;
                    for (int i = 0; i < 8; i++)
                        bits |= (d[p[0]++] & 0xFFL) << (8 * i);
                    return Double.longBitsToDouble(bits); }
                case 2 -> { int bits = 0;
                    for (int i = 0; i < 4; i++)
                        bits |= (d[p[0]++] & 0xFF) << (8 * i);
                    return (double) Float
                        .intBitsToFloat(bits); }
                default -> skip(d, p, wt);
            }
        }
        return "";
    }

    private void parseFeature(Theme theme, String layer,
            byte[] d, int start, int end, List<String> keys,
            List<Object> vals, int extent, int tx, int ty,
            List<OsmStructureIndex.OsmStructure> out) {
        int[] p = {start};
        long id = 0;
        int gtype = 0;
        Map<String, Object> tags = new HashMap<>();
        int gStart = -1, gEnd = -1;
        while (p[0] < end) {
            long tag = varint(d, p);
            int f = (int) (tag >> 3), wt = (int) (tag & 7);
            switch (f) {
                case 1 -> id = varint(d, p);
                case 2 -> { int len = (int) varint(d, p);
                    int stop = p[0] + len;
                    while (p[0] < stop) {
                        int k = (int) varint(d, p);
                        int v = (int) varint(d, p);
                        if (k < keys.size()
                                && v < vals.size())
                            tags.put(keys.get(k),
                                vals.get(v));
                    } }
                case 3 -> gtype = (int) varint(d, p);
                case 4 -> { int len = (int) varint(d, p);
                    gStart = p[0]; gEnd = p[0] + len;
                    p[0] += len; }
                default -> skip(d, p, wt);
            }
        }
        if (gStart < 0 || gtype < 2) return; // skip points
        List<OsmStructureIndex.OsmNode> nodes =
            decodeGeometry(d, gStart, gEnd, extent, tx, ty);
        if (nodes.size() < 2) return;
        OsmStructureIndex.OsmStructure s = convert(
            theme, layer, gtype, id, tags, nodes);
        if (s != null) out.add(s);
    }

    /** MVT command stream -> first ring/line as lat/lon nodes.
     *  (Appetizer fidelity: outer ring only, no holes.) */
    private static List<OsmStructureIndex.OsmNode>
            decodeGeometry(byte[] d, int start, int end,
            int extent, int tx, int ty) {
        int[] p = {start};
        long cx = 0, cy = 0;
        List<OsmStructureIndex.OsmNode> nodes =
            new ArrayList<>();
        boolean firstRingDone = false;
        while (p[0] < end && !firstRingDone) {
            long cmd = varint(d, p);
            int op = (int) (cmd & 7), cnt = (int) (cmd >> 3);
            if (op == 7) { firstRingDone = true; break; }
            for (int i = 0; i < cnt && p[0] < end; i++) {
                long zx = varint(d, p), zy = varint(d, p);
                cx += (zx >>> 1) ^ -(zx & 1);
                cy += (zy >>> 1) ^ -(zy & 1);
                if (op == 1 && !nodes.isEmpty()) {
                    firstRingDone = true; break;
                }
                nodes.add(toLatLon(cx, cy, extent, tx, ty));
            }
        }
        return nodes;
    }

    private static OsmStructureIndex.OsmNode toLatLon(
            long px, long py, int extent, int tx, int ty) {
        double n = 1 << Z;
        double lon = (tx + (double) px / extent) / n * 360
            - 180;
        double yr = Math.PI * (1 - 2
            * (ty + (double) py / extent) / n);
        double lat = Math.toDegrees(Math.atan(Math.sinh(yr)));
        return new OsmStructureIndex.OsmNode(lat, lon);
    }

    // ══════════════════════════════════════════════════════════
    // OVERTURE FEATURE -> OsmStructure (appetizer fidelity)
    // ══════════════════════════════════════════════════════════

    private OsmStructureIndex.OsmStructure convert(Theme theme,
            String layer, int gtype, long id,
            Map<String, Object> tags,
            List<OsmStructureIndex.OsmNode> nodes) {
        String cls = str(tags.get("class"));
        String sub = str(tags.get("subtype"));
        String name = str(tags.get("@name"));
        if (name.isEmpty()) name = str(tags.get("name"));

        OsmStructureIndex.StructureType type = null;
        String subType = null;
        boolean isArea = gtype == 3;

        switch (theme) {
            case BUILDINGS -> {
                if (!"building".equals(layer)
                        && !layer.isEmpty()
                        && !"building_part".equals(layer))
                    return null;
                if ("building_part".equals(layer)) return null;
                type = OsmStructureIndex
                    .StructureType.BUILDING;
                subType = cls.isEmpty() ? "yes" : cls;
                isArea = true;
            }
            case TRANSPORTATION -> {
                if ("rail".equals(sub)) {
                    type = OsmStructureIndex
                        .StructureType.RAILWAY;
                    subType = "rail";
                } else if ("road".equals(sub)
                        || sub.isEmpty()) {
                    if (cls.isEmpty()) return null;
                    switch (cls) {
                        case "footway", "path", "sidewalk",
                             "pedestrian", "steps" -> {
                            type = OsmStructureIndex
                                .StructureType.FOOTWAY;
                            subType = "footway";
                        }
                        case "cycleway" -> {
                            type = OsmStructureIndex
                                .StructureType.CYCLEWAY;
                            subType = "cycleway";
                        }
                        default -> {
                            type = OsmStructureIndex
                                .StructureType.ROAD;
                            subType = cls;
                        }
                    }
                } else return null;
                isArea = false;
            }
            case BASE -> {
                // water layer only (land/landuse skipped in the
                // appetizer: vegetation comes from ESA anyway)
                if (!"water".equals(layer)) return null;
                return null; // water handled by water indexes;
                             // appetizer scope = structures
            }
        }
        if (type == null) return null;

        double height = num(tags.get("height"));
        int levels = (int) num(tags.get("num_floors"));
        if (levels == 0)
            levels = (int) num(tags.get("levels"));

        return new OsmStructureIndex.OsmStructure(
            type, subType, name.isEmpty() ? null : name,
            nodes.toArray(
                new OsmStructureIndex.OsmNode[0]),
            isArea,
            // negative id-space: never collides with real OSM
            // ids, and marks provenance for the swap/debug
            -Math.abs(id == 0
                ? (long) (nodes.get(0).lat * 1e6)
                    * 31 + (long) (nodes.get(0).lon * 1e6)
                : id),
            null, levels, height, null, null,
            str(tags.get("surface")), 2, false,
            0, 0, 0, null, null, false, 0,
            0, false, false, null,
            HistoricalDateParser.UNKNOWN_YEAR,
            HistoricalDateParser.UNKNOWN_YEAR);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); }
        catch (Exception e) { return 0; }
    }
    private static void skip(byte[] d, int[] p, int wt) {
        switch (wt) {
            case 0 -> varint(d, p);
            case 1 -> p[0] += 8;
            case 2 -> p[0] += (int) varint(d, p);
            case 5 -> p[0] += 4;
            default -> p[0] = d.length;
        }
    }
}
