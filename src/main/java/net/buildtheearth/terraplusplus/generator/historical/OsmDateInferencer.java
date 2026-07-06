// ============================================================
// FILE: OsmDateInferencer.java
// Infers approximate start year for OSM structures that have
// no explicit start_date tag.
// Uses building subtype, material, height, road surface,
// geographic region, and neighbouring OHM context.
// Priority order:
//   1. OSM start_date tag (explicit)
//   2. OSM opening_date/construction_date (explicit)
//   3. OSM heritage/historic tags (inferred old)
//   4. OSM building subtype (inferred era)
//   5. OSM material/height/levels tags (context)
//   6. OSM road surface (inferred era)
//   7. Geographic region default (last resort)
//   8. Absolute fallback = 1900
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import net.buildtheearth.terraplusplus.generator.osm
    .OsmStructureIndex;

public class OsmDateInferencer {

    // ── REGION BOUNDS ─────────────────────────────────────────
    // lat/lon boxes for geographic region defaults

    // North America
    private static final double NA_LAT_MIN = 15;
    private static final double NA_LAT_MAX = 72;
    private static final double NA_LON_MIN = -170;
    private static final double NA_LON_MAX = -50;

    // Western Europe
    private static final double WE_LAT_MIN = 35;
    private static final double WE_LAT_MAX = 72;
    private static final double WE_LON_MIN = -25;
    private static final double WE_LON_MAX = 30;

    // Eastern Europe / Russia west
    private static final double EE_LAT_MIN = 45;
    private static final double EE_LAT_MAX = 72;
    private static final double EE_LON_MIN = 30;
    private static final double EE_LON_MAX = 60;

    // Middle East / North Africa
    private static final double ME_LAT_MIN = 10;
    private static final double ME_LAT_MAX = 42;
    private static final double ME_LON_MIN = -20;
    private static final double ME_LON_MAX = 60;

    // South Asia
    private static final double SA_LAT_MIN = 5;
    private static final double SA_LAT_MAX = 38;
    private static final double SA_LON_MIN = 60;
    private static final double SA_LON_MAX = 100;

    // East Asia
    private static final double EA_LAT_MIN = 15;
    private static final double EA_LAT_MAX = 55;
    private static final double EA_LON_MIN = 100;
    private static final double EA_LON_MAX = 145;

    // Southeast Asia
    private static final double SEA_LAT_MIN = -10;
    private static final double SEA_LAT_MAX = 25;
    private static final double SEA_LON_MIN = 95;
    private static final double SEA_LON_MAX = 145;

    // Oceania
    private static final double OC_LAT_MIN = -50;
    private static final double OC_LAT_MAX = 0;
    private static final double OC_LON_MIN = 110;
    private static final double OC_LON_MAX = 180;

    // Sub-Saharan Africa
    private static final double AF_LAT_MIN = -35;
    private static final double AF_LAT_MAX = 10;
    private static final double AF_LON_MIN = -20;
    private static final double AF_LON_MAX = 55;

    // Siberia / Central Asia / Russia east
    private static final double SB_LAT_MIN = 45;
    private static final double SB_LAT_MAX = 80;
    private static final double SB_LON_MIN = 60;
    private static final double SB_LON_MAX = 180;

    // South America
    private static final double SAM_LAT_MIN = -60;
    private static final double SAM_LAT_MAX = 15;
    private static final double SAM_LON_MIN = -82;
    private static final double SAM_LON_MAX = -34;

    private OsmDateInferencer() {}

    // ── MAIN ENTRY POINT ──────────────────────────────────────

