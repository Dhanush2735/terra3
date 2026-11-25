package net.buildtheearth.terraplusplus.generator;

import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// ============================================================
// KarstData — where are Earth's cave-forming rocks?
//
// Bundled 0.5-degree grid (720x360, one byte per cell:
// karst strength 0..100), derived 2026-11-20 from the
// WHYMAP World Karst Aquifer Map (WOKAM v1, BGR/UNESCO,
// doi:10.25928/b2.21_sfkq-r406), weighted by rock type:
// continuous carbonate 100, discontinuous carbonate 70,
// mixed carbonate+evaporite 70, continuous evaporite 55,
// discontinuous evaporite 40; cell value = coverage-weighted
// mean over 0.125-degree sub-cells. Sanity-checked against
// known karst (Yucatan, Kras, Mammoth Cave, Guilin,
// Nullarbor, Florida, Podolian gypsum) and non-karst
// (Sahara, Amazon, Deccan, Siberia) — see
// CAVES_PHASE1.md and make_karst_grid.py.
//
// Fail-soft: if the resource is missing, every answer is
// "no karst" and the cave layer simply never activates.
// ============================================================
public final class KarstData {

    private static final Logger LOGGER =
            LogManager.getLogger("Terra++Karst");

    public static final int COLS = 720;
    public static final int ROWS = 360;
    /** Score at/above which a cell counts as karst. */
    public static final int KARST_MIN_SCORE = 20;

    private static final String RESOURCE =
            "/net/buildtheearth/terraplusplus/karst.gz";

    private static final byte[] GRID = load();

    private KarstData() {}

    private static byte[] load() {
        try (InputStream raw =
                     KarstData.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) {
                LOGGER.warn("karst.gz missing — cave layer disabled");
                return null;
            }
            try (GZIPInputStream gz = new GZIPInputStream(raw)) {
                byte[] data = gz.readAllBytes();
                if (data.length != COLS * ROWS) {
                    LOGGER.warn("karst.gz size {} != {} — cave layer disabled",
                            data.length, COLS * ROWS);
                    return null;
                }
                LOGGER.info("KarstData loaded ({} cells)", data.length);
                return data;
            }
        } catch (Exception e) {
            LOGGER.warn("KarstData load failed — cave layer disabled: {}",
                    e.getMessage());
            return null;
        }
    }

    /** Karst strength 0..100 at a lat/lon (0 outside data). */
    public static int score(double lat, double lon) {
        if (GRID == null) return 0;
        if (Double.isNaN(lat) || Double.isNaN(lon)) return 0;
        double la = Math.max(-89.999, Math.min(89.999, lat));
        double lo = lon % 360.0;
        if (lo < -180.0) lo += 360.0;
        if (lo >= 180.0) lo -= 360.0;
        int row = (int) ((90.0 - la) / 0.5);
        int col = (int) ((lo + 180.0) / 0.5);
        if (row < 0) row = 0; else if (row >= ROWS) row = ROWS - 1;
        if (col < 0) col = 0; else if (col >= COLS) col = COLS - 1;
        return GRID[row * COLS + col] & 0xFF;
    }

    public static boolean isKarst(double lat, double lon) {
        return score(lat, lon) >= KARST_MIN_SCORE;
    }
}
