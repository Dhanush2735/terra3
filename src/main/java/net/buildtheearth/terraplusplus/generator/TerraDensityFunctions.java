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
    private static final String TILE_URL =
            "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/";
    private static final int TILE_SIZE = 256, TIMEOUT = 10000, MAX_RETRY = 2;
    private static final int[] BATHY_ZOOM_CHAIN = {8, 7, 6, 5, 3};
    private static final double CLUSTER_THRESHOLD = 150.0,
            ARTIFACT_LOW_CEILING  = 10.0,
            ARTIFACT_HIGH_FLOOR   = 100.0;
    private static final BufferedImage SENTINEL_FAILED =
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

    // ── COASTLINE THRESHOLDS ──────────────────────────────────
    // Used ONLY for elevation fallback when OSM tiles not loaded.
    // COASTLINE terrain type is never produced by classify().
    private static final double COAST_LAND_THRESHOLD  =  2.0;
    private static final double COAST_OCEAN_THRESHOLD = -1.0;

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

    private final int zoom;
    private final GeographicProjection projection;
    private final boolean projectionOk;
    private final ConcurrentHashMap<String, BufferedImage> tileCache;
    private final ConcurrentHashMap<String, CompletableFuture<BufferedImage>>
            pendingTiles;
    private final ConcurrentHashMap<Long, Double> elevCache;
    private static final int ELEV_CACHE_MAX = 32768;
    private final Path diskCacheDir;
    private final boolean useDiskCache;
    private final ExecutorService dlPool;

    private final AtomicInteger elevDebug     = new AtomicInteger(0);
    private final AtomicInteger bathyDebug    = new AtomicInteger(0);
    private final AtomicInteger belowSeaDebug = new AtomicInteger(0);
    private final AtomicInteger classifyDebug = new AtomicInteger(0);

    // ── CONSTRUCTOR ───────────────────────────────────────────
    public TerraDensityFunctions(int zoom) {
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

        Path dir = Paths.get("terra_cache", "tiles");
        boolean diskOk = false;
        try {
            Files.createDirectories(dir);
            diskOk = true;
        } catch (Exception e) {
            LOGGER.warn("Disk cache failed: {}", e.getMessage());
        }
        this.diskCacheDir = dir;
        this.useDiskCache = diskOk;
        this.dlPool = Executors.newFixedThreadPool(
                Math.max(2, Math.min(4,
                        Runtime.getRuntime().availableProcessors())),
                r -> {
                    Thread t = new Thread(r, "Terra++Tile");
                    t.setDaemon(true);
                    return t;
                });
        LOGGER.info("TerraDensityFunctions: zoom={} projection={} "
                        + "coastLand={}m coastOcean={}m snap={}m",
                zoom, ok, COAST_LAND_THRESHOLD,
                COAST_OCEAN_THRESHOLD, SEA_LEVEL_SNAP);
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
            return projection.toGeo((double) blockX, (double) blockZ);
        } catch (OutOfProjectionBoundsException e) {
            return null;
        }
    }

    public double[] fromGeo(double lat, double lon) {
        if (projection == null) return null;
        try {
            return projection.fromGeo(lon, lat);
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
            this.type            = t;
            this.elevationMeters = e;
            this.surfaceY        = s;
            this.latitude        = lat;
            this.longitude       = lon;
            this.invalidReason   = r;
            this.waterBody       = wb;
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
            double[] ll = projection.toGeo(
                    (double) blockX, (double) blockZ);
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
                        elev, latitude, longitude); }
                case OCEAN     -> { return classifyAsOcean(
                        elev, latitude, longitude); }
                case COASTLINE -> { return classifyAsLandFromCoastline(
                        elev, latitude, longitude); }
                case UNKNOWN   -> { /* fall through */ }
            }
        }

        // ── STEP 2: ELEVATION FALLBACK ────────────────────────
        // No water query here. Just land vs ocean from elevation.
        if (elev >= COAST_OCEAN_THRESHOLD)
            return classifyAsLand(elev, latitude, longitude);
        else
            return classifyAsOcean(elev, latitude, longitude);
    }

    // ── INTERNAL CLASSIFY HELPERS ─────────────────────────────
    private TerrainColumn invalidColumn(InvalidReason r) {
        return new TerrainColumn(TerrainType.OCEAN, -4000,
                elevMetersToSurfaceY(-4000, false), 0, 0, r);
    }

    // On the OSM coastline boundary — treat as LAND.
    // No water query. surfaceY from elevation only.
    private TerrainColumn classifyAsLandFromCoastline(double elev,
                                                       double lat,
                                                       double lon) {
        int surfY = elevMetersToSurfaceY(elev, true);
        return new TerrainColumn(TerrainType.LAND, elev, surfY, lat, lon);
    }

    // Always returns LAND. Never COASTLINE.
    // surfaceY always from elevMetersToSurfaceY.
    private TerrainColumn classifyAsLand(double elev,
                                          double lat, double lon) {
        int surfY = elevMetersToSurfaceY(elev, true);

        if (belowSeaDebug.getAndIncrement() < 15 && elev < -1.0)
            LOGGER.info("DRY BASIN (LAND): lat={} lon={} elev={}m -> Y={}",
                    String.format("%.4f", lat),
                    String.format("%.4f", lon),
                    String.format("%.1f", elev), surfY);

        return new TerrainColumn(TerrainType.LAND, elev, surfY, lat, lon);
    }

    private TerrainColumn classifyAsOcean(double elev,
                                           double lat, double lon) {
        double oe = elev >= -0.5 ? -5.0 : elev;
        int floorY = elevMetersToSurfaceY(oe, false);
        return new TerrainColumn(
                oe >= -200.0 ? TerrainType.SHALLOW_WATER : TerrainType.OCEAN,
                oe, floorY, lat, lon);
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
        if (Double.isNaN(latitude)  || Double.isNaN(longitude)
                || latitude  <= -85 || latitude  >= 85
                || longitude < -180 || longitude > 180) return -50;
        try {
            double elev = getElevationBilinear(latitude, longitude, this.zoom);
            boolean noData   = Double.isNaN(elev);
            boolean nearZero = !noData && elev > -1.0 && elev < 1.0;
            if (noData || nearZero) {
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
        for (int bz : BATHY_ZOOM_CHAIN) {
            try {
                double c = getElevationBilinear(latitude, longitude, bz);
                if (Double.isNaN(c)) continue;
                if (bathyDebug.getAndIncrement() < 20)
                    LOGGER.info("Bathy: lat={} lon={} z={} -> {}m",
                            String.format("%.4f", latitude),
                            String.format("%.4f", longitude),
                            bz, String.format("%.1f", c));
                if (c < -1.0) return c;
            } catch (Exception e) {
                LOGGER.debug("Bathy z{} failed: {}", bz, e.getMessage());
            }
        }
        return Double.NaN;
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

    private BufferedImage getTile(int zl, int tx, int ty) {
        String key = zl + "/" + tx + "/" + ty;
        BufferedImage c = tileCache.get(key);
        if (c == SENTINEL_FAILED) return null;
        if (c != null) return c;
        if (useDiskCache) {
            BufferedImage disk = loadFromDisk(key);
            if (disk != null) { tileCache.put(key, disk); return disk; }
        }
        CompletableFuture<BufferedImage> f = pendingTiles.computeIfAbsent(
                key,
                k -> CompletableFuture.supplyAsync(
                        () -> downloadTile(k), dlPool));
        try {
            BufferedImage res = f.get(3, TimeUnit.SECONDS);
            pendingTiles.remove(key);
            return res;
        } catch (Exception e) {
            pendingTiles.remove(key);
            tileCache.put(key, SENTINEL_FAILED);
            return null;
        }
    }

    private BufferedImage downloadTile(String key) {
        for (int att = 1; att <= MAX_RETRY; att++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection)
                        new URL(TILE_URL + key + ".png").openConnection();
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setRequestProperty("User-Agent", "TerraPlusPlus/2.0");
                int sc = conn.getResponseCode();
                if (sc == 200) {
                    BufferedImage img;
                    try (InputStream is = conn.getInputStream()) {
                        img = ImageIO.read(is);
                    }
                    if (img != null) {
                        tileCache.put(key, img);
                        if (useDiskCache) saveToDisk(key, img);
                        return img;
                    }
                } else if (sc == 404) {
                    tileCache.put(key, SENTINEL_FAILED);
                    return null;
                }
            } catch (Exception e) {
                LOGGER.debug("Tile {} att {}: {}", key, att, e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (att < MAX_RETRY) {
                try { Thread.sleep(500L * att); }
                catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        tileCache.put(key, SENTINEL_FAILED);
        return null;
    }

    private BufferedImage loadFromDisk(String key) {
        try {
            File f = diskCacheDir.resolve(
                    key.replace('/', File.separatorChar) + ".png").toFile();
            if (f.exists() && f.length() > 0) return ImageIO.read(f);
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

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public void shutdown() { dlPool.shutdown(); }
}