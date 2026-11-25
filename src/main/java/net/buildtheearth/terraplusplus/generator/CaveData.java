package net.buildtheearth.terraplusplus.generator;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// ============================================================
// CaveData — every publicly-known cave & underground
// structure on Earth, as points (Phase 2).
//
// Bundled binary caves.bin.gz (see cave_map/pack_caves_bin.py
// and cave_map/CAVES_MAP.md): GrottoCenter (UIS/WikiCaves)
// 154k entrances + OpenStreetMap cave_entrance/mineshaft/
// adit/bunker + WHYMAP famous caves, deduplicated.
// Record: lat/lon (microdegrees), depth m, length m,
// kind (0 cave, 1 mine_shaft, 2 mine_adit, 3 bunker),
// flags (bit0 hasDepth, bit1 hasLength, bit2 sensitive,
// bit3 blueprint), name index.
//
// Fail-soft like KarstData: missing/corrupt resource =>
// no real caves anywhere, Phase 1 karst caves unaffected.
// ============================================================
public final class CaveData {

    private static final Logger LOGGER =
            LogManager.getLogger("Terra++Caves");

    private static final String RESOURCE =
            "/net/buildtheearth/terraplusplus/caves.bin.gz";

    public static final int KIND_CAVE = 0;
    public static final int KIND_MINE_SHAFT = 1;
    public static final int KIND_MINE_ADIT = 2;
    public static final int KIND_BUNKER = 3;

    private static int count;
    private static int[] latE6, lonE6, lengthM, nameIdx;
    private static short[] depthM;
    private static byte[] kind, flags;
    private static String[] names;
    /** 0.05-degree cell -> record indices. */
    private static Map<Long, List<Integer>> cells;
    private static volatile boolean loaded;
    private static volatile boolean failed;

    private CaveData() {}

    private static long cellKey(double lat, double lon) {
        int r = (int) Math.floor((lat + 90.0) * 20.0);
        int c = (int) Math.floor((lon + 180.0) * 20.0);
        if (r < 0) r = 0; else if (r > 3599) r = 3599;
        if (c < 0) c = 0; else if (c > 7199) c = 7199;
        return r * 7200L + c;
    }

    public static synchronized boolean ensureLoaded() {
        if (loaded) return true;
        if (failed) return false;
        try (InputStream raw =
                     CaveData.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) {
                LOGGER.warn("caves.bin.gz missing — real caves disabled");
                failed = true;
                return false;
            }
            byte[] d;
            try (GZIPInputStream gz = new GZIPInputStream(raw)) {
                d = gz.readAllBytes();
            }
            if (d.length < 8 || d[0] != 'T' || d[1] != 'C'
                    || d[2] != 'V' || d[3] != '1') {
                LOGGER.warn("caves.bin.gz bad magic — real caves disabled");
                failed = true;
                return false;
            }
            int n = u32(d, 4);
            int recSize = 20;
            long need = 8L + (long) n * recSize + 4L;
            if (n <= 0 || d.length < need) {
                LOGGER.warn("caves.bin.gz truncated — real caves disabled");
                failed = true;
                return false;
            }
            count = n;
            latE6 = new int[n]; lonE6 = new int[n];
            lengthM = new int[n]; nameIdx = new int[n];
            depthM = new short[n];
            kind = new byte[n]; flags = new byte[n];
            cells = new HashMap<>();
            int off = 8;
            for (int i = 0; i < n; i++, off += recSize) {
                latE6[i] = i32(d, off);
                lonE6[i] = i32(d, off + 4);
                depthM[i] = (short) u16(d, off + 8);
                lengthM[i] = u32(d, off + 10);
                kind[i] = d[off + 14];
                flags[i] = d[off + 15];
                nameIdx[i] = u32(d, off + 16);
                cells.computeIfAbsent(
                        cellKey(latE6[i] / 1e6, lonE6[i] / 1e6),
                        k -> new ArrayList<>()).add(i);
            }
            int nameCount = u32(d, off);
            off += 4;
            names = new String[Math.max(0, nameCount)];
            for (int i = 0; i < nameCount; i++) {
                if (off + 2 > d.length) break;
                int len = u16(d, off);
                off += 2;
                if (off + len > d.length) break;
                names[i] = new String(d, off, len,
                        java.nio.charset.StandardCharsets.UTF_8);
                off += len;
            }
            loaded = true;
            LOGGER.info("CaveData loaded: {} points, {} names",
                    count, names.length);
            return true;
        } catch (Exception e) {
            LOGGER.warn("CaveData load failed — real caves disabled: {}",
                    e.getMessage());
            failed = true;
            return false;
        }
    }

    private static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }
    private static int i32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)
                | ((d[o + 2] & 0xFF) << 16) | (d[o + 3] << 24);
    }
    private static int u32(byte[] d, int o) { return i32(d, o); }

    public static int count() { return loaded ? count : 0; }
    public static double lat(int i) { return latE6[i] / 1e6; }
    public static double lon(int i) { return lonE6[i] / 1e6; }
    public static int depthM(int i) { return depthM[i] & 0xFFFF; }
    public static int lengthM(int i) { return lengthM[i]; }
    public static int kind(int i) { return kind[i]; }
    public static boolean hasDepth(int i) { return (flags[i] & 1) != 0; }
    public static boolean hasLength(int i) { return (flags[i] & 2) != 0; }
    public static boolean isSensitive(int i) { return (flags[i] & 4) != 0; }
    public static boolean hasBlueprint(int i) { return (flags[i] & 8) != 0; }
    public static String name(int i) {
        int ni = nameIdx[i];
        if (ni < 0 || names == null || ni >= names.length) return "";
        String s = names[ni];
        return s == null ? "" : s;
    }

    /** Indices of points inside a lat/lon box (no wrap handling;
     *  clamp boxes away from the antimeridian). */
    public static List<Integer> inBox(double latMin, double latMax,
                                      double lonMin, double lonMax) {
        List<Integer> out = new ArrayList<>();
        if (!ensureLoaded()) return out;
        long k0 = cellKey(latMin, lonMin), k1 = cellKey(latMax, lonMax);
        int r0 = (int) (k0 / 7200), r1 = (int) (k1 / 7200);
        int c0 = (int) (k0 % 7200), c1 = (int) (k1 % 7200);
        for (int r = r0; r <= r1; r++)
            for (int c = c0; c <= c1; c++) {
                List<Integer> cell = cells.get(r * 7200L + c);
                if (cell == null) continue;
                for (int i : cell) {
                    double la = lat(i), lo = lon(i);
                    if (la >= latMin && la <= latMax
                            && lo >= lonMin && lo <= lonMax)
                        out.add(i);
                }
            }
        return out;
    }
}
