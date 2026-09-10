// ============================================================
// FILE: ParadoxMath.java
// Pure paradox mechanics — no Minecraft imports, unit-testable.
//
// The rule of the world-line system:
//   consistent change of the past  -> timeline branch (axis 5)
//   CONTRADICTION (paradox)        -> universe rupture (axis 6)
//
// A paradox is committed when a player, standing in the past,
// destroys a FIXED POINT of history: a structure whose explicit
// start_date documents that it exists from year S onward and
// still stands in the present. Removing it at year Y (S <= Y <
// PRESENT) contradicts (PRESENT - Y) years of recorded history.
//
// Contradictions accumulate as temporal stress. Small ones decay
// ("reality heals"); past PARADOX_THRESHOLD reality can no longer
// reconcile the contradiction inside one physics — it RUPTURES,
// birthing an alternative universe whose parameters are warped
// deterministically by the paradox that caused it.
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

public final class ParadoxMath {

    /** The anchor "present" that recorded history runs up to. */
    public static final int PRESENT_YEAR = 2026;

    /** Stress at which reality ruptures into a new universe. */
    public static final double PARADOX_THRESHOLD = 500.0;

    /** Stress decay per second — reality healing small wounds. */
    public static final double DECAY_PER_SECOND = 1.0;

    /** Warning bands for player feedback. */
    public static final double BAND_TREMBLE = 0.35; // of threshold
    public static final double BAND_CRACK   = 0.70;

    private ParadoxMath() {}

    // ── VIOLATION TEST ────────────────────────────────────────

    /**
     * Is destroying this structure, at this simulation year, a
     * paradox?
     *
     * @param startYear   explicit start year, UNKNOWN if undated
     * @param endYear     explicit end year, UNKNOWN if standing
     * @param currentYear simulation year of the actor
     * @param unknownYear the UNKNOWN_YEAR sentinel
     */
    public static boolean isParadox(int startYear, int endYear,
            int currentYear, int unknownYear) {
        // Only actions in the past can contradict the record.
        if (currentYear >= PRESENT_YEAR) return false;
        // Undated structures are not fixed points — their
        // history is estimated, so changing them merely
        // diverges a timeline.
        if (startYear == unknownYear) return false;
        // Not built yet at the actor's year: nothing violated.
        if (currentYear < startYear) return false;
        // History already records its demolition before the
        // present: removing it early is a small inconsistency,
        // not a contradiction of the standing record.
        if (endYear != unknownYear && endYear <= PRESENT_YEAR)
            return false;
        return true;
    }

    /**
     * Stress contributed by one violation.
     * Weight scales with how much recorded future is
     * contradicted (years from action to present) and how
     * significant the structure type is.
     */
    public static double violationStress(int currentYear,
            double typeWeight) {
        int yearsContradicted =
            Math.max(1, PRESENT_YEAR - currentYear);
        // sqrt keeps ancient violations severe but not absurd:
        // breaking a Roman fixed point (~2000 yrs) ~ 45x a
        // 1-year one, not 2000x. Multiplier tuned so ONE block
        // of a named ancient landmark lands in the warning
        // bands (~83% for a Roman cathedral) and the SECOND
        // block ruptures — the player always gets a warning
        // before reality gives way.
        return typeWeight * Math.sqrt(yearsContradicted) * 2.0;
    }

    /** Significance weight by structure sub-type keywords. */
    public static double typeWeight(String subType,
            String name) {
        String s = subType == null ? "" : subType.toLowerCase();
        boolean named = name != null && !name.isBlank();
        double w = switch (s) {
            case "cathedral", "castle", "palace",
                 "monument", "fort", "citadel"     -> 3.0;
            case "church", "mosque", "temple",
                 "synagogue", "shrine", "monastery" -> 2.2;
            case "train_station", "station",
                 "university", "townhall",
                 "government", "hospital"           -> 1.6;
            default                                  -> 1.0;
        };
        // Named structures are anchors of record — heavier.
        return named ? w * 1.5 : w;
    }

    /** Lazy decay: stress after elapsed wall-clock millis. */
    public static double decay(double stress,
            long elapsedMs) {
        double healed = stress
            - (elapsedMs / 1000.0) * DECAY_PER_SECOND;
        return Math.max(0, healed);
    }

    public static boolean ruptures(double stress) {
        return stress >= PARADOX_THRESHOLD;
    }

    /** 0=calm 1=tremble 2=crack 3=rupture, for feedback. */
    public static int band(double stress) {
        if (stress >= PARADOX_THRESHOLD)  return 3;
        if (stress >= PARADOX_THRESHOLD * BAND_CRACK)   return 2;
        if (stress >= PARADOX_THRESHOLD * BAND_TREMBLE) return 1;
        return 0;
    }

    // ── RUPTURE PHYSICS ───────────────────────────────────────
    // The newborn universe's physics are warped by the paradox
    // that created it: deterministic in (violated structure,
    // year, accumulated stress). The same paradox always births
    // the same universe.

    public static final class RuptureParams {
        public final double  seaOffset;
        public final double  elevScale;
        public final boolean invert;
        public final boolean mirror;
        public RuptureParams(double seaOffset, double elevScale,
                boolean invert, boolean mirror) {
            this.seaOffset = seaOffset;
            this.elevScale = elevScale;
            this.invert    = invert;
            this.mirror    = mirror;
        }
    }

    public static long ruptureSeed(long osmId, int year,
            double stress) {
        long h = 0x9E3779B97F4A7C15L;
        h ^= osmId;  h = Long.rotateLeft(h, 27)
            * 0xBF58476D1CE4E5B9L;
        h ^= year;   h = Long.rotateLeft(h, 31)
            * 0x94D049BB133111EBL;
        h ^= (long) stress;
        h ^= h >>> 33;
        return h;
    }

    public static RuptureParams rupturePhysics(long seed,
            double stress) {
        // Violence of the warp grows with overshoot beyond the
        // threshold, capped at 3x.
        double violence = Math.min(3.0,
            stress / PARADOX_THRESHOLD);
        long h = seed;
        double sea = ((Math.floorMod(h, 241) - 120))
            * violence;                       // ±120m * violence
        h >>>= 8;
        double scale = 1.0
            + (Math.floorMod(h, 21) - 10) / 10.0
              * 0.5 * violence;               // 1 ± 0.5*violence
        if (Math.abs(scale) < 0.3)
            scale = scale < 0 ? -0.3 : 0.3;
        h >>>= 8;
        boolean invert = Math.floorMod(h, 12) == 0
            || violence >= 2.5;               // extreme paradox
        h >>>= 4;
        boolean mirror = Math.floorMod(h, 12) == 0;
        return new RuptureParams(sea, scale, invert, mirror);
    }
}
