// ============================================================
// FILE: StructureDateEstimator.java
// Combines Monument Gravity Model and Population History Model
// into a single estimator used by HistoricalStructureManager.
//
// Monument Gravity Model:
//   Finds all nearby features with known dates (anchors).
//   Weight = (1 / distance²) × significance(type).
//   Pulls unknown feature's date toward weighted anchor mean.
//
// Population History Model:
//   HYDE-approximated hardcoded population curve per region.
//   Returns earliest possible year — hard floor that no
//   estimate can go below for a given location.
//   If region had zero population at year X → no buildings
//   before year X.
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import net.buildtheearth.terraplusplus.generator.osm
    .OsmStructureIndex;

import java.util.List;

public class StructureDateEstimator {

    // ── SIGNIFICANCE WEIGHTS ──────────────────────────────────
    // Higher = stronger gravitational pull on neighbours.
    // Monumental / religious structures are strong anchors.
    // Generic buildings are weak anchors.

    private static final double SIG_CATHEDRAL   = 3.0;
    private static final double SIG_CASTLE      = 2.5;
    private static final double SIG_CHURCH      = 2.0;
    private static final double SIG_MOSQUE      = 2.0;
    private static final double SIG_TEMPLE      = 2.0;
    private static final double SIG_PALACE      = 2.0;
    private static final double SIG_MONUMENT    = 2.0;
    private static final double SIG_WALL        = 1.8;
    private static final double SIG_BRIDGE      = 1.5;
    private static final double SIG_STATION     = 1.5;
    private static final double SIG_TOWNHALL    = 1.5;
    private static final double SIG_RAILWAY     = 1.4;
    private static final double SIG_ROAD        = 1.2;
    private static final double SIG_GENERIC     = 1.0;
    private static final double SIG_FARM        = 0.8;

    // Maximum distance in degrees for gravity pull
    // (~500m at equator)
    private static final double MAX_GRAVITY_DEG = 0.005;

    // Minimum weight sum before we trust the result
    private static final double MIN_WEIGHT_SUM  = 0.1;

    // ── POPULATION FLOOR CONSTANTS ────────────────────────────
    // Based on HYDE 3.2 approximate data.
    // Each region returns the earliest year at which
    // sustained settlement (hence buildings) can exist.
    // These are conservative floors — they will NOT prevent
    // ancient structures in regions with ancient history.

    // Year floors per region (inclusive lower bound)
    private static final int FLOOR_NORTH_AMERICA      = 1500;
    private static final int FLOOR_MESOAMERICA        = -1000;
    private static final int FLOOR_SOUTH_AMERICA      = -500;
    private static final int FLOOR_WESTERN_EUROPE     = -1000;
    private static final int FLOOR_EASTERN_EUROPE     = -500;
    private static final int FLOOR_MIDDLE_EAST        = -5000;
    private static final int FLOOR_CENTRAL_ASIA       = -2000;
    private static final int FLOOR_SOUTH_ASIA         = -2500;
    private static final int FLOOR_EAST_ASIA          = -2000;
    private static final int FLOOR_SOUTHEAST_ASIA     = -1000;
    private static final int FLOOR_OCEANIA            = 1700;
    private static final int FLOOR_SUB_SAHARAN_AFRICA = -2000;
    private static final int FLOOR_NORTH_AFRICA       = -3000;
    private static final int FLOOR_SIBERIA            = 1600;
    private static final int FLOOR_ARCTIC             = 1800;
    private static final int FLOOR_GLOBAL_FALLBACK    = 1800;

    // Region bounds (reused from OsmDateInferencer
    // but defined independently here so this class
    // has no dependency on it)

    private StructureDateEstimator() {}

    // ── PUBLIC API ────────────────────────────────────────────

