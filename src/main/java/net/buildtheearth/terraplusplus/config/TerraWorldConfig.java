// ============================================================
// FILE: TerraWorldConfig.java (FIXED — split codec for >16
// fields. RecordCodecBuilder.group() max is 16 parameters.
// We split into two partial codecs and merge them.
// ============================================================
package net.buildtheearth.terraplusplus.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class TerraWorldConfig {

    // ── PARTIAL RECORD A (fields 1-15) ───────────────────────
    // Holds the original 15 fields.
    private record PartA(
            boolean buildings, boolean roads,
            boolean railways, boolean bridges,
            boolean walls, boolean gardens,
            boolean landuse, boolean streetLights,
            boolean naturalFeatures, boolean waterBodies,
            int buildingDetail, double roadWidthScale,
            boolean cleanupArtifacts,
            boolean overtureBuildings,
            boolean overtureSupplement) {}

    // ── PARTIAL RECORD B (fields 16-19) ──────────────────────
    // Holds the 4 new time/OHM fields.
    private record PartB(
            boolean timeEnabled, int timeScale,
            int baseYear, boolean ohmEnabled) {}

    private static final Codec<PartA> CODEC_A =
            RecordCodecBuilder.create(i -> i.group(
                    Codec.BOOL.optionalFieldOf(
                            "buildings", true)
                            .forGetter(PartA::buildings),
                    Codec.BOOL.optionalFieldOf(
                            "roads", true)
                            .forGetter(PartA::roads),
                    Codec.BOOL.optionalFieldOf(
                            "railways", true)
                            .forGetter(PartA::railways),
                    Codec.BOOL.optionalFieldOf(
                            "bridges", true)
                            .forGetter(PartA::bridges),
                    Codec.BOOL.optionalFieldOf(
                            "walls", true)
                            .forGetter(PartA::walls),
                    Codec.BOOL.optionalFieldOf(
                            "gardens", true)
                            .forGetter(PartA::gardens),
                    Codec.BOOL.optionalFieldOf(
                            "landuse", true)
                            .forGetter(PartA::landuse),
                    Codec.BOOL.optionalFieldOf(
                            "street_lights", true)
                            .forGetter(PartA::streetLights),
                    Codec.BOOL.optionalFieldOf(
                            "natural_features", true)
                            .forGetter(PartA::naturalFeatures),
                    Codec.BOOL.optionalFieldOf(
                            "water_bodies", true)
                            .forGetter(PartA::waterBodies),
                    Codec.INT.optionalFieldOf(
                            "building_detail", 2)
                            .forGetter(PartA::buildingDetail),
                    Codec.DOUBLE.optionalFieldOf(
                            "road_width_scale", 1.0)
                            .forGetter(PartA::roadWidthScale),
                    Codec.BOOL.optionalFieldOf(
                            "cleanup_artifacts", true)
                            .forGetter(PartA::cleanupArtifacts),
                    Codec.BOOL.optionalFieldOf(
                            "overture_buildings", true)
                            .forGetter(PartA::overtureBuildings),
                    Codec.BOOL.optionalFieldOf(
                            "overture_supplement", true)
                            .forGetter(PartA::overtureSupplement)
            ).apply(i, PartA::new));

    private static final Codec<PartB> CODEC_B =
            RecordCodecBuilder.create(i -> i.group(
                    Codec.BOOL.optionalFieldOf(
                            "time_enabled", true)
                            .forGetter(PartB::timeEnabled),
                    Codec.INT.optionalFieldOf(
                            "time_scale", 1)
                            .forGetter(PartB::timeScale),
                    Codec.INT.optionalFieldOf(
                            "base_year", -3000)
                            .forGetter(PartB::baseYear),
                    Codec.BOOL.optionalFieldOf(
                            "ohm_enabled", true)
                            .forGetter(PartB::ohmEnabled)
            ).apply(i, PartB::new));

    // ── MAIN CODEC ────────────────────────────────────────────
    // Combines PartA and PartB using Codec.pair() then maps
    // to TerraWorldConfig.
    public static final Codec<TerraWorldConfig> CODEC =
            Codec.pair(CODEC_A, CODEC_B).xmap(
                    pair -> new TerraWorldConfig(
                            pair.getFirst().buildings(),
                            pair.getFirst().roads(),
                            pair.getFirst().railways(),
                            pair.getFirst().bridges(),
                            pair.getFirst().walls(),
                            pair.getFirst().gardens(),
                            pair.getFirst().landuse(),
                            pair.getFirst().streetLights(),
                            pair.getFirst().naturalFeatures(),
                            pair.getFirst().waterBodies(),
                            pair.getFirst().buildingDetail(),
                            pair.getFirst().roadWidthScale(),
                            pair.getFirst().cleanupArtifacts(),
                            pair.getFirst().overtureBuildings(),
                            pair.getFirst().overtureSupplement(),
                            pair.getSecond().timeEnabled(),
                            pair.getSecond().timeScale(),
                            pair.getSecond().baseYear(),
                            pair.getSecond().ohmEnabled()),
                    cfg -> com.mojang.datafixers.util.Pair.of(
                            new PartA(
                                    cfg.buildings,
                                    cfg.roads,
                                    cfg.railways,
                                    cfg.bridges,
                                    cfg.walls,
                                    cfg.gardens,
                                    cfg.landuse,
                                    cfg.streetLights,
                                    cfg.naturalFeatures,
                                    cfg.waterBodies,
                                    cfg.buildingDetail,
                                    cfg.roadWidthScale,
                                    cfg.cleanupArtifacts,
                                    cfg.overtureBuildings,
                                    cfg.overtureSupplement),
                            new PartB(
                                    cfg.timeEnabled,
                                    cfg.timeScale,
                                    cfg.baseYear,
                                    cfg.ohmEnabled)));

    // ── DEFAULT ───────────────────────────────────────────────
    public static final TerraWorldConfig DEFAULT =
            new TerraWorldConfig(
                    true,  // buildings
                    true,  // roads
                    true,  // railways
                    true,  // bridges
                    true,  // walls
                    true,  // gardens
                    true,  // landuse
                    true,  // streetLights
                    true,  // naturalFeatures
                    true,  // waterBodies
                    2,     // buildingDetail
                    1.0,   // roadWidthScale
                    true,  // cleanupArtifacts
                    true,  // overtureBuildings
                    true,  // overtureSupplement
                    true,  // timeEnabled
                    1,     // timeScale
                    -3000, // baseYear
                    true   // ohmEnabled
            );

    // ── FIELDS ────────────────────────────────────────────────
    public final boolean buildings;
    public final boolean roads;
    public final boolean railways;
    public final boolean bridges;
    public final boolean walls;
    public final boolean gardens;
    public final boolean landuse;
    public final boolean streetLights;
    public final boolean naturalFeatures;
    public final boolean waterBodies;
    public final int     buildingDetail;
    public final double  roadWidthScale;
    public final boolean cleanupArtifacts;
    public final boolean overtureBuildings;
    /** Enables VIDA / Source Cooperative buildings (tier between OSM and Microsoft). */
    public final boolean vidaBuildings;
    public final boolean overtureSupplement;
    public final boolean timeEnabled;
    public final int     timeScale;
    public final int     baseYear;
    public final boolean ohmEnabled;

    // ── CONSTRUCTOR ───────────────────────────────────────────
    public TerraWorldConfig(
            boolean buildings, boolean roads,
            boolean railways, boolean bridges,
            boolean walls, boolean gardens,
            boolean landuse, boolean streetLights,
            boolean naturalFeatures, boolean waterBodies,
            int buildingDetail, double roadWidthScale,
            boolean cleanupArtifacts,
            boolean overtureBuildings,
            boolean overtureSupplement,
            boolean timeEnabled, int timeScale,
            int baseYear, boolean ohmEnabled) {
        this(buildings, roads, railways, bridges, walls,
                gardens, landuse, streetLights,
                naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled,
                timeScale, baseYear, ohmEnabled,
                true);
    }

    /**
     * Full constructor including the VIDA (Source Cooperative) building toggle.
     *
     * VIDA sits between OSM and Microsoft in the source hierarchy:
     * OHM -> OSM -> VIDA -> Microsoft. The 19-argument overload above defaults
     * it to enabled so existing call sites keep working unchanged.
     */
    public TerraWorldConfig(
            boolean buildings, boolean roads,
            boolean railways, boolean bridges,
            boolean walls, boolean gardens,
            boolean landuse, boolean streetLights,
            boolean naturalFeatures, boolean waterBodies,
            int buildingDetail, double roadWidthScale,
            boolean cleanupArtifacts,
            boolean overtureBuildings,
            boolean overtureSupplement,
            boolean timeEnabled, int timeScale,
            int baseYear, boolean ohmEnabled,
            boolean vidaBuildings) {
        this.vidaBuildings     = vidaBuildings;
        this.buildings         = buildings;
        this.roads             = roads;
        this.railways          = railways;
        this.bridges           = bridges;
        this.walls             = walls;
        this.gardens           = gardens;
        this.landuse           = landuse;
        this.streetLights      = streetLights;
        this.naturalFeatures   = naturalFeatures;
        this.waterBodies       = waterBodies;
        this.buildingDetail    = Math.max(1,
                Math.min(3, buildingDetail));
        this.roadWidthScale    = Math.max(0.5,
                Math.min(2.0, roadWidthScale));
        this.cleanupArtifacts  = cleanupArtifacts;
        this.overtureBuildings = overtureBuildings;
        this.overtureSupplement = overtureSupplement;
        this.timeEnabled       = timeEnabled;
        this.timeScale         = clampTimeScale(timeScale);
        this.baseYear          = Math.max(-3000,
                Math.min(9999, baseYear));
        this.ohmEnabled        = ohmEnabled;
    }

    // ── TIME SCALE VALIDATION ─────────────────────────────────
    private static int clampTimeScale(int scale) {
        if (scale <= 1)  return 1;
        if (scale <= 10) return 10;
        if (scale <= 25) return 25;
        return 50;
    }

    // ── WITH-ERS ──────────────────────────────────────────────
    public TerraWorldConfig withBuildings(boolean v) {
        return new TerraWorldConfig(v, roads, railways,
                bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withRoads(boolean v) {
        return new TerraWorldConfig(buildings, v, railways,
                bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withRailways(boolean v) {
        return new TerraWorldConfig(buildings, roads, v,
                bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withBridges(boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, v, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withWalls(boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, v, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withGardens(boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, v, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withLanduse(boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, v,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withStreetLights(boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                v, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withNaturalFeatures(boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                streetLights, v, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withWaterBodies(boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, v,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withBuildingDetail(int v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                v, roadWidthScale, cleanupArtifacts,
                overtureBuildings, overtureSupplement,
                timeEnabled, timeScale, baseYear, ohmEnabled);
    }
    public TerraWorldConfig withRoadWidthScale(double v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, v, cleanupArtifacts,
                overtureBuildings, overtureSupplement,
                timeEnabled, timeScale, baseYear, ohmEnabled);
    }
    public TerraWorldConfig withCleanupArtifacts(boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale, v,
                overtureBuildings, overtureSupplement,
                timeEnabled, timeScale, baseYear, ohmEnabled);
    }
    public TerraWorldConfig withMicrosoftBuildings(boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, v, overtureSupplement,
                timeEnabled, timeScale, baseYear, ohmEnabled);
    }
    public TerraWorldConfig withOvertureSupplement(
            boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings, v,
                timeEnabled, timeScale, baseYear, ohmEnabled);
    }
    public TerraWorldConfig withTimeEnabled(boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, v, timeScale,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withTimeScale(int v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, v,
                baseYear, ohmEnabled);
    }
    public TerraWorldConfig withBaseYear(int v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                v, ohmEnabled);
    }
    public TerraWorldConfig withOhmEnabled(boolean v) {
        return new TerraWorldConfig(buildings, roads,
                railways, bridges, walls, gardens, landuse,
                streetLights, naturalFeatures, waterBodies,
                buildingDetail, roadWidthScale,
                cleanupArtifacts, overtureBuildings,
                overtureSupplement, timeEnabled, timeScale,
                baseYear, v);
    }

    // ── UTILITY ───────────────────────────────────────────────
    public boolean hasAnyStructures() {
        return buildings || roads || railways || bridges
                || walls || gardens || landuse
                || streetLights;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(
                "TerraWorldConfig[");
        if (buildings)         sb.append("bld,");
        if (roads)             sb.append("road,");
        if (railways)          sb.append("rail,");
        if (bridges)           sb.append("brg,");
        if (walls)             sb.append("wall,");
        if (gardens)           sb.append("park,");
        if (landuse)           sb.append("land,");
        if (streetLights)      sb.append("light,");
        if (naturalFeatures)   sb.append("nature,");
        if (waterBodies)       sb.append("water,");
        if (overtureBuildings) sb.append("overture,");
        if (overtureSupplement) sb.append("supplement,");
        if (timeEnabled)       sb.append("time,");
        if (ohmEnabled)        sb.append("ohm,");
        sb.append("detail=").append(buildingDetail);
        sb.append(",roadScale=").append(
                String.format("%.1f", roadWidthScale));
        sb.append(",timeScale=").append(timeScale)
                .append("x");
        sb.append(",baseYear=").append(baseYear);
        sb.append("]");
        return sb.toString();
    }
}