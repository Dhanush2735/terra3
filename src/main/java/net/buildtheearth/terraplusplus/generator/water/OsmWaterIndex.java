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
//   - Hedged endpoint racing (raceQuery) + Taiwan endpoint
//     + shared bounded coast wait (getTileBlockingShared):
//     JUHU_COAST_SPEED_FIX.md
//   - Coastline spatial index (CoastGrid): classifyCoastline
//     no longer scans every segment in 3x3 tiles per point;
//     it measures only segments in nearby grid cells and
//     expands until the nearest segment is proven found.
//     Same answers, far fewer distance computations
//     (NW28 proved the full scan cost ~137 ms per coastal
//     chunk at Juhu). See COAST_SCAN_INDEX_FIX.md
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
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
    // Slow-start cure: how long a failed tile fetch is
    // remembered. Inside this window callers fail fast
    // instead of relaunching a full fetch round per chunk.
    private static final long   FAIL_TTL_MS     = 30_000L;
    private static final int    DL_THREADS      = 4; // increased from 2
    private static final int    MAX_CONSECUTIVE_FAILURES = 5;
    // Source-wide pause is temporary: public Overpass outages must never
    // permanently disable OSM water for the rest of a play session.
    private static final long   PAUSE_ON_FAILURES_MS = 60_000L;
    // A 4xx response generally means this particular mirror rejects our query.
    // Quarantine only that mirror and continue with the other endpoints.
    private static final long   ENDPOINT_4XX_PAUSE_MS = 5 * 60_000L;
    private static final double COASTLINE_BAND_M = 5.0;
    // Coastline spatial index cell size (~111 m of latitude).
    // A classification point only measures segments whose
    // bounding boxes touch its own cell neighbourhood instead
    // of every segment stored in the surrounding 3x3 tiles.
    private static final double COAST_GRID_CELL_DEG = 0.001;
    // Half-size (degrees) of the first search box around a
    // point (~178 m). The box grows x4 until the nearest
    // segment is proven found or the tile's whole coastline
    // extent has been covered.
    private static final double COAST_GRID_FIRST_DEG = 0.0016;
    // 3x3 tile visit order for coastline classification:
    // centre tile first (see classifyCoastline).
    private static final int[][] COAST_TILE_OFFS = {
        {0, 0}, {0, -1}, {0, 1}, {-1, 0}, {1, 0},
        {-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
    // Hedged endpoint racing: if the first Overpass endpoint
    // has produced nothing after this long, the SAME query is
    // launched on the next endpoint in parallel (first 200
    // wins, losers are disconnected). Kills the old sequential
    // worst case where a hung endpoint burned a full 20 s read
    // timeout before the next endpoint was even tried (NW24:
    // real batches at 39-66 s near Juhu, far past the 3 s
    // COAST WAIT, so shores fossilised from the fallback).
    private static final long   HEDGE_MS        = 6000L;
    private static final ExecutorService RACE_POOL =
        Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Terra++OSM-Race");
            t.setDaemon(true);
            return t;
        });

    private static final String[] OVERPASS_ENDPOINTS = {
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        // Taiwan (NCHC) — global coverage per the OSM wiki and
        // geographically the closest public instance to India,
        // where overpass-api.de/kumi routinely take 39-66 s.
        "https://overpass.nchc.org.tw/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    };

    // ── COASTLINE FALLBACK DOOR (official OSM API) ────────────
    // The open sea exists only as coastline ways, and Overpass
    // is chronically overloaded (502/timeout) in some networks.
    // When every Overpass endpoint fails, coastline segments are
    // fetched from api.openstreetmap.org map.json instead — the
    // door OsmStructureIndex already proves alive. Coastline
    // only: water polygons still come from Overpass when it
    // answers. See COASTLINE_MISSING_SEA.md.
    private static final String OSM_API_URL =
        "https://api.openstreetmap.org/api/0.6/map.json?bbox=";
    private static final long OSM_API_SPACING_MS = 300;
    private static final Object OSM_API_GATE = new Object();
    private static long osmApiLastCall = 0;
    private static volatile long osmApiDownUntil = 0;
    private static final AtomicInteger osmApiFails =
        new AtomicInteger();

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

    // ── COASTLINE SPATIAL INDEX ─────────────────────────────
    // NW28 (in-game, Juhu) proved the beach slowness is this
    // class re-measuring EVERY coastline segment in the 3x3
    // tile neighbourhood for EVERY classified point: ~32 us
    // per point, ~137 ms of pure classification per coastal
    // chunk, re-paid by every chunk along a shore. The old
    // mightBeNear(lat, lon, 0.5) filter was 0.5 DEGREES wide
    // (~55 km), so it filtered out almost nothing.
    //
    // CoastGrid files each segment of one tile into the grid
    // cells its bounding box touches. A query gathers only
    // the cells overlapped by a small box around the point,
    // then grows the box (x4) until either the whole coastline
    // extent of the tile was covered or the best distance
    // found is smaller than anything outside the box could
    // possibly be (box half-size converted to metres with the
    // most poleward latitude in play, i.e. a safe lower
    // bound). The nearest segment — and therefore the
    // LAND/OCEAN/COASTLINE answer — is exactly the one the
    // old full scan would have found.
    //
    // Segments whose bounding box touches too many cells
    // (very long straight ways) go to a small "big" list that
    // is always measured; there are only ever a few of those.
    private static final class CoastGrid {
        final CoastlineSegment[] segs;
        final double minLat, maxLat, minLon, maxLon, cellDeg;
        final int nLat, nLon;
        final int[][] cells;   // [latCell * nLon + lonCell]
        final int[] big;       // always-measured segment idx
        final int[] seen;      // per-segment gather stamp
        final AtomicInteger seenSeq = new AtomicInteger();

        CoastGrid(CoastlineSegment[] segs,
                double minLat, double maxLat,
                double minLon, double maxLon,
                double cellDeg, int nLat, int nLon,
                int[][] cells, int[] big) {
            this.segs    = segs;
            this.minLat  = minLat;  this.maxLat = maxLat;
            this.minLon  = minLon;  this.maxLon = maxLon;
            this.cellDeg = cellDeg;
            this.nLat    = nLat;    this.nLon   = nLon;
            this.cells   = cells;   this.big    = big;
            this.seen    = new int[segs.length];
        }

        int nextMark() {
            int m = seenSeq.incrementAndGet();
            if (m == Integer.MAX_VALUE) {
                synchronized (this) {
                    Arrays.fill(seen, 0);
                    seenSeq.set(1);
                    m = 1;
                }
            }
            return m;
        }
    }

    /** Mutable nearest-segment result for one tile query. */
    private static final class Nearest {
        double dist   = Double.MAX_VALUE;
        double signed = 0;
        boolean found = false;
        // Conservative metres-per-degree-longitude lower-bound
        // factor for this query (most poleward latitude in
        // play), set by nearestCoastInTile before gathering.
        double mPerDegLonLB = 111320.0;
    }

    private static int coastCellIdx(double v, double min,
            double cellDeg, int n) {
        int i = (int) Math.floor((v - min) / cellDeg);
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }

    private static CoastGrid buildCoastGrid(
            CoastlineSegment[] segs) {
        double mnLat = Double.MAX_VALUE,
               mxLat = -Double.MAX_VALUE,
               mnLon = Double.MAX_VALUE,
               mxLon = -Double.MAX_VALUE;
        for (CoastlineSegment s : segs) {
            if (s.minLat < mnLat) mnLat = s.minLat;
            if (s.maxLat > mxLat) mxLat = s.maxLat;
            if (s.minLon < mnLon) mnLon = s.minLon;
            if (s.maxLon > mxLon) mxLon = s.maxLon;
        }
        double spanLat = Math.max(0, mxLat - mnLat);
        double spanLon = Math.max(0, mxLon - mnLon);
        // Cap the grid at ~48x48 cells however large the
        // stored geometry is; never finer than ~278 m.
        double cell = Math.max(COAST_GRID_CELL_DEG,
            Math.max(spanLat, spanLon) / 48.0);
        int nLat = Math.max(1,
            (int) Math.ceil(spanLat / cell));
        int nLon = Math.max(1,
            (int) Math.ceil(spanLon / cell));

        List<List<Integer>> tmp = new ArrayList<>(nLat * nLon);
        for (int i = 0; i < nLat * nLon; i++)
            tmp.add(new ArrayList<>());
        List<Integer> bigList = new ArrayList<>();
        for (int i = 0; i < segs.length; i++) {
            CoastlineSegment s = segs[i];
            int la0 = coastCellIdx(s.minLat, mnLat, cell, nLat);
            int la1 = coastCellIdx(s.maxLat, mnLat, cell, nLat);
            int lo0 = coastCellIdx(s.minLon, mnLon, cell, nLon);
            int lo1 = coastCellIdx(s.maxLon, mnLon, cell, nLon);
            long cellCount = (long) (la1 - la0 + 1)
                           * (lo1 - lo0 + 1);
            if (cellCount > 1024) {
                bigList.add(i);
                continue;
            }
            for (int la = la0; la <= la1; la++)
                for (int lo = lo0; lo <= lo1; lo++)
                    tmp.get(la * nLon + lo).add(i);
        }
        int[][] cells = new int[nLat * nLon][];
        for (int i = 0; i < cells.length; i++) {
            List<Integer> l = tmp.get(i);
            int[] a = new int[l.size()];
            for (int j = 0; j < a.length; j++) a[j] = l.get(j);
            cells[i] = a;
        }
        int[] big = new int[bigList.size()];
        for (int i = 0; i < big.length; i++)
            big[i] = bigList.get(i);
        return new CoastGrid(segs, mnLat, mxLat, mnLon, mxLon,
            cell, nLat, nLon, cells, big);
    }

    /** Lazily built (once per tile), then read-only. */
    private static CoastGrid coastGrid(WaterTile tile) {
        CoastGrid g = tile.coastGrid;
        if (g != null) return g;
        synchronized (tile) {
            if (tile.coastGrid == null)
                tile.coastGrid =
                    buildCoastGrid(tile.coastline);
            return tile.coastGrid;
        }
    }

    private static void considerSeg(CoastGrid g, int idx,
            int mark, double lat, double lon, Nearest out) {
        if (g.seen[idx] == mark) return;
        g.seen[idx] = mark;
        CoastlineSegment s = g.segs[idx];
        if (out.found) {
            // Cheap rejection: the segment lies inside its
            // bounding box, so if even the box is farther
            // than the best distance so far, the segment
            // cannot win — skip the exact (trig) distance.
            double dLatDeg = lat < s.minLat ? s.minLat - lat
                : (lat > s.maxLat ? lat - s.maxLat : 0);
            double dLonDeg = lon < s.minLon ? s.minLon - lon
                : (lon > s.maxLon ? lon - s.maxLon : 0);
            if (dLatDeg > 0 || dLonDeg > 0) {
                double mLat = dLatDeg * 111320.0;
                double mLon = dLonDeg * out.mPerDegLonLB;
                if (mLat * mLat + mLon * mLon
                        >= out.dist * out.dist)
                    return;
            }
        }
        double signed = s.signedDistMeters(lat, lon);
        double d = Math.abs(signed);
        if (d < out.dist) {
            out.dist   = d;
            out.signed = signed;
            out.found  = true;
        }
    }

    /** Measure every not-yet-measured segment that could lie
     *  within the box lat/lon +/- rDeg of the point. */
    private static void gatherCoast(CoastGrid g,
            double lat, double lon, double rDeg,
            int mark, Nearest out) {
        for (int idx : g.big)
            considerSeg(g, idx, mark, lat, lon, out);
        if (lat + rDeg < g.minLat || lat - rDeg > g.maxLat
                || lon + rDeg < g.minLon
                || lon - rDeg > g.maxLon)
            return;
        int la0 = coastCellIdx(lat - rDeg,
            g.minLat, g.cellDeg, g.nLat);
        int la1 = coastCellIdx(lat + rDeg,
            g.minLat, g.cellDeg, g.nLat);
        int lo0 = coastCellIdx(lon - rDeg,
            g.minLon, g.cellDeg, g.nLon);
        int lo1 = coastCellIdx(lon + rDeg,
            g.minLon, g.cellDeg, g.nLon);
        for (int la = la0; la <= la1; la++)
            for (int lo = lo0; lo <= lo1; lo++)
                for (int idx : g.cells[la * g.nLon + lo])
                    considerSeg(g, idx, mark, lat, lon, out);
    }

    /** Exact nearest coastline segment of one tile — the same
     *  segment the old full scan would have picked. Returns
     *  null when this tile cannot beat limitDist (the caller's
     *  best-so-far across tiles), which lets far tiles be
     *  skipped with one bounding-box check. */
    private static Nearest nearestCoastInTile(WaterTile tile,
            double lat, double lon, double limitDist) {
        CoastGrid g = coastGrid(tile);
        if (g == null || g.segs.length == 0) return null;
        // Most poleward latitude in play = smallest
        // metres-per-degree-longitude = safe lower bounds.
        double maxAbsLat = Math.max(Math.abs(lat),
            Math.max(Math.abs(g.minLat), Math.abs(g.maxLat)));
        double mPerDeg = 111320.0 * Math.min(1.0,
            Math.cos(Math.toRadians(
                Math.min(89.0, maxAbsLat))));
        // Whole-tile rejection: if even this tile's coastline
        // bounding box is farther than the caller's best, no
        // segment in it can win.
        double bbLatDeg = lat < g.minLat ? g.minLat - lat
            : (lat > g.maxLat ? lat - g.maxLat : 0);
        double bbLonDeg = lon < g.minLon ? g.minLon - lon
            : (lon > g.maxLon ? lon - g.maxLon : 0);
        if (bbLatDeg > 0 || bbLonDeg > 0) {
            double a = bbLatDeg * 111320.0;
            double b = bbLonDeg * mPerDeg;
            if (a * a + b * b >= limitDist * limitDist)
                return null;
        }
        Nearest out = new Nearest();
        out.mPerDegLonLB = mPerDeg;
        int mark = g.nextMark();
        double rDeg = COAST_GRID_FIRST_DEG;
        while (true) {
            gatherCoast(g, lat, lon, rDeg, mark, out);
            if (out.found && out.dist <= COASTLINE_BAND_M)
                return out;   // on the line; cannot be beaten
            if (out.found) {
                // Nothing outside the box can be closer than
                // the box half-size in metres.
                if (out.dist < rDeg * mPerDeg) return out;
            } else if (rDeg * mPerDeg >= limitDist) {
                // Nothing in the box, and everything outside
                // it is already farther than the caller's
                // best — this tile cannot contribute.
                return null;
            }
            boolean covers = lat - rDeg <= g.minLat
                && lat + rDeg >= g.maxLat
                && lon - rDeg <= g.minLon
                && lon + rDeg >= g.maxLon;
            if (covers) return out.found ? out : null;
            rDeg *= 4.0;
            if (rDeg > 8.0) return out.found ? out : null;
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
        // Lazily built coastline spatial index (CoastGrid);
        // built once on first classification of this tile.
        volatile CoastGrid coastGrid;

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
    // Tile key -> last failed fetch time (slow-start cure:
    // anti-stampede memory, see FAIL_TTL_MS).
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

        // Centre tile first: it normally holds the nearest
        // segment, and the best distance found there lets the
        // remaining tiles be skipped by bounding box.
        tiles:
        for (int[] off : COAST_TILE_OFFS) {
                long key = tileKey(
                    lat + off[0] * TILE_DEG,
                    lon + off[1] * TILE_DEG);
                WaterTile tile = memCache.get(key);
                if (tile == null || !tile.valid)
                    continue;
                anyLoaded = true;
                if (!tile.hasCoastline()) continue;
                anyCoast = true;
                // Spatial index: measure only segments in
                // the grid cells near this point (expanding
                // until the nearest is proven) instead of
                // scanning every segment of the tile. The
                // nearest segment found is identical to the
                // old full scan's.
                Nearest n = nearestCoastInTile(
                    tile, lat, lon, bestDist);
                if (n != null && n.found
                        && n.dist < bestDist) {
                    bestDist   = n.dist;
                    bestSigned = n.signed;
                    if (bestDist <= COASTLINE_BAND_M)
                        break tiles;
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

    /** Coastline segments currently in memory around a point
     *  (3x3 tiles). 0 can mean open ocean (no coast near) or
     *  that no tile has loaded — callers combine this with
     *  classifyCoastlineMemOnly (UNKNOWN = no data). */
    public int coastSegCountAround(double lat, double lon) {
        int n = 0;
        for (int dlat = -1; dlat <= 1; dlat++)
            for (int dlon = -1; dlon <= 1; dlon++) {
                WaterTile t = memCache.get(tileKey(
                        lat + dlat * TILE_DEG,
                        lon + dlon * TILE_DEG));
                if (t != null && t.valid && t.coastline != null)
                    n += t.coastline.length;
            }
        return n;
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

        // Recent failure on record: fail fast. Without this,
        // every coastal chunk paid the full wait (or launched
        // its own fetch round) for a tile that just failed —
        // the cold-start stall stampede.
        if (recentlyFailed(key)) return false;

        // Not on disk — submit to thread pool
        CompletableFuture<WaterTile> future =
            pending.computeIfAbsent(key,
                k -> submitFetch(k, lat, lon));

        try {
            WaterTile r = future.get(
                maxWaitMs, TimeUnit.MILLISECONDS);
            return r != null && r.valid;
        } catch (TimeoutException e) {
            // Slow-start cure: do NOT drop the pending entry
            // while the fetch is still running — the next
            // chunk would launch a duplicate download of the
            // same tile, and waiters would multiply fetches
            // instead of sharing one. The fetch itself
            // completes/removes the entry when done.
            if (future.isDone())
                pending.remove(key, future);
            return false;
        } catch (Exception e) {
            pending.remove(key, future);
            return false;
        }
    }

    // ── SHARED BOUNDED WAIT ─────────────────────────────────
    // Tile key -> absolute deadline (ms) of the shared wait
    // window for that tile. The first waiter opens the window;
    // later waiters for the SAME tile only wait out what is
    // left of it instead of each parking for the full budget.
    // After the window closes they fail fast while the shared
    // fetch keeps running in the background (AsyncWaterManager
    // requeues the chunk for authoritative water placement).
    private final ConcurrentHashMap<Long, Long>
        sharedWaitDeadline = new ConcurrentHashMap<>();

    /**
     * Like {@link #getTileBlocking} but the wait budget is
     * shared per tile: total waiting for one tile is bounded by
     * maxWaitMs from the first call, not maxWaitMs per caller.
     * Used by the COAST WAIT in terrain generation, where many
     * chunks queue on the same coastline tile.
     */
    public boolean getTileBlockingShared(double lat, double lon,
            long maxWaitMs) {
        if (!enabled || lat <= -85 || lat >= 85)
            return false;
        long key = tileKey(lat, lon);
        WaterTile tile = memCache.get(key);
        if (tile != null) {
            sharedWaitDeadline.remove(key);
            return tile.valid;
        }
        long nowMs = System.currentTimeMillis();
        if (sharedWaitDeadline.size() > 4096)
            sharedWaitDeadline.clear();
        CompletableFuture<WaterTile> pf = pending.get(key);
        boolean fetchRunning = pf != null && !pf.isDone();
        Long dl = sharedWaitDeadline.get(key);
        if (dl == null || (!fetchRunning && nowMs >= dl)) {
            // No window yet, or the window closed with no fetch
            // running: open a fresh one.
            long fresh = nowMs + maxWaitMs;
            if (dl == null) {
                Long prev = sharedWaitDeadline.putIfAbsent(
                    key, fresh);
                dl = (prev != null) ? prev : fresh;
            } else {
                sharedWaitDeadline.put(key, fresh);
                dl = fresh;
            }
        }
        long eff = dl - nowMs;
        if (eff <= 0) return false; // window spent, fetch running
        try {
            return getTileBlocking(lat, lon, eff);
        } finally {
            CompletableFuture<WaterTile> f2 = pending.get(key);
            if (f2 == null || f2.isDone())
                sharedWaitDeadline.remove(key);
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
            if (!sub.valid) stampFailure(e.getKey());
            else failedAt.remove(e.getKey());
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

    /** Endpoint-hedged POST of a water query; null on total
     *  failure (all endpoints down/paused). Returns raw JSON. */
    private String executeWaterQuery(String bbox,
            int timeoutSec) {
        if (System.currentTimeMillis() < pauseUntilMs)
            return null;
        String query = waterQueryFor(bbox, timeoutSec);
        totalQueries.incrementAndGet();
        String json = raceQuery(query, timeoutSec,
            new AtomicBoolean(false));
        if (json != null) consecutiveFailures = 0;
        return json;
    }

    /**
     * Hedged race of one Overpass query across the endpoints.
     *
     * The first non-paused endpoint launches immediately; if it
     * has produced nothing after HEDGE_MS the same query launches
     * on the next endpoint IN PARALLEL, and so on. First HTTP 200
     * wins; losing connections are disconnected at once. An
     * endpoint that fails fast never waits out the hedge — the
     * next endpoint launches immediately.
     *
     * Replaces sequential endpoint rotation, whose worst case was
     * every endpoint burning a full read timeout in series. Each
     * attempt still takes its own endpoint's rate-limiter token
     * and in-flight permit, so per-endpoint politeness is
     * unchanged; the race only removes the serial waiting between
     * endpoints. Attempts run on RACE_POOL, never on dlPool, so a
     * race can never starve the fetch threads that launched it.
     */
    private String raceQuery(String query, int timeoutSec,
            AtomicBoolean attemptedOut) {
        if (System.currentTimeMillis() < pauseUntilMs)
            return null;
        long nowMs = System.currentTimeMillis();
        List<Integer> cand = new ArrayList<>();
        int startIdx = endpointIdx.get()
            % OVERPASS_ENDPOINTS.length;
        if (startIdx < 0)
            startIdx += OVERPASS_ENDPOINTS.length;
        for (int i = 0; i < OVERPASS_ENDPOINTS.length; i++) {
            int idx = (startIdx + i) % OVERPASS_ENDPOINTS.length;
            if (nowMs < endpointPauseUntil[idx]) continue;
            cand.add(idx);
        }
        if (cand.isEmpty()) return null;

        ExecutorCompletionService<String> ecs =
            new ExecutorCompletionService<>(RACE_POOL);
        Set<HttpURLConnection> live =
            ConcurrentHashMap.newKeySet();
        List<Future<String>> futures = new ArrayList<>();
        int launched = 0, completed = 0;
        final int firstIdx = cand.get(0);
        futures.add(ecs.submit(() -> attemptEndpoint(
            firstIdx, query, timeoutSec, live, attemptedOut)));
        launched++;
        try {
            while (completed < launched) {
                Future<String> done = ecs.poll(
                    HEDGE_MS, TimeUnit.MILLISECONDS);
                if (done == null) {
                    // Hedge interval elapsed with no answer:
                    // launch the next endpoint in parallel.
                    if (launched < cand.size()) {
                        final int idx = cand.get(launched);
                        futures.add(ecs.submit(() ->
                            attemptEndpoint(idx, query,
                                timeoutSec, live,
                                attemptedOut)));
                        launched++;
                    }
                    continue;
                }
                completed++;
                String json = null;
                try { json = done.get(); }
                catch (Exception ignored) {}
                if (json != null) {
                    // Winner: cut the losers' connections now
                    // instead of letting them run to timeout.
                    for (HttpURLConnection c : live) {
                        try { c.disconnect(); }
                        catch (Exception ignored) {}
                    }
                    for (Future<String> f : futures)
                        f.cancel(true);
                    return json;
                }
                // Fast failure: never wait out the hedge.
                if (launched == completed
                        && launched < cand.size()) {
                    final int idx = cand.get(launched);
                    futures.add(ecs.submit(() ->
                        attemptEndpoint(idx, query,
                            timeoutSec, live, attemptedOut)));
                    launched++;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            for (Future<String> f : futures) f.cancel(true);
        }
        return null;
    }

    /** One endpoint attempt of a water query. Returns the raw
     *  JSON on HTTP 200, null otherwise. Handles this endpoint's
     *  rate limiter, 429/504 penalties and 4xx quarantine. The
     *  live connection is registered in {@code live} so the race
     *  winner can disconnect losers immediately. */
    private String attemptEndpoint(int idx, String query,
            int timeoutSec, Set<HttpURLConnection> live,
            AtomicBoolean attemptedOut) {
        String ep = OVERPASS_ENDPOINTS[idx];
        OverpassRateLimiter limiter = endpointLimiters[idx];
        HttpURLConnection conn = null;
        boolean inFlight = false;
        try {
            attemptedOut.set(true);
            limiter.acquire();
            limiter.acquireInFlight();
            inFlight = true;
            conn = (HttpURLConnection)
                new URL(ep).openConnection();
            live.add(conn);
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
                return json;
            } else if (code == 429 || code == 504) {
                limiter.penalize(
                    code == 429 ? 30_000L : 10_000L);
            } else if (code >= 400 && code < 500) {
                endpointPauseUntil[idx] =
                    System.currentTimeMillis()
                        + ENDPOINT_4XX_PAUSE_MS;
                LOGGER.warn("{} returned {}; pausing this "
                    + "endpoint for {}s",
                    epName(ep), code,
                    ENDPOINT_4XX_PAUSE_MS / 1000);
                limiter.penalize(8_000L);
            } else {
                LOGGER.warn("{} returned {}",
                    epName(ep), code);
                limiter.penalize(8_000L);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.debug("water query {}: {}",
                epName(ep), e.getMessage());
        } finally {
            if (conn != null) live.remove(conn);
            if (inFlight) limiter.releaseInFlight();
            net.buildtheearth.terraplusplus.util
                .HttpKeepAlive.release(conn);
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

        if (recentlyFailed(key)) return null;

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
            () -> {
                WaterTile t =
                    fetchTile(key, tileLat, tileLon);
                // Remember the outcome: failures are stamped
                // so following chunks fail fast (FAIL_TTL_MS)
                // instead of stampeding the endpoints.
                if (t == null || !t.valid)
                    stampFailure(key);
                else
                    failedAt.remove(key);
                return t;
            },
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

        // Hedged endpoint race (see raceQuery): the first
        // endpoint to answer wins, instead of endpoints tried
        // one by one with a full read timeout each.
        AtomicBoolean attemptedEndpoint =
            new AtomicBoolean(false);
        String json = raceQuery(query, 20, attemptedEndpoint);
        if (json != null) {
            WaterTile wt = parseResponse(json);
            consecutiveFailures = 0;
            pauseUntilMs = 0;
            pauseLogged.set(false);
            memCache.put(key, wt);
            pending.remove(key);
            evictIfNeeded();
            if (diskOk) saveDisk(key, json);
            LOGGER.info(
                "OSM [{},{}]: {} features "
                + "{} coast segs",
                String.format("%.4f", minLat),
                String.format("%.4f", minLon),
                wt.features.length,
                wt.coastline.length);
            return wt;
        }

        // ── COASTLINE FALLBACK: official OSM API ──────────────
        // Overpass gave nothing this round. The sea must not
        // depend on Overpass alone, so try the main OSM API for
        // coastline segments before declaring the tile failed.
        WaterTile apiTile =
            fetchCoastlineViaOsmApi(key, minLat, minLon);
        if (apiTile != null) return apiTile;

        // Only count a round that actually contacted at least one endpoint.
        // If all mirrors are in their own cooldown windows, retry later without
        // escalating to a source-wide outage.
        if (attemptedEndpoint.get()) consecutiveFailures++;
        pending.remove(key);
        return WaterTile.FAILED;
    }

    /**
     * Coastline-only tile from the official OSM API
     * ({@code /api/0.6/map.json}). The tile box is split into
     * 2x2 sub-boxes to stay under the API's 50k-node cap; every
     * sub-box must answer, otherwise the tile stays FAILED so a
     * later retry can complete it. Returns null on any failure.
     */
    private WaterTile fetchCoastlineViaOsmApi(long key,
            double minLat, double minLon) {
        if (System.currentTimeMillis() < osmApiDownUntil)
            return null;
        double expand = TILE_DEG * 0.1;
        double lat0 = minLat - expand;
        double lon0 = minLon - expand;
        double lat1 = minLat + TILE_DEG + expand;
        double lon1 = minLon + TILE_DEG + expand;
        double midLat = (lat0 + lat1) / 2.0;
        double midLon = (lon0 + lon1) / 2.0;
        double[][] boxes = {
            {lat0, lon0, midLat, midLon},
            {lat0, midLon, midLat, lon1},
            {midLat, lon0, lat1, midLon},
            {midLat, midLon, lat1, lon1}};
        List<CoastlineSegment> segs = new ArrayList<>();
        for (double[] b : boxes) {
            if (!fetchCoastlineBox(b[0], b[1], b[2], b[3], segs)) {
                if (osmApiFails.incrementAndGet() >= 3)
                    osmApiDownUntil =
                        System.currentTimeMillis() + 120_000L;
                return null;
            }
        }
        osmApiFails.set(0);
        WaterTile wt = new WaterTile(
            new WaterFeature[0],
            segs.toArray(new CoastlineSegment[0]), true);
        memCache.put(key, wt);
        pending.remove(key);
        evictIfNeeded();
        if (diskOk) saveDisk(key, coastlineJson(segs));
        LOGGER.info(
            "OSM API coastline [{},{}]: {} coast segs "
            + "(Overpass fallback door)",
            String.format("%.4f", minLat),
            String.format("%.4f", minLon),
            segs.size());
        return wt;
    }

    /** One map.json sub-box; appends coastline segments. */
    private boolean fetchCoastlineBox(double minLat,
            double minLon, double maxLat, double maxLon,
            List<CoastlineSegment> out) {
        // polite spacing across all threads (mirrors
        // OsmStructureIndex's OSM API gate)
        synchronized (OSM_API_GATE) {
            long wait = osmApiLastCall + OSM_API_SPACING_MS
                - System.currentTimeMillis();
            if (wait > 0) {
                try { Thread.sleep(wait); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            osmApiLastCall = System.currentTimeMillis();
        }
        HttpURLConnection conn = null;
        try {
            // API order: left,bottom,right,top (lon,lat)
            String url = OSM_API_URL + String.format(
                java.util.Locale.ROOT,
                "%.6f,%.6f,%.6f,%.6f",
                minLon, minLat, maxLon, maxLat);
            conn = (HttpURLConnection)
                new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("User-Agent",
                "TerraPlusPlus/2.0 Minecraft");
            conn.setRequestProperty(
                "Accept-Encoding", "gzip");
            if (conn.getResponseCode() != 200) return false;
            String json = readResponse(conn);
            JsonObject root =
                JsonParser.parseString(json).getAsJsonObject();
            JsonArray elements =
                root.getAsJsonArray("elements");
            if (elements == null) return true;
            Map<Long, double[]> nodes = new HashMap<>();
            for (JsonElement el : elements) {
                JsonObject o = el.getAsJsonObject();
                if ("node".equals(getStr(o, "type"))
                        && o.has("id") && o.has("lat")
                        && o.has("lon"))
                    nodes.put(o.get("id").getAsLong(),
                        new double[]{
                            o.get("lat").getAsDouble(),
                            o.get("lon").getAsDouble()});
            }
            for (JsonElement el : elements) {
                JsonObject o = el.getAsJsonObject();
                if (!"way".equals(getStr(o, "type"))) continue;
                JsonObject tags = o.has("tags")
                    ? o.getAsJsonObject("tags") : null;
                if (tags == null || !"coastline".equals(
                        getTag(tags, "natural"))) continue;
                if (!o.has("nodes")) continue;
                JsonArray refs = o.getAsJsonArray("nodes");
                double[] prev = null;
                for (JsonElement r : refs) {
                    double[] p =
                        nodes.get(r.getAsLong());
                    if (p == null) { prev = null; continue; }
                    if (prev != null)
                        out.add(new CoastlineSegment(
                            prev[0], prev[1], p[0], p[1]));
                    prev = p;
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.debug("OSM API coastline box failed: {}",
                e.getMessage());
            return false;
        } finally {
            net.buildtheearth.terraplusplus.util
                .HttpKeepAlive.release(conn);
        }
    }

    private static String getStr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive()
            ? o.get(k).getAsString() : null;
    }

    /** Out-geom-shaped JSON for the disk cache, so a coastline
     *  tile fetched via the API door reloads through the normal
     *  Overpass parser ({@link #parseResponse}). */
    private static String coastlineJson(
            List<CoastlineSegment> segs) {
        StringBuilder sb = new StringBuilder(segs.size() * 96);
        sb.append("{\"elements\":[");
        for (int i = 0; i < segs.size(); i++) {
            CoastlineSegment s = segs.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"type\":\"way\",\"tags\":{\"natural\":")
              .append("\"coastline\"},\"geometry\":[{\"lat\":")
              .append(String.format(java.util.Locale.ROOT,
                  "%.6f", s.aLat))
              .append(",\"lon\":")
              .append(String.format(java.util.Locale.ROOT,
                  "%.6f", s.aLon))
              .append("},{\"lat\":")
              .append(String.format(java.util.Locale.ROOT,
                  "%.6f", s.bLat))
              .append(",\"lon\":")
              .append(String.format(java.util.Locale.ROOT,
                  "%.6f", s.bLon))
              .append("}]}");
        }
        sb.append("]}");
        return sb.toString();
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