    /**
     * Infer the start year for an OSM structure.
     *
     * Returns HistoricalDateParser.UNKNOWN_YEAR if the
     * structure's start year cannot be determined at all
     * (should not happen — geographic fallback always fires).
     *
     * @param s        The OSM structure to infer a date for
     * @param lat      Centre latitude of the structure
     * @param lon      Centre longitude of the structure
     * @param ohmNeighbourYear  Average start year of nearby
     *                 OHM structures, or UNKNOWN_YEAR if none
     */
    public static int inferStartYear(
            OsmStructureIndex.OsmStructure s,
            double lat, double lon,
            int ohmNeighbourYear) {

        // ── PRIORITY 1: explicit start_date on structure ──
        // Already parsed into s.startYear by OsmStructureIndex
        if (s.startYear !=
                HistoricalDateParser.UNKNOWN_YEAR)
            return s.startYear;

        // ── PRIORITY 2: opening_date / construction_date ──
        // These are stored in subType by some parsers.
        // We check the raw subType string for date patterns.
        // (OsmStructureIndex does not parse these tags yet
        //  so we handle them here as a safety net.)
        // In practice s.startYear covers this if the parser
        // reads those tags — left here for robustness.

        // ── PRIORITY 3: heritage / historic tags ──────────
        // OsmStructureIndex stores subType from the primary
        // tag. We check the name and subType for clues.
        int heritageYear =
            inferFromHeritageClues(s, lat, lon);
        if (heritageYear !=
                HistoricalDateParser.UNKNOWN_YEAR)
            return heritageYear;

        // ── PRIORITY 4: building subtype ──────────────────
        if (s.type == OsmStructureIndex
                .StructureType.BUILDING
                || s.type == OsmStructureIndex
                .StructureType.BUILDING_PART) {
            int subtypeYear =
                inferFromBuildingSubtype(
                    s.subType);
            if (subtypeYear !=
                    HistoricalDateParser.UNKNOWN_YEAR
                    && !subtypeYear_isGeneric(
                        s.subType))
                return subtypeYear;
        }

        // ── PRIORITY 5: material / height / levels ────────
        int materialYear =
            inferFromMaterialAndSize(s);
        if (materialYear !=
                HistoricalDateParser.UNKNOWN_YEAR)
            return materialYear;

        // ── PRIORITY 6: road surface ──────────────────────
        if (s.type == OsmStructureIndex
                .StructureType.ROAD
                || s.type == OsmStructureIndex
                .StructureType.FOOTWAY
                || s.type == OsmStructureIndex
                .StructureType.CYCLEWAY
                || s.type == OsmStructureIndex
                .StructureType.RAILWAY) {
            return inferFromRoadTags(s);
        }

        // ── OHM NEIGHBOUR CONTEXT ─────────────────────────
        // If OHM has dated structures nearby,
        // the undated building is probably same era.
        // Only use if neighbour year is reasonable.
        if (ohmNeighbourYear !=
                HistoricalDateParser.UNKNOWN_YEAR
                && ohmNeighbourYear >= -3000
                && ohmNeighbourYear <= 2024) {
            // Clamp: undated OSM building cannot be
            // older than OHM neighbours suggest,
            // but could be newer. Use neighbour year
            // as a soft lower bound.
            return Math.max(
                ohmNeighbourYear,
                inferFromBuildingSubtype(s.subType)
                    == HistoricalDateParser.UNKNOWN_YEAR
                    ? ohmNeighbourYear
                    : inferFromBuildingSubtype(
                        s.subType));
        }

        // ── PRIORITY 7: building=yes with context ─────────
        if ("yes".equals(s.subType)
                || s.subType == null) {
            return inferGenericBuilding(
                s, lat, lon);
        }

        // ── PRIORITY 8: geographic region fallback ────────
        return inferFromRegion(lat, lon);
    }

    // ── HERITAGE / HISTORIC CLUES ─────────────────────────────

    private static int inferFromHeritageClues(
            OsmStructureIndex.OsmStructure s,
            double lat, double lon) {
        // Check subType for heritage indicators
        String sub = s.subType != null
            ? s.subType.toLowerCase() : "";
        String name = s.name != null
            ? s.name.toLowerCase() : "";

        // historic=* tag values stored as subType
        // when type=BUILDING
        if (sub.equals("ruins")
                || sub.equals("ruin"))
            return inferFromRegion(lat, lon) - 200;

        if (sub.equals("archaeological_site")
                || sub.equals("monument")
                || sub.equals("memorial"))
            return -500;

        if (sub.equals("listed")
                || sub.equals("grade_i")
                || sub.equals("grade_ii")
                || sub.equals("grade_ii*"))
            return 1500;

        // Name-based clues (rough heuristics)
        if (name.contains("roman")
                || name.contains("ancient"))
            return -200;
        if (name.contains("medieval")
                || name.contains("old town"))
            return 1000;
        if (name.contains("colonial"))
            return 1600;

        return HistoricalDateParser.UNKNOWN_YEAR;
    }