    /**
     * Estimate start year using Monument Gravity Model
     * followed by Population History floor.
     *
     * @param targetLat    Latitude of unknown structure
     * @param targetLon    Longitude of unknown structure
     * @param targetType   StructureType of unknown structure
     * @param targetSubType SubType string (may be null)
     * @param anchors      All nearby structures with
     *                     known dates — OHM + dated OSM
     * @return Estimated start year, clamped to population
     *         floor. Never returns UNKNOWN_YEAR.
     */
    public static int estimate(
            double targetLat, double targetLon,
            OsmStructureIndex.StructureType targetType,
            String targetSubType,
            List<AnchorPoint> anchors) {

        int populationFloor =
            getPopulationFloor(targetLat, targetLon);

        // ── MONUMENT GRAVITY MODEL ────────────────
        int gravityYear = applyGravityModel(
            targetLat, targetLon,
            targetType, targetSubType,
            anchors, populationFloor);

        // Population floor is a hard minimum —
        // even if gravity says earlier, we clamp.
        // Technology floor is a second hard minimum:
        // gravity anchors near a medieval cathedral
        // must not date a railway to 1300.
        int estimated = Math.max(
            Math.max(gravityYear, populationFloor),
            getTechnologyFloor(
                targetType, targetSubType));

        // ── WORLD-LINE (axis 5) ───────────────────
        // On an alternative timeline, ESTIMATED dates shift by
        // the branch's development offset (event-driven when an
        // Eternal Engine provider is registered, seeded
        // otherwise). Explicit start_date tags never reach this
        // method, so history's fixed points stay fixed. Floors
        // still apply after the shift — no branch can create
        // railways before 1825.
        estimated = WorldLineRegistry.adjustEstimatedYear(
            estimated, targetLat, targetLon);
        return Math.max(
            Math.max(estimated, populationFloor),
            getTechnologyFloor(
                targetType, targetSubType));
    }

    /**
     * Earliest year a structure of this type can exist,
     * regardless of what nearby anchors suggest.
     * Prevents anachronisms: no railways before 1825,
     * no motorways before 1930, no airports before 1909.
     */
    public static int getTechnologyFloor(
            OsmStructureIndex.StructureType type,
            String subType) {
        if (type == null) return Integer.MIN_VALUE;
        String st = subType == null ? "" : subType;
        return switch (type) {
            case RAILWAY -> switch (st) {
                case "subway"          -> 1863; // London Underground
                case "tram"            -> 1881; // electric tram
                case "light_rail",
                     "monorail"        -> 1901;
                case "funicular"       -> 1825;
                default                -> 1825; // Stockton & Darlington
            };
            case PLATFORM              -> 1825;
            case ROAD -> switch (st) {
                case "motorway",
                     "motorway_link"   -> 1930; // autobahn era
                case "trunk",
                     "trunk_link"      -> 1920;
                case "cycleway"        -> 1885; // safety bicycle
                default -> Integer.MIN_VALUE;   // roads are ancient
            };
            case CYCLEWAY               -> 1885;
            case RUNWAY, APRON          -> 1909; // first airfields
            case STREET_LAMP            -> 1800; // gas lighting
            case PYLON                  -> 1880; // power transmission
            case BRIDGE -> switch (st) {
                case "suspension"       -> 1820;
                case "cable-stayed",
                     "cable_stayed"     -> 1950;
                default -> Integer.MIN_VALUE;   // bridges are ancient
            };
            case PARKING                -> 1900; // motor car
            case POOL                   -> 1830; // public baths era
            default -> Integer.MIN_VALUE;
        };
    }

