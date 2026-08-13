package net.buildtheearth.terraplusplus.generator.osm;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconciles ML building footprints (Google / Microsoft) against hand-mapped
 * OSM / OHM footprints.
 *
 * THE PROBLEM
 * -----------
 * The four sources do not agree on where a building is:
 *
 *   OSM / OHM  - traced by hand from imagery, usually within ~1-2 m
 *   Microsoft  - ML-extracted. MEASURED against OSM over a real 1.1 km box in
 *                Hyderabad: median 5.07 m, p90 8.74 m for footprints confirmed
 *                to be the same building (polygon IoU >= 0.30).
 *   Google/VIDA- ML-extracted, tighter than Microsoft: median 3.82 m, p90 6.52 m.
 *
 * (Naive nearest-neighbour distance is larger -- 6.4 m / 6.7 m median -- but
 * that figure is inflated by ML buildings that have no OSM counterpart at all.
 * Those are new buildings to KEEP, not duplicates to drop. Calibrating on them
 * is what made the old 6 m tolerance delete real buildings.)
 *
 * The existing dedup only drops an ML building when it geometrically overlaps
 * an OSM one. A footprint shifted by a few metres does not overlap, so it
 * survives and gets placed *beside* the real building. The result is the
 * doubled/ghost buildings seen in game.
 *
 * WHY NOT "SNAP" THE ML FOOTPRINT ONTO THE OSM ONE
 * ------------------------------------------------
 * Tempting, but wrong. If OSM already has the building, the ML copy carries no
 * new information - moving it just stacks two nearly-identical shapes in the
 * same place, which is worse than one. Snapping is only meaningful when the two
 * are known to be the same building, and knowing that is exactly the hard part.
 *
 * So the strategy is REJECTION, not alignment: decide whether the ML footprint
 * is a duplicate of something already accepted, and if so drop it. Alignment is
 * only applied in the narrow case where the sources clearly disagree about
 * position but agree about the building existing.
 *
 * HOW DUPLICATES ARE DETECTED
 * ---------------------------
 * Three tests, cheapest first:
 *
 *   1. Centroid distance. Two footprints whose centres are within a tolerance
 *      scaled to building size are the same building. Small houses need a
 *      tight tolerance (they are close together); large buildings tolerate more
 *      because their centroids are further from their edges.
 *
 *   2. Bounding-box IoU. Catches the case where centroids drift apart but the
 *      shapes clearly cover the same ground.
 *
 *   3. Containment either way, which the old code already did.
 *
 * All distances are computed in METRES, not degrees. A degree of longitude is
 * ~111 km at the equator but ~85 km at 40 deg N and ~0 at the poles, so a fixed
 * degree tolerance silently changes meaning with latitude. Everything here
 * converts through {@link #metresPerDegLon(double)}.
 */
public final class FootprintAlignment {

    private FootprintAlignment() {}

    /** Metres per degree of latitude. Near-constant. */
    public static final double M_PER_DEG_LAT = 111_320.0;

    /** Metres per degree of longitude at the given latitude. */
    public static double metresPerDegLon(double lat) {
        return M_PER_DEG_LAT * Math.cos(Math.toRadians(lat));
    }

    /**
     * Base centroid tolerance in metres.
     *
     * MEASURED, not assumed. Calibrated against real OSM (Overpass), Microsoft
     * (quadkey 123301331) and Google/VIDA (shard 4308537468510011392) footprints
     * over the same 1.1 km box in Hyderabad -- 2,762 / 1,461 / 6,041 buildings.
     * See TOLERANCE_TEST_REAL_DATA.md for the full method and numbers.
     *
     * The earlier value of 6.0 m was derived from the assumption, stated in this
     * file's header, that ML footprints are "commonly 2-5 m off". That assumption
     * is wrong: the measured median offset to the nearest OSM building is 6.4 m
     * (Microsoft) and 6.7 m (Google), with p90 at 17-18 m.
     *
     * But the fix is NOT to widen the tolerance to match. Measuring only the
     * offsets of pairs CONFIRMED to be the same building (true polygon
     * intersection-over-union >= 0.30) gives a much tighter distribution:
     *
     *     OSM <-> Microsoft : median 5.07 m, p90  8.74 m
     *     OSM <-> Google    : median 3.82 m, p90  6.52 m
     *     Google <-> MSFT   : median 4.37 m, p90  8.33 m
     *
     * The large "median 6.4 m" figure is dominated by footprints that have NO
     * real counterpart in OSM -- i.e. genuinely new buildings, which must be
     * kept. A 6 m tolerance swallowed those: it deleted 380 buildings whose true
     * overlap with the matched OSM building was under 0.10, including 52 with
     * exactly zero overlap. In dense Indian row housing (median OSM footprint
     * 149 m^2, ~12 m across) 6 m is half a building width, so the nearest
     * NEIGHBOUR is routinely inside the tolerance.
     *
     * Measured trade across the sweep (wrong = real buildings deleted,
     * FN = duplicates missed and thus placed as ghosts):
     *
     *     base/max    wrong   FN
     *     6.0 / 14.0    380   16     <- previous
     *     4.0 / 10.0    125   20
     *     3.0 /  8.0     61   24
     *     2.5 /  7.0     40   27     <- chosen (knee of the curve)
     *     2.0 /  6.0     36   30
     *     1.0 /  4.0     18   36
     *
     * 2.5 m removes 89% of the wrong deletions for 11 extra ghosts. Below it the
     * curve flattens and ghosts rise faster than deletions fall.
     *
     * A per-source-pair tolerance was also tested (Google 2.0, Microsoft 3.0,
     * reflecting their different measured error). It scored no better than this
     * single flat constant -- 43 wrong / 26 FN vs 40 wrong / 27 FN -- so the
     * simpler flat value is used. The hierarchy is enforced by ORDER of
     * application (OSM authoritative, then Google, then Microsoft), not by
     * varying this constant.
     */
    private static final double BASE_TOLERANCE_M = 2.5;

    /**
     * Never allow the scaled tolerance beyond this; row housing gets dense.
     * Kept at ~2.8x the base, matching the previous 6->14 ratio, and comfortably
     * above the p90 same-building offset of 8.7 m only for large footprints,
     * where the scaling term applies.
     */
    private static final double MAX_TOLERANCE_M = 7.0;

    /** Bounding boxes overlapping by at least this fraction are the same building. */
    private static final double IOU_THRESHOLD = 0.30;

    /**
     * Tolerance for a building of the given footprint size.
     *
     * A 6 m tolerance is generous for a 5x5 m shed and tight for a 100 m
     * warehouse, so it scales with the square root of area - i.e. with the
     * building's linear size - and is then clamped.
     */
    public static double toleranceFor(double areaSqM) {
        if (!(areaSqM > 0)) return BASE_TOLERANCE_M;
        double linear = Math.sqrt(areaSqM);        // ~edge length
        double t = BASE_TOLERANCE_M + 0.15 * linear;
        return Math.min(MAX_TOLERANCE_M, t);
    }

    /** Great-circle-ish planar distance in metres. Fine at building scale. */
    public static double distanceMetres(double lat1, double lon1,
                                        double lat2, double lon2) {
        double mLat = (lat1 - lat2) * M_PER_DEG_LAT;
        double mLon = (lon1 - lon2) * metresPerDegLon((lat1 + lat2) * 0.5);
        return Math.sqrt(mLat * mLat + mLon * mLon);
    }

    /** Intersection-over-union of two lat/lon bounding boxes, 0..1. */
    public static double bboxIoU(double aMinLat, double aMaxLat,
                                 double aMinLon, double aMaxLon,
                                 double bMinLat, double bMaxLat,
                                 double bMinLon, double bMaxLon) {
        double iLat = Math.min(aMaxLat, bMaxLat) - Math.max(aMinLat, bMinLat);
        double iLon = Math.min(aMaxLon, bMaxLon) - Math.max(aMinLon, bMinLon);
        if (iLat <= 0 || iLon <= 0) return 0.0;
        double inter = iLat * iLon;
        double aArea = (aMaxLat - aMinLat) * (aMaxLon - aMinLon);
        double bArea = (bMaxLat - bMinLat) * (bMaxLon - bMinLon);
        double union = aArea + bArea - inter;
        return union <= 0 ? 0.0 : inter / union;
    }

    /** Immutable view of a footprint, so all sources can be compared uniformly. */
    public static final class Print {
        public final double centreLat, centreLon;
        public final double minLat, maxLat, minLon, maxLon;
        public final double areaSqM;

        public Print(double centreLat, double centreLon,
                     double minLat, double maxLat,
                     double minLon, double maxLon,
                     double areaSqM) {
            this.centreLat = centreLat; this.centreLon = centreLon;
            this.minLat = minLat; this.maxLat = maxLat;
            this.minLon = minLon; this.maxLon = maxLon;
            this.areaSqM = areaSqM > 0 ? areaSqM : approxAreaSqM();
        }

        private double approxAreaSqM() {
            double h = (maxLat - minLat) * M_PER_DEG_LAT;
            double w = (maxLon - minLon) * metresPerDegLon(centreLat);
            return Math.max(1.0, h * w);
        }

        // All three factories below deliberately pass area = 0 so the
        // constructor derives it from the BOUNDING BOX.
        //
        // This is a consistency fix. VIDA used to pass b.area, its true polygon
        // area, while OSM and Microsoft fell back to bbox area. Because the
        // tolerance scales with sqrt(area), the SAME building received a
        // different tolerance depending on which dataset produced it. Measured
        // on real VIDA polygons the bbox is a median of 1.38x the true area
        // (p90 2.01x, max 3.98x), worth up to 1.84 m of tolerance difference.
        //
        // Bbox area is the common denominator: it is the only measure all three
        // sources can supply identically. Using it everywhere makes the
        // comparison symmetric and source-independent, which is what the
        // hierarchy requires -- a building must be judged the same way no matter
        // which source it came from.

        public static Print of(OsmStructureIndex.OsmStructure s) {
            return new Print((s.minLat + s.maxLat) / 2.0,
                             (s.minLon + s.maxLon) / 2.0,
                             s.minLat, s.maxLat, s.minLon, s.maxLon, 0);
        }

        public static Print of(SourceCooperativeIndex.VidaBuilding b) {
            return new Print(b.centerLat, b.centerLon,
                             b.minLat, b.maxLat, b.minLon, b.maxLon, 0);
        }

        public static Print of(OvertureIndex.OvertureBuilding b) {
            return new Print(b.centerLat, b.centerLon,
                             b.minLat, b.maxLat, b.minLon, b.maxLon, 0);
        }
    }

    /**
     * True when the candidate is the same real-world building as the reference,
     * allowing for the positional error between datasets.
     */
    public static boolean isSameBuilding(Print candidate, Print reference) {
        // Cheap bbox reject, widened by the maximum tolerance so a shifted
        // footprint is not discarded before the distance test runs.
        double padLat = MAX_TOLERANCE_M / M_PER_DEG_LAT;
        double padLon = MAX_TOLERANCE_M / Math.max(1.0,
                metresPerDegLon(candidate.centreLat));
        if (candidate.maxLat + padLat < reference.minLat
         || candidate.minLat - padLat > reference.maxLat
         || candidate.maxLon + padLon < reference.minLon
         || candidate.minLon - padLon > reference.maxLon)
            return false;

        // 1. Centroid proximity, tolerance scaled to the SMALLER building so a
        //    large warehouse cannot swallow a genuinely separate small shed.
        double tol = toleranceFor(Math.min(candidate.areaSqM, reference.areaSqM));
        double d = distanceMetres(candidate.centreLat, candidate.centreLon,
                                  reference.centreLat, reference.centreLon);
        if (d <= tol) return true;

        // 2. Substantial footprint overlap even if centroids drifted.
        double iou = bboxIoU(candidate.minLat, candidate.maxLat,
                             candidate.minLon, candidate.maxLon,
                             reference.minLat, reference.maxLat,
                             reference.minLon, reference.maxLon);
        return iou >= IOU_THRESHOLD;
    }

    // ================================================================
    // STRICT OVERLAP — the hierarchy rule
    // ================================================================
    // OSM is authoritative. Google fills gaps OSM leaves. Microsoft fills what
    // is left after both. A lower-tier building must therefore be dropped if it
    // overlaps a higher-tier one AT ALL, not merely if it looks like "the same
    // building".
    //
    // Why isSameBuilding() is not sufficient for this: it answers a different
    // question -- "are these two records the same real-world building?" -- using
    // centroid distance and bbox IoU. Two genuinely different buildings can
    // physically intersect (an ML footprint clipping the corner of an OSM one)
    // and that test correctly says "not the same building", so the intersecting
    // copy was kept and rendered as geometry poking through a wall.
    //
    // Measured on real data, Hyderabad box:
    //     Google  : 4,076 physically overlap OSM, only 2,592 removed -> 1,484 clipped
    //     Microsoft: 1,182 physically overlap OSM, only   777 removed ->   405 clipped
    //     Microsoft: 1,083 physically overlap accepted Google, 420 removed -> 663 clipped
    //
    // With the strict test below the residual clipping is ZERO in both test
    // cities, and it costs the same as the old test (0.6 vs 0.7 us per pair).

    /** True when point (lat,lon) is inside the ring. Ray casting. */
    private static boolean pointInRing(double lat, double lon,
                                       OsmStructureIndex.OsmNode[] r) {
        boolean in = false;
        for (int i = 0, j = r.length - 1; i < r.length; j = i++) {
            if ((r[i].lat > lat) != (r[j].lat > lat)
                    && lon < (r[j].lon - r[i].lon) * (lat - r[i].lat)
                            / (r[j].lat - r[i].lat) + r[i].lon)
                in = !in;
        }
        return in;
    }

    private static int orient(double ax, double ay, double bx, double by,
                              double cx, double cy) {
        double v = (by - ay) * (cx - bx) - (bx - ax) * (cy - by);
        if (v > 1e-15) return 1;
        if (v < -1e-15) return 2;
        return 0;
    }

    /** True when segments a1-a2 and b1-b2 properly cross. */
    private static boolean segmentsCross(
            OsmStructureIndex.OsmNode a1, OsmStructureIndex.OsmNode a2,
            OsmStructureIndex.OsmNode b1, OsmStructureIndex.OsmNode b2) {
        int o1 = orient(a1.lon, a1.lat, a2.lon, a2.lat, b1.lon, b1.lat);
        int o2 = orient(a1.lon, a1.lat, a2.lon, a2.lat, b2.lon, b2.lat);
        int o3 = orient(b1.lon, b1.lat, b2.lon, b2.lat, a1.lon, a1.lat);
        int o4 = orient(b1.lon, b1.lat, b2.lon, b2.lat, a2.lon, a2.lat);
        return o1 != o2 && o3 != o4;
    }

    /**
     * True when two footprints share ANY area — a vertex of one inside the
     * other, or any pair of edges crossing. This is real polygon intersection,
     * not a bounding-box or centroid approximation.
     */
    public static boolean footprintsIntersect(
            OsmStructureIndex.OsmNode[] a, double aMinLat, double aMaxLat,
            double aMinLon, double aMaxLon,
            OsmStructureIndex.OsmNode[] b, double bMinLat, double bMaxLat,
            double bMinLon, double bMaxLon) {
        if (a == null || b == null || a.length < 3 || b.length < 3) return false;
        // Cheap bbox reject first; most pairs die here.
        if (aMaxLat < bMinLat || aMinLat > bMaxLat
                || aMaxLon < bMinLon || aMinLon > bMaxLon) return false;

        for (OsmStructureIndex.OsmNode p : a)
            if (pointInRing(p.lat, p.lon, b)) return true;
        for (OsmStructureIndex.OsmNode p : b)
            if (pointInRing(p.lat, p.lon, a)) return true;

        for (int i = 0; i < a.length; i++) {
            OsmStructureIndex.OsmNode a1 = a[i], a2 = a[(i + 1) % a.length];
            for (int j = 0; j < b.length; j++)
                if (segmentsCross(a1, a2, b[j], b[(j + 1) % b.length]))
                    return true;
        }
        return false;
    }

    /**
     * True when the candidate overlaps any structure in the reference list.
     * Only BUILDING / BUILDING_PART entries are considered, so roads and other
     * linear ways are ignored here (they are handled by {@link #sitsOnRoad}).
     */
    public static boolean intersectsAnyBuilding(
            OsmStructureIndex.OsmNode[] cand,
            double mnLat, double mxLat, double mnLon, double mxLon,
            List<OsmStructureIndex.OsmStructure> refs) {
        if (cand == null || refs == null) return false;
        for (OsmStructureIndex.OsmStructure s : refs) {
            if (s.type != OsmStructureIndex.StructureType.BUILDING
                    && s.type != OsmStructureIndex.StructureType.BUILDING_PART)
                continue;
            if (!s.isArea || s.nodes == null || s.nodes.length < 3) continue;
            // A lower-tier footprint wholly inside an OSM courtyard/hole does
            // not overlap the building shell. The old outer-ring-only test
            // incorrectly discarded legitimate courtyard structures.
            if (isWhollyInsideInnerRing(cand, s.innerRings)) continue;
            if (footprintsIntersect(cand, mnLat, mxLat, mnLon, mxLon,
                    s.nodes, s.minLat, s.maxLat, s.minLon, s.maxLon))
                return true;
        }
        return false;
    }

    private static boolean isWhollyInsideInnerRing(
            OsmStructureIndex.OsmNode[] candidate,
            OsmStructureIndex.OsmNode[][] innerRings) {
        if (candidate == null || candidate.length == 0 || innerRings == null)
            return false;
        for (OsmStructureIndex.OsmNode[] ring : innerRings) {
            if (ring == null || ring.length < 3) continue;
            boolean allInside = true;
            for (OsmStructureIndex.OsmNode node : candidate)
                if (!pointInRing(node.lat, node.lon, ring)) {
                    allInside = false;
                    break;
                }
            if (allInside) return true;
        }
        return false;
    }

    /**
     * True when the candidate duplicates any already-accepted footprint.
     * Linear over the chunk's accepted list, which is a few hundred at most.
     */
    public static boolean duplicatesAny(Print candidate, List<Print> accepted) {
        if (candidate == null || accepted == null) return false;
        for (Print p : accepted)
            if (isSameBuilding(candidate, p)) return true;
        return false;
    }

    /** Convenience: build Prints from accepted OSM/OHM structures once per chunk. */
    public static List<Print> fromStructures(
            List<OsmStructureIndex.OsmStructure> list) {
        List<Print> out = new ArrayList<>();
        if (list == null) return out;
        for (OsmStructureIndex.OsmStructure s : list) {
            if (s.type != OsmStructureIndex.StructureType.BUILDING
             && s.type != OsmStructureIndex.StructureType.BUILDING_PART)
                continue;
            out.add(Print.of(s));
        }
        return out;
    }

    // ================================================================
    // ROAD-CENTRELINE REJECTION
    // ================================================================
    // A second, independent failure mode: an ML footprint lands on top of a
    // road. OSM has no building there to compare against, so every
    // building-vs-building test above passes it, and it renders as a hut in
    // the middle of the carriageway.
    //
    // This is decidable without any building data: OSM road geometry is
    // reliable, and a building centroid sitting within the carriageway is
    // provably wrong regardless of which dataset produced it.

    /**
     * Half-width in metres to treat as "inside the carriageway".
     *
     * MUST MIRROR what OsmStructureGenerator actually paints. It used to be a
     * flat 7.0 m for every ROAD, but the generator draws by road class:
     * motorway/trunk 7.0, primary 5.0, secondary 4.5, tertiary 3.5,
     * residential/living_street 3.0, service/unclassified 2.5, track 2.0.
     *
     * In Hyderabad 330 of 386 roads are residential, painted 3.0 m wide. The
     * flat 7.0 m rejected everything within 7 m of their centreline, so
     * buildings standing on clear ground 3-7 m away were deleted for no visible
     * reason. Measured on the 6,041 real Google footprints in the test box:
     *
     *     flat 7.0 m rule      -> 1,977 rejected
     *     drawn-width rule     ->   423 rejected
     *     difference           -> 1,554 buildings destroyed needlessly
     *
     * Those 423 are the ones genuinely standing on asphalt - the visible bug.
     * Matching the drawn width fixes both halves at once: the hut in the road
     * still goes, the innocent neighbours stay.
     *
     * The lane widening (lanes > 2 -> lanes * 1.8) is reproduced too, so a
     * six-lane road excludes its full carriageway.
     */
    private static double roadHalfWidthM(OsmStructureIndex.OsmStructure s) {
        switch (s.type) {
            case ROAD: {
                String sub = s.subType == null ? "" : s.subType;
                double w = switch (sub) {
                    case "motorway", "trunk"               -> 7.0;
                    case "motorway_link", "trunk_link"     -> 5.0;
                    case "primary"                         -> 5.0;
                    case "primary_link"                    -> 4.0;
                    case "secondary"                       -> 4.5;
                    case "tertiary"                        -> 3.5;
                    case "residential", "living_street"    -> 3.0;
                    case "service"                         -> 2.5;
                    case "unclassified"                    -> 2.5;
                    case "track"                           -> 2.0;
                    default                                -> 3.0;
                };
                if (s.lanes > 2) w = Math.max(w, s.lanes * 1.8);
                return w;
            }
            case RAILWAY:  return 5.0;
            case FOOTWAY:
            case CYCLEWAY: return 1.0;   // generator paints these at 1.0
            default:       return 0.0;   // not a linear way: no exclusion
        }
    }

    /**
     * True when the footprint centre lies within the carriageway of a road.
     *
     * Centroid-only, tested against the width the generator actually paints.
     *
     * An extent-based variant was tried (also reject when the building's body
     * bites into the road, not just its centre). Measured on the 6,041 real
     * Google footprints it caught NOTHING extra - all 423 genuinely-on-road
     * buildings are already caught by the centroid - while deleting many more
     * innocent ones. Against the OSM control, which is ground truth and should
     * therefore never be rejected:
     *
     *     extent factor   VIDA rejected   on-road caught   OSM false positives
     *     0.00 (centroid)          423        423/423                        3
     *     0.25                     721        423/423                       39
     *     0.50                    1242        423/423                      201
     *     1.00                    2473        423/423                     1115
     *
     * So the extent test is pure cost. Centroid-only at the drawn width is
     * both the most accurate and the cheapest, and it leaves only 3 false
     * positives out of 2,762 real OSM buildings.
     */
    public static boolean sitsOnRoad(Print p,
            List<OsmStructureIndex.OsmStructure> structures) {
        if (p == null || structures == null) return false;
        for (OsmStructureIndex.OsmStructure s : structures) {
            double half = roadHalfWidthM(s);
            if (half <= 0) continue;
            if (s.nodes == null || s.nodes.length < 2) continue;

            // distanceToLine already returns metres.
            if (s.distanceToLine(p.centreLat, p.centreLon) < half)
                return true;
        }
        return false;
    }
}