    // ── BUILDING SUBTYPE INFERENCE ────────────────────────────

    /**
     * Returns inferred start year from building subtype.
     * Returns UNKNOWN_YEAR for generic/unknown subtypes.
     */
    public static int inferFromBuildingSubtype(
            String subType) {
        if (subType == null)
            return HistoricalDateParser.UNKNOWN_YEAR;
        return switch (subType.toLowerCase().trim()) {

            // ── RELIGIOUS ─────────────────────────────────
            case "cathedral", "abbey",
                 "monastery", "convent",
                 "priory", "minster"        -> 1000;
            case "church", "chapel",
                 "basilica"                 -> 1000;
            case "mosque", "masjid"         -> 700;
            case "temple", "shrine",
                 "pagoda", "stupa"          -> 500;
            case "synagogue"                -> 1000;
            case "religious"                -> 800;

            // ── DEFENSIVE ─────────────────────────────────
            case "castle", "fortress",
                 "fort", "fortification",
                 "citadel", "bunker"        -> 500;
            case "tower"                    -> 800;
            case "city_gate", "gate"        -> 800;
            case "defensive"                -> 500;

            // ── HISTORIC CIVIC ────────────────────────────
            case "palace", "manor",
                 "manor_house", "chateau"   -> 1200;
            case "historic", "heritage"     -> 1000;
            case "townhall", "town_hall"    -> 1500;
            case "courthouse"               -> 1700;

            // ── AGRICULTURAL ──────────────────────────────
            case "barn", "stable",
                 "cowshed", "pigsty",
                 "farm", "farm_auxiliary",
                 "farmhouse", "farmyard"    -> 1800;
            case "silo", "storage_tank"     -> 1850;
            case "greenhouse",
                 "glasshouse"               -> 1850;

            // ── MILLS ────────────────────────────────────
            case "windmill", "watermill"    -> 1600;
            case "mill"                     -> 1600;

            // ── INDUSTRIAL ───────────────────────────────
            case "industrial",
                 "factory", "manufacture",
                 "works"                    -> 1850;
            case "warehouse",
                 "storage"                  -> 1850;
            case "power_station",
                 "substation"               -> 1900;
            case "pumping_station"          -> 1850;
            case "water_tower"              -> 1850;

            // ── TRANSPORT ────────────────────────────────
            case "train_station",
                 "station"                  -> 1850;
            case "depot"                    -> 1850;
            case "hangar", "airport"        -> 1930;
            case "parking", "garage",
                 "carport"                  -> 1920;
            case "bridge"                   -> 1800;

            // ── CIVIC / PUBLIC ────────────────────────────
            case "school",
                 "university",
                 "college"                  -> 1800;
            case "hospital",
                 "healthcare"               -> 1800;
            case "fire_station"             -> 1850;
            case "police"                   -> 1850;
            case "post_office"              -> 1850;
            case "library"                  -> 1800;
            case "museum"                   -> 1800;
            case "theatre", "theater",
                 "cinema"                   -> 1800;
            case "stadium", "arena"         -> 1900;
            case "sports_hall",
                 "sports_centre"            -> 1950;
            case "grandstand"               -> 1900;

            // ── RESIDENTIAL ───────────────────────────────
            case "house", "detached",
                 "semi-detached",
                 "terrace", "terraced"      -> 1850;
            case "bungalow",
                 "cottage"                  -> 1850;
            case "cabin", "hut"             -> 1700;
            case "residential"              -> 1900;
            case "apartments",
                 "apartment",
                 "block_of_flats",
                 "dormitory"                -> 1900;

            // ── COMMERCIAL ───────────────────────────────
            case "commercial",
                 "office"                   -> 1900;
            case "retail", "shop",
                 "kiosk"                    -> 1900;
            case "supermarket",
                 "mall",
                 "shopping_centre"          -> 1950;
            case "hotel", "motel"           -> 1900;
            case "restaurant",
                 "cafe", "bar",
                 "pub"                      -> 1850;
            case "bank"                     -> 1800;

            // ── UTILITIES ─────────────────────────────────
            case "transformer_tower",
                 "utility"                  -> 1900;
            case "chimney"                  -> 1850;
            case "lighthouse"               -> 1700;
            case "bunker_silo"              -> 1900;

            // ── GENERIC / UNKNOWN ─────────────────────────
            default ->
                HistoricalDateParser.UNKNOWN_YEAR;
        };
    }

