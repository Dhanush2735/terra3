import net.buildtheearth.terraminusminus.projection.dymaxion.BTEDymaxionProjection;
import net.buildtheearth.terraplusplus.generator.water.OsmWaterIndex.CoastlineSegment;
import java.util.*;

/**
 * RECREATION of the Juhu biome-slowness problem (owner asked:
 * "can you recreate the problem?").
 *
 * Not Minecraft — a faithful stand-in built from the mod's own
 * compiled pieces (real BTE Dymaxion projection + real
 * OsmWaterIndex coastline-segment maths from the project jar),
 * running the two versions of TerraChunkGenerator.assignBiomes:
 *
 *   OLD: for each of 254 sections (world height 4064 = 254 x 16)
 *          for each of 16 quarts: classify() + shore probes
 *   NEW: classify the 16 quarts once, then only paint.
 *
 * classify() here does the expensive parts of the game's
 * classify(): elevation bilinear (x2 in the Y-rule low zone,
 * Juhu reads ~3 m) + coastline scan over every segment in the
 * 3x3 tiles in memory (NW(27) log: 1,771). The real Dymaxion
 * toGeo is timed separately (0.38 us/call) and NOT included in
 * the loop totals: worst case it would add ~28 ms per chunk
 * to OLD (73k calls) and ~0.1 ms to NEW — under 1%, it does
 * not change any conclusion.
 *
 * Coastline geometry below is synthetic (sandbox cannot reach
 * Overpass), matched to the real load: 1,771 segments inside
 * the same 0.15 x 0.15 deg box around Juhu, ~14 m each, sea to
 * the west. Segment COUNT is what costs time, not exact shape.
 */
public class RecreateBiomeProblem {
    // ---- Juhu ----
    static final double JUHU_LAT = 19.0968, JUHU_LON = 72.8265;
    static final double TILE_DEG = 0.05, PROBE_FILTER_DEG = 0.5;

    static BTEDymaxionProjection proj;
    static Map<Long, List<CoastlineSegment>> tiles = new HashMap<>();
    static int segTotal = 0;

    static long tileKey(double lat, double lon) { // verbatim from OsmWaterIndex
        int tLat = (int) Math.floor(lat / TILE_DEG);
        int tLon = (int) Math.floor(lon / TILE_DEG);
        return ((long) tLat << 32) | (tLon & 0xFFFFFFFFL);
    }

    static void buildCoastline() {
        // Wiggly N-S coast, lat 19.00..19.15, sea WEST (lon < coast).
        List<double[]> pts = new ArrayList<>();
        for (double lat = 19.00; lat <= 19.15; lat += 0.00010) { // ~11 m steps
            double lon = 72.8265
                + 0.0035 * Math.sin((lat - 19.0) * 220.0)
                + 0.0015 * Math.sin((lat - 19.0) * 61.0)
                + 0.0006 * Math.sin((lat - 19.0) * 900.0);
            pts.add(new double[]{lat, lon});
        }
        // one creek excursion (Versova-like) for wiggle
        for (int i = 0; i < 271; i++) {
            double t = i / 271.0;
            double lat = 19.128 + 0.004 * Math.sin(t * Math.PI);
            double lon = 72.824 + 0.006 * Math.sin(t * Math.PI * 0.5);
            pts.add(new double[]{lat, lon});
        }
        for (int i = 0; i + 1 < pts.size() && segTotal < 1771; i++) {
            double[] A = pts.get(i), B = pts.get(i + 1);
            CoastlineSegment s = new CoastlineSegment(A[0], A[1], B[0], B[1]);
            long k = tileKey((A[0] + B[0]) / 2, (A[1] + B[1]) / 2);
            tiles.computeIfAbsent(k, kk -> new ArrayList<>()).add(s);
            segTotal++;
        }
    }

