// ============================================================
// FILE: HistoricalDateParser.java
// Parses OSM/OHM start_date and end_date tag strings into
// integer years. Handles all known OSM date formats including
// BCE dates, centuries, decades, approximations.
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HistoricalDateParser {

    private static final Logger LOGGER =
            LogManager.getLogger("Terra++DateParser");

    public static final int UNKNOWN_YEAR  = Integer.MIN_VALUE;
    public static final int DEFAULT_OSM_YEAR = 2000;
    public static final int DEFAULT_OHM_YEAR = Integer.MIN_VALUE;

    /**
     * Parse an OSM/OHM date string into an integer year.
     * Returns UNKNOWN_YEAR if unparseable.
     *
     * Supported formats:
     *   "1823"           → 1823
     *   "1823-04"        → 1823
     *   "1823-04-15"     → 1823
     *   "-500"           → -500
     *   "BCE 500"        → -500
     *   "BC 500"         → -500
     *   "500 BCE"        → -500
     *   "500 BC"         → -500
     *   "~1900"          → 1900
     *   "circa 1900"     → 1900
     *   "1940s"          → 1940
     *   "C18"            → 1700
     *   "18th century"   → 1700
     *   "early 19th century" → 1800
     *   "mid 19th century"   → 1850
     *   "late 19th century"  → 1880
     *   "early modern"   → 1500
     *   "medieval"       → 1000
     *   "ancient"        → -500
     *   "prehistoric"    → -3000
     */
    public static int parse(String dateStr) {
        if (dateStr == null || dateStr.isBlank())
            return UNKNOWN_YEAR;

        String s = dateStr.trim().toLowerCase();

        // ── SPECIAL WORDS ────────────────────────────────────
        if (s.equals("prehistoric") || s.equals("prehistory"))
            return -3000;
        if (s.equals("ancient"))
            return -500;
        if (s.equals("medieval") || s.equals("middle ages"))
            return 1000;
        if (s.equals("early modern"))
            return 1500;
        if (s.equals("modern"))
            return 1900;

        // ── BCE / BC FORMATS ─────────────────────────────────
        // "BCE 500", "BC 500"
        if (s.startsWith("bce ") || s.startsWith("bc ")) {
            String num = s.replaceAll("[^0-9]", "");
            try { return -Integer.parseInt(num); }
            catch (NumberFormatException e) { return UNKNOWN_YEAR; }
        }
        // "500 BCE", "500 BC"
        if (s.endsWith(" bce") || s.endsWith(" bc")) {
            String num = s.replaceAll("[^0-9]", "");
            try { return -Integer.parseInt(num); }
            catch (NumberFormatException e) { return UNKNOWN_YEAR; }
        }

        // ── CENTURY FORMATS ───────────────────────────────────
        // "C18", "c18"
        if (s.matches("c\\d{1,2}")) {
            try {
                int century = Integer.parseInt(s.substring(1));
                return (century - 1) * 100;
            } catch (NumberFormatException e) { return UNKNOWN_YEAR; }
        }
        // "18th century", "19th century"
        if (s.contains("century")) {
            String prefix = "";
            if (s.startsWith("early "))  prefix = "early";
            if (s.startsWith("mid "))    prefix = "mid";
            if (s.startsWith("late "))   prefix = "late";
            String num = s.replaceAll("[^0-9]", "");
            if (!num.isEmpty()) {
                try {
                    int century = Integer.parseInt(num);
                    int baseYear = (century - 1) * 100;
                    return switch (prefix) {
                        case "early" -> baseYear;
                        case "mid"   -> baseYear + 50;
                        case "late"  -> baseYear + 80;
                        default      -> baseYear;
                    };
                } catch (NumberFormatException e) {
                    return UNKNOWN_YEAR;
                }
            }
        }

        // ── DECADE FORMAT ─────────────────────────────────────
        // "1940s", "1940's"
        if (s.matches("\\d{4}s") || s.matches("\\d{4}'s")) {
            try {
                return Integer.parseInt(s.substring(0, 4));
            } catch (NumberFormatException e) { return UNKNOWN_YEAR; }
        }

        // ── APPROXIMATE ───────────────────────────────────────
        // "~1900", "circa 1900", "ca. 1900", "ca 1900"
        s = s.replaceAll("^[~≈]", "");
        s = s.replaceAll("^circa\\s+", "");
        s = s.replaceAll("^ca\\.?\\s*", "");
        s = s.trim();

        // ── NEGATIVE YEAR ────────────────────────────────────
        // "-500"
        if (s.startsWith("-")) {
            String num = s.substring(1).replaceAll("[^0-9]", "");
            if (!num.isEmpty()) {
                try { return -Integer.parseInt(num); }
                catch (NumberFormatException e) { return UNKNOWN_YEAR; }
            }
        }

        // ── STANDARD DATE ────────────────────────────────────
        // "1823", "1823-04", "1823-04-15"
        String[] parts = s.split("-");
        if (parts.length >= 1) {
            String yearStr = parts[0].replaceAll("[^0-9]", "");
            if (!yearStr.isEmpty()) {
                try {
                    int year = Integer.parseInt(yearStr);
                    // Sanity check
                    if (year >= -10000 && year <= 9999) return year;
                } catch (NumberFormatException e) {
                    return UNKNOWN_YEAR;
                }
            }
        }

        LOGGER.debug("Unparseable date: '{}'", dateStr);
        return UNKNOWN_YEAR;
    }

    /**
     * Returns true if the structure should be visible at the given year.
     *
     * @param startYear  Parsed start year (UNKNOWN_YEAR = no start date)
     * @param endYear    Parsed end year   (UNKNOWN_YEAR = no end date)
     * @param currentYear Current game year
     * @param isOsm      True if from OSM (undated = 2000+),
     *                   False if from OHM (undated = always)
     */
    public static boolean isVisibleAt(int startYear, int endYear,
                                       int currentYear, boolean isOsm) {
        // Resolve effective start year
        int effectiveStart;
        if (startYear == UNKNOWN_YEAR) {
            effectiveStart = isOsm ? DEFAULT_OSM_YEAR : Integer.MIN_VALUE;
        } else {
            effectiveStart = startYear;
        }

        // Check start date
        if (currentYear < effectiveStart) return false;

        // Check end date (UNKNOWN_YEAR = never ends)
        if (endYear != UNKNOWN_YEAR && currentYear >= endYear) return false;

        return true;
    }

    /**
     * Returns how many years until a structure appears.
     * Negative means it already appeared.
     * Used for forest clearing logic.
     */
    public static int yearsUntilAppear(int startYear, int currentYear,
                                        boolean isOsm) {
        int effectiveStart = (startYear == UNKNOWN_YEAR)
                ? (isOsm ? DEFAULT_OSM_YEAR : Integer.MIN_VALUE)
                : startYear;
        return effectiveStart - currentYear;
    }

    /**
     * Clamp a year to the valid game range.
     */
    public static int clampYear(int year) {
        return Math.max(-3000, Math.min(9999, year));
    }
}