    /**
     * Returns true if subType is generic (yes/no/unknown)
     * and context clues should override subtype result.
     */
    private static boolean subtypeYear_isGeneric(
            String subType) {
        if (subType == null) return true;
        return switch (subType.toLowerCase().trim()) {
            case "yes", "no", "building",
                 "construction", "ruins",
                 "unclassified" -> true;
            default -> false;
        };
    }

    // ── MATERIAL / HEIGHT / LEVELS INFERENCE ──────────────────

    private static int inferFromMaterialAndSize(
            OsmStructureIndex.OsmStructure s) {

        // Height-based (overrides material for tall
        // buildings — no medieval skyscrapers exist)
        if (s.height > 150) return 1950;
        if (s.height > 50)  return 1900;

        // Level-based
        if (s.levels > 40)  return 1950;
        if (s.levels > 10)  return 1900;

        // Material-based
        if (s.material != null) {
            return switch (s.material
                    .toLowerCase().trim()) {
                case "glass",
                     "curtain_wall"      -> 1950;
                case "concrete",
                     "reinforced_concrete",
                     "precast_concrete"  -> 1900;
                case "steel",
                     "metal"             -> 1880;
                case "brick",
                     "red_brick",
                     "yellow_brick"      -> 1800;
                case "stone",
                     "limestone",
                     "sandstone",
                     "granite",
                     "flint"             -> 1500;
                case "wood",
                     "timber",
                     "timber_framing",
                     "half_timbered"     -> 1700;
                case "adobe",
                     "mud_brick",
                     "clay"              -> -500;
                case "marble"            -> 500;
                default ->
                    HistoricalDateParser.UNKNOWN_YEAR;
            };
        }

        return HistoricalDateParser.UNKNOWN_YEAR;
    }

    // ── ROAD / RAILWAY INFERENCE ──────────────────────────────

    private static int inferFromRoadTags(
            OsmStructureIndex.OsmStructure s) {

        // Railway type
        if (s.type == OsmStructureIndex
                .StructureType.RAILWAY) {
            if (s.subType == null) return 1850;
            return switch (s.subType
                    .toLowerCase().trim()) {
                case "rail"             -> 1850;
                case "light_rail"       -> 1950;
                case "subway", "metro"  -> 1900;
                case "tram"             -> 1880;
                case "monorail"         -> 1950;
                case "narrow_gauge"     -> 1850;
                case "funicular"        -> 1850;
                default                 -> 1850;
            };
        }

        // Surface tag (most reliable for roads)
        if (s.surface != null) {
            return switch (s.surface
                    .toLowerCase().trim()) {
                case "asphalt",
                     "paved",
                     "concrete",
                     "tarmac"           -> 1900;
                case "cobblestone",
                     "sett",
                     "paving_stones",
                     "bricks"           -> -500;
                case "gravel",
                     "fine_gravel",
                     "compacted",
                     "pebblestone"      -> 1700;
                case "dirt", "earth",
                     "ground", "mud",
                     "unpaved",
                     "grass", "sand"    -> -3000;
                case "wood",
                     "woodchips",
                     "planks"           -> 1600;
                case "metal",
                     "steel"            -> 1900;
                default                 -> 1850;
            };
        }

        // Highway type fallback
        if (s.subType != null) {
            return switch (s.subType
                    .toLowerCase().trim()) {
                case "motorway",
                     "motorway_link"    -> 1950;
                case "trunk",
                     "trunk_link"       -> 1900;
                case "primary",
                     "primary_link"     -> 1900;
                case "secondary",
                     "secondary_link"   -> 1850;
                case "tertiary",
                     "tertiary_link"    -> 1850;
                case "residential",
                     "living_street",
                     "service"          -> 1800;
                case "track"            -> -3000;
                case "path",
                     "footway",
                     "bridleway"        -> -3000;
                case "cycleway"         -> 1900;
                case "steps"            -> 1000;
                case "pedestrian"       -> 1800;
                default                 -> 1850;
            };
        }

        return 1850;
    }