    // ---- elevation stand-in: 512x512 decoded tile, Terrarium packing ----
    static final int[] TILE = new int[512 * 512];
    static double elevBilinear(double lat, double lon) {
        double fx = ((lon + 180.0) * 256.0) % 511.0;   // position inside tile
        double fy = ((90.0 - lat) * 256.0) % 511.0;
        int x0 = (int) fx, y0 = (int) fy;
        double tx = fx - x0, ty = fy - y0;
        java.util.function.IntUnaryOperator px = i -> TILE[i];
        double v00 = px.applyAsInt(y0 * 512 + x0),     v10 = px.applyAsInt(y0 * 512 + x0 + 1);
        double v01 = px.applyAsInt((y0 + 1) * 512 + x0), v11 = px.applyAsInt((y0 + 1) * 512 + x0 + 1);
        double v = (v00 * (1 - tx) + v10 * tx) * (1 - ty) + (v01 * (1 - tx) + v11 * tx) * ty;
        int packed = (int) Math.round(v);
        return ((packed >> 16 & 255) * 256 * 256 + (packed >> 8 & 255) * 256 + (packed & 255)) / 256.0 - 32768.0;
    }

    // ---- classify(): returns signed coast distance ( >=0 = LAND ) ----
    static long nClassify = 0, nCoastScan = 0, nSigned = 0;
    static double classify(double lat, double lon) {
        nClassify++;
        double elev = elevBilinear(lat, lon);
        if (elev < 5.0) elevBilinear(lat, lon); // Y rule: old book sampled too at Juhu heights
        // verbatim classifyCoastline loop shape (9 tiles, 0.5-deg filter)
        double bestDist = Double.MAX_VALUE, bestSigned = 0; boolean any = false;
        for (int dlat = -1; dlat <= 1; dlat++) for (int dlon = -1; dlon <= 1; dlon++) {
            List<CoastlineSegment> t = tiles.get(tileKey(lat + dlat * TILE_DEG, lon + dlon * TILE_DEG));
            if (t == null) continue;
            for (CoastlineSegment seg : t) {
                nCoastScan++;
                if (!seg.mightBeNear(lat, lon, PROBE_FILTER_DEG)) continue;
                nSigned++;
                double signed = seg.signedDistMeters(lat, lon);
                double dist = Math.abs(signed);
                if (dist < bestDist) { bestDist = dist; bestSigned = signed; }
                any = true;
            }
        }
        return any ? bestSigned : -9999; // -9999 = UNKNOWN in the real code
    }

    // ---- isShoreCellPublic(): LAND column with OCEAN within 12/28 blocks ----
    static final int[][] OFFS = {{12,0},{-12,0},{0,12},{0,-12},{12,12},{12,-12},{-12,12},{-12,-12},
                                 {28,0},{-28,0},{0,28},{0,-28},{28,28},{28,-28},{-28,28},{-28,-28}};
    static boolean isShore(double lat, double lon, double coastLonHere) {
        // NOTE sign: this synthetic coast is drawn northward with the
        // sea to the WEST, so here LAND reads signed<0. (In the real
        // index the sign follows the OSM way direction; the cost of
        // the probes is identical either way.)
        if (classify(lat, lon) > 0) return false;              // self: not LAND
        double mLat = 1.0 / 111320.0, mLon = 1.0 / (111320.0 * Math.cos(Math.toRadians(lat)));
        for (int[] o : OFFS) {
            double s = classify(lat + o[1] * mLat, lon + o[0] * mLon); // blockX east = lon
            if (s > 5.0) return true;                          // OCEAN probe hit
        }
        return false;
    }

    // One quart of biome work = 1 classify + 1 shore test.
    static void oneQuart(double lat, double lon) { classify(lat, lon); isShore(lat, lon, lon); }

    public static void main(String[] a) throws Exception {
        proj = new BTEDymaxionProjection();
        int packed = (int) ((3.0 + 32768.0) * 256.0); // ~+3 m, Terrarium packing
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++)
            TILE[y * 512 + x] = (x < 256 ? ((int)((0.0 + 32768.0) * 256.0)) : packed);
        buildCoastline();
        System.out.printf("coastline segments in 3x3 tiles: %,d (NW27 log count: 1,771)%n", segTotal);

