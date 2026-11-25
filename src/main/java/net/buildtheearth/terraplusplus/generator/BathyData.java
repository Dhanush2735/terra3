package net.buildtheearth.terraplusplus.generator;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// ============================================================
// BathyData — bundled coarse global ocean-depth grid.
//
// Why this exists (2026-11-20, single-elevation-source
// switch): Mapterhorn is the mod's ONLY live height source
// now, but it is a land DEM — its sea is flat 0.0 everywhere.
// Ocean depth used to come from Terrarium's coarse zooms;
// running two live height books side by side caused mixed
// tiles (elevated chunks at teleports, floating river
// water, slow double-downloaded coastlines), so Terrarium
// was removed completely (owner directive). This bundled
// grid is the bathymetry replacement: no second live
// source, no downloads, same answer every restart.
//
// Grid: 720x360 float32 big-endian (gzip), 0.5-degree cells,
// row 0 = 89.75N, col 0 = -179.75. Built from the GMRT
// Global Multi-Resolution Topography synthesis (GEBCO-
// filled global topo, gmrt.org GridServer layer=topo,
// 20 km requests resampled to 0.5 deg, NaN patches
// diffusion-filled) by bathy_build/build_bathy_grid.py.
// Sanity: Mariana Trench -4628 m (narrow trench smoothed
// at this resolution), Mid-Atlantic Ridge -3139 m,
// Juhu offshore -32 m, Everest +5825 m.
//
// Fail-soft: missing resource -> NaN (caller falls back to
// the flat -5 m mapped-sea depth, as before Terrarium).
// ============================================================
public final class BathyData {

    private static final Logger LOGGER =
            LogManager.getLogger("Terra++Bathy");

    public static final int COLS = 720;
    public static final int ROWS = 360;

    private static final String RESOURCE =
            "/net/buildtheearth/terraplusplus/bathy.gz";

    private static final float[] GRID = load();

    private BathyData() {}

    private static float[] load() {
        try (InputStream raw =
                     BathyData.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) {
                LOGGER.warn("bathy.gz missing — ocean depth flat");
                return null;
            }
            try (DataInputStream in = new DataInputStream(
                    new GZIPInputStream(raw))) {
                float[] data = new float[COLS * ROWS];
                for (int i = 0; i < data.length; i++)
                    data[i] = in.readFloat();
                LOGGER.info("BathyData loaded ({} cells)", data.length);
                return data;
            }
        } catch (Exception e) {
            LOGGER.warn("BathyData load failed — ocean depth flat: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Elevation (m, negative = below sea level) from the
     * coarse global grid, bilinear. NaN when unavailable.
     * Intended for ocean depth; on land it is only a 0.5-deg
     * smoothed height (callers use it accordingly).
     */
    public static double sample(double lat, double lon) {
        if (GRID == null) return Double.NaN;
        if (Double.isNaN(lat) || Double.isNaN(lon)) return Double.NaN;
        double la = Math.max(-89.75, Math.min(89.75, lat));
        double lo = lon % 360.0;
        if (lo < -180.0) lo += 360.0;
        if (lo >= 180.0) lo -= 360.0;
        // Cell centres: row r lat = 89.75 - r*0.5,
        // col c lon = -179.75 + c*0.5.
        double fr = (89.75 - la) / 0.5;
        double fc = (lo + 179.75) / 0.5;
        int r0 = (int) Math.floor(fr);
        int c0 = (int) Math.floor(fc);
        double tr = fr - r0, tc = fc - c0;
        r0 = Math.max(0, Math.min(ROWS - 2, r0));
        c0 = Math.max(0, Math.min(COLS - 1, c0));
        int c1 = (c0 + 1) % COLS; // longitude wraps
        double v00 = GRID[r0 * COLS + c0];
        double v01 = GRID[r0 * COLS + c1];
        double v10 = GRID[(r0 + 1) * COLS + c0];
        double v11 = GRID[(r0 + 1) * COLS + c1];
        double top = v00 + (v01 - v00) * tc;
        double bot = v10 + (v11 - v10) * tc;
        return top + (bot - top) * tr;
    }
}
