package net.buildtheearth.terraplusplus.generator;

import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** Version 1 regional rock profiles. Surface lithology is mapped; layer depths are estimates. */
final class UndergroundGeology {
    static final int UNKNOWN = 0, MAMMOTH = 17;
    private static final int COLS = 720, ROWS = 360;
    private static final byte[] GRID = load();

    enum Rock { STONE, DEEPSLATE, CALCITE, SANDSTONE, TUFF, GRANITE, BASALT }

    record Profile(int source, int upperFloor, int middleFloor,
                   Rock upper, Rock middle, Rock deep) {
        Rock at(int y) {
            return y >= upperFloor ? upper : y >= middleFloor ? middle : deep;
        }
    }

    private static byte[] load() {
        try (InputStream raw = UndergroundGeology.class.getResourceAsStream(
                "/net/buildtheearth/terraplusplus/glim_v1_0p5.bin.gz")) {
            if (raw == null) return null;
            try (GZIPInputStream in = new GZIPInputStream(raw)) {
                byte[] data = in.readNBytes(COLS * ROWS + 1);
                if (data.length != COLS * ROWS) return null;
                for (byte value : data) if (value < 0 || value > 16) return null;
                return data;
            }
        } catch (java.io.IOException ignored) {
            return null;
        }
    }

    static int mappedClass(double lat, double lon) {
        if (GRID == null || !Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90 || lat > 90 || lon < -180 || lon > 180) return UNKNOWN;
        int row = ROWS - 1 - Math.min(ROWS - 1, (int)Math.floor((lat + 90) * 2));
        int col = lon == 180 ? 0 : (int)Math.floor((lon + 180) * 2);
        return GRID[row * COLS + col];
    }

    static Profile profile(double lat, double lon, int x, int z, boolean land) {
        if (!land) return fallback(UNKNOWN);
        double dx = (lon + 86.103437) * 88_600;
        double dz = (lat - 37.186886) * 111_195;
        // Local pilot only: NPS documents layer order, not these Minecraft elevations.
        if (dx*dx + dz*dz <= 1_000.0 * 1_000.0) {
            int tilt = (int)Math.round(4 * Math.sin(x / 420.0) + 3 * Math.sin(z / 510.0));
            return new Profile(MAMMOTH, 250 + tilt, 232 + tilt,
                    Rock.SANDSTONE, Rock.TUFF, Rock.CALCITE);
        }
        int id = mappedClass(lat, lon);
        // Continuous absolute elevations keep layers aligned across chunk edges.
        int roll = (int)Math.round(32 * Math.sin(x / 1800.0) + 25 * Math.sin(z / 2100.0));
        return switch (id) {
            case 3 -> new Profile(id, 45+roll, -155+roll, Rock.SANDSTONE, Rock.TUFF, Rock.DEEPSLATE);
            case 5 -> new Profile(id, 35+roll, -150+roll, Rock.STONE, Rock.TUFF, Rock.DEEPSLATE);
            case 6 -> new Profile(id, 30+roll, -180+roll, Rock.CALCITE, Rock.CALCITE, Rock.DEEPSLATE);
            case 2, 7, 10, 12 -> new Profile(id, 40+roll, -210+roll, Rock.BASALT, Rock.TUFF, Rock.DEEPSLATE);
            case 4, 9, 13 -> new Profile(id, 30+roll, -250+roll, Rock.GRANITE, Rock.GRANITE, Rock.DEEPSLATE);
            case 8 -> new Profile(id, 20+roll, -210+roll, Rock.STONE, Rock.DEEPSLATE, Rock.DEEPSLATE);
            default -> fallback(id);
        };
    }

    private static Profile fallback(int id) {
        return new Profile(id, 1, 1, Rock.STONE, Rock.DEEPSLATE, Rock.DEEPSLATE);
    }
}