    /**
     * Get only the population floor for a location.
     * Used when there are no anchors at all.
     */
    public static int getPopulationFloor(
            double lat, double lon) {

        // Arctic (above 70°N)
        if (lat > 70)
            return FLOOR_ARCTIC;

        // Siberia / Russia east
        if (lat >= 50 && lat <= 80
                && lon >= 60 && lon <= 180)
            return FLOOR_SIBERIA;

        // North America (excl. Mesoamerica)
        if (lat >= 30 && lat <= 72
                && lon >= -170 && lon <= -50)
            return FLOOR_NORTH_AMERICA;

        // Mesoamerica (Mexico + Central America)
        if (lat >= 8 && lat <= 30
                && lon >= -120 && lon <= -60)
            return FLOOR_MESOAMERICA;

        // South America
        if (lat >= -60 && lat <= 8
                && lon >= -82 && lon <= -34)
            return FLOOR_SOUTH_AMERICA;

        // Western Europe
        if (lat >= 35 && lat <= 72
                && lon >= -25 && lon <= 30)
            return FLOOR_WESTERN_EUROPE;

        // Eastern Europe
        if (lat >= 45 && lat <= 72
                && lon >= 30 && lon <= 60)
            return FLOOR_EASTERN_EUROPE;

        // North Africa (Mediterranean coast)
        if (lat >= 15 && lat <= 38
                && lon >= -20 && lon <= 42)
            return FLOOR_NORTH_AFRICA;

        // Middle East
        if (lat >= 10 && lat <= 42
                && lon >= 28 && lon <= 65)
            return FLOOR_MIDDLE_EAST;

        // Central Asia
        if (lat >= 35 && lat <= 55
                && lon >= 50 && lon <= 90)
            return FLOOR_CENTRAL_ASIA;

        // South Asia
        if (lat >= 5 && lat <= 38
                && lon >= 60 && lon <= 100)
            return FLOOR_SOUTH_ASIA;

        // East Asia
        if (lat >= 15 && lat <= 55
                && lon >= 100 && lon <= 145)
            return FLOOR_EAST_ASIA;

        // Southeast Asia
        if (lat >= -10 && lat <= 25
                && lon >= 95 && lon <= 145)
            return FLOOR_SOUTHEAST_ASIA;

        // Oceania / Australia / Pacific
        if (lat >= -50 && lat <= 0
                && lon >= 110 && lon <= 180)
            return FLOOR_OCEANIA;

        // Sub-Saharan Africa
        if (lat >= -35 && lat <= 15
                && lon >= -20 && lon <= 55)
            return FLOOR_SUB_SAHARAN_AFRICA;

        return FLOOR_GLOBAL_FALLBACK;
    }

    // ── GRAVITY MODEL ─────────────────────────────────────────

    private static int applyGravityModel(
            double targetLat, double targetLon,
            OsmStructureIndex.StructureType targetType,
            String targetSubType,
            List<AnchorPoint> anchors,
            int populationFloor) {

        if (anchors == null || anchors.isEmpty()) {
            // No anchors — return population floor
            // adjusted by subtype context
            return adjustBySubtype(
                populationFloor, targetSubType);
        }

        double weightedYearSum = 0.0;
        double totalWeight     = 0.0;

        for (AnchorPoint anchor : anchors) {
            double dLat =
                anchor.lat - targetLat;
            double dLon =
                anchor.lon - targetLon;
            double distSq =
                dLat * dLat + dLon * dLon;

            // Skip anchors beyond max radius
            if (distSq > MAX_GRAVITY_DEG
                    * MAX_GRAVITY_DEG)
                continue;

            // Avoid division by zero for
            // same-position anchors
            if (distSq < 1e-12)
                distSq = 1e-12;

            double significance =
                getSignificance(anchor.type,
                    anchor.subType);

            // Weight = significance / distance²
            double weight = significance / distSq;

            weightedYearSum +=
                anchor.year * weight;
            totalWeight += weight;
        }

        if (totalWeight < MIN_WEIGHT_SUM) {
            // Not enough anchor influence —
            // fall back to population floor
            return adjustBySubtype(
                populationFloor, targetSubType);
        }

        int gravityYear = (int) Math.round(
            weightedYearSum / totalWeight);

        // Apply subtype adjustment as a nudge
        // (±10% blend toward subtype estimate)
        gravityYear = adjustBySubtype(
            gravityYear, targetSubType);

        // Never earlier than population floor
        return Math.max(gravityYear, populationFloor);
    }

    // ── SIGNIFICANCE SCORE ────────────────────────────────────

