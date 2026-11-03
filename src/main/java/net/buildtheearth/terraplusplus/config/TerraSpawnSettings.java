package net.buildtheearth.terraplusplus.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spawn latitude/longitude chosen on the Create World screen.
 * Defaults to 0,0 (Gulf of Guinea) when the player leaves the fields empty.
 * Written by the client UI, consumed once when the overworld picks a spawn.
 */
public final class TerraSpawnSettings {

    private static final Logger LOGGER = LogManager.getLogger("Terra++Spawn");
    private static final Path PENDING_FILE = Paths.get("terra_pending_spawn.json");

    private static volatile double latitude = 0.0;
    private static volatile double longitude = 0.0;

    private TerraSpawnSettings() {}

    public static double latitude() {
        return latitude;
    }

    public static double longitude() {
        return longitude;
    }

    public static void set(double lat, double lon) {
        latitude = clamp(lat, -90.0, 90.0);
        longitude = clamp(lon, -180.0, 180.0);
        writeFile();
    }

    public static void setLatitude(double lat) {
        set(lat, longitude);
    }

    public static void setLongitude(double lon) {
        set(latitude, lon);
    }

    /** Apply a pasted "lat lon" / "lat, lon" pair. Blank → 0, 0. */
    public static void setFromLatLonString(String raw) {
        double[] geo = parseLatLon(raw);
        set(geo[0], geo[1]);
    }

    /**
     * Current spawn coords without consuming. Prefers the pending file
     * (survives client/server thread handoff), then in-memory values, then 0,0.
     */
    public static double[] peek() {
        double lat = latitude;
        double lon = longitude;
        try {
            if (Files.exists(PENDING_FILE)) {
                String json = Files.readString(PENDING_FILE, StandardCharsets.UTF_8);
                lat = readDouble(json, "latitude", lat);
                lon = readDouble(json, "longitude", lon);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not read pending spawn file: {}", e.getMessage());
        }
        return new double[] {
                clamp(lat, -90.0, 90.0),
                clamp(lon, -180.0, 180.0)
        };
    }

    /**
     * Read spawn coords for a new world and clear pending state so the next
     * world starts at 0,0 again.
     */
    public static double[] consume() {
        double[] geo = peek();
        try {
            Files.deleteIfExists(PENDING_FILE);
        } catch (Exception e) {
            LOGGER.debug("Could not delete pending spawn file: {}", e.getMessage());
        }
        latitude = 0.0;
        longitude = 0.0;
        return geo;
    }

    public static double parseCoord(String raw, double min, double max, double fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = raw.trim();
        if (s.isEmpty() || "-".equals(s) || ".".equals(s) || "-.".equals(s)) {
            return fallback;
        }
        try {
            return clamp(Double.parseDouble(s), min, max);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Parse one box: {@code 17.4346 78.5016} or {@code 17.4346, 78.5016}.
     * Empty / "0" / "0 0" → {0, 0}.
     */
    public static double[] parseLatLon(String raw) {
        if (raw == null) {
            return new double[] {0.0, 0.0};
        }
        String s = raw.trim().replace(',', ' ').replaceAll("\\s+", " ");
        if (s.isEmpty() || "0".equals(s) || "0 0".equals(s)) {
            return new double[] {0.0, 0.0};
        }
        String[] parts = s.split(" ");
        double lat = parseCoord(parts[0], -90.0, 90.0, 0.0);
        double lon = parts.length >= 2
                ? parseCoord(parts[1], -180.0, 180.0, 0.0)
                : 0.0;
        return new double[] {lat, lon};
    }

    public static String formatLatLon() {
        if (latitude == 0.0 && longitude == 0.0) {
            return "";
        }
        return formatCoord(latitude) + " " + formatCoord(longitude);
    }

    public static String formatCoord(double value) {
        if (value == 0.0) {
            return "0";
        }
        String s = Double.toString(value);
        if (s.contains("E") || s.contains("e")) {
            return String.format(java.util.Locale.ROOT, "%.15f", value)
                    .replaceAll("0+$", "")
                    .replaceAll("\\.$", "");
        }
        return s;
    }

    public static boolean isNumericInput(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        return s.matches("-?\\d*\\.?\\d*");
    }

    public static boolean isLatLonInput(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        return s.matches("[0-9eE+\\-.,\\s]*");
    }

    /**
     * Empty and in-progress typing are OK. Red-text errors:
     * more than two numbers, unparseable tokens, lat outside ±90, lon outside ±180.
     */
    public static boolean isSyntaxValid(String raw) {
        if (raw == null) {
            return true;
        }
        String s = raw.trim().replace(',', ' ').replaceAll("\\s+", " ");
        if (s.isEmpty()) {
            return true;
        }
        String[] parts = s.split(" ");
        if (parts.length > 2) {
            return false;
        }
        if (!isPartialNumber(parts[0])) {
            return false;
        }
        if (isFinishedNumber(parts[0]) && !inRange(parts[0], -90.0, 90.0)) {
            return false;
        }
        if (parts.length == 1) {
            return true;
        }
        if (!isPartialNumber(parts[1])) {
            return false;
        }
        return !isFinishedNumber(parts[1]) || inRange(parts[1], -180.0, 180.0);
    }

    private static boolean isPartialNumber(String token) {
        if (token == null || token.isEmpty()) {
            return true;
        }
        if ("-".equals(token) || "+".equals(token) || ".".equals(token)
                || "-.".equals(token) || "+.".equals(token)) {
            return true;
        }
        if (token.endsWith("e") || token.endsWith("E")
                || token.endsWith("e-") || token.endsWith("E-")
                || token.endsWith("e+") || token.endsWith("E+")) {
            return token.length() > 1;
        }
        try {
            Double.parseDouble(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isFinishedNumber(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if ("-".equals(token) || "+".equals(token) || ".".equals(token)
                || "-.".equals(token) || "+.".equals(token)) {
            return false;
        }
        if (token.endsWith("e") || token.endsWith("E") || token.endsWith("-")
                || token.endsWith("+") || token.endsWith(".")) {
            return false;
        }
        try {
            double v = Double.parseDouble(token);
            return Double.isFinite(v);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean inRange(String token, double min, double max) {
        try {
            double v = Double.parseDouble(token);
            return Double.isFinite(v) && v >= min && v <= max;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeFile() {
        try {
            String json = String.format(java.util.Locale.ROOT,
                    "{\"latitude\":%.8f,\"longitude\":%.8f}",
                    latitude, longitude);
            Files.writeString(PENDING_FILE, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("Could not write pending spawn file: {}", e.getMessage());
        }
    }

    private static double readDouble(String json, String key, double def) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) {
            return def;
        }
        int start = idx + search.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (Character.isDigit(c) || c == '-' || c == '.' || c == 'e' || c == 'E' || c == '+') {
                end++;
            } else {
                break;
            }
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (Exception e) {
            return def;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
