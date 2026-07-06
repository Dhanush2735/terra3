// ============================================================
// FILE: RiverLakeEngine.java
// MODIFIED: Queries OHM first for year-filtered water data.
//           Falls back to OSM for natural features not in OHM
//           or when OHM tile not yet loaded.
//           Coastline from OHM (same data, no date filter).
// ============================================================
package net.buildtheearth.terraplusplus.generator.water;

import net.buildtheearth.terraplusplus.generator.TerraDensityFunctions;
import net.buildtheearth.terraplusplus.generator.historical.TerraTimeManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RiverLakeEngine {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Water");

    private final TerraDensityFunctions tdf;
    private final OsmWaterIndex osmWater;
    private final OhmWaterIndex ohmWater;

    private final ConcurrentHashMap<Long, WaterBodyInfo>
        resultCache = new ConcurrentHashMap<>(4096);

    // Year-keyed cache invalidation
    // Key = block key, Value = year when cached
    private final ConcurrentHashMap<Long, Integer>
        cacheYears = new ConcurrentHashMap<>(4096);

    private static final int CACHE_MAX = 16384;

    private final AtomicInteger totalQueries =
        new AtomicInteger();
    private final AtomicInteger ohmHits =
        new AtomicInteger();
    private final AtomicInteger osmHits =
        new AtomicInteger();
    private final AtomicInteger cacheHits =
        new AtomicInteger();
    private final AtomicInteger dbgCount =
        new AtomicInteger();

    public RiverLakeEngine(TerraDensityFunctions tdf) {
        this.tdf      = tdf;
        this.osmWater = new OsmWaterIndex();
        this.ohmWater = new OhmWaterIndex();

        tdf.setOsmWaterIndex(osmWater);
        tdf.setWaterBodyQuerySource(
            (lat, lon, elev) ->
                queryNonBlocking(lat, lon, elev));

        LOGGER.info(
            "RiverLakeEngine initialized "
            + "(OHM primary + OSM fallback)");
    }

    public OsmWaterIndex getOsmWaterIndex() {
        return osmWater;
    }

    public OhmWaterIndex getOhmWaterIndex() {
        return ohmWater;
    }

    public void waitForCenterTile(
            double lat, double lon,
            long maxWaitMs) {
        try {
            // Wait for both OHM and OSM tiles
            ohmWater.getTileBlocking(
                lat, lon, maxWaitMs / 2);
            osmWater.getTileBlocking(
                lat, lon, maxWaitMs / 2);
        } catch (Exception e) {
            LOGGER.debug(
                "tile wait failed: {}",
                e.getMessage());
        }
    }

    public void prefetch(
            double lat, double lon) {
        try {
            ohmWater.prefetch(lat, lon);
            osmWater.prefetch(lat, lon);
        } catch (Exception e) {
            LOGGER.debug(
                "Prefetch failed: {}",
                e.getMessage());
        }
    }

    // ── MAIN QUERY ────────────────────────────────────────────

    public WaterBodyInfo analyze(
            int blockX, int blockZ,
            double lat, double lon,
            double elevMeters) {

        totalQueries.incrementAndGet();

        long key = cacheKey(blockX, blockZ);
        int currentYear =
            TerraTimeManager.getCurrentYear();

        // Check cache — invalidate if year changed
        WaterBodyInfo cached =
            resultCache.get(key);
        if (cached != null) {
            Integer cachedYear =
                cacheYears.get(key);
            if (cachedYear != null
                    && cachedYear == currentYear) {
                cacheHits.incrementAndGet();
                return cached;
            }
            // Year changed — remove stale entry
            resultCache.remove(key);
            cacheYears.remove(key);
        }

        // ── OHM QUERY (primary) ───────────────────
        // OHM has start_date/end_date — year filtered
        long ohmTileKey =
            ohmWater.getTileKeyPublic(lat, lon);
        boolean ohmTileLoaded =
            ohmWater.isTileCached(ohmTileKey);

        if (ohmTileLoaded) {
            try {
                WaterBodyInfo ohmResult =
                    ohmWater.query(
                        lat, lon, elevMeters);

                if (ohmResult != null
                        && (ohmResult.isWater
                            || ohmResult.isBank())) {
                    ohmHits.incrementAndGet();
                    WaterBodyInfo result =
                        withSurfaceY(
                            ohmResult, elevMeters);

                    if (dbgCount.get() < 20) {
                        dbgCount.incrementAndGet();
                        LOGGER.info(
                            "OHM WATER [{},{}] "
                            + "{} w={}m d={}m "
                            + "edge={} surfY={} "
                            + "year={} {}",
                            blockX, blockZ,
                            result.type,
                            result.widthMeters,
                            String.format(
                                "%.1f", result.depth),
                            String.format(
                                "%.2f",
                                result.edgeFactor),
                            result.waterSurfaceY,
                            currentYear,
                            result.name != null
                                ? "\""
                                    + result.name
                                    + "\""
                                : "");
                    }

                    cacheResult(
                        key, result, currentYear);
                    return result;
                }

                // OHM tile loaded but no match —
                // do NOT fall back to OSM for
                // man-made features (canals,
                // reservoirs) as they may not
                // exist yet at currentYear.
                // Only fall back for natural
                // large rivers/streams.
                if (ohmResult != null) {
                    // OHM returned NONE — check if
                    // OSM has a natural water body
                    // (river/stream) that predates
                    // human modification
                    WaterBodyInfo osmFallback =
                        tryOsmNaturalOnly(
                            lat, lon, elevMeters,
                            currentYear);
                    if (osmFallback != null
                            && (osmFallback.isWater
                                || osmFallback
                                    .isBank())) {
                        osmHits.incrementAndGet();
                        WaterBodyInfo result =
                            withSurfaceY(
                                osmFallback,
                                elevMeters);
                        cacheResult(
                            key, result,
                            currentYear);
                        return result;
                    }

                    cacheResult(
                        key, WaterBodyInfo.NONE,
                        currentYear);
                    return WaterBodyInfo.NONE;
                }

            } catch (Exception e) {
                LOGGER.debug(
                    "OHM query failed "
                    + "[{},{}]: {}",
                    blockX, blockZ,
                    e.getMessage());
            }
        }

        // ── OHM TILE NOT LOADED ───────────────────
        // Do not cache — return NONE so next call
        // can retry when tile arrives
        long osmTileKey =
            osmWater.getTileKeyPublic(lat, lon);
        if (!osmWater.isTileCached(osmTileKey))
            return WaterBodyInfo.NONE;

        // ── OSM FALLBACK ─────────────────────────
        // Used when OHM tile not yet available.
        // Apply year-based filtering to prevent
        // future water bodies appearing too early.
        try {
            WaterBodyInfo osmResult =
                osmWater.query(
                    lat, lon, elevMeters);

            if (osmResult != null
                    && (osmResult.isWater
                        || osmResult.isBank())) {

                // Year-based filtering for OSM:
                // suppress man-made features before
                // they could plausibly exist
                if (!isPlausibleAtYear(
                        osmResult, currentYear)) {
                    cacheResult(
                        key, WaterBodyInfo.NONE,
                        currentYear);
                    return WaterBodyInfo.NONE;
                }

                osmHits.incrementAndGet();
                WaterBodyInfo result =
                    withSurfaceY(
                        osmResult, elevMeters);
                cacheResult(
                    key, result, currentYear);
                return result;
            }
        } catch (Exception e) {
            LOGGER.debug(
                "OSM fallback failed "
                + "[{},{}]: {}",
                blockX, blockZ,
                e.getMessage());
        }

        cacheResult(
            key, WaterBodyInfo.NONE,
            currentYear);
        return WaterBodyInfo.NONE;
    }

    // ── OSM NATURAL-ONLY FALLBACK ─────────────────────────────
    // Only returns rivers/streams — not canals,
    // reservoirs, or ponds (those are man-made
    // and should come from OHM with dates)
    private WaterBodyInfo tryOsmNaturalOnly(
            double lat, double lon,
            double elevMeters,
            int currentYear) {
        if (!osmWater.isEnabled()) return null;
        long osmKey =
            osmWater.getTileKeyPublic(lat, lon);
        if (!osmWater.isTileCached(osmKey))
            return null;

        try {
            WaterBodyInfo r =
                osmWater.query(lat, lon, elevMeters);
            if (r == null
                    || (!r.isWater && !r.isBank()))
                return null;

            // Only allow rivers and streams
            // as natural fallback
            if (r.type != WaterBodyInfo
                        .WaterType.RIVER
                    && r.type != WaterBodyInfo
                        .WaterType.STREAM)
                return null;

            // Rivers existed before recorded history
            // so no year cutoff for them
            return r;

        } catch (Exception e) {
            return null;
        }
    }

    // ── YEAR PLAUSIBILITY (OSM FALLBACK) ─────────────────────
    // Applied when OHM tile not loaded.
    // Prevents obviously anachronistic water bodies.
    private static boolean isPlausibleAtYear(
            WaterBodyInfo wb, int year) {
        return switch (wb.type) {
            // Rivers and streams are natural —
            // always plausible
            case RIVER, STREAM ->
                true;

            // Lakes are natural —
            // always plausible
            case LAKE ->
                true;

            // Wetlands are natural —
            // always plausible
            case WETLAND ->
                true;

            // Canals — earliest large canals
            // ~4000 BCE (Mesopotamia)
            case CANAL ->
                year >= -4000;

            // Reservoirs — earliest dams
            // ~3000 BCE
            case RESERVOIR ->
                year >= -3000;

            // Ponds — man-made ponds
            // ~1000 BCE onwards
            case POND ->
                year >= -1000;

            case COASTLINE, NONE ->
                true;
        };
    }

    // ── NON-BLOCKING QUERY ────────────────────────────────────
    // Used by TerraDensityFunctions callback
    private WaterBodyInfo queryNonBlocking(
            double lat, double lon, double elev) {
        // Try OHM first (mem-only)
        if (ohmWater.isEnabled()) {
            long ohmKey =
                ohmWater.getTileKeyPublic(lat, lon);
            if (ohmWater.isTileCached(ohmKey)) {
                try {
                    WaterBodyInfo r =
                        ohmWater.query(lat, lon, elev);
                    if (r != null
                            && r != WaterBodyInfo.NONE
                            && (r.isWater
                                || r.isBank()))
                        return withSurfaceY(r, elev);
                } catch (Exception e) {
                    LOGGER.debug(
                        "OHM mem query failed: {}",
                        e.getMessage());
                }
            }
        }

        // Fall back to OSM mem-only
        try {
            WaterBodyInfo r =
                osmWater.queryMemOnly(
                    lat, lon, elev);
            if (r != null
                    && r != WaterBodyInfo.NONE
                    && (r.isWater || r.isBank())) {
                int year =
                    TerraTimeManager.getCurrentYear();
                if (!isPlausibleAtYear(r, year))
                    return WaterBodyInfo.NONE;
                return withSurfaceY(r, elev);
            }
        } catch (Exception e) {
            LOGGER.debug(
                "OSM mem query failed: {}",
                e.getMessage());
        }

        return WaterBodyInfo.NONE;
    }

    // ── HELPERS ───────────────────────────────────────────────
    private static WaterBodyInfo withSurfaceY(
            WaterBodyInfo wb, double elev) {
        int sy =
            TerraDensityFunctions
            .elevMetersToSurfaceY(elev, true);
        if (wb.waterSurfaceY == sy) return wb;
        return new WaterBodyInfo(
            wb.type, wb.source, wb.isWater,
            wb.surfaceElevation, wb.depth,
            wb.widthMeters, wb.edgeFactor,
            wb.flowDirection, wb.bankWidth,
            wb.name, sy);
    }

    private void cacheResult(
            long key, WaterBodyInfo result,
            int year) {
        if (resultCache.size() > CACHE_MAX) {
            resultCache.clear();
            cacheYears.clear();
        }
        resultCache.put(key, result);
        cacheYears.put(key, year);
    }

    private static long cacheKey(
            int blockX, int blockZ) {
        return ((long) blockX << 32)
            | (blockZ & 0xFFFFFFFFL);
    }

    // ── STATS ─────────────────────────────────────────────────
    public int getCacheSize() {
        return resultCache.size();
    }
    public int getOsmCacheSize() {
        return osmWater.getMemCacheSize();
    }
    public int getOhmCacheSize() {
        return ohmWater.getMemCacheSize();
    }
    public int getCoastlineCacheSize() {
        return ohmWater.getMemCacheSize()
            + osmWater.getMemCacheSize();
    }
    public int getOsmTotalQueries() {
        return osmWater.getTotalQueries();
    }
    public int getOhmTotalQueries() {
        return ohmWater.getTotalQueries();
    }
    public boolean isOsmEnabled() {
        return osmWater.isEnabled();
    }
    public boolean isOhmEnabled() {
        return ohmWater.isEnabled();
    }
    public boolean isCoastlineEnabled() {
        return ohmWater.isEnabled()
            || osmWater.isEnabled();
    }
    public int getTotalQueries() {
        return totalQueries.get();
    }
    public int getOsmHits() {
        return osmHits.get();
    }
    public int getOhmHits() {
        return ohmHits.get();
    }
    public int getElevHits() {
        return 0;
    }
    public int getCacheHits() {
        return cacheHits.get();
    }

    public void shutdown() {
        osmWater.shutdown();
        ohmWater.shutdown();
        resultCache.clear();
        cacheYears.clear();
        LOGGER.info(
            "RiverLakeEngine shutdown "
            + "(queries={} ohm={} osm={} "
            + "cache={})",
            totalQueries.get(),
            ohmHits.get(),
            osmHits.get(),
            cacheHits.get());
    }
}