    private static double getSignificance(
            OsmStructureIndex.StructureType type,
            String subType) {
        // Road, railway, bridge are decent anchors
        if (type == OsmStructureIndex
                .StructureType.RAILWAY)
            return SIG_RAILWAY;
        if (type == OsmStructureIndex
                .StructureType.ROAD)
            return SIG_ROAD;
        if (type == OsmStructureIndex
                .StructureType.BRIDGE)
            return SIG_BRIDGE;
        if (type == OsmStructureIndex
                .StructureType.WALL)
            return SIG_WALL;

        // Buildings — score by subtype
        if (type == OsmStructureIndex
                .StructureType.BUILDING
                || type == OsmStructureIndex
                .StructureType.BUILDING_PART) {
            if (subType == null) return SIG_GENERIC;
            return switch (subType.toLowerCase()
                    .trim()) {
                case "cathedral", "abbey",
                     "monastery", "convent",
                     "minster"          -> SIG_CATHEDRAL;
                case "castle", "fortress",
                     "fort", "citadel"  -> SIG_CASTLE;
                case "church", "chapel",
                     "basilica"         -> SIG_CHURCH;
                case "mosque", "masjid" -> SIG_MOSQUE;
                case "temple", "shrine",
                     "pagoda", "stupa"  -> SIG_TEMPLE;
                case "palace", "manor",
                     "chateau"          -> SIG_PALACE;
                case "monument",
                     "memorial"         -> SIG_MONUMENT;
                case "townhall",
                     "town_hall"        -> SIG_TOWNHALL;
                case "train_station",
                     "station"          -> SIG_STATION;
                case "barn", "stable",
                     "farm", "farmhouse" -> SIG_FARM;
                default                 -> SIG_GENERIC;
            };
        }

        return SIG_GENERIC;
    }

    // ── SUBTYPE ADJUSTMENT ────────────────────────────────────
    // A gentle nudge — if subtype implies a specific era,
    // blend 20% toward that era so gravity doesn't place
    // a "motorway" in 1200 AD just because a castle is nearby.

    private static int adjustBySubtype(
            int baseYear, String subType) {
        if (subType == null) return baseYear;
        int subtypeYear =
            OsmDateInferencer
                .inferFromBuildingSubtype(subType);
        if (subtypeYear ==
                HistoricalDateParser.UNKNOWN_YEAR)
            return baseYear;
        // 20% blend toward subtype year
        return (int) Math.round(
            baseYear * 0.8 + subtypeYear * 0.2);
    }

    // ── ANCHOR POINT ──────────────────────────────────────────

    /**
     * A dated anchor point used by the gravity model.
     * Constructed from OHM structures, dated OSM
     * structures, and dated Overture structures.
     */
    public static class AnchorPoint {
        public final double lat, lon;
        public final int    year;
        public final OsmStructureIndex.StructureType
            type;
        public final String subType;

        public AnchorPoint(double lat, double lon,
                int year,
                OsmStructureIndex.StructureType type,
                String subType) {
            this.lat     = lat;
            this.lon     = lon;
            this.year    = year;
            this.type    = type;
            this.subType = subType;
        }

        /**
         * Build from an OHM structure (always dated).
         */
        public static AnchorPoint fromOhm(
                OhmStructureIndex.OhmStructure s) {
            OsmStructureIndex.OsmStructure base =
                s.base;
            double lat = (base.minLat
                + base.maxLat) / 2.0;
            double lon = (base.minLon
                + base.maxLon) / 2.0;
            return new AnchorPoint(lat, lon,
                s.startYear, base.type,
                base.subType);
        }

        /**
         * Build from a dated OSM structure.
         * Only call if startYear != UNKNOWN_YEAR.
         */
        public static AnchorPoint fromOsm(
                OsmStructureIndex.OsmStructure s) {
            double lat = (s.minLat + s.maxLat) / 2.0;
            double lon = (s.minLon + s.maxLon) / 2.0;
            return new AnchorPoint(lat, lon,
                s.startYear, s.type, s.subType);
        }
    }
}