// ============================================================
// FILE: TerraBiomeProvider.java
// FIX: COAST_LAND_THRESHOLD synced to 2.0m (matches TerraDensityFunctions)
//      BUILT_UP WorldCover no longer forces polished andesite (code 8 removed)
//      BUILT_UP in classifyByWorldCover routes to climate fallback
//      STONY_SHORE threshold raised to 15.0m (realistic cliff threshold)
//      classifyCoast simplified — no longer uses COAST_LAND_THRESHOLD
//      getBiomeTypeCode BEACH returns 9 for sand surface
//      Precipitation dataset is annual mm/year (climate channel 1)
//      WorldCover remains primary classifier for vegetation biomes
//      Soil fallback retained for when WorldCover tile not loaded
// ============================================================
package net.buildtheearth.terraplusplus.generator;

import net.buildtheearth.terraminusminus.dataset.builtin.Climate;
import net.buildtheearth.terraminusminus.dataset.builtin.Soil;
import net.buildtheearth.terraplusplus.generator.EsaWorldCoverIndex.WorldCoverClass;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TerraBiomeProvider {

    private static final Logger LOGGER = LogManager.getLogger("Terra++Biome");

    // ================================================================
    // THRESHOLDS
    // ================================================================

    // FIX: Must match COAST_LAND_THRESHOLD in TerraDensityFunctions.
    // Both files use 2.0m. If these differ, biome and terrain type
    // will disagree at the coastline boundary causing surface mismatches.
    private static final double COAST_LAND_THRESHOLD = 2.0;

    // FIX: Stony shore only appears on real cliffs (>15m elevation).
    // The old broken code used COAST_LAND_THRESHOLD (3.0m) here which
    // caused Dubai at 4-8m to be classified as stony shore instead of beach.
    private static final double STONY_SHORE_THRESHOLD = 15.0;

    // ================================================================
    // PRECIPITATION THRESHOLDS (annual mm/year)
    // ================================================================

    private static final double PRECIP_HYPER_ARID =   50.0;
    private static final double PRECIP_DESERT_MAX = 250.0;
    private static final double PRECIP_ARID        =  150.0;
    private static final double PRECIP_SEMI_ARID   =  400.0;
    private static final double PRECIP_SUB_HUMID   =  800.0;
    private static final double PRECIP_HUMID       = 1500.0;

    // ================================================================
    // ELEVATION TIERS (meters)
    // ================================================================

    private static final double ELEV_HIGHLAND    =  500.0;
    private static final double ELEV_MOUNTAIN    = 1500.0;
    private static final double ELEV_HIGH_PEAK   = 3000.0;
    private static final double ELEV_FROZEN_PEAK = 4500.0;

    // ================================================================
    // TERRA-- DATASETS
    // ================================================================

    private final Soil                   soilDataset;
    private final Climate.Precipitation  precipDataset;
    private final Climate.Temperature    tempDataset;
    private final boolean                datasetsAvailable;

    private final ConcurrentHashMap<Long, double[]> dataCache =
            new ConcurrentHashMap<>(8192);
    private static final int DATA_CACHE_MAX = 32768;

    private final AtomicInteger debugCount = new AtomicInteger(0);

    // ================================================================
    // BIOME HOLDERS
    // ================================================================

    private final Map<ResourceKey<Biome>, Holder<Biome>> biomeMap = new HashMap<>();

    public final Holder<Biome> OCEAN;
    public final Holder<Biome> DEEP_OCEAN;
    public final Holder<Biome> WARM_OCEAN;
    public final Holder<Biome> LUKEWARM_OCEAN;
    public final Holder<Biome> DEEP_LUKEWARM_OCEAN;
    public final Holder<Biome> COLD_OCEAN;
    public final Holder<Biome> DEEP_COLD_OCEAN;
    public final Holder<Biome> FROZEN_OCEAN;
    public final Holder<Biome> DEEP_FROZEN_OCEAN;
    public final Holder<Biome> BEACH;
    public final Holder<Biome> SNOWY_BEACH;
    public final Holder<Biome> STONY_SHORE;
    public final Holder<Biome> RIVER;
    public final Holder<Biome> FROZEN_RIVER;
    public final Holder<Biome> PLAINS;
    public final Holder<Biome> SUNFLOWER_PLAINS;
    public final Holder<Biome> MEADOW;
    public final Holder<Biome> FOREST;
    public final Holder<Biome> FLOWER_FOREST;
    public final Holder<Biome> BIRCH_FOREST;
    public final Holder<Biome> OLD_GROWTH_BIRCH_FOREST;
    public final Holder<Biome> DARK_FOREST;
    public final Holder<Biome> GROVE;
    public final Holder<Biome> CHERRY_GROVE;
    public final Holder<Biome> TAIGA;
    public final Holder<Biome> OLD_GROWTH_PINE_TAIGA;
    public final Holder<Biome> OLD_GROWTH_SPRUCE_TAIGA;
    public final Holder<Biome> SNOWY_TAIGA;
    public final Holder<Biome> JUNGLE;
    public final Holder<Biome> SPARSE_JUNGLE;
    public final Holder<Biome> BAMBOO_JUNGLE;
    public final Holder<Biome> DESERT;
    public final Holder<Biome> BADLANDS;
    public final Holder<Biome> ERODED_BADLANDS;
    public final Holder<Biome> WOODED_BADLANDS;
    public final Holder<Biome> SAVANNA;
    public final Holder<Biome> SAVANNA_PLATEAU;
    public final Holder<Biome> WINDSWEPT_SAVANNA;
    public final Holder<Biome> SWAMP;
    public final Holder<Biome> MANGROVE_SWAMP;
    public final Holder<Biome> SNOWY_PLAINS;
    public final Holder<Biome> ICE_SPIKES;
    public final Holder<Biome> FROZEN_PEAKS;
    public final Holder<Biome> SNOWY_SLOPES;
    public final Holder<Biome> WINDSWEPT_HILLS;
    public final Holder<Biome> WINDSWEPT_GRAVELLY_HILLS;
    public final Holder<Biome> WINDSWEPT_FOREST;
    public final Holder<Biome> STONY_PEAKS;
    public final Holder<Biome> JAGGED_PEAKS;
    /** Caves Phase 1 (2026-11-20): underground biome holders. */
    public final Holder<Biome> LUSH_CAVES;
    public final Holder<Biome> DRIPSTONE_CAVES;
    public final Holder<Biome> DEEP_DARK;

    // ================================================================
    // CONSTRUCTOR
    // ================================================================

    public TerraBiomeProvider(BiomeSource source) {
        Soil                  sDataset = null;
        Climate.Precipitation pDataset = null;
        Climate.Temperature   tDataset = null;
        boolean ok = false;
        try {
            sDataset = new Soil();
            pDataset = new Climate.Precipitation();
            tDataset = new Climate.Temperature();
            ok = true;
            LOGGER.info("Terra-- datasets loaded OK");
            try {
                double testSoil   = sDataset.getAsync(0.0, 0.0).join();
                double testPrecip = pDataset.getAsync(0.0, 0.0).join();
                double testTemp   = tDataset.getAsync(0.0, 0.0).join();
                LOGGER.info("Dataset test (0,0): soil={} precip_raw={} "
                                + "precip_annual={} temp={}",
                        String.format("%.0f", testSoil),
                        String.format("%.1f", testPrecip),
                        String.format("%.1f", testPrecip),
                        String.format("%.1f", testTemp));
            } catch (Exception e) {
                LOGGER.warn("Dataset test failed: {}", e.getMessage());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load terra-- datasets: {}", e.getMessage(), e);
        }
        this.soilDataset       = sDataset;
        this.precipDataset     = pDataset;
        this.tempDataset       = tDataset;
        this.datasetsAvailable = ok;

        Set<Holder<Biome>> possible = source.possibleBiomes();
        for (Holder<Biome> holder : possible) {
            holder.unwrapKey().ifPresent(key -> biomeMap.put(key, holder));
        }

        OCEAN                    = get(Biomes.OCEAN);
        DEEP_OCEAN               = get(Biomes.DEEP_OCEAN);
        WARM_OCEAN               = get(Biomes.WARM_OCEAN);
        LUKEWARM_OCEAN           = get(Biomes.LUKEWARM_OCEAN);
        DEEP_LUKEWARM_OCEAN      = get(Biomes.DEEP_LUKEWARM_OCEAN);
        COLD_OCEAN               = get(Biomes.COLD_OCEAN);
        DEEP_COLD_OCEAN          = get(Biomes.DEEP_COLD_OCEAN);
        FROZEN_OCEAN             = get(Biomes.FROZEN_OCEAN);
        DEEP_FROZEN_OCEAN        = get(Biomes.DEEP_FROZEN_OCEAN);
        BEACH                    = get(Biomes.BEACH);
        SNOWY_BEACH              = get(Biomes.SNOWY_BEACH);
        STONY_SHORE              = get(Biomes.STONY_SHORE);
        RIVER                    = get(Biomes.RIVER);
        FROZEN_RIVER             = get(Biomes.FROZEN_RIVER);
        PLAINS                   = get(Biomes.PLAINS);
        SUNFLOWER_PLAINS         = get(Biomes.SUNFLOWER_PLAINS);
        MEADOW                   = get(Biomes.MEADOW);
        FOREST                   = get(Biomes.FOREST);
        FLOWER_FOREST            = get(Biomes.FLOWER_FOREST);
        BIRCH_FOREST             = get(Biomes.BIRCH_FOREST);
        OLD_GROWTH_BIRCH_FOREST  = get(Biomes.OLD_GROWTH_BIRCH_FOREST);
        DARK_FOREST              = get(Biomes.DARK_FOREST);
        GROVE                    = get(Biomes.GROVE);
        CHERRY_GROVE             = get(Biomes.CHERRY_GROVE);
        TAIGA                    = get(Biomes.TAIGA);
        OLD_GROWTH_PINE_TAIGA    = get(Biomes.OLD_GROWTH_PINE_TAIGA);
        OLD_GROWTH_SPRUCE_TAIGA  = get(Biomes.OLD_GROWTH_SPRUCE_TAIGA);
        SNOWY_TAIGA              = get(Biomes.SNOWY_TAIGA);
        JUNGLE                   = get(Biomes.JUNGLE);
        SPARSE_JUNGLE            = get(Biomes.SPARSE_JUNGLE);
        BAMBOO_JUNGLE            = get(Biomes.BAMBOO_JUNGLE);
        DESERT                   = get(Biomes.DESERT);
        BADLANDS                 = get(Biomes.BADLANDS);
        ERODED_BADLANDS          = get(Biomes.ERODED_BADLANDS);
        WOODED_BADLANDS          = get(Biomes.WOODED_BADLANDS);
        SAVANNA                  = get(Biomes.SAVANNA);
        SAVANNA_PLATEAU          = get(Biomes.SAVANNA_PLATEAU);
        WINDSWEPT_SAVANNA        = get(Biomes.WINDSWEPT_SAVANNA);
        SWAMP                    = get(Biomes.SWAMP);
        MANGROVE_SWAMP           = get(Biomes.MANGROVE_SWAMP);
        SNOWY_PLAINS             = get(Biomes.SNOWY_PLAINS);
        ICE_SPIKES               = get(Biomes.ICE_SPIKES);
        FROZEN_PEAKS             = get(Biomes.FROZEN_PEAKS);
        SNOWY_SLOPES             = get(Biomes.SNOWY_SLOPES);
        WINDSWEPT_HILLS          = get(Biomes.WINDSWEPT_HILLS);
        WINDSWEPT_GRAVELLY_HILLS = get(Biomes.WINDSWEPT_GRAVELLY_HILLS);
        WINDSWEPT_FOREST         = get(Biomes.WINDSWEPT_FOREST);
        STONY_PEAKS              = get(Biomes.STONY_PEAKS);
        JAGGED_PEAKS             = get(Biomes.JAGGED_PEAKS);
        LUSH_CAVES               = biomeMap.get(Biomes.LUSH_CAVES);
        DRIPSTONE_CAVES          = biomeMap.get(Biomes.DRIPSTONE_CAVES);
        DEEP_DARK                = biomeMap.get(Biomes.DEEP_DARK);
        if (LUSH_CAVES == null || DRIPSTONE_CAVES == null
                || DEEP_DARK == null)
            LOGGER.warn("cave biome holders missing (lush={} drip={} "
                    + "deepDark={}) — cave biome painting limited",
                    LUSH_CAVES != null, DRIPSTONE_CAVES != null,
                    DEEP_DARK != null);

        LOGGER.info("TerraBiomeProvider: indexed {} biomes, datasets={}",
                biomeMap.size(), ok ? "OK" : "FALLBACK");
    }

    private Holder<Biome> get(ResourceKey<Biome> key) { return biomeMap.get(key); }
    public int getBiomeCount() { return biomeMap.size(); }

    // ================================================================
    // DATASET QUERY
    // ================================================================

    // Returns double[3]:
    //   [0] = soil code
    //   [1] = annual precipitation mm/year (raw * 12)
    //   [2] = mean annual temperature C
    private double[] queryData(double latitude, double longitude) {
        long rLat = Math.round(latitude  * 100.0);
        long rLon = Math.round(longitude * 100.0);
        long key  = rLat * 720001L + rLon;

        double[] cached = dataCache.get(key);
        if (cached != null) return cached;

        double soil = 0, precip = 0, temp = 15;

        if (datasetsAvailable) {
            try {
                soil = soilDataset.getAsync(longitude, latitude).join();
                double rawPrecip = precipDataset.getAsync(longitude, latitude).join();
                temp = tempDataset.getAsync(longitude, latitude).join();

                // climate.gz channel 1 is annual precipitation
                // in mm/year (verified by decoding the dataset:
                // Cairo ~29, Berlin ~691, Amazon ~2221). Do NOT
                // multiply by 12 — that old assumption turned
                // normal temperate rainfall into rainforest data.
                if (rawPrecip >= 0 && rawPrecip <= 12000) {
                    precip = rawPrecip;
                } else {
                    precip = estimatePrecipFromLatLon(latitude, longitude);
                }

                // Temperature is a separate channel now; only a
                // physically impossible reading falls back to the
                // latitude estimate.
                if (temp < -89 || temp > 60) {
                    temp = estimateTempFromLat(latitude);
                }

                if (precip < 0)     precip = 0;
                if (precip > 12000) precip = estimatePrecipFromLatLon(latitude, longitude);

                if (debugCount.getAndIncrement() < 30) {
                    LOGGER.info("QUERY lat={} lon={} soil={} "
                                    + "precip_raw={}mm precip_annual={}mm temp={}C",
                            String.format("%.4f", latitude),
                            String.format("%.4f", longitude),
                            String.format("%.0f", soil),
                            String.format("%.1f", rawPrecip),
                            String.format("%.1f", precip),
                            String.format("%.1f", temp));
                }
            } catch (Exception e) {
                if (debugCount.get() < 5) {
                    LOGGER.error("Dataset query FAILED lat={} lon={}: {}",
                            String.format("%.4f", latitude),
                            String.format("%.4f", longitude),
                            e.getMessage());
                }
                soil   = 0;
                precip = estimatePrecipFromLatLon(latitude, longitude);
                temp   = estimateTempFromLat(latitude);
            }
        } else {
            temp   = estimateTempFromLat(latitude);
            precip = estimatePrecipFromLatLon(latitude, longitude);
        }

        double[] result = new double[]{ soil, precip, temp };
        if (dataCache.size() > DATA_CACHE_MAX) dataCache.clear();
        dataCache.put(key, result);
        return result;
    }

    /** Public climate sample for /terracheck: [soil, annualPrecipMm, tempC]. */
    public double[] getClimateSample(double latitude, double longitude) {
        double[] d = queryData(latitude, longitude);
        return new double[]{ d[0], d[1], d[2] };
    }

    /** Aridity zone name for an annual precipitation value. */
    public String describeAridity(double annualPrecipMm) {
        return getAridity(annualPrecipMm).name();
    }

    // ================================================================
    // FALLBACK ESTIMATORS
    // ================================================================

    private static double estimateTempFromLat(double lat) {
        double a = Math.abs(lat);
        if (a < 10) return 27.0;
        if (a < 20) return 25.0;
        if (a < 30) return 20.0;
        if (a < 40) return 14.0;
        if (a < 50) return  8.0;
        if (a < 60) return  2.0;
        if (a < 70) return -5.0;
        return -15.0;
    }

    private static double estimatePrecipFromLatLon(double lat, double lon) {
        double a = Math.abs(lat);
        if (isInDesertBelt(lat, lon)) return 20.0;
        if (a < 10)  return 2000.0;
        if (a < 15)  return 1200.0;
        if (a < 35)  return  350.0;
        if (a < 55)  return  700.0;
        if (a < 70)  return  400.0;
        return 150.0;
    }

    private static boolean isInDesertBelt(double lat, double lon) {
        double a = Math.abs(lat);
        if (a < 15 || a > 35) return false;
        // Sahara and Arabian desert
        if (lat > 15 && lat < 35 && lon > -17  && lon < 60)   return true;
        // Thar desert
        if (lat > 23 && lat < 30 && lon > 67   && lon < 73)   return true;
        // Sonoran desert
        if (lat > 23 && lat < 35 && lon > -117 && lon < -103) return true;
        // Kalahari
        if (lat < -18 && lat > -30 && lon > 14  && lon < 28)  return true;
        // Australian outback
        if (lat < -20 && lat > -32 && lon > 120 && lon < 145) return true;
        // Atacama
        if (lat < -18 && lat > -30 && lon > -72 && lon < -68) return true;
        return false;
    }

    // ================================================================
    // ARIDITY
    // ================================================================

    private enum AridityZone {
        HYPER_ARID, ARID, SEMI_ARID, SUB_HUMID, HUMID, WET
    }

    private static AridityZone getAridity(double annualPrecip) {
        if (annualPrecip < PRECIP_HYPER_ARID) return AridityZone.HYPER_ARID;
        if (annualPrecip < PRECIP_ARID)       return AridityZone.ARID;
        if (annualPrecip < PRECIP_SEMI_ARID)  return AridityZone.SEMI_ARID;
        if (annualPrecip < PRECIP_SUB_HUMID)  return AridityZone.SUB_HUMID;
        if (annualPrecip < PRECIP_HUMID)      return AridityZone.HUMID;
        return AridityZone.WET;
    }

    // ================================================================
    // GEOGRAPHIC HELPERS
    // ================================================================

    private static boolean isSEAsiaBamboo(double lat, double lon) {
        return lon >= 95.0 && lon <= 140.0 && lat >= -10.0 && lat <= 25.0;
    }

    private static boolean isCherryGroveRegion(double lat, double lon) {
        return (lon >= 129.0 && lon <= 146.0 && lat >= 30.0 && lat <= 45.0)
            || (lon >= 124.0 && lon <= 130.0 && lat >= 33.0 && lat <= 42.0);
    }

    private static boolean isTropicalCoast(double lat) {
        return Math.abs(lat) <= 30.0;
    }

    private static boolean isWindsweptLatitude(double lat) {
        double a = Math.abs(lat);
        return lat <= -40.0 || (a >= 55.0 && a <= 70.0);
    }

    // ================================================================
    // ELEVATION HELPERS
    // ================================================================

    private static boolean isFrozenPeak(double e)  { return e >= ELEV_FROZEN_PEAK; }
    private static boolean isHighPeak(double e)    { return e >= ELEV_HIGH_PEAK   && e < ELEV_FROZEN_PEAK; }
    private static boolean isMountain(double e)    { return e >= ELEV_MOUNTAIN    && e < ELEV_HIGH_PEAK; }
    private static boolean isHighland(double e)    { return e >= ELEV_HIGHLAND    && e < ELEV_MOUNTAIN; }

    // ================================================================
    // BIOME TYPE HELPERS
    // ================================================================

    public boolean isBeach(Holder<Biome> b) {
        return b != null && (b == BEACH || b == SNOWY_BEACH || b == STONY_SHORE);
    }

    public boolean isCoastalSand(Holder<Biome> b) {
        return b != null && (b == BEACH || b == SNOWY_BEACH);
    }

    public boolean isMesa(Holder<Biome> b) {
        return b != null && (b == BADLANDS || b == ERODED_BADLANDS || b == WOODED_BADLANDS);
    }

    public boolean isDesert(Holder<Biome> b) {
        return b != null && b == DESERT;
    }

    public boolean isFrozen(Holder<Biome> b) {
        return b != null && (b == FROZEN_PEAKS || b == SNOWY_PLAINS || b == ICE_SPIKES
                || b == SNOWY_SLOPES || b == SNOWY_TAIGA);
    }

    public boolean isSwamp(Holder<Biome> b) {
        return b != null && (b == SWAMP || b == MANGROVE_SWAMP);
    }

    public boolean isSavanna(Holder<Biome> b) {
        return b != null && (b == SAVANNA || b == SAVANNA_PLATEAU
                || b == WINDSWEPT_SAVANNA);
    }

    public boolean isJungle(Holder<Biome> b) {
        return b != null && (b == JUNGLE || b == SPARSE_JUNGLE || b == BAMBOO_JUNGLE);
    }

    public boolean isTaiga(Holder<Biome> b) {
        return b != null && (b == TAIGA || b == OLD_GROWTH_PINE_TAIGA
                || b == OLD_GROWTH_SPRUCE_TAIGA || b == SNOWY_TAIGA);
    }

    public boolean isForest(Holder<Biome> b) {
        return b != null && (b == FOREST || b == FLOWER_FOREST || b == BIRCH_FOREST
                || b == OLD_GROWTH_BIRCH_FOREST || b == DARK_FOREST
                || b == GROVE || b == CHERRY_GROVE);
    }

    public boolean isMountainBiome(Holder<Biome> b) {
        return b != null && (b == WINDSWEPT_HILLS || b == WINDSWEPT_GRAVELLY_HILLS
                || b == WINDSWEPT_FOREST || b == STONY_PEAKS
                || b == JAGGED_PEAKS || b == FROZEN_PEAKS || b == SNOWY_SLOPES);
    }

    // ================================================================
    // MAIN ENTRY
    // ================================================================

    public Holder<Biome> getBiomeForLocation(double latitude, double longitude,
                                              double elevMeters,
                                              TerraDensityFunctions.TerrainType terrainType,
                                              WorldCoverClass wcClass) {
        // ── LAYER 1: TERRAIN TYPE ──────────────────────────────────
        switch (terrainType) {
            case OCEAN:
            case SHALLOW_WATER:
                return classifyOcean(latitude, elevMeters);

            case COASTLINE:
                // Always route coastline terrain to coast classifier.
                // classifyCoast uses STONY_SHORE_THRESHOLD (15m) not
                // COAST_LAND_THRESHOLD (2m) so Dubai at 4-8m gets BEACH
                // not STONY_SHORE.
                return classifyCoast(latitude, longitude, elevMeters, wcClass);

            case RIVER:
            case RIVER_BANK: {
                double[] d = queryData(latitude, longitude);
                return d[2] < 0.0 ? safe(FROZEN_RIVER, RIVER) : safe(RIVER);
            }
            case LAKE:
            case LAKE_SHORE: {
                double[] d = queryData(latitude, longitude);
                return d[2] < 0.0 ? safe(FROZEN_RIVER, RIVER) : safe(RIVER);
            }
            case BELOW_SEA_WATER: {
                double[] d = queryData(latitude, longitude);
                return d[2] < 0.0 ? safe(FROZEN_RIVER, RIVER) : safe(RIVER);
            }
            case INVALID:
                return classifyOcean(latitude, elevMeters);
            default:
                break;
        }

        // ── LAYER 2: ELEVATION PEAKS ───────────────────────────────
        double tierElev = Math.max(0, elevMeters);

        if (isFrozenPeak(tierElev))
            return safe(FROZEN_PEAKS, JAGGED_PEAKS);

        if (isHighPeak(tierElev)) {
            double[] d = queryData(latitude, longitude);
            return d[2] < 5.0
                    ? safe(JAGGED_PEAKS, FROZEN_PEAKS)
                    : safe(STONY_PEAKS, JAGGED_PEAKS);
        }

        // ── LAYER 3: WORLDCOVER PRIMARY ────────────────────────────
        double[]    data   = queryData(latitude, longitude);
        return classifyAt(latitude, longitude, elevMeters, wcClass, data);
    }

    // ================================================================
    // WORLDCOVER PRIMARY CLASSIFICATION
    // ================================================================

    private Holder<Biome> classifyByWorldCover(WorldCoverClass wc,
                                                double temp, double precip,
                                                AridityZone ard,
                                                double tierElev, double realElev,
                                                double lat, double lon) {
        return switch (wc) {
            case TREE_COVER -> classifyTreeCover(temp, precip, ard, tierElev, lat, lon);
            case SHRUBLAND  -> classifyShrubland(temp, ard, tierElev, lat, lon);
            case GRASSLAND  -> classifyGrassland(temp, ard, tierElev, lat, lon);
            case CROPLAND   -> classifyCropland(temp, tierElev);

            // FIX: BUILT_UP no longer forces a specific surface code.
            // Routes to climate fallback so Dubai gets DESERT biome
            // (sand/sandstone surface) instead of polished andesite.
            // The actual buildings are placed by OSM structure generation
            // on top of the natural terrain surface.
            case BUILT_UP   -> classifyLandFallback(
                    0, precip, temp, ard, tierElev, realElev, lat, lon);

            case BARE       -> classifyBare(temp, ard, tierElev, lat, lon);
            case SNOW_ICE   -> classifySnowIce(temp, tierElev);
            case WATER      -> temp < 0.0 ? safe(FROZEN_RIVER, RIVER) : safe(RIVER);
            case WETLAND    -> classifyWetland(temp, precip, lat);
            case MANGROVES  -> safe(MANGROVE_SWAMP, SWAMP);
            case MOSS_LICHEN-> classifyMossLichen(temp);
            default         -> classifyGrassland(temp, ard, tierElev, lat, lon);
        };
    }

    // ================================================================
    // TREE COVER
    // ================================================================

    private Holder<Biome> classifyTreeCover(double temp, double precip,
                                             AridityZone ard,
                                             double tierElev,
                                             double lat, double lon) {
        if (isMountain(tierElev)) {
            if (temp < 0.0) return safe(SNOWY_SLOPES, GROVE);
            if (temp < 5.0) return safe(GROVE, SNOWY_SLOPES);
            if (isCherryGroveRegion(lat, lon)) return safe(CHERRY_GROVE, GROVE);
            return safe(GROVE, WINDSWEPT_FOREST);
        }
        if (isHighland(tierElev)) {
            if (temp < 0.0)  return safe(SNOWY_TAIGA, GROVE);
            if (temp < 5.0)  return safe(WINDSWEPT_FOREST, GROVE);
            if (isCherryGroveRegion(lat, lon)) return safe(CHERRY_GROVE, FOREST);
            if (temp < 15.0) return safe(FOREST, BIRCH_FOREST);
            return safe(WINDSWEPT_FOREST, FOREST);
        }
        if (temp < -5.0) return safe(SNOWY_TAIGA, TAIGA);
        if (temp < 0.0) {
            int hash = hashLL(lat, lon);
            return hash % 2 == 0
                    ? safe(SNOWY_TAIGA, TAIGA)
                    : safe(OLD_GROWTH_SPRUCE_TAIGA, TAIGA);
        }
        if (temp < 5.0) {
            double absLat = Math.abs(lat);
            int hash = hashLL(lat, lon);
            if (absLat > 55.0) {
                return hash % 2 == 0
                        ? safe(OLD_GROWTH_SPRUCE_TAIGA, TAIGA)
                        : safe(OLD_GROWTH_PINE_TAIGA, TAIGA);
            }
            return safe(TAIGA, OLD_GROWTH_PINE_TAIGA);
        }
        if (temp < 10.0) {
            if (isCherryGroveRegion(lat, lon)) return safe(CHERRY_GROVE, BIRCH_FOREST);
            int hash = hashLL(lat, lon);
            return switch (hash % 4) {
                case 0  -> safe(BIRCH_FOREST);
                case 1  -> safe(OLD_GROWTH_BIRCH_FOREST, BIRCH_FOREST);
                case 2  -> safe(DARK_FOREST);
                default -> safe(FOREST);
            };
        }
        if (temp < 15.0) {
            if (isCherryGroveRegion(lat, lon)) return safe(CHERRY_GROVE, FOREST);
            int hash = hashLL(lat, lon);
            return switch (hash % 5) {
                case 0  -> safe(BIRCH_FOREST);
                case 1  -> safe(DARK_FOREST);
                case 2  -> safe(FLOWER_FOREST, FOREST);
                case 3  -> safe(FOREST);
                default -> safe(OLD_GROWTH_BIRCH_FOREST, BIRCH_FOREST);
            };
        }
        if (temp < 22.0) {
            if (isSEAsiaBamboo(lat, lon)) return safe(BAMBOO_JUNGLE, SPARSE_JUNGLE);
            return safe(SPARSE_JUNGLE, FOREST);
        }
        if (isSEAsiaBamboo(lat, lon)) return safe(BAMBOO_JUNGLE, JUNGLE);
        int hash = hashLL(lat, lon);
        return hash % 6 == 0
                ? safe(SPARSE_JUNGLE, JUNGLE)
                : safe(JUNGLE);
    }

    // ================================================================
    // SHRUBLAND
    // ================================================================

    private Holder<Biome> classifyShrubland(double temp, AridityZone ard,
                                             double tierElev,
                                             double lat, double lon) {
        if (isMountain(tierElev) || isHighland(tierElev)) {
            if (temp < 0.0)  return safe(WINDSWEPT_GRAVELLY_HILLS, WINDSWEPT_HILLS);
            if (temp < 10.0) return safe(WINDSWEPT_HILLS, PLAINS);
            // Step 5: hot high shrubland = windswept savanna
            // (was never selected before).
            if (temp >= 18.0 && tierElev >= 1000.0)
                return safe(WINDSWEPT_SAVANNA, SAVANNA_PLATEAU);
            return safe(WINDSWEPT_HILLS, SAVANNA_PLATEAU);
        }
        if (temp < 0.0)  return safe(SNOWY_PLAINS, PLAINS);
        if (temp < 5.0)  return safe(PLAINS, TAIGA);
        if (temp < 15.0) return safe(WINDSWEPT_HILLS, PLAINS);
        if (temp < 22.0) return safe(SAVANNA, PLAINS);
        return safe(SAVANNA, PLAINS);
    }

    // ================================================================
    // GRASSLAND
    // ================================================================

    private Holder<Biome> classifyGrassland(double temp, AridityZone ard,
                                             double tierElev,
                                             double lat, double lon) {
        if (isMountain(tierElev)) {
            if (temp < 0.0) return safe(SNOWY_SLOPES, FROZEN_PEAKS);
            if (temp < 8.0) return safe(GROVE, WINDSWEPT_HILLS);
            return safe(MEADOW, WINDSWEPT_HILLS);
        }
        if (isHighland(tierElev)) {
            if (temp < 0.0) return safe(SNOWY_PLAINS, SNOWY_SLOPES);
            if (temp < 8.0) return safe(WINDSWEPT_HILLS, MEADOW);
            return safe(MEADOW, PLAINS);
        }
        if (temp < -5.0) return safe(SNOWY_PLAINS, ICE_SPIKES);
        if (temp < 0.0)  return safe(SNOWY_PLAINS);
        if (temp < 5.0)  return safe(PLAINS, TAIGA);
        if (temp < 15.0) {
            if (isWindsweptLatitude(lat)) return safe(WINDSWEPT_HILLS, PLAINS);
            int hash = hashLL(lat, lon);
            return switch (hash % 4) {
                case 0  -> safe(MEADOW);
                case 1  -> safe(SUNFLOWER_PLAINS, PLAINS);
                default -> safe(PLAINS);
            };
        }
        if (temp < 22.0) return safe(PLAINS, SAVANNA);
        return safe(SAVANNA, PLAINS);
    }

    // ================================================================
    // CROPLAND
    // ================================================================

    private Holder<Biome> classifyCropland(double temp, double tierElev) {
        if (temp < 0.0)  return safe(SNOWY_PLAINS, PLAINS);
        if (temp < 5.0)  return safe(PLAINS, TAIGA);
        if (temp < 15.0) return safe(PLAINS, MEADOW);
        if (temp < 22.0) return safe(PLAINS, SAVANNA);
        return safe(PLAINS, SAVANNA);
    }

    // ================================================================
    // BARE
    // ================================================================

    private Holder<Biome> classifyBare(double temp, AridityZone ard,
                                        double tierElev,
                                        double lat, double lon) {
        if (isMountain(tierElev)) {
            if (temp < 0.0) return safe(SNOWY_SLOPES, FROZEN_PEAKS);
            // NW(22) Atacama evidence: hot + dry mountains
            // are desert (Atacama/Altiplano), not stony
            // peaks. 20 mm/yr at 2400 m is a desert.
            if (temp >= 8.0
                    && (ard == AridityZone.HYPER_ARID
                        || ard == AridityZone.ARID))
                return safe(DESERT, STONY_PEAKS);
            return safe(STONY_PEAKS, WINDSWEPT_HILLS);
        }
        if (isHighland(tierElev)) {
            if (temp < 0.0) return safe(WINDSWEPT_GRAVELLY_HILLS, SNOWY_SLOPES);
            // Sahara fix (2026-11-20, owner report at
            // 23.40,12.60 Tibesti; widened by NW(22) Erg
            // Chebbi evidence, 91 mm/yr): hot highlands with
            // under 150 mm/yr rain are true desert
            // (Sahara/Arabia/Atacama uplands), not mesa.
            // Without this gate every hot bare massif above
            // 500 m became badlands + terracotta + grass
            // patches. Badlands family now requires at
            // least SEMI_ARID rain (Monument Valley
            // 236 mm/yr and Grand Canyon still qualify).
            if ((ard == AridityZone.HYPER_ARID
                    || ard == AridityZone.ARID) && temp >= 8.0)
                return safe(DESERT, BADLANDS);
            // Step 5: use the whole badlands family. High +
            // driest = eroded (gullied plateau edges); a bit
            // more rain (semi-arid) = wooded badlands.
            // (Gate was 1200 m in v1; the NW17 save proved
            // every visited badlands column sits at
            // ~500-1090 m, so nothing ever qualified.
            // 1000 m = canyon-bench country.)
            if (tierElev >= 1000.0
                    && ard != AridityZone.SEMI_ARID
                    && ard != AridityZone.SUB_HUMID)
                return safe(ERODED_BADLANDS, BADLANDS);
            if (ard == AridityZone.SEMI_ARID)
                return safe(WOODED_BADLANDS, BADLANDS);
            return safe(BADLANDS, WINDSWEPT_GRAVELLY_HILLS);
        }
        if (temp < -5.0) return safe(SNOWY_PLAINS, ICE_SPIKES);
        if (temp < 0.0)  return safe(SNOWY_PLAINS);
        if (temp < 8.0)  return safe(BADLANDS, PLAINS);
        return safe(DESERT);
    }

    // ================================================================
    // SNOW AND ICE
    // ================================================================

    private Holder<Biome> classifySnowIce(double temp, double tierElev) {
        if (isFrozenPeak(tierElev) || isHighPeak(tierElev))
            return safe(FROZEN_PEAKS, JAGGED_PEAKS);
        if (isMountain(tierElev))
            return safe(SNOWY_SLOPES, FROZEN_PEAKS);
        int hash = (int) (temp * 1000) & 0x7FFFFFFF;
        return hash % 4 == 0
                ? safe(ICE_SPIKES, SNOWY_PLAINS)
                : safe(SNOWY_PLAINS, ICE_SPIKES);
    }

    // ================================================================
    // WETLAND
    // ================================================================

    private Holder<Biome> classifyWetland(double temp, double precip,
                                           double lat) {
        if (temp < 0.0) return safe(SNOWY_PLAINS, SWAMP);
        if (isTropicalCoast(lat) && temp >= 20.0 && precip >= PRECIP_SUB_HUMID)
            return safe(MANGROVE_SWAMP, SWAMP);
        if (temp >= 18.0) return safe(SWAMP, JUNGLE);
        return safe(SWAMP, DARK_FOREST);
    }

    // ================================================================
    // MOSS / LICHEN
    // ================================================================

    private Holder<Biome> classifyMossLichen(double temp) {
        if (temp < -5.0) {
            int hash = (int) (temp * 1000) & 0x7FFFFFFF;
            return hash % 5 == 0
                    ? safe(ICE_SPIKES, SNOWY_PLAINS)
                    : safe(SNOWY_PLAINS);
        }
        return safe(SNOWY_PLAINS, TAIGA);
    }

    // ================================================================
    // OCEAN
    // ================================================================

    private Holder<Biome> classifyOcean(double lat, double elevMeters) {
        double  a    = Math.abs(lat);
        boolean deep = Math.abs(elevMeters) > 1000.0;
        if (a >= 60.0) return deep
                ? safe(DEEP_FROZEN_OCEAN,   FROZEN_OCEAN)   : safe(FROZEN_OCEAN);
        if (a >= 50.0) return deep
                ? safe(DEEP_COLD_OCEAN,     COLD_OCEAN)     : safe(COLD_OCEAN);
        if (a >= 40.0) return deep
                ? safe(DEEP_OCEAN,          OCEAN)          : safe(OCEAN);
        if (a >= 25.0) return deep
                ? safe(DEEP_LUKEWARM_OCEAN, LUKEWARM_OCEAN) : safe(LUKEWARM_OCEAN);
        return deep
                ? safe(DEEP_LUKEWARM_OCEAN, LUKEWARM_OCEAN) : safe(WARM_OCEAN);
    }

    // ================================================================
    // COAST CLASSIFICATION
    // ================================================================

    // FIX: classifyCoast now uses STONY_SHORE_THRESHOLD (15.0m) not
    // COAST_LAND_THRESHOLD (2.0m) for the stony shore check.
    //
    // Old broken code used COAST_LAND_THRESHOLD (3.0m) which caused:
    //   - Dubai at 4-8m elevation → STONY_SHORE (wrong, should be BEACH)
    //   - Low cliffs at 3-4m → STONY_SHORE (wrong, should be sandy)
    //
    // Correct geological rule:
    //   > 15m elevation on coast → STONY_SHORE (actual cliff)
    //   0 to 15m on coast        → BEACH (sandy shore)
    //   < 0m on coast            → BEACH (tidal flat)
    //   Frozen coast             → SNOWY_BEACH
    //   Mangrove coast           → MANGROVE_SWAMP
    private Holder<Biome> classifyCoast(double lat, double lon,
                                         double elevMeters,
                                         WorldCoverClass wcClass) {
        double[] d    = queryData(lat, lon);
        double   temp = d[2];

        // Frozen coast
        if (temp < 0.0) return safe(SNOWY_BEACH, BEACH);

        // FIX: Only use STONY_SHORE for genuine cliffs (>15m)
        // not for low coastal land like Dubai (4-8m)
        if (elevMeters > STONY_SHORE_THRESHOLD)
            return safe(STONY_SHORE, BEACH);

        // Mangrove coast — WorldCover confirmed
        if (wcClass == WorldCoverClass.MANGROVES)
            return safe(MANGROVE_SWAMP, SWAMP);

        // Tropical wetland coast
        if (wcClass == WorldCoverClass.WETLAND
                && isTropicalCoast(lat) && temp >= 20.0)
            return safe(MANGROVE_SWAMP, SWAMP);

        // Step 4 v3 (NW15 proof): a precip-only mangrove rule
        // used to sit here ("tropical + wet -> MANGROVE_SWAMP").
        // It hijacked every wet tropical SAND beach — Juhu
        // (2,111 mm/yr) came out mangrove_swamp on the whole
        // open shore. Real mangroves are muddy sheltered
        // water, already covered by the MANGROVES / WETLAND
        // class rules above. Rain alone does not make a
        // mangrove; open wet-tropical sand is BEACH.

        // Default sandy beach
        return safe(BEACH);
    }

    // ================================================================
    // LAND FALLBACK (WorldCover tile not loaded yet)
    // ================================================================

    private Holder<Biome> classifyLandFallback(int soil, double precip,
                                                double temp, AridityZone ard,
                                                double tierElev, double realElev,
                                                double lat, double lon) {
        if (isMountain(tierElev))
            return classifyMountainFallback(soil, temp, ard, lat, lon);
        if (isHighland(tierElev))
            return classifyHighlandFallback(soil, temp, ard, lat, lon);
        return classifyLowlandFallback(soil, precip, temp, ard,
                realElev, lat, lon);
    }

    private Holder<Biome> classifyMountainFallback(int soil, double temp,
                                                    AridityZone ard,
                                                    double lat, double lon) {
        if (ard == AridityZone.HYPER_ARID || ard == AridityZone.ARID) {
            // Hot dry mountains = desert (Atacama rule,
            // NW22); cold dry mountains stay stony.
            if (temp >= 8.0) return safe(DESERT, STONY_PEAKS);
            return safe(STONY_PEAKS, BADLANDS);
        }
        if (temp < 0.0)  return safe(SNOWY_SLOPES, FROZEN_PEAKS);
        if (temp < 5.0)  return safe(GROVE, SNOWY_SLOPES);
        if (temp < 12.0) {
            if (isCherryGroveRegion(lat, lon)) return safe(CHERRY_GROVE, GROVE);
            return safe(GROVE, FOREST);
        }
        return safe(WINDSWEPT_HILLS, STONY_PEAKS);
    }

    private Holder<Biome> classifyHighlandFallback(int soil, double temp,
                                                    AridityZone ard,
                                                    double lat, double lon) {
        if (ard == AridityZone.HYPER_ARID) return safe(DESERT);
        if (ard == AridityZone.ARID)       return safe(BADLANDS, DESERT);
        if (temp < 0.0)  return safe(SNOWY_SLOPES, SNOWY_PLAINS);
        if (temp < 5.0)  return safe(WINDSWEPT_HILLS, TAIGA);
        if (temp < 15.0) {
            if (isCherryGroveRegion(lat, lon)) return safe(CHERRY_GROVE, MEADOW);
            return safe(MEADOW, PLAINS);
        }
        return safe(SAVANNA_PLATEAU, PLAINS);
    }

    private Holder<Biome> classifyLowlandFallback(int soil, double precip,
                                                   double temp, AridityZone ard,
                                                   double realElev,
                                                   double lat, double lon) {
        double absLat = Math.abs(lat);

        if (ard == AridityZone.HYPER_ARID) {
            if (temp < 0.0) return safe(SNOWY_PLAINS);
            return safe(DESERT);
        }
        if (ard == AridityZone.ARID) {
            if (temp < 0.0) return safe(SNOWY_PLAINS);
            return getSoilGroup(soil) == SoilGroup.ROCKY
                    ? safe(BADLANDS, DESERT) : safe(DESERT);
        }
        if (temp < -10.0 || absLat > 75.0) {
            int h = hashLL(lat, lon);
            return h % 5 == 0
                    ? safe(ICE_SPIKES, SNOWY_PLAINS)
                    : safe(SNOWY_PLAINS, ICE_SPIKES);
        }
        if (temp < 0.0 || absLat > 60.0) {
            return getSoilGroup(soil) == SoilGroup.FOREST
                    ? safe(SNOWY_TAIGA, TAIGA) : safe(SNOWY_PLAINS);
        }
        if (temp < 5.0) {
            return switch (getSoilGroup(soil)) {
                case FOREST -> {
                    int h = hashLL(lat, lon);
                    yield h % 2 == 0
                            ? safe(OLD_GROWTH_SPRUCE_TAIGA, TAIGA)
                            : safe(OLD_GROWTH_PINE_TAIGA, TAIGA);
                }
                case WETLAND -> safe(SWAMP, TAIGA);
                default      -> safe(TAIGA);
            };
        }
        if (temp < 15.0) {
            return switch (getSoilGroup(soil)) {
                case DESERT  -> safe(BADLANDS, DESERT);
                case ROCKY   -> safe(WINDSWEPT_HILLS, PLAINS);
                case WETLAND -> safe(SWAMP, DARK_FOREST);
                case FOREST  -> safe(FOREST, BIRCH_FOREST);
                case JUNGLE  -> safe(DARK_FOREST, FOREST);
                default      -> safe(PLAINS, MEADOW);
            };
        }
        if (temp < 22.0) {
            return switch (getSoilGroup(soil)) {
                case DESERT  -> safe(DESERT);
                case JUNGLE  -> safe(SPARSE_JUNGLE, JUNGLE);
                case SAVANNA -> safe(SAVANNA);
                case WETLAND -> safe(SWAMP, MANGROVE_SWAMP);
                default      -> safe(PLAINS, SAVANNA);
            };
        }
        return switch (getSoilGroup(soil)) {
            case DESERT  -> safe(DESERT);
            case ROCKY   -> safe(BADLANDS, DESERT);
            case JUNGLE  -> isSEAsiaBamboo(lat, lon)
                    ? safe(BAMBOO_JUNGLE, JUNGLE) : safe(JUNGLE);
            case SAVANNA -> safe(SAVANNA);
            case WETLAND -> safe(MANGROVE_SWAMP, SWAMP);
            default      -> safe(SAVANNA);
        };
    }

    // ================================================================
    // SOIL GROUPS
    // ================================================================

    private enum SoilGroup {
        DESERT, ROCKY, JUNGLE, SAVANNA, WETLAND, FOREST, GRASSLAND, COLD, OCEAN
    }

    private static SoilGroup getSoilGroup(int soil) {
        return switch (soil) {
            case 1,51,97             -> SoilGroup.DESERT;
            case 2,52,53,55,99       -> SoilGroup.ROCKY;
            case 10,34               -> SoilGroup.JUNGLE;
            case 11,12,29,30,31,
                 32,33,54,56,96      -> SoilGroup.SAVANNA;
            case 13,15,16,17,18,
                 19,40,71,80,95,98   -> SoilGroup.WETLAND;
            case 60,61,62,63,64,
                 81,83,84,86,
                 90,91,92,93,94      -> SoilGroup.FOREST;
            case 41,42,43,44,45,
                 70,72,73,74,75,
                 76,77,82,85         -> SoilGroup.GRASSLAND;
            case 3,5,6,7,50          -> SoilGroup.COLD;
            case 0                   -> SoilGroup.OCEAN;
            default                  -> SoilGroup.GRASSLAND;
        };
    }

    // ================================================================
    // HASH HELPER
    // ================================================================

    private static int hashLL(double lat, double lon) {
        return ((int)(lat * 1000) * 31 + (int)(lon * 1000)) & 0x7FFFFFFF;
    }

    // ================================================================
    // PUBLIC API
    // ================================================================

    public Holder<Biome> classifyAt(double lat, double lon,
                                     double elevMeters,
                                     WorldCoverClass wcClass) {
        return classifyAt(lat, lon, elevMeters, wcClass, queryData(lat, lon));
    }

    private Holder<Biome> classifyAt(double lat, double lon, double elevMeters,
                                    WorldCoverClass wcClass, double[] d) {
        double      tierElev = Math.max(0, elevMeters);
        AridityZone ard      = getAridity(d[1]);
        Holder<Biome> result = wcClass != WorldCoverClass.UNKNOWN
                ? classifyByWorldCover(wcClass, d[2], d[1], ard, tierElev, elevMeters, lat, lon)
                : classifyLandFallback((int) d[0], d[1], d[2], ard, tierElev, elevMeters, lat, lon);
        if (!isDesert(result) || d[1] < PRECIP_DESERT_MAX) return result;
        if (wcClass == WorldCoverClass.BARE)
            return safe(STONY_PEAKS, WINDSWEPT_HILLS, PLAINS);
        if (d[2] >= 22.0 && d[1] >= 1000.0) return safe(JUNGLE, FOREST, PLAINS);
        if (d[2] >= 20.0) return safe(SAVANNA, PLAINS);
        return safe(PLAINS, FOREST);
    }

    public Holder<Biome> classifyAt(double lat, double lon, double elevMeters) {
        return classifyAt(lat, lon, elevMeters, WorldCoverClass.UNKNOWN);
    }

    public Holder<Biome> classifyAt(double lat, double lon) {
        return classifyAt(lat, lon, 0.0, WorldCoverClass.UNKNOWN);
    }

    // ================================================================
    // SURFACE BLOCK TYPE CODE
    // ================================================================

    // Returns integer surface block code:
    //   0 = grass (default)
    //   1 = desert / sand / sandstone
    //   2 = mesa / terracotta
    //   3 = frozen / snow / ice
    //   4 = swamp / grass
    //   5 = savanna / coarse dirt
    //   6 = shrubland / coarse dirt
    //   7 = bare-hot / red sand
    //   9 = beach / sand
    //
    // FIX: Code 8 (BUILT_UP / polished andesite) REMOVED.
    // BUILT_UP now falls through to climate classification
    // which returns the natural surface (desert for Dubai etc.)
    // The actual city buildings are placed by OSM structure
    // generation on top of the natural terrain.

    /**
     * Step 4 shore-aware entry. classify() never returns
     * TerrainType.COASTLINE, so the COASTLINE route in the
     * 5-arg method is unreachable and real shores got inland
     * biome names. The generator detects shore land itself
     * (LAND column at shore height with sea water beside it)
     * and passes shore=true; those columns go to the coast
     * classifier (beach / snowy beach / stony shore /
     * mangrove swamp by climate + WorldCover).
     */
    public Holder<Biome> getBiomeForLocation(double latitude, double longitude,
                                              double elevMeters,
                                              TerraDensityFunctions.TerrainType terrainType,
                                              WorldCoverClass wcClass,
                                              boolean shore) {
        if (shore && terrainType == TerraDensityFunctions.TerrainType.LAND)
            return classifyCoast(latitude, longitude, elevMeters, wcClass);
        return getBiomeForLocation(latitude, longitude, elevMeters, terrainType, wcClass);
    }

    // Approximate regional envelopes; the dry bare-ground gate still applies.
    private static boolean isRedSandRegion(double lat, double lon) {
        return (lat >= -30 && lat <= -17 && lon >= 118 && lon <= 145)
            || (lat >= -29 && lat <= -17 && lon >= 12 && lon <= 17)
            || (lat >= -29 && lat <= -19 && lon >= 18 && lon <= 26)
            || (lat >= 16 && lat <= 24 && lon >= 45 && lon <= 57);
    }

    public int getBiomeTypeCode(double lat, double lon,
                                 double elevMeters,
                                 WorldCoverClass wcClass) {
        // WorldCover direct surface overrides
        // NOTE: BUILT_UP is intentionally NOT listed here.
        switch (wcClass) {
            case SNOW_ICE:  return 3;
            case MANGROVES: return 4;
            case WETLAND:   return 4;
            case SHRUBLAND: return 6;
            case BUILT_UP:  return 10;
            case BARE: {
                // Step 5 palette: bare ground is not one
                // colour. (Was: temp>=12 -> RED_SAND, which
                // painted even the Sahara red.)
                double[] d = queryData(lat, lon);
                double precip = d[1];
                double temp = d[2];
                // Arid elevated country -> mesa painter
                // (terracotta bands): canyon/plateau rock.
                // NW(22): mesa paint needs >= 150 mm/yr
                // rain (PRECIP_ARID). Below that it is true
                // desert — Tibesti 12 mm and Erg Chebbi
                // 91 mm were painted as Arizona mesa.
                // Monument Valley (236 mm) keeps mesa.
                if (temp >= 8.0 && precip >= PRECIP_ARID
                        && precip < 500.0
                        && elevMeters >= 500.0
                        && elevMeters <= 2600.0)
                    return 2;
                if (temp >= 12.0) {
                    // Red-sand country = arid plateau sands;
                    // low hot desert (Sahara) stays yellow.
                    if (precip < 400.0 && isRedSandRegion(lat, lon))
                        return 7;
                    return 1;
                }
                return 1;
            }
            default: break;
        }

        Holder<Biome> biome = classifyAt(lat, lon, elevMeters, wcClass);
        if (biome == null)    return 0;
        if (isDesert(biome))  return 1;
        if (isMesa(biome))    return 2;
        if (isFrozen(biome))  return 3;
        if (isSwamp(biome))   return 4;
        if (isSavanna(biome)) return 5;

        // BEACH returns code 9 so getSurfaceBlock places sand
        // at coastline locations instead of grass
        if (isBeach(biome))   return 9;

        return 0;
    }

    public int getBiomeTypeCode(double lat, double lon, double elevMeters) {
        return getBiomeTypeCode(lat, lon, elevMeters, WorldCoverClass.UNKNOWN);
    }

    public int getBiomeTypeCode(double lat, double lon) {
        return getBiomeTypeCode(lat, lon, 0.0, WorldCoverClass.UNKNOWN);
    }

    // ================================================================
    // UTILITY
    // ================================================================

    @SafeVarargs
    private static Holder<Biome> safe(Holder<Biome>... holders) {
        for (Holder<Biome> h : holders) { if (h != null) return h; }
        return null;
    }
}