        // quart columns of a beach chunk: 4x4, x east across the shore
        double mLat = 1.0 / 111320.0, mLon = 1.0 / (111320.0 * Math.cos(Math.toRadians(JUHU_LAT)));
        double coastLon = 72.8265 + 0.0035 * Math.sin((JUHU_LAT - 19.0) * 220.0)
                        + 0.0015 * Math.sin((JUHU_LAT - 19.0) * 61.0) + 0.0006 * Math.sin((JUHU_LAT - 19.0) * 900.0);
        double[][] beach = new double[16][2], inland = new double[16][2], ocean = new double[16][2];
        for (int bx = 0; bx < 4; bx++) for (int bz = 0; bz < 4; bz++) {
            int i = bx * 4 + bz;
            beach[i]  = new double[]{JUHU_LAT + (bz * 4 - 8) * mLat, coastLon + (bx * 4 - 8) * mLon};
            inland[i] = new double[]{JUHU_LAT + (bz * 4 - 8) * mLat, coastLon + (60 + bx * 4) * mLon};
            ocean[i]  = new double[]{JUHU_LAT + (bz * 4 - 8) * mLat, coastLon - (60 + bx * 4) * mLon};
        }
        // sanity: land east, sea west
        System.out.printf("sanity: beach quart signed = %.1f m (land side, <0 here), ocean quart = %.1f m%n",
            classify(beach[15][0], beach[15][1]), classify(ocean[0][0], ocean[0][1]));

        // component timing: real projection
        double[] xy = proj.fromGeo(JUHU_LON, JUHU_LAT);
        for (int i = 0; i < 20000; i++) proj.toGeo(xy[0], xy[1]);
        long t0 = System.nanoTime(); double sink = 0;
        for (int i = 0; i < 100000; i++) sink += proj.toGeo(xy[0] + i * 1e-9, xy[1])[0];
        System.out.printf("real projection toGeo: %.2f us/call%n", (System.nanoTime() - t0) / 1e3 / 100000);

        // warm the classify path
        for (int i = 0; i < 3000; i++) oneQuart(beach[i % 16][0], beach[i % 16][1]);

        runCase("A. BEACH chunk (Juhu shore)", beach);
        runCase("B. INLAND chunk 60 m east (worst case)", inland);
        runCase("C. OCEAN chunk 60 m west", ocean);
        if (sink == 12345) System.out.println("x");
    }

    static void runCase(String name, double[][] quarts) {
        // OLD: 254 sections x 16 quarts
        nClassify = 0; nCoastScan = 0; nSigned = 0;
        long t0 = System.nanoTime();
        for (int si = 0; si < 254; si++) for (double[] q : quarts) oneQuart(q[0], q[1]);
        double oldMs = (System.nanoTime() - t0) / 1e6;
        long oc = nClassify, os = nSigned;

        // NEW: 16 quarts once (+ painting, negligible)
        nClassify = 0; nCoastScan = 0; nSigned = 0;
        for (double[] q : quarts) oneQuart(q[0], q[1]); // one counted pass
        long newCount = nClassify;
        long tot = 0;
        for (int r = 0; r < 30; r++) {
            long s = System.nanoTime();
            for (double[] q : quarts) oneQuart(q[0], q[1]);
            tot += System.nanoTime() - s;
        }
        double newMs = tot / 30.0 / 1e6;
        System.out.printf("%n%s%n", name);
        System.out.printf("  OLD: %,d classify calls (%,d signed-distance) -> %.0f ms per chunk%n", oc, os, oldMs);
        System.out.printf("  NEW: %,d classify calls -> %.2f ms per chunk (avg of 30)%n", newCount, newMs);
        System.out.printf("  speed-up: %.0fx | 100 chunks: OLD %.1f min vs NEW %.1f s (1 thread)%n",
            oldMs / Math.max(newMs, 0.01), oldMs * 100 / 60000.0, newMs * 100 / 1000.0);
    }
}
