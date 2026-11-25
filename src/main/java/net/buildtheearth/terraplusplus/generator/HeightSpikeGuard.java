package net.buildtheearth.terraplusplus.generator;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Height spike guard (Step 3 of the heightmap plan,
 * HEIGHTMAP_STEP2.md / HEIGHTMAP_STEP3.md).
 *
 * Source tiles are never modified on disk: this class
 * only cleans the decoded elevation array in memory, at
 * tile-load time. (Written for Terrarium tiles; since the
 * 2026-11-20 single-source switch it guards Mapterhorn
 * tiles, whose archive is far cleaner — the guard stays
 * as cheap insurance and can still be switched off.)
 * Two stages:
 *
 *  1. Neighbour-median repair (the long-standing tile
 *     repair, moved here unchanged): a sample that is
 *     globally impossible, or that deviates from its 5x5
 *     neighbourhood median by >500 m while that
 *     neighbourhood is stable (spread <=120 m), is replaced
 *     by the median. Up to 8 passes.
 *
 *  2. Zoom-anchor (new): stage 1 cannot see locally
 *     consistent garbage - smooth void-fill blobs and
 *     full-width seam rows - because the neighbours agree
 *     with the lie. A fine pixel is therefore compared
 *     with the coarse (zoom-4) tile the game already
 *     fetches for bathymetry. The anchor may only fire
 *     where the coarse neighbourhood is flat (3x3 range
 *     <25 m). A monster PIT (fine < coarse-150) is replaced
 *     by the coarse value; a monster SPIKE
 *     (fine > coarse+150) additionally requires the two
 *     books to agree on the area level (16x16 fine block
 *     median within 15 m of coarse). The agreement gate is
 *     what protects Manhattan, where the old Terrarium
 *     coarse zoom carried rooftops (workshop v3 regression,
 *     HEIGHTMAP_STEP2.md). Since the single-source switch
 *     the coarse anchor is the same Mapterhorn book
 *     (z11 stitched from the planet archive), so the two
 *     levels can no longer disagree by source.
 *
 * Workshop proof on real tiles (offline, same maths):
 * Dead Sea - exactly the 21 crazy pixels repaired, Caspian
 * Sea 1,165-px seam row removed, Grand Canyon / Alps /
 * Everest / Manhattan / Amsterdam / Flevoland / Tokyo
 * byte-identical. The whole guard can be switched off
 * (/terra heights guard off) - tiles then behave exactly
 * as downloaded.
 */
public final class HeightSpikeGuard {

    private HeightSpikeGuard() {}

    /** Master switch: /terra heights guard on|off. */
    public static volatile boolean enabled = true;

    /** Session counters for /terracheck. */
    public static final AtomicLong TOTAL_REPAIRED  = new AtomicLong();
    public static final AtomicLong ANCHOR_REPAIRED = new AtomicLong();
    public static final AtomicLong TILES_GUARDED   = new AtomicLong();

    // Stage 1 bounds - mirror of the historical tile repair.
    private static final double MIN_EARTH_ELEVATION = -11_000.0;
    private static final double MAX_EARTH_ELEVATION = 9_500.0;
    private static final double OUTLIER_DELTA_METERS = 500.0;
    private static final double STABLE_NEIGHBOUR_SPREAD_METERS = 120.0;
    private static final int REPAIR_RADIUS = 2;
    private static final int REPAIR_PASSES = 8;

    // Stage 2 gates - workshop v3c operating point.
    private static final double ANCHOR_FLAT_RANGE_METERS = 25.0;
    private static final double ANCHOR_MONSTER_DELTA_METERS = 150.0;
    private static final double ANCHOR_AGREE_METERS = 15.0;
    private static final int ANCHOR_BLOCK = 16;   // fine px per block side

    public static final class Outcome {
        public int impossible;
        public int medianRepaired;
        public int anchorRepaired;
        public int total() { return medianRepaired + anchorRepaired; }
    }

    public static boolean isPlausible(double elevation) {
        return Double.isFinite(elevation)
                && elevation >= MIN_EARTH_ELEVATION
                && elevation <= MAX_EARTH_ELEVATION;
    }

