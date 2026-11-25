package net.buildtheearth.terraplusplus.generator;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import javax.imageio.ImageIO;

/**
 * Mapterhorn elevation tiles (owner-approved switch,
 * 2026-11-19): Copernicus GLO-30 globally (planet archive,
 * z0-12) with national LiDAR DEMs swapped in where published
 * (regional archives, up to z17). Tiles are Terrarium-encoded
 * WebP inside PMTiles archives, read via HTTP range requests.
 *
 * This class only PRODUCES a 256x256 Terrarium-encoded tile
 * image for any zoom TerraDensityFunctions consumes
 * (terrain z15, parent z14, guard anchors z10-11 stitched
 * from the planet archive). Since the single-source switch
 * (2026-11-20) this is the mod's ONLY live elevation
 * source — Terrarium was removed completely. Ocean note:
 * Mapterhorn is a land DEM (sea = 0.0); ocean depth comes
 * from the bundled BathyData grid, not from any tile book.
 *
 * WebP decoding relies on the TwelveMonkeys ImageIO plugin
 * (build.gradle, jarJar). If the plugin is missing, decode
 * returns null and the tile honestly fails (heights fall
 * back through parent/bathymetry, never a second source).
 */
public final class MapterhornTiles {

    private MapterhornTiles() {}

    static {
        // TwelveMonkeys normally self-registers via
        // META-INF/services, but inside Forge jar-in-jar
        // nesting that scan can miss providers. Register the
        // WebP reader explicitly; if the plugin jar is absent
        // this is a no-op and tile fetches honestly fail
        // (there is no second source to fall back to).
        try {
            Class<?> spi = Class.forName(
                    "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi");
            javax.imageio.spi.IIORegistry.getDefaultInstance()
                    .registerServiceProvider(
                            (javax.imageio.spi.ImageReaderSpi)
                                    spi.getDeclaredConstructor()
                                            .newInstance());
        } catch (Throwable ignored) {}
    }

    public static final class Result {
        public final BufferedImage image;
        /** "regional" (high-res archive) or "planet" (z12 base). */
        public final String source;
        Result(BufferedImage image, String source) {
            this.image = image; this.source = source;
        }
    }

    private static final String PLANET_URL =
            "https://download.mapterhorn.com/planet.pmtiles";
    private static final int CONNECT_MS = 10_000;
    private static final int READ_MS = 20_000;
    private static final long ABSENT_TTL_MS = 600_000L;

    private static final ConcurrentHashMap<String, PmTilesArchive>
            ARCHIVES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long>
            ABSENT = new ConcurrentHashMap<>();

    private static String regionalUrl(int z, int x, int y) {
        int s = z - 6;
        return "https://download.mapterhorn.com/6-"
                + (x >> s) + "-" + (y >> s) + ".pmtiles";
    }

    private static PmTilesArchive archive(String url) {
        Long absentAt = ABSENT.get(url);
        if (absentAt != null
                && System.currentTimeMillis() - absentAt < ABSENT_TTL_MS)
            return null;
        return ARCHIVES.computeIfAbsent(url, PmTilesArchive::new);
    }

    private static void markAbsent(String url) {
        ABSENT.put(url, System.currentTimeMillis());
    }