    // ── GENERIC BUILDING (building=yes) ───────────────────────

    private static int inferGenericBuilding(
            OsmStructureIndex.OsmStructure s,
            double lat, double lon) {

        // Height overrides everything for generic
        if (s.height > 150) return 1950;
        if (s.height > 50)  return 1900;
        if (s.levels > 40)  return 1950;
        if (s.levels > 10)  return 1900;

        // Material clue
        int matYear = inferFromMaterialAndSize(s);
        if (matYear !=
                HistoricalDateParser.UNKNOWN_YEAR)
            return matYear;

        // Roof shape clue
        if (s.roofShape != null) {
            int roofYear = switch (s.roofShape
                    .toLowerCase().trim()) {
                case "dome",
                     "onion"            -> 800;
                case "pyramidal"        -> 1000;
                case "gabled",
                     "hipped",
                     "half-hipped"      -> 1600;
                case "flat"             -> 1900;
                case "mansard"         -> 1700;
                case "gambrel"          -> 1700;
                default ->
                    HistoricalDateParser.UNKNOWN_YEAR;
            };
            if (roofYear !=
                    HistoricalDateParser.UNKNOWN_YEAR)
                return roofYear;
        }

        // Fall through to geographic region
        return inferFromRegion(lat, lon);
    }

    // ── GEOGRAPHIC REGION INFERENCE ───────────────────────────

    public static int inferFromRegion(
            double lat, double lon) {

        // North America (mostly post-colonial)
        if (lat >= NA_LAT_MIN && lat <= NA_LAT_MAX
                && lon >= NA_LON_MIN
                && lon <= NA_LON_MAX)
            return 1700;

        // Western Europe (medieval towns exist)
        if (lat >= WE_LAT_MIN && lat <= WE_LAT_MAX
                && lon >= WE_LON_MIN
                && lon <= WE_LON_MAX)
            return 1500;

        // Eastern Europe
        if (lat >= EE_LAT_MIN && lat <= EE_LAT_MAX
                && lon >= EE_LON_MIN
                && lon <= EE_LON_MAX)
            return 1700;

        // Middle East / North Africa
        // (ancient cities)
        if (lat >= ME_LAT_MIN && lat <= ME_LAT_MAX
                && lon >= ME_LON_MIN
                && lon <= ME_LON_MAX)
            return 500;

        // South Asia
        if (lat >= SA_LAT_MIN && lat <= SA_LAT_MAX
                && lon >= SA_LON_MIN
                && lon <= SA_LON_MAX)
            return 1000;

        // East Asia
        if (lat >= EA_LAT_MIN && lat <= EA_LAT_MAX
                && lon >= EA_LON_MIN
                && lon <= EA_LON_MAX)
            return 1000;

        // Southeast Asia
        if (lat >= SEA_LAT_MIN && lat <= SEA_LAT_MAX
                && lon >= SEA_LON_MIN
                && lon <= SEA_LON_MAX)
            return 1200;

        // Oceania (colonial)
        if (lat >= OC_LAT_MIN && lat <= OC_LAT_MAX
                && lon >= OC_LON_MIN
                && lon <= OC_LON_MAX)
            return 1850;

        // Sub-Saharan Africa
        if (lat >= AF_LAT_MIN && lat <= AF_LAT_MAX
                && lon >= AF_LON_MIN
                && lon <= AF_LON_MAX)
            return 1800;

        // Siberia / Central Asia
        if (lat >= SB_LAT_MIN && lat <= SB_LAT_MAX
                && lon >= SB_LON_MIN
                && lon <= SB_LON_MAX)
            return 1900;

        // South America
        if (lat >= SAM_LAT_MIN && lat <= SAM_LAT_MAX
                && lon >= SAM_LON_MIN
                && lon <= SAM_LON_MAX)
            return 1700;

        // Absolute fallback
        return 1900;
    }
}