    /**
     * Repairs {@code elev} (row-major, size*size) in place.
     * {@code coarse} is the zoom-4 tile (also size*size) that
     * fully contains the fine tile, or null to skip stage 2.
     * {@code coarseOriginX/Y} is the fine tile's top-left
     * corner inside the coarse tile, in coarse pixels
     * (multiples of 16 for 256-px tiles).
     */
    public static Outcome repair(double[] elev, int size,
                                 double[] coarse,
                                 int coarseOriginX, int coarseOriginY) {
        Outcome o = new Outcome();
        for (double v : elev) if (!isPlausible(v)) o.impossible++;

        // ── Stage 1: iterated neighbour-median ────────────
        for (int pass = 0; pass < REPAIR_PASSES; pass++) {
            int passRepairs = 0;
            double[] next = elev.clone();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int i = y * size + x;
                    double value = elev[i];
                    double[] neighbours = new double[
                            (REPAIR_RADIUS * 2 + 1) * (REPAIR_RADIUS * 2 + 1) - 1];
                    int count = 0;
                    for (int dy = -REPAIR_RADIUS; dy <= REPAIR_RADIUS; dy++)
                        for (int dx = -REPAIR_RADIUS; dx <= REPAIR_RADIUS; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int ny = y + dy, nx = x + dx;
                            if (ny < 0 || ny >= size || nx < 0 || nx >= size)
                                continue;
                            double nv = elev[ny * size + nx];
                            if (isPlausible(nv)) neighbours[count++] = nv;
                        }
                    if (count < 8) continue;
                    java.util.Arrays.sort(neighbours, 0, count);
                    double median = neighbours[count / 2];
                    double spread = neighbours[(count * 3) / 4]
                            - neighbours[count / 4];
                    if (!isPlausible(value) || (spread <= STABLE_NEIGHBOUR_SPREAD_METERS
                            && Math.abs(value - median) > OUTLIER_DELTA_METERS)) {
                        next[i] = median;
                        passRepairs++;
                    }
                }
            }
            System.arraycopy(next, 0, elev, 0, next.length);
            o.medianRepaired += passRepairs;
            if (passRepairs == 0) break;
        }

        // ── Stage 2: zoom-anchor ──────────────────────────
        if (coarse != null && coarse.length == size * size) {
            // 16x16 fine-block medians (area-agreement gate)
            int blocksPerSide = Math.max(1, size / ANCHOR_BLOCK);
            double[] blockMedian = new double[blocksPerSide * blocksPerSide];
            for (int by = 0; by < blocksPerSide; by++)
                for (int bx = 0; bx < blocksPerSide; bx++) {
                    double[] vals = new double[ANCHOR_BLOCK * ANCHOR_BLOCK];
                    int c = 0;
                    for (int y = 0; y < ANCHOR_BLOCK; y++)
                        for (int x = 0; x < ANCHOR_BLOCK; x++)
                            vals[c++] = elev[(by * ANCHOR_BLOCK + y) * size
                                    + bx * ANCHOR_BLOCK + x];
                    java.util.Arrays.sort(vals);
                    blockMedian[by * blocksPerSide + bx] = vals[vals.length / 2];
                }

            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int i = y * size + x;
                    // fine pixel centre in coarse pixel coords
                    double u = coarseOriginX + (x + 0.5) / 16.0 - 0.5;
                    double v = coarseOriginY + (y + 0.5) / 16.0 - 0.5;
                    double c = bilinear(coarse, size, u, v);
                    // coarse neighbourhood must be flat
                    int cx = clamp((int) Math.round(u), 0, size - 1);
                    int cy = clamp((int) Math.round(v), 0, size - 1);
                    double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++) {
                            double cv = coarse[clamp(cy + dy, 0, size - 1) * size
                                    + clamp(cx + dx, 0, size - 1)];
                            if (cv < lo) lo = cv;
                            if (cv > hi) hi = cv;
                        }
                    if (hi - lo >= ANCHOR_FLAT_RANGE_METERS) continue;
                    double f = elev[i];
                    if (f < c - ANCHOR_MONSTER_DELTA_METERS) {
                        elev[i] = c;
                        o.anchorRepaired++;
                    } else if (f > c + ANCHOR_MONSTER_DELTA_METERS) {
                        int bx = Math.min(blocksPerSide - 1, x / ANCHOR_BLOCK);
                        int by = Math.min(blocksPerSide - 1, y / ANCHOR_BLOCK);
                        if (Math.abs(blockMedian[by * blocksPerSide + bx] - c)
                                < ANCHOR_AGREE_METERS) {
                            elev[i] = c;
                            o.anchorRepaired++;
                        }
                    }
                }
            }
        }

        if (o.total() > 0) {
            TOTAL_REPAIRED.addAndGet(o.total());
            ANCHOR_REPAIRED.addAndGet(o.anchorRepaired);
        }
        TILES_GUARDED.incrementAndGet();
        return o;
    }

    private static double bilinear(double[] a, int size, double u, double v) {
        u = Math.max(0.0, Math.min(size - 1.0, u));
        v = Math.max(0.0, Math.min(size - 1.0, v));
        int x0 = (int) u, y0 = (int) v;
        int x1 = Math.min(size - 1, x0 + 1), y1 = Math.min(size - 1, y0 + 1);
        double fx = u - x0, fy = v - y0;
        double top = a[y0 * size + x0] + (a[y0 * size + x1] - a[y0 * size + x0]) * fx;
        double bot = a[y1 * size + x0] + (a[y1 * size + x1] - a[y1 * size + x0]) * fx;
        return top + (bot - top) * fy;
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