    /**
     * Best Mapterhorn tile for (z,x,y) rendered at 256x256, or
     * null when Mapterhorn cannot serve it (no regional archive,
     * missing tile, decode failure, network down). z >= 12.
     */
    public static Result fetchTile256(int z, int x, int y) {
        if (z > 17 || z < 10) return null;
        try {
            // Below the planet archive's native z12 — the
            // spike-guard anchor zooms (terrain zoom-4 = z11)
            // after the single-source switch (2026-11-20;
            // these used to come from Terrarium). Stitch the
            // covering z12 planet tiles and downsample, so
            // the anchor is the SAME book as the terrain.
            if (z < 12) {
                PmTilesArchive planet = archive(PLANET_URL);
                if (planet == null) return null;
                int shift = 12 - z;
                int side = 1 << shift;              // z12 tiles per side
                int canvasSize = 512 * side;
                double[] canvas = new double[canvasSize * canvasSize];
                boolean any = false;
                for (int dy = 0; dy < side; dy++) {
                    for (int dx = 0; dx < side; dx++) {
                        byte[] webp = planet.get(12,
                                (x << shift) + dx, (y << shift) + dy);
                        if (webp == null) continue;
                        double[] el = decode512(webp);
                        if (el == null) continue;
                        any = true;
                        for (int row = 0; row < 512; row++)
                            System.arraycopy(el, row * 512,
                                    canvas, (dy * 512 + row) * canvasSize
                                            + dx * 512, 512);
                    }
                }
                if (!any) {
                    if (!planet.exists()) markAbsent(PLANET_URL);
                    return null;
                }
                double[] out = new double[256 * 256];
                for (int j = 0; j < 256; j++) {
                    double sy = (j + 0.5) * canvasSize / 256.0 - 0.5;
                    int y0 = clamp((int) Math.floor(sy), 0, canvasSize - 1);
                    int y1 = Math.min(canvasSize - 1, y0 + 1);
                    double fy = sy - Math.floor(sy);
                    for (int i = 0; i < 256; i++) {
                        double sx = (i + 0.5) * canvasSize / 256.0 - 0.5;
                        int x0 = clamp((int) Math.floor(sx), 0, canvasSize - 1);
                        int x1 = Math.min(canvasSize - 1, x0 + 1);
                        double fx = sx - Math.floor(sx);
                        double v00 = canvas[y0 * canvasSize + x0];
                        double v10 = canvas[y0 * canvasSize + x1];
                        double v01 = canvas[y1 * canvasSize + x0];
                        double v11 = canvas[y1 * canvasSize + x1];
                        out[j * 256 + i] =
                                (v00 * (1 - fx) + v10 * fx) * (1 - fy)
                                + (v01 * (1 - fx) + v11 * fx) * fy;
                    }
                }
                return new Result(encode256(out), "planet-derived z" + z);
            }
            // 1) Regional high-resolution archive (z13+).
            if (z > 12) {
                String url = regionalUrl(z, x, y);
                PmTilesArchive a = archive(url);
                if (a != null) {
                    byte[] webp = a.get(z, x, y);
                    if (webp == null && !a.exists()) {
                        markAbsent(url);
                    } else if (webp != null) {
                        double[] el = decode512(webp);
                        if (el != null)
                            return new Result(
                                    encode256(downsample2x(el)),
                                    "regional z" + z);
                    }
                }
            }
            // 2) Planet archive (Copernicus GLO-30, z12).
            PmTilesArchive planet = archive(PLANET_URL);
            if (planet == null) return null;
            if (z == 12) {
                byte[] webp = planet.get(12, x, y);
                if (webp == null) {
                    if (!planet.exists()) markAbsent(PLANET_URL);
                    return null;
                }
                double[] el = decode512(webp);
                if (el == null) return null;
                return new Result(encode256(downsample2x(el)),
                        "planet z12");
            }
            int shift = z - 12;
            int px = x >> shift, py = y >> shift;
            byte[] webp = planet.get(12, px, py);
            if (webp == null) {
                if (!planet.exists()) markAbsent(PLANET_URL);
                return null;
            }
            double[] parent = decode512(webp);
            if (parent == null) return null;
            int crop = 512 >> shift;
            int ox = (x & ((1 << shift) - 1)) * crop;
            int oy = (y & ((1 << shift) - 1)) * crop;
            double[] out = new double[256 * 256];
            for (int j = 0; j < 256; j++) {
                double sy = oy + (j + 0.5) * crop / 256.0 - 0.5;
                int y0 = clamp((int) Math.floor(sy), 0, 511);
                int y1 = Math.min(511, y0 + 1);
                double fy = sy - Math.floor(sy);
                for (int i = 0; i < 256; i++) {
                    double sx = ox + (i + 0.5) * crop / 256.0 - 0.5;
                    int x0 = clamp((int) Math.floor(sx), 0, 511);
                    int x1 = Math.min(511, x0 + 1);
                    double fx = sx - Math.floor(sx);
                    double v00 = parent[y0 * 512 + x0];
                    double v10 = parent[y0 * 512 + x1];
                    double v01 = parent[y1 * 512 + x0];
                    double v11 = parent[y1 * 512 + x1];
                    out[j * 256 + i] =
                            (v00 * (1 - fx) + v10 * fx) * (1 - fy)
                            + (v01 * (1 - fx) + v11 * fx) * fy;
                }
            }
            return new Result(encode256(out), "planet-derived z" + z);
        } catch (Exception e) {
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Decode a 512x512 Terrarium-encoded WebP to elevations. */
    private static double[] decode512(byte[] webp) {
        try {
            BufferedImage img;
            try (InputStream is = new ByteArrayInputStream(webp)) {
                img = ImageIO.read(is);
            }
            if (img == null || img.getWidth() != 512
                    || img.getHeight() != 512)
                return null;
            double[] el = new double[512 * 512];
            for (int yy = 0; yy < 512; yy++)
                for (int xx = 0; xx < 512; xx++) {
                    int rgb = img.getRGB(xx, yy);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    el[yy * 512 + xx] =
                            r * 256.0 + g + b / 256.0 - 32768.0;
                }
            return el;
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] downsample2x(double[] el) {
        double[] out = new double[256 * 256];
        for (int j = 0; j < 256; j++)
            for (int i = 0; i < 256; i++) {
                double sum = 0; int n = 0;
                for (int dj = 0; dj < 2; dj++)
                    for (int di = 0; di < 2; di++) {
                        double v = el[(2 * j + dj) * 512 + 2 * i + di];
                        if (v > -32768.0) { sum += v; n++; }
                    }
                out[j * 256 + i] = n == 0 ? -32768.0 : sum / n;
            }
        return out;
    }

    private static BufferedImage encode256(double[] el) {
        BufferedImage img = new BufferedImage(256, 256,
                BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < 256; j++)
            for (int i = 0; i < 256; i++) {
                double v = el[j * 256 + i] + 32768.0;
                if (v < 0) v = 0;
                if (v > 65535.996) v = 65535.996;
                int r = (int) (v / 256.0);
                double rem = v - r * 256.0;
                int g = (int) rem;
                int b = (int) Math.round((rem - g) * 256.0);
                if (b > 255) { b = 255; }
                img.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        return img;
    }

    // ── PMTiles (v3) minimal range-request client ────────────

    private static byte[] rangeGet(String url, long offset, long length)
            throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setRequestProperty("User-Agent", "TerraPlusPlus/2.0");
            conn.setRequestProperty("Range",
                    "bytes=" + offset + "-" + (offset + length - 1));
            int sc = conn.getResponseCode();
            if (sc == 404) return null;
            if (sc != 206 && sc != 200)
                throw new java.io.IOException("HTTP " + sc);
            if (sc == 200 && offset > 0)
                return null; // server ignored Range; refuse whole-archive download
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                long remaining = length;
                int rd;
                while (remaining > 0
                        && (rd = is.read(buf, 0,
                                (int) Math.min(buf.length, remaining))) != -1) {
                    bos.write(buf, 0, rd);
                    remaining -= rd;
                }
                byte[] out = bos.toByteArray();
                return out.length == 0 ? null : out;
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final class Dir {
        long[] ids; int[] run; long[] off; long[] len;
    }

    private static long readVarint(byte[] b, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            int cur = b[pos[0]++] & 0xFF;
            result |= (long) (cur & 0x7F) << shift;
            if ((cur & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private static Dir parseDirectory(byte[] raw, int internalCompression)
            throws Exception {
        byte[] data = raw;
        if (internalCompression == 2) {
            try (GZIPInputStream gz = new GZIPInputStream(
                    new ByteArrayInputStream(raw))) {
                data = gz.readAllBytes();
            }
        }
        int[] pos = {0};
        int n = (int) readVarint(data, pos);
        Dir d = new Dir();
        d.ids = new long[n]; d.run = new int[n];
        d.off = new long[n]; d.len = new long[n];
        long acc = 0;
        for (int i = 0; i < n; i++) {
            acc += readVarint(data, pos);
            d.ids[i] = acc;
        }
        for (int i = 0; i < n; i++)
            d.run[i] = (int) readVarint(data, pos);
        for (int i = 0; i < n; i++)
            d.len[i] = readVarint(data, pos);
        for (int i = 0; i < n; i++) {
            long v = readVarint(data, pos);
            if (v == 0 && i > 0)
                d.off[i] = d.off[i - 1] + d.len[i - 1];
            else
                d.off[i] = v - 1;
        }
        return d;
    }

    /** PMTiles tile id: zoom-ordered Hilbert curve index. */
    static long zxyToTileId(int z, int x, int y) {
        long acc = 0;
        for (int i = 0; i < z; i++) acc += 1L << (2 * i);
        int n = 1 << z;
        long d = 0;
        int xx = x, yy = y;
        for (int s = n >> 1; s > 0; s >>= 1) {
            int rx = (xx & s) != 0 ? 1 : 0;
            int ry = (yy & s) != 0 ? 1 : 0;
            d += (long) s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) {
                    xx = s - 1 - (xx & (s - 1));
                    yy = s - 1 - (yy & (s - 1));
                }
                int t = xx; xx = yy; yy = t;
            }
        }
        return acc + d;
    }

    private static final class PmTilesArchive {
        private final String url;
        private boolean headerLoaded;
        private boolean headerOk;
        private long tilesOffset, leafOffset;
        private int internalCompression, tileCompression;
        private Dir root;

        PmTilesArchive(String url) { this.url = url; }

        synchronized boolean exists() {
            ensureHeader();
            return headerOk;
        }

        private void ensureHeader() {
            if (headerLoaded) return;
            headerLoaded = true;
            try {
                byte[] h = rangeGet(url, 0, 127);
                if (h == null || h.length < 127) return;
                String magic = new String(h, 0, 7,
                        java.nio.charset.StandardCharsets.US_ASCII);
                if (!"PMTiles".equals(magic) || h[7] != 3) return;
                ByteBuffer bb = ByteBuffer.wrap(h)
                        .order(ByteOrder.LITTLE_ENDIAN);
                long rootOff = bb.getLong(8);
                long rootLen = bb.getLong(16);
                leafOffset = bb.getLong(40);
                tilesOffset = bb.getLong(56);
                internalCompression = h[97] & 0xFF;
                tileCompression = h[98] & 0xFF;
                byte[] rootRaw = rangeGet(url, rootOff, rootLen);
                if (rootRaw == null) return;
                root = parseDirectory(rootRaw, internalCompression);
                headerOk = true;
            } catch (Exception e) {
                headerOk = false;
            }
        }

        /** Tile bytes (WebP) or null when absent/unreachable. */
        synchronized byte[] get(int z, int x, int y) {
            ensureHeader();
            if (!headerOk || root == null) return null;
            try {
                long id = zxyToTileId(z, x, y);
                Dir dir = root;
                for (int depth = 0; depth < 3; depth++) {
                    int idx = findEntry(dir, id);
                    if (idx < 0) return null;
                    if (dir.run[idx] == 0) {
                        // leaf directory
                        byte[] leafRaw = rangeGet(url,
                                leafOffset + dir.off[idx], dir.len[idx]);
                        if (leafRaw == null) return null;
                        dir = parseDirectory(leafRaw,
                                internalCompression);
                        continue;
                    }
                    if (id >= dir.ids[idx] + dir.run[idx]) return null;
                    byte[] tile = rangeGet(url,
                            tilesOffset + dir.off[idx], dir.len[idx]);
                    if (tile == null) return null;
                    if (tileCompression == 2) {
                        try (GZIPInputStream gz = new GZIPInputStream(
                                new ByteArrayInputStream(tile))) {
                            tile = gz.readAllBytes();
                        }
                    }
                    return tile;
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        private int findEntry(Dir dir, long id) {
            int lo = 0, hi = dir.ids.length - 1, best = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (dir.ids[mid] <= id) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return best;
        }
    }
}
