// ============================================================
// FILE: TerraDensityFunctions.java
// FIX: COASTLINE terrain type eliminated from classify flow.
//      Water body queries removed from classify() entirely.
//      classify() is now non-blocking and fast.
//      buildWaterColumn() replaces classifyWaterBody() for use
//      by TerraChunkGenerator after water body analysis.
//      classifyAsLandFromCoastline no longer queries water bodies.
//      getSubSurfaceBlock and getDeepSubBlock handle bt=9 (beach).
//      getLandSurfaceBlock sand-at-low-elevation removed.
//      elevMetersToSurfaceY snap zone widened to +/-1.0m.
// ============================================================
package net.buildtheearth.terraplusplus.generator;

import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorSettings;
import net.buildtheearth.terraplusplus.generator.water.OsmWaterIndex;
import net.buildtheearth.terraplusplus.config.TerraConfig;
import net.buildtheearth.terraplusplus.generator.water.WaterBodyInfo;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TerraDensityFunctions {

    private static final Logger LOGGER = LogManager.getLogger("Terra++Density");

    public static final int MIN_Y = -2032, MAX_Y = 2032, SEA_LEVEL = 63,
            GEN_DEPTH = MAX_Y - MIN_Y;
    public static final double DRY_LAND_MIN_ELEV = -500.0;
    private static final double BELOW_SEA_Y_SCALE = 0.1;
    private static final int TILE_SIZE = 256;

    // ELEVATION BOOKS (owner directives 2026-11-20):
    // The single-source switch removed Terrarium completely
    // because the old hybrid mixed two books per tile/zoom
    // (teleported-chunk seams, floating river water, slow
    // double coastline downloads). The owner then ordered
    // the Y rule instead: the NEW book (Mapterhorn) builds
    // at Y65 and above, the OLD book (Terrarium) at Y64 and
    // below, with a transition gap around the line so the
    // join can never step. In metres (sea level Y63):
    //   elev >= +5 m (Y68+) : 100% new book
    //   elev <= -1 m (Y62-) : 100% old book
    //   -1..+5 m (Y62..Y68) : smooth mix; the owner's 64/65
    //   line (+1/+2 m) is the middle of the gap, where the
    //   mix passes 26% -> 50% new book.
    // The mix ratio is driven by the NEW book's elevation
    // only, so the ratio itself can never jump, and the old
    // book is only ever fetched when the new book reads
    // below +5 m — high/inland land never touches it. The
    // bundled BathyData grid stays as the fallback when the
    // old book has no answer (fetch failed / no depth).
    // Note: height values travel in "Terrarium encoding"
    // (elevation packed into RGB) — that is only a pixel
    // FORMAT (Mapterhorn tiles use it too), not the source.
    //
    // Negative values are valid over oceans. These bounds
    // only reject values that cannot exist on Earth.
    private static final double MIN_EARTH_ELEVATION = -11_000.0;
    private static final double MAX_EARTH_ELEVATION = 9_500.0;
    private static final double OUTLIER_DELTA_METERS = 500.0;
    private static final double STABLE_NEIGHBOUR_SPREAD_METERS = 120.0;
    private static final int REPAIR_RADIUS = 2;
    private static final int REPAIR_PASSES = 8;
    private static final double QUARANTINE_REPAIR_FRACTION = 0.05;
    // Bathymetry zooms {8,7,6,5,3} on Terrarium were here
    // until 2026-11-20; replaced by the bundled BathyData
    // grid in the single-source switch. (The same ladder
    // returned with the Y rule as OLD_BATHY_ZOOMS, inside
    // the old-book sampler only — fetchBathymetry still
    // reads the bundled grid.)
    private static final double CLUSTER_THRESHOLD = 150.0,
            ARTIFACT_LOW_CEILING  = 10.0,
            ARTIFACT_HIGH_FLOOR   = 100.0;
    private static final BufferedImage SENTINEL_FAILED =
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

    // ── OWNER Y RULE (2026-11-20) ─────────────────────────
    // New book >= Y65, old book <= Y64, transition gap
    // around the line. Expressed in metres above sea level
    // (Y = SEA_LEVEL + elev for dry land at these heights).
    private static final double OLD_FULL_BELOW_M = -1.0;  // Y62
    private static final double NEW_FULL_ABOVE_M =  5.0;  // Y68
    // Inside the gap the old book may RAISE a column by at
    // most this much over the new book (beach protection);
    // deepening is uncapped (ocean depth is the old book's
    // whole job, and its tiles are spike-guarded at load).
    private static final double GAP_MAX_UP_PULL_M = 10.0;
    private static final String OLD_TILE_URL =
            "https://s3.amazonaws.com/elevation-tiles-prod"
            + "/terrarium/%d/%d/%d.png";
    // ── OLD BOOK ONLY (TEST MODE, 2026-11-21) ────────────
    // Owner: "once try running with old book only". With
    // -Dterra.oldBookOnly=true (or env TERRA_OLD_BOOK_ONLY=
    // true) at launch, EVERY height sample comes from the
    // old book alone — the full Terrarium DEM at terrain
    // zoom (accepted as-is, incl. its flat ~0 m sea) with
    // the coarse bathymetry ladder behind it, exactly the
    // pre-single-source arrangement. The new book is never
    // consulted for heights: not at high land, not as spike-
    // guard anchor (old tiles anchor to OLD coarse tiles in
    // this mode), not as parent fallback, not prefetched.
    // Only the bundled bathy grid remains as last resort
    // where the old book has no answer at all. Default OFF:
    // a normal launch is the owner Y rule, unchanged. The
    // mode is announced loudly at startup and in /terracheck
    // so a forgotten flag can never silently persist.
    private static final boolean OLD_BOOK_ONLY =
            Boolean.getBoolean("terra.oldBookOnly")
            || "true".equalsIgnoreCase(
                    System.getenv("TERRA_OLD_BOOK_ONLY"));
    private static final AtomicInteger oldOnlyCount =
            new AtomicInteger(0);

    /** True when this session runs old-book-only (test). */
    public static boolean oldBookOnlyMode() {
        return OLD_BOOK_ONLY;
    }
    // Old book depth ladder (Terrarium coarse zooms, as in
    // the pre-removal bathymetry chain): first zoom whose
    // sample is a real depth (< -1 m) wins.
    private static final int[] OLD_BATHY_ZOOMS = {8, 7, 6, 5, 3};
    private static final AtomicInteger oldSrcDebug =
            new AtomicInteger(0);
    // Session counters for /terracheck: samples fully
    // served by the old book (below the gap) and samples
    // blended inside the gap.
    private static final AtomicInteger lowFullCount =
            new AtomicInteger(0);
    private static final AtomicInteger lowBlendCount =
            new AtomicInteger(0);

    // ── COASTLINE THRESHOLDS ──────────────────────────────────
    // Used ONLY for elevation fallback when OSM tiles not loaded.
    // COASTLINE terrain type is never produced by classify().
    private static final double COAST_LAND_THRESHOLD  =  2.0;
    private static final double COAST_OCEAN_THRESHOLD = -1.0;
    // ESA "permanent water" also covers rivers and mountain
    // lakes. Only water whose terrain sits near sea level may
    // become sea; anything higher is a river/lake that the
    // river engine paints at its true height. Without this
    // gate the Penna river at Gandikota (191 m) was flattened
    // into a Y63 coral sea (NW(18), owner screenshots
    // 2026-11-19). 5 m leaves real coasts untouched: mapped
    // sea pixels read ~0 m in both height books.
    private static final double ESA_SEA_MAX_ELEV = 5.0;

    // ── SEA LEVEL SNAP ZONE ───────────────────────────────────
    // Widened from +/-0.5m to +/-1.0m to absorb coastal noise.
    private static final double SEA_LEVEL_SNAP = 1.0;

    // ── WATER BODY QUERY SOURCE ───────────────────────────────
    // Used by TerraChunkGenerator after prefetch completes.
    // NOT called from classify().
    public interface WaterBodyQuerySource {
        WaterBodyInfo query(double lat, double lon, double elevMeters);
    }

    private volatile WaterBodyQuerySource waterBodyQuery = null;
    public void setWaterBodyQuerySource(WaterBodyQuerySource src) {
        this.waterBodyQuery = src;
    }

    private volatile OsmWaterIndex osmWater = null;
    public void setOsmWaterIndex(OsmWaterIndex idx) {
        this.osmWater = idx;
    }

    // ── ESA WATER AREAS (second sea witness) ──────────────────
    // ESA WorldCover "Permanent water bodies" is an area map
    // served from S3 — independent of OSM coastline arrival.
    // When the coastline test has no answer, an ESA WATER area
    // still means sea/lake, never dry land. Non-blocking:
    // implementations must answer from memory only.
    public interface EsaWaterQuery {
        boolean isWater(double lat, double lon);
    }

    private volatile EsaWaterQuery esaWater = null;
    public void setEsaWaterQuery(EsaWaterQuery q) {
        this.esaWater = q;
    }

    private final int zoom;
    private final GeographicProjection projection;
    private final boolean projectionOk;
    private final ConcurrentHashMap<String, BufferedImage> tileCache;
    private final ConcurrentHashMap<String, CompletableFuture<BufferedImage>>
            pendingTiles;
    // Tile key -> last completed-fetch failure time. Lets the
    // generation readiness gate (awaitTilesForChunk) respect
    // negative caching instead of re-hammering a dead server
    // on every chunk. Success removes the entry.
    private final ConcurrentHashMap<String, Long>
            tileFailedAt = new ConcurrentHashMap<>();
    // Tile key -> where the cached tile actually came from
    // ("mapterhorn (<archive detail>)", "disk (mapterhorn-v2)"
    // or "FAILED"). Recorded at cache time so /terracheck can
    // prove in-game that every height tile has exactly one
    // book (NW23 owner request: terracheck must also check
    // elevation sources).
    private final ConcurrentHashMap<String, String>
            tileSource = new ConcurrentHashMap<>();
    // OLD book (Terrarium) tile state for the Y rule. Kept
    // in maps/ folders of its own, keyed exactly like the
    // new book's ("z/x/y") — the two books can only meet
    // inside the transition-gap blend, never inside a
    // cache, so a tile can never be served by the wrong
    // book (the NW23 disk-poison lesson).
    private final ConcurrentHashMap<String, BufferedImage>
            oldTileCache = new ConcurrentHashMap<>(256);
    private final ConcurrentHashMap<String,
            CompletableFuture<BufferedImage>>
            oldPendingTiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>
            oldTileFailedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>
            oldTileSource = new ConcurrentHashMap<>();
    // How often the parent-zoom fallback replaced a terrain
    // sample this session. Warm, healthy areas stay at 0.
    private static final AtomicInteger parentFallbackCount =
            new AtomicInteger(0);
    private final ConcurrentHashMap<Long, Double> elevCache;
    private static final int ELEV_CACHE_MAX = 32768;
    private final Path diskCacheDir;
    private final Path oldDiskCacheDir;
    private final boolean useDiskCache;
    private final ExecutorService dlPool;

    // LOADING-SCREEN FIX: these debug gates were per-INSTANCE,
    // and 11 universes create 11 instances -> ~900 INFO lines
    // during boot (console I/O on Windows is slow and these run
    // inside the hot terrain path). Static = global gates: same
    // diagnostic value, ~1/11th the spam.
    private static final AtomicInteger elevDebug     = new AtomicInteger(0);
    private static final AtomicInteger bathyDebug    = new AtomicInteger(0);
    private static final AtomicInteger belowSeaDebug = new AtomicInteger(0);
    private static final AtomicInteger classifyDebug = new AtomicInteger(0);
    private static final AtomicInteger maptSrcDebug  = new AtomicInteger(0);
    private static final AtomicInteger esaInlandDebug = new AtomicInteger(0);

    // ── UNIVERSE (axis 6) ─────────────────────────────────────
    // Pure transform applied at the geography/elevation
    // chokepoints; everything downstream inherits it.
    private final UniverseTransforms.Universe universe;

    public UniverseTransforms.Universe getUniverse() {
        return universe;
    }

    // ── CONSTRUCTOR ───────────────────────────────────────────
    public TerraDensityFunctions(int zoom) {
        this(zoom, UniverseTransforms.PRIME);
    }

    public TerraDensityFunctions(int zoom, String universeId) {
        this.universe = UniverseTransforms.get(universeId);
        this.zoom = zoom;
        GeographicProjection proj = null;
        boolean ok = false;
        try {
            EarthGeneratorSettings s = EarthGeneratorSettings.parse(
                    EarthGeneratorSettings.BTE_DEFAULT_SETTINGS);
            proj = s.projection();
            ok   = true;
            LOGGER.info("BTE projection loaded: {}",
                    proj.getClass().getSimpleName());
        } catch (Throwable t) {
            LOGGER.error("BTE projection FAILED", t);
        }
        this.projection   = proj;
        this.projectionOk = ok;
        this.tileCache    = new ConcurrentHashMap<>(512);
        this.pendingTiles = new ConcurrentHashMap<>();
        this.elevCache    = new ConcurrentHashMap<>(8192);

        // Single-source switch: the old "tiles" folder holds
        // archived Terrarium PNGs. NEVER read those again —
        // Mapterhorn tiles cache under their own folder, so
        // the two books can never mix on disk either.
        // NW(23) bump v1 -> v2: the PNG encoding is IDENTICAL
        // for both books, so a tile PNG carries no proof of
        // which book wrote it. The v1 folder could contain
        // tiles saved by the pre-removal hybrid build (when
        // Terrarium was still a live per-tile fallback), and
        // loadFromDisk would serve them as "Mapterhorn" —
        // NW(23) contained exactly such Terrarium-born chunks
        // (see NW23_AUDIT.md). The folder NAME is the only
        // source marker a PNG has: bump it whenever the tile
        // derivation or validation changes, so no older
        // build's tiles can ever be read again.
        Path dir = net.buildtheearth.terraplusplus.util.TerraCachePaths
                .dir("tiles").resolve("mapterhorn-v2");
        boolean diskOk = false;
        try {
            Files.createDirectories(dir);
            diskOk = true;
        } catch (Exception e) {
            LOGGER.warn("Disk cache failed: {}", e.getMessage());
        }
        this.diskCacheDir = dir;
        this.useDiskCache = diskOk;
        // Old book disk cache for the Y rule: its OWN new
        // folder (Terrarium-y-v1). The legacy archive under
        // "tiles" is still never read — PNGs carry no source
        // proof, so only tiles fetched+validated by THIS
        // build's Y-rule path may serve the low zone.
        Path oldDir = dir.getParent().resolve("Terrarium-y-v1");
        try {
            Files.createDirectories(oldDir);
        } catch (Exception e) {
            LOGGER.warn("Old-book disk cache failed: {}",
                    e.getMessage());
        }
        this.oldDiskCacheDir = oldDir;
        if (OLD_BOOK_ONLY)
            LOGGER.warn("===================================================");
        if (OLD_BOOK_ONLY)
            LOGGER.warn("ELEVATION MODE: OLD BOOK ONLY (TEST) — every"
                    + " height this session comes from Terrarium"
                    + " alone; the new book is NOT consulted."
                    + " Restart without -Dterra.oldBookOnly=true"
                    + " to return to the owner Y rule.");
        if (OLD_BOOK_ONLY)
            LOGGER.warn("===================================================");

        this.dlPool = Executors.newFixedThreadPool(
                Math.max(4, Math.min(8,
                        Runtime.getRuntime().availableProcessors())),
                r -> {
                    Thread t = new Thread(r, "Terra++Tile");
                    t.setDaemon(true);
                    return t;
                });
        LOGGER.info("TerraDensityFunctions: zoom={} projection={} "
                        + "coastLand={}m coastOcean={}m snap={}m universe={}",
                zoom, ok, COAST_LAND_THRESHOLD,
                COAST_OCEAN_THRESHOLD, SEA_LEVEL_SNAP, universe.id());
    }

    public TerraDensityFunctions() { this(15); }

    public GeographicProjection getProjection()  { return projection; }
    public boolean isProjectionLoaded()          { return projectionOk; }
    public int getCacheSize()                    { return tileCache.size(); }
    public int getElevationCacheSize()           { return elevCache.size(); }

    // ── COORDINATE CONVERSION ─────────────────────────────────
    public double[] toGeo(int blockX, int blockZ) {
        if (projection == null) return null;
        try {
            return universe.mapGeo(
                projection.toGeo((double) blockX, (double) blockZ));
        } catch (OutOfProjectionBoundsException e) {
            return null;
        }
    }

    public double[] fromGeo(double lat, double lon) {
        if (projection == null) return null;
        try {
            double[] ll = universe.unmapGeo(lat, lon);
            return projection.fromGeo(ll[1], ll[0]);
        } catch (OutOfProjectionBoundsException e) {
            return null;
        }
    }

    // ── ENUMS ─────────────────────────────────────────────────
    // COASTLINE kept for backward compatibility, never produced
    // by classify().
    public enum TerrainType {
        LAND, COASTLINE, SHALLOW_WATER, OCEAN, RIVER, LAKE,
        RIVER_BANK, LAKE_SHORE, RIVER_ISLAND_SHORE,
        LAKE_ISLAND_SHORE, ISLAND_STEP, BELOW_SEA_WATER, INVALID
    }

    public enum InvalidReason {
        PROJECTION_NULL("Projection not loaded"),
        OUT_OF_BOUNDS("Outside projection bounds"),
        NAN_COORDINATES("Projection returned NaN"),
        POLAR_REGION("Too close to poles"),
        NONE("Valid");
        public final String message;
        InvalidReason(String msg) { this.message = msg; }
    }

    public enum OceanZone {
        COASTAL      (0,     50,    "Coastal"),
        SHELF        (50,    200,   "Continental Shelf"),
        SLOPE        (200,   1000,  "Continental Slope"),
        ABYSSAL_PLAIN(1000,  4000,  "Abyssal Plain"),
        ABYSSAL_DEEP (4000,  6000,  "Abyssal Deep"),
        HADAL        (6000,  11100, "Hadal Zone");

        public final double minDepth, maxDepth;
        public final String displayName;
        OceanZone(double min, double max, String name) {
            this.minDepth    = min;
            this.maxDepth    = max;
            this.displayName = name;
        }
        public static OceanZone fromDepth(double d) {
            d = Math.abs(d);
            if (d <= 50)   return COASTAL;
            if (d <= 200)  return SHELF;
            if (d <= 1000) return SLOPE;
            if (d <= 4000) return ABYSSAL_PLAIN;
            if (d <= 6000) return ABYSSAL_DEEP;
            return HADAL;
        }
    }

    // ── TERRAIN COLUMN ────────────────────────────────────────
    public static class TerrainColumn {
        public final TerrainType   type;
        public final double        elevationMeters;
        public final int           surfaceY;
        public final double        latitude, longitude;
        public final InvalidReason invalidReason;
        public final WaterBodyInfo waterBody;
        // True only when the sea verdict came from a MAPPED
        // area (OSM coastline area or ESA permanent water),
        // never from the elevation guess. The chunk generator's
        // elevation-based cleanup passes must never turn a
        // mapped sea back into land (missing-sea round 2:
        // the bench said SHALLOW_WATER while oceanBFS +
        // reclassWB quietly re-landified every shallow cell
        // whose elevation was above -500 m).
        public final boolean       mappedSea;
        // Mirror of mappedSea for the land side: true only
        // when the LAND verdict came from the OSM coastline
        // area test (land inside a mapped land area, or on
        // the coastline band), never from the elevation guess
        // and never from ESA merely not seeing water. The
        // coast repair may fill fake sea back into land only
        // on a mapped land verdict (coastline-teeth fix:
        // backup maps drew a different shoreline while the
        // coastline map was still downloading).
        public final boolean       mappedLand;

        public TerrainColumn(TerrainType t, double e, int s,
                             double lat, double lon) {
            this(t, e, s, lat, lon, InvalidReason.NONE, null);
        }
        public TerrainColumn(TerrainType t, double e, int s,
                             double lat, double lon, InvalidReason r) {
            this(t, e, s, lat, lon, r, null);
        }
        public TerrainColumn(TerrainType t, double e, int s,
                             double lat, double lon,
                             InvalidReason r, WaterBodyInfo wb) {
            this(t, e, s, lat, lon, r, wb, false);
        }
        public TerrainColumn(TerrainType t, double e, int s,
                             double lat, double lon,
                             InvalidReason r, WaterBodyInfo wb,
                             boolean mappedSea) {
            this(t, e, s, lat, lon, r, wb, mappedSea, false);
        }
        public TerrainColumn(TerrainType t, double e, int s,
                             double lat, double lon,
                             InvalidReason r, WaterBodyInfo wb,
                             boolean mappedSea, boolean mappedLand) {
            this.type            = t;
            this.elevationMeters = e;
            this.surfaceY        = s;
            this.latitude        = lat;
            this.longitude       = lon;
            this.invalidReason   = r;
            this.waterBody       = wb;
            this.mappedSea       = mappedSea;
            this.mappedLand      = mappedLand;
        }
    }

    // ── Y CONVERSION ──────────────────────────────────────────
    // Single source of truth for surface Y.
    // Snap zone: +/-1.0m snaps to exactly SEA_LEVEL.
    public static int elevMetersToSurfaceY(double elevMeters,
                                            boolean isDryLand) {
        if (!isDryLand) return getOceanFloorY(elevMeters);

        if (elevMeters >= SEA_LEVEL_SNAP)
            return Math.min(SEA_LEVEL + scaleElevation(elevMeters), MAX_Y);

        if (elevMeters >= -SEA_LEVEL_SNAP)
            return SEA_LEVEL;

        double clamped = Math.max(DRY_LAND_MIN_ELEV, elevMeters);
        int y = SEA_LEVEL - 1 + (int) Math.round(clamped * BELOW_SEA_Y_SCALE);
        return Math.max(SEA_LEVEL - 50, Math.min(SEA_LEVEL - 1, y));
    }

    // ── CLASSIFY ──────────────────────────────────────────────
    // Non-blocking. Never returns COASTLINE.
    // No water body queries. Returns LAND or OCEAN only.
    // All water body analysis happens in TerraChunkGenerator
    // stage 1B after prefetch completes.
    public TerrainColumn classify(int blockX, int blockZ) {
        if (projection == null)
            return invalidColumn(InvalidReason.PROJECTION_NULL);

        double longitude, latitude;
        try {
            double[] ll = universe.mapGeo(projection.toGeo(
                    (double) blockX, (double) blockZ));
            longitude = ll[0];
            latitude  = ll[1];
        } catch (OutOfProjectionBoundsException e) {
            return invalidColumn(InvalidReason.OUT_OF_BOUNDS);
        } catch (Exception e) {
            return invalidColumn(InvalidReason.OUT_OF_BOUNDS);
        }

        if (Double.isNaN(longitude) || Double.isNaN(latitude))
            return invalidColumn(InvalidReason.NAN_COORDINATES);

        if (latitude <= -85 || latitude >= 85)
            return new TerrainColumn(TerrainType.OCEAN, -1000,
                    elevMetersToSurfaceY(-1000, false),
                    latitude, longitude, InvalidReason.POLAR_REGION);

        double elev = getElevationInterpolated(latitude, longitude);

        // ── STEP 1: OSM COASTLINE ─────────────────────────────
        OsmWaterIndex owi = this.osmWater;
        if (owi != null) {
            OsmWaterIndex.CoastSide side =
                    owi.classifyCoastlineMemOnly(latitude, longitude);

            if (classifyDebug.getAndIncrement() < 50)
                LOGGER.info("CLASSIFY: lat={} lon={} elev={}m coast={}",
                        String.format("%.5f", latitude),
                        String.format("%.5f", longitude),
                        String.format("%.1f", elev), side);

            switch (side) {
                case LAND      -> { return classifyAsLand(
                        elev, latitude, longitude, true); }
                case OCEAN     -> { return classifyAsOcean(
                        elev, latitude, longitude, true); }
                case COASTLINE -> { return classifyAsLandFromCoastline(
                        elev, latitude, longitude); }
                case UNKNOWN   -> { /* fall through */ }
            }
        }

        // ── STEP 1C: ESA WATER AREAS ──────────────────────────
        // Coastline had no answer (tile not loaded). ESA
        // WorldCover permanent-water areas are the second
        // witness: a mapped water area is never dry land, even
        // though the height map reads 0m over the sea.
        EsaWaterQuery ew = this.esaWater;
        if (ew != null) {
            try {
                if (ew.isWater(latitude, longitude)) {
                    // Inland gate (2026-11-19): ESA water far
                    // above sea level is a river/lake, not sea.
                    // Falling through lets the elevation check
                    // return LAND, and the river engine paints
                    // the real water at its true height.
                    if (elev <= ESA_SEA_MAX_ELEV)
                        return classifyAsOcean(
                            elev, latitude, longitude, true);
                    else if (esaInlandDebug.getAndIncrement() < 15)
                        LOGGER.info("ESA WATER INLAND: lat={} lon={} elev={}m -> not sea (river/lake)",
                                String.format("%.5f", latitude),
                                String.format("%.5f", longitude),
                                String.format("%.1f", elev));
                }
            } catch (Exception ignored) {}
        }

        // ── STEP 2: ELEVATION FALLBACK ────────────────────────
        // No water query here. Just land vs ocean from elevation.
        // An elevation guess is NOT mapped land (mappedLand=false):
        // the coast repair must never fill real sea on a guess.
        if (elev >= COAST_OCEAN_THRESHOLD)
            return classifyAsLand(elev, latitude, longitude, false);
        else
            return classifyAsOcean(elev, latitude, longitude, false);
    }

    // ── INTERNAL CLASSIFY HELPERS ─────────────────────────────
    private TerrainColumn invalidColumn(InvalidReason r) {
        return new TerrainColumn(TerrainType.OCEAN, -4000,
                elevMetersToSurfaceY(-4000, false), 0, 0, r);
    }

    // On the OSM coastline boundary — treat as LAND.
    // No water query. surfaceY from elevation only.
    // The verdict comes from the mapped coastline, so it is
    // mapped land.
    private TerrainColumn classifyAsLandFromCoastline(double elev,
                                                       double lat,
                                                       double lon) {
        int surfY = elevMetersToSurfaceY(elev, true);
        return new TerrainColumn(TerrainType.LAND, elev, surfY,
                lat, lon, InvalidReason.NONE, null, false, true);
    }

    // Always returns LAND. Never COASTLINE.
    // surfaceY always from elevMetersToSurfaceY.
    // mapped=true only for the OSM coastline area verdict;
    // the elevation fallback passes false.
    private TerrainColumn classifyAsLand(double elev,
                                          double lat, double lon,
                                          boolean mapped) {
        int surfY = elevMetersToSurfaceY(elev, true);

        if (belowSeaDebug.getAndIncrement() < 15 && elev < -1.0)
            LOGGER.info("DRY BASIN (LAND): lat={} lon={} elev={}m -> Y={}",
                    String.format("%.4f", lat),
                    String.format("%.4f", lon),
                    String.format("%.1f", elev), surfY);

        return new TerrainColumn(TerrainType.LAND, elev, surfY,
                lat, lon, InvalidReason.NONE, null, false, mapped);
    }

    private TerrainColumn classifyAsOcean(double elev,
                                           double lat, double lon,
                                           boolean mappedSea) {
        double oe = elev >= -0.5 ? -5.0 : elev;
        int floorY = elevMetersToSurfaceY(oe, false);
        return new TerrainColumn(
                oe >= -200.0 ? TerrainType.SHALLOW_WATER : TerrainType.OCEAN,
                oe, floorY, lat, lon,
                InvalidReason.NONE, null, mappedSea);
    }

    // ── BUILD WATER COLUMN ────────────────────────────────────
    // Called by TerraChunkGenerator after r.analyze() succeeds.
    // Replaces the old classifyWaterBody private method.
    // Public so the chunk generator can call it directly.
    public TerrainColumn buildWaterColumn(WaterBodyInfo wb, double elev,
                                           double lat, double lon) {
        int surfaceY = elevMetersToSurfaceY(elev, true);
        WaterBodyInfo corrected = new WaterBodyInfo(
                wb.type, wb.source, wb.isWater,
                wb.surfaceElevation, wb.depth,
                wb.widthMeters, wb.edgeFactor,
                wb.flowDirection, wb.bankWidth,
                wb.name, surfaceY);

        if (elev < -1.0) {
            if (belowSeaDebug.getAndIncrement() < 15)
                LOGGER.info("BELOW-SEA WATER: lat={} lon={} "
                                + "elev={}m -> Y={} type={}",
                        String.format("%.4f", lat),
                        String.format("%.4f", lon),
                        String.format("%.1f", elev), surfaceY, wb.type);
            return new TerrainColumn(TerrainType.BELOW_SEA_WATER, elev,
                    surfaceY, lat, lon, InvalidReason.NONE, corrected);
        }
        return new TerrainColumn(
                wb.isRiver() ? TerrainType.RIVER : TerrainType.LAKE,
                elev, surfaceY, lat, lon,
                InvalidReason.NONE, corrected);
    }

    // ── SCALING ───────────────────────────────────────────────
    public static int scaleElevation(double e) {
        if (e <= 0)    return 0;
        if (e <= 1000) return (int) Math.round(e);
        if (e >= 8849) return 1969;
        double r = (e - 1000.0) / 7849.0;
        return 1000 + Math.min(
                (int) Math.round(969.0 * Math.pow(r, 0.65)), 969);
    }

    public static int getOceanFloorY(double e) {
        double d = Math.abs(e);
        int db;
        if (d <= 2000) db = (int) Math.round(d);
        else if (d >= 11034) db = 2091;
        else {
            double r = (d - 2000.0) / 9034.0;
            db = 2000 + Math.min(
                    (int) Math.round(91.0 * Math.pow(r, 0.7)), 91);
        }
        return Math.max(MIN_Y + 4, Math.min(SEA_LEVEL - 1, SEA_LEVEL - db));
    }

    // ── BLOCK STATES ──────────────────────────────────────────
    private static final BlockState BS_SAND          =
            Blocks.SAND.defaultBlockState();
    private static final BlockState BS_RED_SAND      =
            Blocks.RED_SAND.defaultBlockState();
    private static final BlockState BS_SANDSTONE     =
            Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState BS_RED_SANDSTONE =
            Blocks.RED_SANDSTONE.defaultBlockState();
    private static final BlockState BS_TERRACOTTA    =
            Blocks.TERRACOTTA.defaultBlockState();
    private static final BlockState BS_WHITE_TERRA   =
            Blocks.WHITE_TERRACOTTA.defaultBlockState();
    private static final BlockState BS_ORANGE_TERRA  =
            Blocks.ORANGE_TERRACOTTA.defaultBlockState();
    private static final BlockState BS_RED_TERRA     =
            Blocks.RED_TERRACOTTA.defaultBlockState();
    private static final BlockState BS_YELLOW_TERRA  =
            Blocks.YELLOW_TERRACOTTA.defaultBlockState();
    private static final BlockState BS_BROWN_TERRA   =
            Blocks.BROWN_TERRACOTTA.defaultBlockState();
    private static final BlockState BS_LIGHT_GRAY_TERRA =
            Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState();
    private static final BlockState BS_GRASS         =
            Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState BS_DIRT          =
            Blocks.DIRT.defaultBlockState();
    private static final BlockState BS_COARSE_DIRT   =
            Blocks.COARSE_DIRT.defaultBlockState();
    private static final BlockState BS_SNOW_BLOCK    =
            Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState BS_PACKED_ICE    =
            Blocks.PACKED_ICE.defaultBlockState();
    private static final BlockState BS_STONE         =
            Blocks.STONE.defaultBlockState();

    // ── SURFACE BLOCK SELECTION ───────────────────────────────
    // Sand-at-low-elevation removed. Beach sand handled by bt=9.
    public static BlockState getLandSurfaceBlock(double r) {
        if (r > 4000) return BS_SNOW_BLOCK;
        if (r > 2500) return BS_STONE;
        if (r > 1500) return BS_COARSE_DIRT;
        return BS_GRASS;
    }

    public static BlockState getLandSurfaceBlock(double r, int b) {
        return getLandSurfaceBlock(r, b, 0.0);
    }

    public static BlockState getLandSurfaceBlock(double r, int b, double rg) {
        if (r > 4000) return BS_SNOW_BLOCK;
        switch (b) {
            case 1: return r > 1500 ? BS_SANDSTONE : BS_SAND;
            case 2:
                if (r > 2500) return BS_TERRACOTTA;
                return rg > 0.4
                        ? getMesaSurfaceBlock(r) : getScrublandSurface(r);
            case 3: return r > 1500 ? BS_PACKED_ICE : BS_SNOW_BLOCK;
            case 4: return r > 1500 ? BS_COARSE_DIRT : BS_GRASS;
            case 5:
                if (r > 2500) return BS_STONE;
                if (r > 1500) return BS_COARSE_DIRT;
                return BS_GRASS;
            default:
                if (r > 2500) return BS_STONE;
                if (r > 1500) return BS_COARSE_DIRT;
                return BS_GRASS;
        }
    }

    private static BlockState getScrublandSurface(double e) {
        int h = ((int) (Math.abs(e) * 7.3)) & 0x7FFFFFFF;
        int p = h % 10;
        if (p < 4) return BS_RED_SAND;
        if (p < 7) return BS_GRASS;
        if (p < 9) return BS_COARSE_DIRT;
        return BS_RED_SAND;
    }

    private static BlockState getMesaSurfaceBlock(double e) {
        int b = ((int) Math.abs(e)) % 16;
        if (b < 2)  return BS_RED_TERRA;
        if (b < 4)  return BS_ORANGE_TERRA;
        if (b < 5)  return BS_YELLOW_TERRA;
        if (b < 7)  return BS_TERRACOTTA;
        if (b < 9)  return BS_BROWN_TERRA;
        if (b < 10) return BS_WHITE_TERRA;
        if (b < 12) return BS_ORANGE_TERRA;
        if (b < 14) return BS_RED_TERRA;
        return BS_LIGHT_GRAY_TERRA;
    }

    // ── SUB-SURFACE BLOCK SELECTION ───────────────────────────
    // bt=9 (beach) returns sandstone under sand.
    public static BlockState getSubSurfaceBlock(double r, int b) {
        return getSubSurfaceBlock(r, b, 0.0);
    }

        // In getSubSurfaceBlock — add case 11:
    public static BlockState getSubSurfaceBlock(
            double r, int b, double rg) {
        switch (b) {
            case 1:  return BS_SANDSTONE;
            case 2:
                if (rg > 0.4) {
                    int band =
                            (((int) Math.abs(r)) + 4) % 16;
                    if (band < 4)  return BS_RED_TERRA;
                    if (band < 8)  return BS_ORANGE_TERRA;
                    if (band < 12) return BS_TERRACOTTA;
                    return BS_BROWN_TERRA;
                } else {
                    int h = ((int) (Math.abs(r) * 3.7))
                            & 0x7FFFFFFF;
                    return (h % 3 == 0)
                            ? BS_RED_SANDSTONE : BS_DIRT;
                }
            case 3:  return BS_PACKED_ICE;
            case 9:  return BS_SANDSTONE;
            case 10: return Blocks.TUFF.defaultBlockState();
            case 11: return BS_DIRT;  // dirt_path subsurface
            default: return BS_DIRT;
        }
    }

    

    // ── DEEP SUB BLOCK SELECTION ──────────────────────────────
    // bt=9 (beach) returns sandstone deep layer.
    public static BlockState getDeepSubBlock(int b) {
        return getDeepSubBlock(b, 0.0);
    }

    public static BlockState getDeepSubBlock(int b, double rg) {
        switch (b) {
            case 1: return BS_SANDSTONE;
            case 2: return rg > 0.4 ? BS_TERRACOTTA : BS_RED_SANDSTONE;
            case 3: return BS_STONE;
            case 9: return BS_SANDSTONE;
            case 10: return Blocks.TUFF.defaultBlockState();
            default: return BS_STONE;
        }
    }

    public static BlockState getOceanFloorBlock(double d) {
        return Blocks.GRAVEL.defaultBlockState();
    }

    public static BlockState getSubFloorBlock(double d) {
        return Math.abs(d) <= 1000
                ? BS_STONE
                : Blocks.DEEPSLATE.defaultBlockState();
    }

    // ── ROUGHNESS ─────────────────────────────────────────────
    public double calculateRoughness(int bx, int bz) {
        try {
            double[] geo = toGeo(bx, bz);
            if (geo == null) return 0.0;
            double lon = geo[0], lat = geo[1];
            if (Double.isNaN(lat) || Double.isNaN(lon)) return 0.0;
            double center = getElevationFast(lat, lon);
            if (Double.isNaN(center) || center < 0) return 0.0;
            double mLon = 111320.0 * Math.cos(Math.toRadians(lat));
            double step = 30.0;
            double dLat = step / 111320.0;
            double dLon = step / mLon;
            double eN = getElevationFast(lat + dLat, lon);
            double eS = getElevationFast(lat - dLat, lon);
            double eE = getElevationFast(lat, lon + dLon);
            double eW = getElevationFast(lat, lon - dLon);
            double maxDiff = 0;
            int valid = 0;
            for (double nb : new double[]{eN, eS, eE, eW}) {
                if (Double.isNaN(nb) || nb < 0) continue;
                valid++;
                double diff = Math.abs(nb - center);
                if (diff > maxDiff) maxDiff = diff;
            }
            if (valid < 2) return 0.0;
            return Math.min(1.0, maxDiff / 20.0);
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ── DECORATION HELPERS ────────────────────────────────────
    public static int blockHash(int x, int z) {
        return ((x * 73856093) ^ (z * 19349663)) & 0x7FFFFFFF;
    }

    public static BlockState getRandomCoral(int h) {
        return switch ((h >> 8) % 5) {
            case 0  -> Blocks.BRAIN_CORAL.defaultBlockState();
            case 1  -> Blocks.TUBE_CORAL.defaultBlockState();
            case 2  -> Blocks.FIRE_CORAL.defaultBlockState();
            case 3  -> Blocks.HORN_CORAL.defaultBlockState();
            default -> Blocks.BUBBLE_CORAL.defaultBlockState();
        };
    }

    public static BlockState getRandomCoralBlock(int h) {
        return switch (h % 5) {
            case 0  -> Blocks.BRAIN_CORAL_BLOCK.defaultBlockState();
            case 1  -> Blocks.TUBE_CORAL_BLOCK.defaultBlockState();
            case 2  -> Blocks.FIRE_CORAL_BLOCK.defaultBlockState();
            case 3  -> Blocks.HORN_CORAL_BLOCK.defaultBlockState();
            default -> Blocks.BUBBLE_CORAL_BLOCK.defaultBlockState();
        };
    }

    // ── GEO HELPERS ───────────────────────────────────────────
    public static double metersPerDegreeLat() { return 111_320.0; }
    public static double metersPerDegreeLon(double lat) {
        return 111_320.0 * Math.cos(Math.toRadians(lat));
    }

    public double sampleElevationOffset(double lat, double lon,
                                         double dLat, double dLon) {
        double sLat = lat + dLat, sLon = lon + dLon;
        if (sLat <= -85 || sLat >= 85) return Double.NaN;
        if (sLon < -180) sLon += 360;
        if (sLon >  180) sLon -= 360;
        return getElevationFast(sLat, sLon);
    }

    // ── ELEVATION ─────────────────────────────────────────────
    public double getElevationFast(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)
                || lat <= -85 || lat >= 85
                || lon < -180 || lon > 180) return -50;
        long rLat = Math.round(lat * 10000.0);
        long rLon = Math.round(lon * 10000.0);
        long key  = rLat * 3600001L + rLon;
        Double cached = elevCache.get(key);
        if (cached != null) return cached;
        double elev = getElevationInterpolated(lat, lon);
        if (elevCache.size() > ELEV_CACHE_MAX) elevCache.clear();
        elevCache.put(key, elev);
        return elev;
    }

    public double getElevationInterpolated(double latitude, double longitude) {
        double raw = getElevationInterpolatedRaw(latitude, longitude);
        // Universe transform: applied to every consumer uniformly
        // (terrain, coastlines, rivers, structure ground checks).
        return universe.mapElevation(raw, latitude, longitude);
    }

    private double getElevationInterpolatedRaw(double latitude, double longitude) {
        if (Double.isNaN(latitude)  || Double.isNaN(longitude)
                || latitude  <= -85 || latitude  >= 85
                || longitude < -180 || longitude > 180) return -50;
        try {
            // ── OLD BOOK ONLY (TEST MODE) ─────────────
            // The old book answers everything; the new book
            // is not even sampled. Where the old book has no
            // answer, only the bundled grid / -50 remain —
            // same last resorts as the Y rule's low zone.
            if (OLD_BOOK_ONLY) {
                double oldElev = getOldElevationOnly(
                        latitude, longitude);
                if (!Double.isNaN(oldElev)) {
                    oldOnlyCount.incrementAndGet();
                    if (elevDebug.getAndIncrement() < 15)
                        LOGGER.info("Elev (OLD BOOK ONLY): lat={}"
                                + " lon={} -> {}m",
                                String.format("%.4f", latitude),
                                String.format("%.4f", longitude),
                                String.format("%.1f", oldElev));
                    return oldElev;
                }
                double bathyElev = fetchBathymetry(
                        latitude, longitude);
                if (!Double.isNaN(bathyElev) && bathyElev < -1.0)
                    return bathyElev;
                return -50;
            }
            double elev = getElevationBilinear(latitude, longitude, this.zoom);

            // A globally impossible value, or a deep value at a location OSM
            // already knows is land, must not become a terrain trench.  Use a
            // coarser parent tile instead. This is non-blocking: only the
            // in-memory coastline index is consulted.
            if (needsParentTileFallback(latitude, longitude, elev)) {
                double parent = getElevationBilinear(latitude, longitude,
                        Math.max(0, this.zoom - 1));
                if (isPlausibleParent(parent, elev)) {
                    LOGGER.warn("Elevation fallback at lat={} lon={}: {}m -> {}m",
                            String.format("%.5f", latitude),
                            String.format("%.5f", longitude),
                            String.format("%.1f", elev),
                            String.format("%.1f", parent));
                    parentFallbackCount.incrementAndGet();
                    elev = parent;
                }
            }
            boolean noData   = Double.isNaN(elev);
            boolean nearZero = !noData && elev > -1.0 && elev < 1.0;
            // ── OWNER Y RULE (2026-11-20) ─────────────────
            // New book >= Y65 / old book <= Y64 with the
            // transition gap -1..+5 m (see constants). Only
            // consulted when the new book actually answered
            // and reads low: a NaN keeps the old failure
            // ladder (the old book is not a general fallback
            // for missing new-book tiles), and land at/above
            // +5 m never pays an old-book fetch.
            boolean oldUsed = false;
            if (!noData && elev < NEW_FULL_ABOVE_M) {
                double old = getOldElevation(latitude, longitude);
                if (!Double.isNaN(old)) {
                    oldUsed = true;
                    if (elev <= OLD_FULL_BELOW_M) {
                        elev = old;
                        if (lowFullCount.getAndIncrement() < 5)
                            LOGGER.info("ELEV LOW [{}]: new book"
                                    + " below gap -> old book"
                                    + " {}m", tileKeyAt(latitude,
                                    longitude), String.format(
                                    "%.1f", elev));
                    } else {
                        double t = (elev - OLD_FULL_BELOW_M)
                                / (NEW_FULL_ABOVE_M - OLD_FULL_BELOW_M);
                        double wNew = t * t * (3.0 - 2.0 * t);
                        double capped = Math.min(old,
                                elev + GAP_MAX_UP_PULL_M);
                        elev = wNew * elev + (1.0 - wNew) * capped;
                        lowBlendCount.incrementAndGet();
                    }
                }
            }
            // Bundled grid: now only the fallback for when
            // the old book had no answer (or new-book data
            // is missing entirely) — same contract as before.
            if (!oldUsed && (noData || nearZero)) {
                double bathyElev = fetchBathymetry(latitude, longitude);
                if (!Double.isNaN(bathyElev) && bathyElev < -1.0) {
                    if (noData) elev = bathyElev;
                    else if (bathyElev < elev) elev = bathyElev;
                }
            }
            if (Double.isNaN(elev)) elev = -50;
            if (elevDebug.getAndIncrement() < 15)
                LOGGER.info("Elev: lat={} lon={} -> {}m",
                        String.format("%.4f", latitude),
                        String.format("%.4f", longitude),
                        String.format("%.1f", elev));
            return elev;
        } catch (Exception e) {
            return -50;
        }
    }

    public double getElevation(double lat, double lon) {
        return getElevationInterpolated(lat, lon);
    }

    private double fetchBathymetry(double latitude, double longitude) {
        // Single-source switch: ocean depth comes from the
        // bundled BathyData grid (GMRT/GEBCO synthesis),
        // not from a second live tile book. Same contract
        // as the old Terrarium zoom chain: a depth below
        // -1 m, or NaN when unknown.
        try {
            double c = BathyData.sample(latitude, longitude);
            if (Double.isNaN(c)) return Double.NaN;
            if (bathyDebug.getAndIncrement() < 20)
                LOGGER.info("Bathy: lat={} lon={} grid -> {}m",
                        String.format("%.4f", latitude),
                        String.format("%.4f", longitude),
                        String.format("%.1f", c));
            return c;
        } catch (Exception e) {
            LOGGER.debug("Bathy grid failed: {}", e.getMessage());
            return Double.NaN;
        }
    }

    // ── OLD BOOK (Terrarium) FOR THE Y RULE ───────────────
    // The old book speaks only in the low zone, and only
    // when it has something real to say:
    //  - its terrain-zoom sample outside -1..+1 m (low land
    //    surface, or a below-sea surface like Dead Sea), or
    //  - a real depth (< -1 m) from the coarse bathymetry
    //    ladder {8,7,6,5,3} when its terrain zoom reads the
    //    sea as flat ~0 (Terrarium carries no bathymetry at
    //    terrain zoom — depth was always the coarse chain's
    //    job, Mariana -10,390 m at z8 in the head-to-head).
    // A flat ~0 with no depth behind it returns NaN ("no
    // opinion") so the caller keeps the new book alone and
    // may still consult the bundled grid.

    private double getOldElevation(double latitude, double longitude) {
        try {
            double surf = getOldElevationBilinear(
                    latitude, longitude, this.zoom);
            if (!Double.isNaN(surf)) {
                if (surf > 1.0 || surf < -1.0) return surf;
                double bathy = getOldBathymetry(latitude, longitude);
                if (!Double.isNaN(bathy) && bathy < -1.0)
                    return bathy;
                return Double.NaN;
            }
            return getOldBathymetry(latitude, longitude);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** Old-book-only sampler (test mode): the old book's
     *  terrain-zoom surface accepted AS-IS — unlike the Y
     *  rule's low-zone opinion gate, a flat ~0 m sample is a
     *  real answer here (Terrarium reads the sea as flat 0
     *  and low flat land near it; the coarse bathymetry
     *  ladder still wins wherever it has a real depth, so
     *  offshore columns get depth instead of the flat 0).
     *  NaN only when the old book has nothing at all. */
    private double getOldElevationOnly(double latitude,
                                       double longitude) {
        try {
            double surf = getOldElevationBilinear(
                    latitude, longitude, this.zoom);
            if (!Double.isNaN(surf)) {
                if (surf > 1.0 || surf < -1.0) return surf;
                double bathy = getOldBathymetry(
                        latitude, longitude);
                if (!Double.isNaN(bathy) && bathy < -1.0)
                    return bathy;
                return surf;
            }
            return getOldBathymetry(latitude, longitude);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private double getOldBathymetry(double latitude, double longitude) {
        for (int zl : OLD_BATHY_ZOOMS) {
            double v = getOldElevationBilinear(latitude, longitude, zl);
            if (!Double.isNaN(v) && v < -1.0) return v;
        }
        return Double.NaN;
    }

    /** Bilinear sample from OLD book tiles; mirrors
     *  getElevationBilinear but reads the old caches. */
    private double getOldElevationBilinear(double latitude,
                                           double longitude,
                                           int zoomLevel) {
        int n = 1 << zoomLevel;
        double latRad = Math.toRadians(latitude);
        double xG = (longitude + 180.0) / 360.0 * n * TILE_SIZE;
        double yG = (1.0 - Math.log(Math.tan(latRad)
                + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n * TILE_SIZE;
        int x0 = (int) Math.floor(xG), y0 = (int) Math.floor(yG);
        double fx = xG - x0, fy = yG - y0;
        int ctx = x0 / TILE_SIZE, cty = y0 / TILE_SIZE;
        boolean crossX = ((x0 + 1) / TILE_SIZE) != ctx;
        boolean crossY = ((y0 + 1) / TILE_SIZE) != cty;
        double sfx = crossX ? 0.0 : fx;
        double sfy = crossY ? 0.0 : fy;
        double e00 = sampleOldPixel(x0,     y0,     n, zoomLevel);
        double e10 = crossX ? e00
                : sampleOldPixel(x0 + 1, y0,     n, zoomLevel);
        double e01 = crossY ? e00
                : sampleOldPixel(x0,     y0 + 1, n, zoomLevel);
        double e11 = (crossX || crossY) ? e00
                : sampleOldPixel(x0 + 1, y0 + 1, n, zoomLevel);
        double[] f = clusterFilter(new double[]{e00, e10, e01, e11});
        e00 = f[0]; e10 = f[1]; e01 = f[2]; e11 = f[3];
        if (Double.isNaN(e00) || Double.isNaN(e10)
                || Double.isNaN(e01) || Double.isNaN(e11))
            return Double.NaN;
        double top = e00 + (e10 - e00) * sfx;
        double bot = e01 + (e11 - e01) * sfx;
        return top + (bot - top) * sfy;
    }

    private double sampleOldPixel(int gx, int gy, int n, int zoomLevel) {
        int total = n * TILE_SIZE;
        gx = clamp(gx, 0, total - 1);
        gy = clamp(gy, 0, total - 1);
        BufferedImage tile = getOldTile(zoomLevel,
                gx / TILE_SIZE, gy / TILE_SIZE);
        if (tile == null) return Double.NaN;
        int px = gx % TILE_SIZE, py = gy % TILE_SIZE;
        if (px >= tile.getWidth() || py >= tile.getHeight())
            return Double.NaN;
        int rgb = tile.getRGB(px, py);
        int r = (rgb >> 16) & 0xFF,
            g = (rgb >>  8) & 0xFF,
            b =  rgb        & 0xFF;
        return (r * 256.0 + g + b / 256.0) - 32768.0;
    }

    private BufferedImage getOldTile(int zl, int tx, int ty) {
        String key = zl + "/" + tx + "/" + ty;
        BufferedImage c = oldTileCache.get(key);
        if (c == SENTINEL_FAILED) return null;
        if (c != null) return c;
        if (useDiskCache) {
            BufferedImage disk = loadOldFromDisk(key);
            if (disk != null) {
                oldTileCache.put(key, disk);
                return disk;
            }
        }
        CompletableFuture<BufferedImage> f = ensureOldFetch(key);
        try {
            return f.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Same slow-start cure as the new book: the
            // fetch keeps running and publishes its tile;
            // never drop the pending entry and duplicate it.
            return null;
        }
    }

    private CompletableFuture<BufferedImage> ensureOldFetch(String key) {
        return oldPendingTiles.computeIfAbsent(key, k -> {
            CompletableFuture<BufferedImage> f =
                CompletableFuture.supplyAsync(
                    () -> fetchOldTile(k), dlPool);
            f.whenComplete((img, ex) -> oldPendingTiles.remove(k, f));
            return f;
        });
    }

    /**
     * The old book's live fetch (Y rule, 2026-11-20):
     * Terrarium PNGs from the AWS elevation-tiles archive.
     * Tiles are validated/spike-guarded like new book
     * tiles, but the guard's zoom-anchor stage anchors old
     * pixels to the OLD book's own coarse parent (see
     * validateAndRepairTile's oldBook flag), which kills
     * Terrarium's flat-water monster pixels (Dead Sea
     * Sea -5,227 m, Caspian Sea -5,535 m in the
     * head-to-head) before they can enter generation.
     * Cached
     * in memory and on disk under Terrarium-y-v1 only.
     * Failure is honest: SENTINEL_FAILED + negative-cache
     * stamp; the sampler returns NaN and generation keeps
     * the new book / bundled grid answer.
     */
    private BufferedImage fetchOldTile(String key) {
        try {
            String[] p = key.split("/");
            int zl = Integer.parseInt(p[0]);
            int tx = Integer.parseInt(p[1]);
            int ty = Integer.parseInt(p[2]);
            HttpURLConnection conn = null;
            BufferedImage img = null;
            try {
                conn = (HttpURLConnection) new URL(String.format(
                        OLD_TILE_URL, zl, tx, ty)).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(20_000);
                conn.setRequestProperty("User-Agent",
                        "TerraPlusPlus/2.0 Minecraft");
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        img = ImageIO.read(is);
                    }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (img != null) {
                BufferedImage ok = validateAndRepairTile(img, key, true);
                if (ok != null) {
                    if (oldSrcDebug.getAndIncrement() < 10)
                        LOGGER.info("ELEV SRC OLD {}: Terrarium"
                                + " (low zone, Y rule)", key);
                    oldTileCache.put(key, ok);
                    oldTileFailedAt.remove(key);
                    oldTileSource.put(key, "Terrarium live");
                    if (useDiskCache) saveOldToDisk(key, ok);
                    return ok;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Old-book tile {} failed: {}",
                    key, e.getMessage());
        }
        oldTileCache.put(key, SENTINEL_FAILED);
        oldTileFailedAt.put(key, System.currentTimeMillis());
        oldTileSource.put(key, "FAILED");
        return null;
    }

    private BufferedImage loadOldFromDisk(String key) {
        try {
            File f = oldDiskCacheDir.resolve(
                    key.replace('/', File.separatorChar) + ".png").toFile();
            if (!f.exists() || f.length() <= 0) return null;
            BufferedImage image = ImageIO.read(f);
            if (image == null || image.getWidth() != TILE_SIZE
                    || image.getHeight() != TILE_SIZE) {
                Files.deleteIfExists(f.toPath());
                return null;
            }
            BufferedImage ok = validateAndRepairTile(image, key, true);
            if (ok != null)
                oldTileSource.put(key, "disk (Terrarium-y-v1)");
            return ok;
        } catch (Exception ignored) {}
        return null;
    }

    private void saveOldToDisk(String key, BufferedImage img) {
        try {
            File f = oldDiskCacheDir.resolve(
                    key.replace('/', File.separatorChar) + ".png").toFile();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            ImageIO.write(img, "PNG", f);
        } catch (Exception ignored) {}
    }

    private boolean oldDiskHasTile(String key) {
        try {
            File f = oldDiskCacheDir.resolve(
                    key.replace('/', File.separatorChar) + ".png").toFile();
            return f.exists() && f.length() > 0;
        } catch (Exception e) { return false; }
    }

    /** Old-book session counts for /terracheck buckets:
     *  "live Terrarium", "disk cache (Terrarium-y-v1)",
     *  "failed", "other". */
    public Map<String, Integer> oldTileSourceCounts() {
        Map<String, Integer> out = new HashMap<>();
        for (String s : oldTileSource.values()) {
            String bucket =
                    s.startsWith("Terrarium") ? "live Terrarium"
                    : s.startsWith("disk") ? "disk cache (Terrarium-y-v1)"
                    : s.equals("FAILED") ? "failed"
                    : "other";
            out.merge(bucket, 1, Integer::sum);
        }
        return out;
    }

    /** [full-old samples, gap-blend samples] this session. */
    public int[] lowBookStats() {
        return new int[]{ lowFullCount.get(), lowBlendCount.get() };
    }

    /** Samples served by the old book in old-book-only
     *  test mode (0 in normal Y-rule sessions). */
    public int oldOnlySamples() {
        return oldOnlyCount.get();
    }

    /** Number of old-book tile PNGs stored for the Y rule. */
    public long oldDiskTileCount() {
        try (var walk = Files.walk(oldDiskCacheDir)) {
            return walk.filter(p -> Files.isRegularFile(p)
                    && p.toString().endsWith(".png"))
                    .limit(500_000).count();
        } catch (Exception e) {
            return -1;
        }
    }

    // ── BILINEAR INTERPOLATION ────────────────────────────────
    private double getElevationBilinear(double latitude, double longitude,
                                         int zoomLevel) {
        int n = 1 << zoomLevel;
        double latRad = Math.toRadians(latitude);
        double xG = (longitude + 180.0) / 360.0 * n * TILE_SIZE;
        double yG = (1.0 - Math.log(Math.tan(latRad)
                + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n * TILE_SIZE;
        int x0 = (int) Math.floor(xG), y0 = (int) Math.floor(yG);
        double fx = xG - x0, fy = yG - y0;
        int ctx = x0 / TILE_SIZE, cty = y0 / TILE_SIZE;
        boolean crossX = ((x0 + 1) / TILE_SIZE) != ctx;
        boolean crossY = ((y0 + 1) / TILE_SIZE) != cty;
        double sfx = crossX ? 0.0 : fx;
        double sfy = crossY ? 0.0 : fy;
        double e00 = samplePixel(x0,     y0,     n, zoomLevel);
        double e10 = crossX ? e00
                : samplePixel(x0 + 1, y0,     n, zoomLevel);
        double e01 = crossY ? e00
                : samplePixel(x0,     y0 + 1, n, zoomLevel);
        double e11 = (crossX || crossY) ? e00
                : samplePixel(x0 + 1, y0 + 1, n, zoomLevel);
        double[] f = clusterFilter(new double[]{e00, e10, e01, e11});
        e00 = f[0]; e10 = f[1]; e01 = f[2]; e11 = f[3];
        if (Double.isNaN(e00) || Double.isNaN(e10)
                || Double.isNaN(e01) || Double.isNaN(e11))
            return Double.NaN;
        double top = e00 + (e10 - e00) * sfx;
        double bot = e01 + (e11 - e01) * sfx;
        return top + (bot - top) * sfy;
    }

    // ── CLUSTER FILTER ────────────────────────────────────────
    private static double[] clusterFilter(double[] s) {
        double[] v = new double[4];
        int vc = 0;
        for (double x : s) if (!Double.isNaN(x)) v[vc++] = x;
        if (vc < 2) return s;
        for (int i = 1; i < vc; i++) {
            double k = v[i]; int j = i - 1;
            while (j >= 0 && v[j] > k) { v[j + 1] = v[j]; j--; }
            v[j + 1] = k;
        }
        int bs = 0, be = 0, bsz = 1;
        double bm = v[0];
        int hs = -1, he = -1;
        double hm = Double.MIN_VALUE;
        int cs = 0;
        for (int ce = 1; ce < vc; ce++) {
            while (v[ce] - v[cs] > CLUSTER_THRESHOLD) cs++;
            int sz = ce - cs + 1;
            double sm = 0;
            for (int k = cs; k <= ce; k++) sm += v[k];
            double m = sm / sz;
            if (sz > bsz || (sz == bsz && m > bm)) {
                bs = cs; be = ce; bm = m; bsz = sz;
            }
            if (m > hm) { hs = cs; he = ce; hm = m; }
        }
        if (bm < ARTIFACT_LOW_CEILING
                && hm > ARTIFACT_HIGH_FLOOR && hs != bs) {
            bs = hs; be = he; bm = hm;
        }
        double cl = v[bs], ch = v[be];
        double[] r = new double[4];
        for (int i = 0; i < 4; i++) {
            if (Double.isNaN(s[i]))
                r[i] = Double.NaN;
            else if (s[i] < cl - 1e-6 || s[i] > ch + 1e-6)
                r[i] = bm;
            else
                r[i] = s[i];
        }
        return r;
    }

    // ── PIXEL / TILE ──────────────────────────────────────────
    private double samplePixel(int gx, int gy, int n, int zoomLevel) {
        int total = n * TILE_SIZE;
        gx = clamp(gx, 0, total - 1);
        gy = clamp(gy, 0, total - 1);
        BufferedImage tile = getTile(zoomLevel,
                gx / TILE_SIZE, gy / TILE_SIZE);
        if (tile == null) return Double.NaN;
        int px = gx % TILE_SIZE, py = gy % TILE_SIZE;
        if (px >= tile.getWidth() || py >= tile.getHeight())
            return Double.NaN;
        int rgb = tile.getRGB(px, py);
        int r = (rgb >> 16) & 0xFF,
            g = (rgb >>  8) & 0xFF,
            b =  rgb        & 0xFF;
        return (r * 256.0 + g + b / 256.0) - 32768.0;
    }

    // ── ELEVATION PREFETCH (teleport/terrain fast-path) ──────
    // Terrain generation speed IS elevation-tile speed: every
    // column asks getElevation*, and on a cold cache each
    // missing tile blocked a worker for up to 3s (f.get(3s)),
    // one tile at a time. This warms every tile covering the
    // bbox (terrain zoom + first bathy zoom) in parallel on
    // dlPool, fire-and-forget. Called from warmDestination at
    // /tpll time, so by the time chunk workers need heights the
    // answers are memory hits. Dedup via pendingTiles/tileCache.
    public int prefetchArea(double minLat, double maxLat,
                            double minLon, double maxLon) {
        int fired = 0;
        try {
            // terrain zoom + spike-guard anchor parent
            // (zoom-4). The bundled bathy grid needs no
            // prefetch (already in memory); the old book's
            // low-zone tiles are warmed separately below.
            // (Before the single-source switch this list
            // also warmed Terrarium tiles through a separate
            // download path, which could poison the terrain
            // zoom with old-book tiles: two books, one map.)
            int[] zooms = OLD_BOOK_ONLY
                    ? new int[0]
                    : this.zoom - 4 >= 8
                    ? new int[]{ this.zoom, this.zoom - 4 }
                    : new int[]{ this.zoom };
            for (int zl : zooms) {
                int n = 1 << zl;
                int txMin = lonToTileX(minLon, n), txMax = lonToTileX(maxLon, n);
                int tyMin = latToTileY(maxLat, n), tyMax = latToTileY(minLat, n);
                // clamp runaway boxes (projection edge cases)
                if ((long)(txMax - txMin + 1) * (tyMax - tyMin + 1) > 128) continue;
                for (int tx = txMin; tx <= txMax; tx++) {
                    for (int ty = tyMin; ty <= tyMax; ty++) {
                        String key = zl + "/" + tx + "/" + ty;
                        if (tileCache.containsKey(key)) continue;
                        if (useDiskCache && diskHasTile(key)) continue;
                        final String k = key;
                        pendingTiles.computeIfAbsent(k,
                                kk -> {
                                    CompletableFuture<BufferedImage> f =
                                        CompletableFuture.supplyAsync(
                                            () -> fetchMapterhornTile(kk), dlPool);
                                    // self-clean: prefetch futures may never
                                    // be get()-ed by getTile, so remove on
                                    // completion (fetchMapterhornTile already
                                    // put the result into tileCache).
                                    f.whenComplete((img, ex) ->
                                        pendingTiles.remove(kk, f));
                                    return f;
                                });
                        fired++;
                    }
                }
            }
            // Y rule: warm the OLD book over the same area
            // (terrain zoom + first bathy zoom), so low and
            // coastal chunks find it resident instead of
            // meeting the 3 s getOldTile wait column by
            // column on a cold cache.
            fired += prefetchOldTiles(minLat, maxLat, minLon, maxLon);
            if (fired > 0)
                LOGGER.info("ELEV PREFETCH [{}..{},{}..{}]: {} height tiles "
                        + "dispatched in parallel",
                        String.format("%.4f", minLat), String.format("%.4f", maxLat),
                        String.format("%.4f", minLon), String.format("%.4f", maxLon),
                        fired);
        } catch (Exception ignored) {}
        return fired;
    }

    /** Fire-and-forget old-book warm-up for a bbox (terrain
     *  zoom + z8 bathy). Dedup via oldTileCache/oldPending;
     *  disk hits are left for getOldTile's synchronous load. */
    private int prefetchOldTiles(double minLat, double maxLat,
                                 double minLon, double maxLon) {
        int fired = 0;
        try {
            int[] zooms = this.zoom == 8
                    ? new int[]{ this.zoom }
                    : new int[]{ this.zoom, 8 };
            for (int zl : zooms) {
                int n = 1 << zl;
                int txMin = lonToTileX(minLon, n), txMax = lonToTileX(maxLon, n);
                int tyMin = latToTileY(maxLat, n), tyMax = latToTileY(minLat, n);
                if ((long)(txMax - txMin + 1) * (tyMax - tyMin + 1) > 128) continue;
                for (int tx = txMin; tx <= txMax; tx++)
                    for (int ty = tyMin; ty <= tyMax; ty++) {
                        String key = zl + "/" + tx + "/" + ty;
                        if (oldTileCache.containsKey(key)) continue;
                        if (useDiskCache && oldDiskHasTile(key)) continue;
                        if (oldPendingTiles.containsKey(key)) continue;
                        ensureOldFetch(key);
                        fired++;
                    }
            }
        } catch (Exception ignored) {}
        return fired;
    }

    private static int lonToTileX(double lon, int n) {
        int t = (int) Math.floor((lon + 180.0) / 360.0 * n);
        return Math.max(0, Math.min(n - 1, t));
    }

    private static int latToTileY(double lat, int n) {
        double latRad = Math.toRadians(Math.max(-85, Math.min(85, lat)));
        int t = (int) Math.floor((1.0 - Math.log(Math.tan(latRad)
                + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
        return Math.max(0, Math.min(n - 1, t));
    }

    private boolean diskHasTile(String key) {
        try {
            File f = diskCacheDir.resolve(
                    key.replace('/', File.separatorChar) + ".png").toFile();
            return f.exists() && f.length() > 0;
        } catch (Exception e) { return false; }
    }

    private BufferedImage getTile(int zl, int tx, int ty) {
        String key = zl + "/" + tx + "/" + ty;
        BufferedImage c = tileCache.get(key);
        if (c == SENTINEL_FAILED) return null;
        if (c != null) return c;
        // SINGLE SOURCE (2026-11-20): every zoom that
        // reaches this method — terrain, parent fallback
        // (zoom-1), spike-guard anchor (zoom-4) — comes
        // from Mapterhorn. There is no second book any
        // more: a tile Mapterhorn cannot serve is a failed
        // tile, and heights fall back through the normal
        // parent/bathymetry chain instead of silently
        // mixing sources inside one landscape.
        if (useDiskCache) {
            BufferedImage disk = loadFromDisk(key);
            if (disk != null) { tileCache.put(key, disk); return disk; }
        }
        CompletableFuture<BufferedImage> f = ensureFetch(key);
        try {
            return f.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Timeout: the fetch is still running and will
            // put the real tile (or SENTINEL_FAILED) into
            // tileCache when it finishes. Do NOT drop the
            // pending entry here — the next caller would
            // launch a duplicate download of the same tile,
            // and awaitTilesForChunk could no longer join
            // the in-flight fetch. (Same lesson as the
            // OsmWaterIndex slow-start cure.)
            return null;
        }
    }

    /** Start (or join) the async fetch for a tile key. The
     *  future self-cleans from pendingTiles on completion;
     *  fetchMapterhornTile itself publishes the result into
     *  tileCache. */
    private CompletableFuture<BufferedImage> ensureFetch(String key) {
        return pendingTiles.computeIfAbsent(key, k -> {
            CompletableFuture<BufferedImage> f =
                CompletableFuture.supplyAsync(
                    () -> fetchMapterhornTile(k), dlPool);
            f.whenComplete((img, ex) -> pendingTiles.remove(k, f));
            return f;
        });
    }

    /**
     * Generation readiness gate (NW23 fossil cure): block
     * until every terrain-zoom tile covering the block box
     * [minBlockX-24, minBlockX+40) x [minBlockZ-24, minBlockZ+40)
     * (one chunk plus the generator's 24-block sampling
     * border) is resident in tileCache, or the deadline
     * passes. Returns how many tiles are still missing.
     *
     * Why: getTile waits only 3 s per tile; on a cold cache
     * a slower fetch made the first chunks classify from
     * the parent-zoom/bathymetry fallback ladder and save
     * those heights forever (the "elevated chunk" fossil
     * class). Teleport prefetch already starts these
     * fetches — this gate simply refuses to build terrain
     * from provisional answers while they are in flight.
     * Warm chunks return immediately (memory hits only).
     * A tile whose fetch failed within the last 30 s is
     * counted missing WITHOUT refetching (negative cache),
     * so a dead tile server degrades to today's behaviour
     * plus a loud log line, never to a retry storm.
     */
    public int awaitTilesForChunk(int minBlockX, int minBlockZ,
                                  long deadlineMs) {
        if (!projectionOk || projection == null) return 0;
        try {
            double[] g1 = toGeo(minBlockX - 24, minBlockZ - 24);
            double[] g2 = toGeo(minBlockX + 40, minBlockZ + 40);
            double[] g3 = toGeo(minBlockX - 24, minBlockZ + 40);
            double[] g4 = toGeo(minBlockX + 40, minBlockZ - 24);
            if (g1 == null || g2 == null || g3 == null || g4 == null)
                return 0;
            double mnLa = Math.min(Math.min(g1[1], g2[1]),
                    Math.min(g3[1], g4[1]));
            double mxLa = Math.max(Math.max(g1[1], g2[1]),
                    Math.max(g3[1], g4[1]));
            double mnLo = Math.min(Math.min(g1[0], g2[0]),
                    Math.min(g3[0], g4[0]));
            double mxLo = Math.max(Math.max(g1[0], g2[0]),
                    Math.max(g3[0], g4[0]));
            int n = 1 << this.zoom;
            int txMin = lonToTileX(mnLo, n), txMax = lonToTileX(mxLo, n);
            int tyMin = latToTileY(mxLa, n), tyMax = latToTileY(mnLa, n);
            // Projection folds can produce absurd boxes;
            // fall back to the centre tile's 3x3.
            if (txMax - txMin > 3 || tyMax - tyMin > 3) {
                double[] gc = toGeo(minBlockX + 8, minBlockZ + 8);
                if (gc == null) return 0;
                txMin = txMax = lonToTileX(gc[0], n);
                tyMin = tyMax = latToTileY(gc[1], n);
            }
            long deadline = System.currentTimeMillis() + deadlineMs;
            int missing = 0;
            List<CompletableFuture<BufferedImage>> waitOn =
                    new ArrayList<>();
            List<String> waitKeys = new ArrayList<>();
            // OLD BOOK ONLY (test): no new-book tiles are
            // used at all, so waiting for them would stall
            // cold chunks on downloads that never serve a
            // single column.
            if (!OLD_BOOK_ONLY)
            for (int tx = txMin; tx <= txMax; tx++)
                for (int ty = tyMin; ty <= tyMax; ty++) {
                    String key = this.zoom + "/" + tx + "/" + ty;
                    BufferedImage c = tileCache.get(key);
                    if (c != null && c != SENTINEL_FAILED) continue;
                    Long failedAt = tileFailedAt.get(key);
                    boolean fetchRunning = pendingTiles.containsKey(key);
                    if (c == SENTINEL_FAILED && !fetchRunning
                            && failedAt != null
                            && System.currentTimeMillis() - failedAt
                                    < 30_000L) {
                        missing++;
                        continue;
                    }
                    if (useDiskCache && !fetchRunning) {
                        BufferedImage disk = loadFromDisk(key);
                        if (disk != null) {
                            tileCache.put(key, disk);
                            continue;
                        }
                    }
                    waitOn.add(ensureFetch(key));
                    waitKeys.add(key);
                }
            // ── OWNER Y RULE: low chunks also wait for the
            // old book. A chunk whose centre reads below the
            // gap top (< +5 m) will blend/fetch old-book
            // tiles while generating; if it started before
            // they land, its first columns would be new-book
            // only and its later columns blended — a seam
            // inside one chunk. So join the old fetches
            // (terrain zoom over the box + the z8 bathy tile)
            // on the same deadline. High chunks skip this
            // entirely: they never touch the old book.
            List<String> oldWaitKeys = new ArrayList<>();
            try {
                double[] gc = toGeo(minBlockX + 8, minBlockZ + 8);
                if (gc != null) {
                    double ce = getElevationFast(gc[1], gc[0]);
                    if (OLD_BOOK_ONLY || ce < NEW_FULL_ABOVE_M) {
                        for (int tx = txMin; tx <= txMax; tx++)
                            for (int ty = tyMin; ty <= tyMax; ty++) {
                                String key = this.zoom + "/" + tx + "/" + ty;
                                BufferedImage c = oldTileCache.get(key);
                                if (c != null) continue;
                                if (useDiskCache
                                        && !oldPendingTiles.containsKey(key)) {
                                    BufferedImage disk =
                                            loadOldFromDisk(key);
                                    if (disk != null) {
                                        oldTileCache.put(key, disk);
                                        continue;
                                    }
                                }
                                waitOn.add(ensureOldFetch(key));
                                oldWaitKeys.add(key);
                            }
                        int n8 = 1 << 8;
                        int bxMin = lonToTileX(mnLo, n8),
                            bxMax = lonToTileX(mxLo, n8);
                        int byMin = latToTileY(mxLa, n8),
                            byMax = latToTileY(mnLa, n8);
                        for (int tx = bxMin; tx <= bxMax; tx++)
                            for (int ty = byMin; ty <= byMax; ty++) {
                                String key = "8/" + tx + "/" + ty;
                                BufferedImage c = oldTileCache.get(key);
                                if (c != null) continue;
                                if (useDiskCache
                                        && !oldPendingTiles.containsKey(key)) {
                                    BufferedImage disk =
                                            loadOldFromDisk(key);
                                    if (disk != null) {
                                        oldTileCache.put(key, disk);
                                        continue;
                                    }
                                }
                                waitOn.add(ensureOldFetch(key));
                                oldWaitKeys.add(key);
                            }
                    }
                }
            } catch (Exception ignored) {}
            for (int i = 0; i < waitOn.size(); i++) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                try {
                    waitOn.get(i).get(rem, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
            }
            // Final recount over every tile we waited on, so a
            // deadline that expired mid-join still reports the
            // chunk honestly instead of under-counting.
            for (String key : waitKeys) {
                BufferedImage c = tileCache.get(key);
                if (c == null || c == SENTINEL_FAILED) missing++;
            }
            for (String key : oldWaitKeys) {
                BufferedImage c = oldTileCache.get(key);
                if (c == null || c == SENTINEL_FAILED) missing++;
            }
            return missing;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Decoded pixels of the OLD book's zoom-4 parent tile
     * containing the fine old tile (zl, tx, ty), or null if
     * unavailable. Mirrors {@link #coarsePixelsForAnchor} but
     * through the old caches/fetcher, so old tiles are
     * spike-guarded against their own book. Synchronous on
     * the calling tile thread (no executor futures — the
     * coarse fetch validates without anchoring again below
     * zoom 13, and anchors chain at most a few zooms, so
     * this cannot deadlock or recurse without bound).
     */
    private double[] coarseOldPixelsForAnchor(int zl, int tx, int ty) {
        int cz = zl - 4;
        if (cz < 3) return null;
        String key = cz + "/" + (tx >> 4) + "/" + (ty >> 4);
        try {
            BufferedImage img = oldTileCache.get(key);
            if (img == SENTINEL_FAILED) return null;
            if (img == null && useDiskCache) {
                img = loadOldFromDisk(key);
                if (img != null) oldTileCache.put(key, img);
            }
            if (img == null) img = fetchOldTile(key);
            if (img == null || img.getWidth() != TILE_SIZE
                    || img.getHeight() != TILE_SIZE) return null;
            double[] px = new double[TILE_SIZE * TILE_SIZE];
            for (int y = 0; y < TILE_SIZE; y++) for (int x = 0; x < TILE_SIZE; x++)
                px[y * TILE_SIZE + x] = decodeTerrarium(img.getRGB(x, y));
            return px;
        } catch (Exception e) {
            return null;
        }
    }

    // ── ELEVATION SOURCE REPORT (for /terracheck) ────────
    // The owner asked /terracheck to also check elevation
    // sources: prove, in-game, which book each height tile
    // came from (new book everywhere, old book only in the
    // Y rule's low zone) and nowhere else.

    /** Terrain-zoom tile key covering (lat, lon). */
    public String tileKeyAt(double lat, double lon) {
        int n = 1 << this.zoom;
        return this.zoom + "/" + lonToTileX(lon, n)
                + "/" + latToTileY(lat, n);
    }

    /** Where the tile covering (lat, lon) came from, or
     *  "not loaded yet" if no fetch has touched it. */
    public String tileSourceAt(double lat, double lon) {
        String s = tileSource.get(tileKeyAt(lat, lon));
        return s != null ? s : "not loaded yet";
    }

    /** Session counts of cached tiles by origin bucket:
     *  "live Mapterhorn", "disk cache (mapterhorn-v2)",
     *  "failed", and (never expected) "other". */
    public Map<String, Integer> tileSourceCounts() {
        Map<String, Integer> out = new HashMap<>();
        for (String s : tileSource.values()) {
            String bucket =
                    s.startsWith("mapterhorn") ? "live Mapterhorn"
                    : s.startsWith("disk") ? "disk cache (mapterhorn-v2)"
                    : s.equals("FAILED") ? "failed"
                    : "other";
            out.merge(bucket, 1, Integer::sum);
        }
        return out;
    }

    /** Times the parent-zoom fallback replaced a terrain
     *  sample this session (0 in warm, healthy areas). */
    public int parentFallbackCount() {
        return parentFallbackCount.get();
    }

    /** Folder holding this build's validated tile PNGs. */
    public Path diskCacheDir() {
        return diskCacheDir;
    }

    /** Parent "tiles" folder (legacy books live here too;
     *  /terracheck reports them as ignored leftovers). */
    public Path tilesRootDir() {
        return diskCacheDir.getParent();
    }

    /** Number of tile PNGs currently stored for this build
     *  (-1 when the disk cache is off or unreadable). */
    public long diskTileCount() {
        if (!useDiskCache) return -1;
        try (var walk = Files.walk(diskCacheDir)) {
            return walk.filter(p -> Files.isRegularFile(p)
                    && p.toString().endsWith(".png"))
                    .limit(500_000).count();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The NEW book's live height fetch: Mapterhorn (regional
     * high-res archive, else the Copernicus planet base,
     * derived to the requested zoom). Validated tiles are
     * cached in memory AND on disk under the mapterhorn-v2
     * folder — repeat visits and restarts no longer
     * re-download. (Single-source switch 2026-11-20; the
     * old book returned later the same day ONLY for the
     * owner Y rule's low zone — see fetchOldTile — through
     * its own caches, so the books still cannot mix inside
     * a cache.) Failure is honest: SENTINEL_FAILED, and
     * callers fall back (parent zoom / bathymetry grid /
     * -50) instead of silently substituting another book
     * at full height.
     */
    private BufferedImage fetchMapterhornTile(String key) {
        try {
            String[] p = key.split("/");
            int zl = Integer.parseInt(p[0]);
            int tx = Integer.parseInt(p[1]);
            int ty = Integer.parseInt(p[2]);
            MapterhornTiles.Result r =
                    MapterhornTiles.fetchTile256(zl, tx, ty);
            if (r != null && r.image != null) {
                BufferedImage ok = validateAndRepairTile(r.image, key);
                if (ok != null) {
                    if (maptSrcDebug.getAndIncrement() < 10)
                        LOGGER.info("ELEV SRC {}: mapterhorn ({})",
                                key, r.source);
                    tileCache.put(key, ok);
                    tileFailedAt.remove(key);
                    tileSource.put(key,
                            "mapterhorn (" + r.source + ")");
                    if (useDiskCache) saveToDisk(key, ok);
                    return ok;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Mapterhorn tile {} failed: {}",
                    key, e.getMessage());
        }
        tileCache.put(key, SENTINEL_FAILED);
        tileFailedAt.put(key, System.currentTimeMillis());
        tileSource.put(key, "FAILED");
        return null;
    }

    private BufferedImage loadFromDisk(String key) {
        try {
            File f = diskCacheDir.resolve(
                    key.replace('/', File.separatorChar) + ".png").toFile();
            if (!f.exists() || f.length() <= 0) return null;
            BufferedImage image = ImageIO.read(f);
            if (image == null || image.getWidth() != TILE_SIZE
                    || image.getHeight() != TILE_SIZE) {
                // Unreadable cache entry: delete so it cannot poison restarts.
                Files.deleteIfExists(f.toPath());
                return null;
            }
            // Disk entries are validated Mapterhorn tiles
            // (mapterhorn-v2 folder). Validation is re-run
            // so the spike guard setting of THIS session
            // applies; repairing is idempotent.
            BufferedImage okDisk = validateAndRepairTile(image, key);
            if (okDisk != null)
                tileSource.put(key, "disk (mapterhorn-v2)");
            return okDisk;
        } catch (Exception ignored) {}
        return null;
    }

    private void saveToDisk(String key, BufferedImage img) {
        try {
            File f = diskCacheDir.resolve(
                    key.replace('/', File.separatorChar) + ".png").toFile();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            ImageIO.write(img, "PNG", f);
        } catch (Exception ignored) {}
    }

    /**
     * Validates a Terrarium tile once at cache time and, while the
     * height spike guard is on (/terra heights guard on|off), cleans
     * its decoded samples in memory via {@link HeightSpikeGuard}.
     * The archived tile on disk is never touched here; with the
     * guard off the tile is returned exactly as downloaded (only
     * malformed tiles are still rejected - a wrong-sized image
     * would break the sampler's assumptions).
     */
    private BufferedImage validateAndRepairTile(BufferedImage image, String key) {
        return validateAndRepairTile(image, key, false);
    }

    /** @param oldBook true for OLD book tiles: the guard's
     *  zoom-anchor stage then anchors to the OLD book's own
     *  coarse tile instead of the new book's. In Y-rule runs
     *  this keeps the blend honest; in old-book-only runs it
     *  is what makes the test pure (no new-book pixels can
     *  enter even through repaired spikes). */
    private BufferedImage validateAndRepairTile(BufferedImage image, String key,
                                                boolean oldBook) {
        if (image == null || image.getWidth() != TILE_SIZE
                || image.getHeight() != TILE_SIZE) {
            LOGGER.warn("Rejected malformed elevation tile {}", key);
            return null;
        }
        if (!HeightSpikeGuard.enabled) return image;

        double[] elevation = new double[TILE_SIZE * TILE_SIZE];
        for (int y = 0; y < TILE_SIZE; y++) for (int x = 0; x < TILE_SIZE; x++)
            elevation[y * TILE_SIZE + x] = decodeTerrarium(image.getRGB(x, y));

        // Stage 2 (zoom-anchor) needs the coarse parent tile. Only
        // fine tiles get anchored; coarse tiles are anchors
        // themselves. A missing parent simply skips the stage.
        double[] coarse = null;
        int fineTx = 0, fineTy = 0;
        try {
            String[] p = key.split("/");
            int zl = Integer.parseInt(p[0]);
            fineTx = Integer.parseInt(p[1]);
            fineTy = Integer.parseInt(p[2]);
            if (zl >= 13) coarse = oldBook
                    ? coarseOldPixelsForAnchor(zl, fineTx, fineTy)
                    : coarsePixelsForAnchor(zl, fineTx, fineTy);
        } catch (Exception ignored) {}

        HeightSpikeGuard.Outcome o = HeightSpikeGuard.repair(
                elevation, TILE_SIZE, coarse,
                (fineTx & 15) * 16, (fineTy & 15) * 16);
        if (o.impossible > TILE_SIZE * TILE_SIZE * QUARANTINE_REPAIR_FRACTION) {
            LOGGER.warn("Rejected tile {}: {} globally impossible samples", key, o.impossible);
            return null;
        }
        int repaired = o.total();
        if (repaired > TILE_SIZE * TILE_SIZE * QUARANTINE_REPAIR_FRACTION) {
            LOGGER.warn("Rejected tile {}: repaired {} suspect samples", key, repaired);
            return null;
        }
        if (repaired == 0) return image;
        for (int y = 0; y < TILE_SIZE; y++) for (int x = 0; x < TILE_SIZE; x++)
            image.setRGB(x, y, encodeTerrarium(elevation[y * TILE_SIZE + x]));
        LOGGER.warn("Height guard repaired {} suspect elevation samples in tile {} ({} by zoom-anchor)",
                repaired, key, o.anchorRepaired);
        return image;
    }

    /**
     * Decoded pixels of the zoom-4 parent tile containing the fine
     * tile (zl, tx, ty), or null if unavailable. Runs synchronously
     * on the calling tile thread - deliberately no executor futures,
     * so a tile download can never deadlock waiting for its parent.
     * The parent is loaded through the normal path (and is itself
     * median-repaired there; at zoom <= 12 it never anchors, so
     * there is no recursion).
     */
    private double[] coarsePixelsForAnchor(int zl, int tx, int ty) {
        int cz = zl - 4;
        if (cz < 3) return null;
        String key = cz + "/" + (tx >> 4) + "/" + (ty >> 4);
        try {
            BufferedImage img = tileCache.get(key);
            if (img == SENTINEL_FAILED) return null;
            if (img == null && useDiskCache) {
                img = loadFromDisk(key);
                if (img != null) tileCache.put(key, img);
            }
            if (img == null) img = fetchMapterhornTile(key);
            if (img == null || img.getWidth() != TILE_SIZE
                    || img.getHeight() != TILE_SIZE) return null;
            double[] px = new double[TILE_SIZE * TILE_SIZE];
            for (int y = 0; y < TILE_SIZE; y++) for (int x = 0; x < TILE_SIZE; x++)
                px[y * TILE_SIZE + x] = decodeTerrarium(img.getRGB(x, y));
            return px;
        } catch (Exception e) {
            return null;
        }
    }

    private static double decodeTerrarium(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return r * 256.0 + g + b / 256.0 - 32768.0;
    }

    private static int encodeTerrarium(double meters) {
        double encoded = Math.max(0.0, Math.min(65535.99609375, meters + 32768.0));
        int r = (int) (encoded / 256.0);
        encoded -= r * 256.0;
        int g = (int) encoded;
        int b = (int) Math.round((encoded - g) * 256.0);
        if (b == 256) { b = 0; if (++g == 256) { g = 0; r = Math.min(255, r + 1); } }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static boolean isGloballyPlausible(double elevation) {
        return Double.isFinite(elevation) && elevation >= MIN_EARTH_ELEVATION
                && elevation <= MAX_EARTH_ELEVATION;
    }

    private boolean needsParentTileFallback(double lat, double lon, double elevation) {
        if (!isGloballyPlausible(elevation)) return true;
        if (elevation >= DRY_LAND_MIN_ELEV) return false;
        OsmWaterIndex water = osmWater;
        return water != null && water.classifyCoastlineMemOnly(lat, lon)
                == OsmWaterIndex.CoastSide.LAND;
    }

    private static boolean isPlausibleParent(double parent, double original) {
        if (!isGloballyPlausible(parent)) return false;
        // Any plausible parent fixes impossible source data. For an inland deep
        // reading, only accept a parent that is not another deep-ocean value.
        return !isGloballyPlausible(original) || parent >= DRY_LAND_MIN_ELEV;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public void shutdown() { dlPool.shutdown(); }
}