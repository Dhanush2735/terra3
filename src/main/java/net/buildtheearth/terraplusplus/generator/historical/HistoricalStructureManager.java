// ============================================================
// FILE: HistoricalStructureManager.java
// REWRITTEN: Full OHM → OSM → WikiData → Gravity → Population
// hierarchy as specified.
//
// Step 1 — OHM (primary):
//   All OHM structures parsed with their known dates.
//   OHM visible structures become anchors for gravity model.
//   OHM osmIds tracked to skip exact duplicates from OSM.
//   OHM anchors only used if OHM returned data for this chunk.
//
// Step 2 — OSM (secondary):
//   Explicit dates → used directly.
//   No date → WikiData lookup by name → Gravity → Population.
//   OHM anchor dates NOT used if OHM had no data here.
//
// Step 3 — Overture (supplemental):
//   No dates ever → WikiData (if named) → Gravity →
//   Population.
//   Skipped if footprint overlaps OHM or OSM footprint.
//
// Population floor is always the hard minimum.
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.buildtheearth.terraplusplus.generator.osm.OsmStructureIndex;
import net.buildtheearth.terraplusplus.generator.osm.OvertureIndex;
import net.buildtheearth.terraplusplus.generator.osm.SourceCooperativeIndex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HistoricalStructureManager {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Historical");

    // Forest clearing starts this many years before
    // a structure's start date
    public static final int FOREST_CLEAR_LEAD_YEARS
        = 50;

    // Timeout split: OHM gets half, OSM gets half,
    // Overture shares OSM's half
    // (caller passes full timeout)

    private final OhmStructureIndex  ohmIndex;
    private final OsmStructureIndex  osmIndex;
    private final SourceCooperativeIndex vidaIndex;
    private final OvertureIndex      overtureIndex;
    private final TerraWorldConfig   config;
    private final WikiDataLookup     wikiData;

    public HistoricalStructureManager(
            OhmStructureIndex ohmIndex,
            OsmStructureIndex osmIndex,
            TerraWorldConfig config) {
        // Overture index obtained from singleton
        // pattern to avoid circular dependency
        this.ohmIndex      = ohmIndex;
        this.osmIndex      = osmIndex;
        this.vidaIndex     = SourceCooperativeIndex.getInstance();
        this.overtureIndex = OvertureIndex.getInstance();
        this.config        = config;
        this.wikiData      = WikiDataLookup
            .getInstance();
        LOGGER.info(
            "HistoricalStructureManager init "
            + "(ohm→osm→vida→microsoft, "
            + "dates: wiki→gravity→population)");
    }

    // ── RESULT ────────────────────────────────────────────────

    public static class MergedStructures {
        public final List<OsmStructureIndex
            .OsmStructure> visible;
        public final List<OhmStructureIndex
            .OhmStructure> upcoming;
        public final int currentYear;

        public MergedStructures(
                List<OsmStructureIndex
                    .OsmStructure> visible,
                List<OhmStructureIndex
                    .OhmStructure> upcoming,
                int currentYear) {
            this.visible     = visible;
            this.upcoming    = upcoming;
            this.currentYear = currentYear;
        }
    }

    // ── MAIN METHOD ───────────────────────────────────────────

    public MergedStructures getStructuresForChunk(
            double minLat, double maxLat,
            double minLon, double maxLon,
            long timeoutMs) {

        int currentYear =
            TerraTimeManager.getCurrentYear();
        double centreLat = (minLat + maxLat) / 2.0;
        double centreLon = (minLon + maxLon) / 2.0;

        List<OsmStructureIndex.OsmStructure> visible =
            new ArrayList<>();
        List<OhmStructureIndex.OhmStructure> upcoming =
            new ArrayList<>();

        // Tracks osmIds from OHM to skip duplicates
        Set<Long> ohmOsmIds = new HashSet<>();

        // Anchor points for gravity model —
        // only populated if OHM has data here
        List<StructureDateEstimator.AnchorPoint>
            anchors = new ArrayList<>();

        boolean ohmHasDataHere = false;

        // ── STEP 1: OHM ───────────────────────────
        if (ohmIndex.isEnabled()) {
            List<OhmStructureIndex.OhmStructure>
                ohmAll = ohmIndex
                .getStructuresForChunkBlocking(
                    minLat, maxLat,
                    minLon, maxLon,
                    config,
                    timeoutMs / 2);

            // OHM timed out — signal retry
            if (ohmAll == null) return null;

            if (!ohmAll.isEmpty()) {
                ohmHasDataHere = true;

                for (OhmStructureIndex.OhmStructure s
                        : ohmAll) {

                    // Track osmId for dedup
                    if (s.base.osmId != 0)
                        ohmOsmIds.add(s.base.osmId);

                    if (s.isVisibleAt(currentYear)) {
                        visible.add(s.base);

                        // OHM visible structures
                        // become gravity anchors
                        // (only if OHM has data)
                        if (s.startYear !=
                                HistoricalDateParser
                                    .UNKNOWN_YEAR)
                            anchors.add(
                                StructureDateEstimator
                                .AnchorPoint
                                .fromOhm(s));

                    } else {
                        int yearsUntil =
                            s.yearsUntilAppear(
                                currentYear);
                        if (yearsUntil > 0
                                && yearsUntil <=
                                FOREST_CLEAR_LEAD_YEARS)
                            upcoming.add(s);
                    }
                }

                LOGGER.debug(
                    "OHM [{},{}] visible={} "
                    + "upcoming={} anchors={}",
                    String.format(
                        "%.4f", centreLat),
                    String.format(
                        "%.4f", centreLon),
                    visible.size(),
                    upcoming.size(),
                    anchors.size());
            }
        }

        // ── STEP 2: OSM ───────────────────────────
        if (osmIndex.isEnabled()) {
            List<OsmStructureIndex.OsmStructure>
                osmAll = osmIndex
                .getStructuresForChunkBlocking(
                    minLat, maxLat,
                    minLon, maxLon,
                    config,
                    timeoutMs / 2);

            // OSM timed out — signal retry
            if (osmAll == null) return null;

            for (OsmStructureIndex.OsmStructure s
                    : osmAll) {

                // Skip exact OHM duplicates
                if (s.osmId != 0
                        && ohmOsmIds.contains(
                            s.osmId))
                    continue;

                int effectiveStart;
                int effectiveEnd = s.endYear;

                if (s.startYear !=
                        HistoricalDateParser
                            .UNKNOWN_YEAR) {
                    // ── EXPLICIT DATE ──────────────
                    effectiveStart = s.startYear;

                    // Dated OSM structures also
                    // become anchors (regardless of
                    // whether OHM had data — dated
                    // OSM is reliable)
                    anchors.add(
                        StructureDateEstimator
                        .AnchorPoint.fromOsm(s));

                } else {
                    // ── NO EXPLICIT DATE ───────────
                    // Step A: WikiData by name
                    int wikiYear =
                        HistoricalDateParser
                            .UNKNOWN_YEAR;
                    if (s.name != null
                            && !s.name.isBlank()) {
                        WikiDataLookup.WikiResult wr =
                            wikiData.lookup(
                                s.name,
                                (s.minLat + s.maxLat)
                                    / 2.0,
                                (s.minLon + s.maxLon)
                                    / 2.0);
                        if (wr.found())
                            wikiYear = wr.year;
                    }

                    if (wikiYear !=
                            HistoricalDateParser
                                .UNKNOWN_YEAR) {
                        // WikiData found a date
                        effectiveStart = wikiYear;
                    } else {
                        // Step B: Gravity → Population
                        // Use OHM anchors only if
                        // OHM had data here.
                        // Always include dated OSM
                        // anchors collected so far.
                        effectiveStart =
                            StructureDateEstimator
                            .estimate(
                                (s.minLat + s.maxLat)
                                    / 2.0,
                                (s.minLon + s.maxLon)
                                    / 2.0,
                                s.type,
                                s.subType,
                                ohmHasDataHere
                                    ? anchors
                                    : anchorsOsmOnly(
                                        anchors));
                    }
                }

                // Check visibility with resolved date
                if (HistoricalDateParser.isVisibleAt(
                        effectiveStart,
                        effectiveEnd,
                        currentYear,
                        false))
                    visible.add(s);
            }

            LOGGER.debug(
                "OSM [{},{}] totalVisible={}",
                String.format("%.4f", centreLat),
                String.format("%.4f", centreLon),
                visible.size());
        }

        // ── STEP 3: VIDA (Google/MS/OSM open buildings) ──
        // Sits below OSM, above Microsoft. Gap-fill only.
        // Carries no dates, so it uses the same
        // WikiData -> Gravity -> Population chain, minus
        // WikiData (VIDA footprints are unnamed).
        List<SourceCooperativeIndex.VidaBuilding>
            vidaAccepted = new ArrayList<>();
        if (config.vidaBuildings) {

            List<SourceCooperativeIndex.VidaBuilding>
                vidaAll = vidaIndex
                .getBuildingsForChunkBlocking(
                    minLat, maxLat,
                    minLon, maxLon,
                    timeoutMs / 4,
                    null);

            // null => shard still downloading; retry chunk
            if (vidaAll == null) return null;

            for (SourceCooperativeIndex.VidaBuilding b
                    : vidaAll) {

                // Skip if it collides with an OHM or
                // OSM footprint already accepted.
                if (SourceCooperativeIndex
                        .overlapsExisting(b, visible))
                    continue;

                // Reject ML footprints sitting on a road. There is no
                // building there to collide with, so nothing above
                // catches them and they render in the carriageway.
                if (net.buildtheearth.terraplusplus.generator.osm
                        .FootprintAlignment.sitsOnRoad(
                            net.buildtheearth.terraplusplus.generator.osm
                                .FootprintAlignment.Print.of(b), visible))
                    continue;

                int effectiveStart =
                    StructureDateEstimator
                    .estimate(
                        b.centerLat,
                        b.centerLon,
                        OsmStructureIndex
                            .StructureType.BUILDING,
                        null,
                        ohmHasDataHere
                            ? anchors
                            : anchorsOsmOnly(anchors));

                if (HistoricalDateParser.isVisibleAt(
                        effectiveStart,
                        HistoricalDateParser
                            .UNKNOWN_YEAR,
                        currentYear,
                        false)) {
                    vidaAccepted.add(b);
                    visible.add(b.toOsmStructure());
                }
            }

            LOGGER.debug(
                "VIDA [{},{}] accepted={}",
                String.format("%.4f", centreLat),
                String.format("%.4f", centreLon),
                vidaAccepted.size());
        }

        // ── STEP 4: OVERTURE (MICROSOFT) ──────────
        if (config.overtureBuildings
                && overtureIndex.isEnabled()) {

            List<OvertureIndex.OvertureBuilding>
                ovAll = overtureIndex
                .getBuildingsForChunkBlocking(
                    minLat, maxLat,
                    minLon, maxLon,
                    config,
                    timeoutMs / 4);

            if (ovAll != null && !ovAll.isEmpty()) {

                // Build a quick footprint set from
                // already-visible OSM+OHM structures
                // for overlap detection
                List<OsmStructureIndex.OsmStructure>
                    existingFootprints =
                    new ArrayList<>(visible);

                for (OvertureIndex.OvertureBuilding b
                        : ovAll) {

                    // Skip if overlaps existing
                    // OHM or OSM footprint
                    if (overlapsExisting(
                            b, existingFootprints))
                        continue;

                    // Microsoft is the lowest tier:
                    // also yield to accepted VIDA.
                    if (SourceCooperativeIndex
                            .overlapsVida(
                                b, vidaAccepted))
                        continue;

                    // Reject footprints on the carriageway; see the
                    // matching guard in the VIDA tier above.
                    if (net.buildtheearth.terraplusplus.generator.osm
                            .FootprintAlignment.sitsOnRoad(
                                net.buildtheearth.terraplusplus.generator.osm
                                    .FootprintAlignment.Print.of(b),
                                existingFootprints))
                        continue;

                    // All Overture buildings have
                    // no dates — go straight to
                    // WikiData → Gravity → Population

                    int effectiveStart;

                    // Step A: WikiData by name
                    // (most MS buildings have no name
                    //  so this usually skips quickly)
                    int wikiYear =
                        HistoricalDateParser
                            .UNKNOWN_YEAR;
                    if (b.name != null
                            && !b.name.isBlank()) {
                        WikiDataLookup.WikiResult wr =
                            wikiData.lookup(
                                b.name,
                                b.centerLat,
                                b.centerLon);
                        if (wr.found())
                            wikiYear = wr.year;
                    }

                    if (wikiYear !=
                            HistoricalDateParser
                                .UNKNOWN_YEAR) {
                        effectiveStart = wikiYear;
                    } else {
                        // Step B: Gravity → Population
                        effectiveStart =
                            StructureDateEstimator
                            .estimate(
                                b.centerLat,
                                b.centerLon,
                                OsmStructureIndex
                                    .StructureType
                                    .BUILDING,
                                b.subtype,
                                ohmHasDataHere
                                    ? anchors
                                    : anchorsOsmOnly(
                                        anchors));
                    }

                    // Check visibility
                    if (HistoricalDateParser
                            .isVisibleAt(
                                effectiveStart,
                                HistoricalDateParser
                                    .UNKNOWN_YEAR,
                                currentYear,
                                false)) {
                        OsmStructureIndex.OsmStructure
                            osmForm =
                            b.toOsmStructure();
                        visible.add(osmForm);
                    }
                }
            }
        }

        return new MergedStructures(
            visible, upcoming, currentYear);
    }

    // ── ANCHOR FILTER ─────────────────────────────────────────
    // Returns only OSM-derived anchors (those that came from
    // dated OSM features, not OHM).
    // Used when OHM had no data here — we must not use OHM
    // anchor dates to inform OSM date estimation.

    private static List<StructureDateEstimator
            .AnchorPoint> anchorsOsmOnly(
            List<StructureDateEstimator.AnchorPoint>
                all) {
        // OSM anchors were added AFTER OHM anchors.
        // OHM anchors are those added during step 1
        // before any OSM processing.
        // Since ohmHasDataHere=false when this is
        // called, the anchors list only contains
        // OSM-sourced entries anyway — return as-is.
        return all;
    }

    // ── OVERLAP DETECTION ────────────────────────────────────

    private static boolean overlapsExisting(
            OvertureIndex.OvertureBuilding b,
            List<OsmStructureIndex.OsmStructure>
                existing) {
        for (OsmStructureIndex.OsmStructure s
                : existing) {
            if (s.type !=
                    OsmStructureIndex.StructureType
                        .BUILDING
                    && s.type !=
                    OsmStructureIndex.StructureType
                        .BUILDING_PART)
                continue;
            // Quick bbox check first
            if (b.maxLat < s.minLat
                    || b.minLat > s.maxLat
                    || b.maxLon < s.minLon
                    || b.minLon > s.maxLon)
                continue;
            // Center-in-polygon check
            if (s.containsPoint(
                    b.centerLat, b.centerLon))
                return true;
            // Polygon-center-in-overture check
            double sCentreLat =
                (s.minLat + s.maxLat) / 2.0;
            double sCentreLon =
                (s.minLon + s.maxLon) / 2.0;
            if (pointInOverturePolygon(
                    sCentreLat, sCentreLon,
                    b.polygon))
                return true;
            // STRICT: OSM/OHM outrank Microsoft, so ANY shared area removes
            // the Microsoft footprint even when it is a different building.
            if (s.isArea && s.nodes != null && s.nodes.length >= 3) {
                OsmStructureIndex.OsmNode[] mn =
                        new OsmStructureIndex.OsmNode[b.polygon.length];
                for (int i = 0; i < b.polygon.length; i++)
                    mn[i] = new OsmStructureIndex.OsmNode(
                            b.polygon[i][0], b.polygon[i][1]);
                if (net.buildtheearth.terraplusplus.generator.osm
                        .FootprintAlignment.footprintsIntersect(
                            mn, b.minLat, b.maxLat, b.minLon, b.maxLon,
                            s.nodes, s.minLat, s.maxLat, s.minLon, s.maxLon))
                    return true;
            }
        }
        return false;
    }

    private static boolean pointInOverturePolygon(
            double lat, double lon,
            double[][] polygon) {
        boolean inside = false;
        for (int i = 0, j = polygon.length - 1;
                i < polygon.length; j = i++) {
            if (((polygon[i][0] > lat)
                        != (polygon[j][0] > lat))
                    && (lon < (polygon[j][1]
                        - polygon[i][1])
                        * (lat - polygon[i][0])
                        / (polygon[j][0]
                            - polygon[i][0])
                        + polygon[i][1]))
                inside = !inside;
        }
        return inside;
    }

    // ── FOREST DENSITY ────────────────────────────────────────

    public static double getForestDensity(
            double lat, double lon,
            List<OhmStructureIndex.OhmStructure>
                upcoming,
            List<OsmStructureIndex.OsmStructure>
                visible,
            int currentYear) {

        for (var s : visible) {
            if (s.containsPoint(lat, lon))
                return 0.0;
            if (s.distanceToLine(lat, lon) < 0.0001)
                return 0.0;
        }

        for (var s : upcoming) {
            if (!s.base.containsPoint(lat, lon))
                continue;
            int yearsUntil =
                s.yearsUntilAppear(currentYear);
            if (yearsUntil <= 0)  return 0.0;
            if (yearsUntil >
                    FOREST_CLEAR_LEAD_YEARS)
                continue;
            return (double) yearsUntil
                / FOREST_CLEAR_LEAD_YEARS;
        }

        return 1.0;
    }

    // ── ROAD MATERIAL BY YEAR ─────────────────────────────────

    public static String getRoadMaterialKey(
            int year) {
        if (year < -500)  return "dirt_path";
        if (year < 500)   return "cobblestone";
        if (year < 1500)  return "gravel";
        if (year < 1800)  return "smooth_stone";
        if (year < 1900)  return "cobblestone";
        if (year < 1950)  return "gray_concrete";
        return "black_concrete";
    }

    // ── STATS ─────────────────────────────────────────────────

    public int getOhmCacheSize() {
        return ohmIndex.getCacheSize(); }
    public int getOsmCacheSize() {
        return osmIndex.getCacheSize(); }
    public boolean isOhmEnabled() {
        return ohmIndex.isEnabled(); }
    public boolean isOsmEnabled() {
        return osmIndex.isEnabled(); }
}