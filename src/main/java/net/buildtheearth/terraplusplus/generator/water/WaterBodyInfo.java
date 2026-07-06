// ============================================================
// FILE: WaterBodyInfo.java
// MODIFIED: Added OHM to Source enum.
//           All other logic unchanged.
// ============================================================
package net.buildtheearth.terraplusplus.generator.water;

public final class WaterBodyInfo {

    public enum WaterType {
        NONE, RIVER, STREAM, CANAL,
        LAKE, RESERVOIR, POND, WETLAND,
        COASTLINE
    }

    public enum Source {
        NONE, OSM, OHM, ELEVATION, HARDCODED
    }

    public static final WaterBodyInfo NONE =
        new WaterBodyInfo(
            WaterType.NONE, Source.NONE, false,
            0, 0, 0, 1.0, 0, 0, null, 0);

    public final WaterType type;
    public final Source    source;
    public final boolean   isWater;
    public final double    surfaceElevation;
    public final double    depth;
    public final int       widthMeters;
    public final double    edgeFactor;
    public final double    flowDirection;
    public final int       bankWidth;
    public final String    name;
    public final int       waterSurfaceY;

    public WaterBodyInfo(
            WaterType type, Source source,
            boolean isWater,
            double surfaceElevation, double depth,
            int widthMeters, double edgeFactor,
            double flowDirection, int bankWidth,
            String name) {
        this(type, source, isWater,
            surfaceElevation, depth,
            widthMeters, edgeFactor,
            flowDirection, bankWidth, name, 0);
    }

    public WaterBodyInfo(
            WaterType type, Source source,
            boolean isWater,
            double surfaceElevation, double depth,
            int widthMeters, double edgeFactor,
            double flowDirection, int bankWidth,
            String name, int waterSurfaceY) {
        this.type             = type;
        this.source           = source;
        this.isWater          = isWater;
        this.surfaceElevation = surfaceElevation;
        this.depth            = depth;
        this.widthMeters      = widthMeters;
        this.edgeFactor       =
            clamp(edgeFactor, 0.0, 100.0);
        this.flowDirection    = flowDirection;
        this.bankWidth        = bankWidth;
        this.name             = name;
        this.waterSurfaceY    = waterSurfaceY;
    }

    public boolean isRiver() {
        return type == WaterType.RIVER
            || type == WaterType.STREAM
            || type == WaterType.CANAL;
    }

    public boolean isLake() {
        return type == WaterType.LAKE
            || type == WaterType.RESERVOIR
            || type == WaterType.POND;
    }

    public boolean isBank() {
        return !isWater
            && edgeFactor > 1.0
            && edgeFactor < (1.0 + bankWidth);
    }

    public boolean isWetland() {
        return type == WaterType.WETLAND;
    }

    public boolean isCoastline() {
        return type == WaterType.COASTLINE;
    }

    public boolean isBelowSeaLevel() {
        return surfaceElevation < -0.5;
    }

    public int profileDepthBlocks() {
        if (!isWater || depth <= 0) return 0;
        double t = clamp(edgeFactor, 0.0, 1.0);
        double rawDepth;
        switch (type) {
            case RIVER -> {
                double flatZone = 0.55;
                if (t <= flatZone) {
                    rawDepth = depth;
                } else {
                    double norm =
                        (t - flatZone)
                        / (1.0 - flatZone);
                    rawDepth = depth
                        * (1.0 - norm * norm * 0.5);
                }
                int minDepth = t < 0.7 ? 4 : 3;
                return Math.max(minDepth,
                    (int) Math.ceil(rawDepth));
            }
            case STREAM -> {
                double flatZone = 0.35;
                if (t <= flatZone) {
                    rawDepth = depth;
                } else {
                    double norm =
                        (t - flatZone)
                        / (1.0 - flatZone);
                    rawDepth = depth
                        * (1.0 - norm * norm * 0.55);
                }
                return Math.max(2,
                    (int) Math.ceil(rawDepth));
            }
            case CANAL -> {
                rawDepth = depth
                    * (1.0 - t * t * 0.2);
                return Math.max(4,
                    (int) Math.ceil(rawDepth));
            }
            case LAKE -> {
                rawDepth = depth
                    * (1.0 - Math.pow(t, 2.0)
                        * 0.65);
                int minDepth = t < 0.8 ? 6 : 3;
                return Math.max(minDepth,
                    (int) Math.ceil(rawDepth));
            }
            case RESERVOIR -> {
                rawDepth = depth
                    * (1.0 - Math.pow(t, 2.5)
                        * 0.55);
                int minDepth = t < 0.8 ? 7 : 4;
                return Math.max(minDepth,
                    (int) Math.ceil(rawDepth));
            }
            case POND -> {
                rawDepth = depth
                    * (1.0 - t * 0.55);
                return Math.max(3,
                    (int) Math.ceil(rawDepth));
            }
            case WETLAND -> {
                return Math.max(1,
                    (int) Math.ceil(depth * 0.85));
            }
            default -> {
                return Math.max(1,
                    (int) Math.ceil(
                        depth * (1.0 - t)));
            }
        }
    }

    public static double defaultDepth(
            WaterType type, int widthMeters) {
        return switch (type) {
            case RIVER     ->
                clamp(8.0 + widthMeters * 0.12,
                    8.0, 50.0);
            case STREAM    ->
                clamp(3.0 + widthMeters * 0.18,
                    3.0, 8.0);
            case CANAL     ->
                clamp(5.0 + widthMeters * 0.08,
                    5.0, 12.0);
            case LAKE      ->
                clamp(12.0 + widthMeters * 0.06,
                    12.0, 150.0);
            case RESERVOIR ->
                clamp(15.0 + widthMeters * 0.07,
                    15.0, 180.0);
            case POND      ->
                clamp(4.0 + widthMeters * 0.08,
                    4.0, 12.0);
            case WETLAND   ->
                clamp(1.5 + widthMeters * 0.008,
                    1.5, 3.0);
            case COASTLINE -> 0;
            case NONE      -> 0;
        };
    }

    public static int defaultWidth(WaterType type) {
        return switch (type) {
            case RIVER     -> 40;
            case STREAM    -> 6;
            case CANAL     -> 12;
            case LAKE      -> 300;
            case RESERVOIR -> 500;
            case POND      -> 40;
            case WETLAND   -> 80;
            case COASTLINE -> 0;
            case NONE      -> 0;
        };
    }

    public static int defaultBankWidth(
            WaterType type) {
        return switch (type) {
            case RIVER     -> 1;
            case STREAM    -> 1;
            case CANAL     -> 1;
            case LAKE      -> 1;
            case RESERVOIR -> 2;
            case POND      -> 1;
            case WETLAND   -> 2;
            case COASTLINE -> 0;
            case NONE      -> 0;
        };
    }

    public static double polygonInsetMeters(
            WaterType type) {
        return switch (type) {
            case LAKE      -> 2.0;
            case RESERVOIR -> 2.0;
            case POND      -> 1.0;
            case RIVER     -> 1.0;
            case WETLAND   -> 3.0;
            case COASTLINE -> 0.0;
            default        -> 1.0;
        };
    }

    public static double innerShoreBuffer(
            WaterType type) {
        return switch (type) {
            case LAKE      -> 4.0;
            case RESERVOIR -> 4.0;
            case POND      -> 2.0;
            case RIVER     -> 3.0;
            case WETLAND   -> 5.0;
            case COASTLINE -> 0.0;
            default        -> 3.0;
        };
    }

    public static double maxElevationRise(
            WaterType type) {
        return switch (type) {
            case LAKE      -> 3.0;
            case RESERVOIR -> 4.0;
            case POND      -> 2.0;
            case RIVER     -> 3.0;
            case STREAM    -> 2.0;
            case CANAL     -> 2.5;
            case WETLAND   -> 2.0;
            case COASTLINE -> 0.0;
            case NONE      -> 0.0;
        };
    }

    private static double clamp(
            double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public String toString() {
        if (type == WaterType.NONE)
            return "WaterBody[NONE]";
        return String.format(
            "WaterBody[%s src=%s w=%dm d=%.1fm "
            + "dBlk=%d edge=%.2f surfY=%d "
            + "bsl=%s%s]",
            type, source, widthMeters, depth,
            profileDepthBlocks(), edgeFactor,
            waterSurfaceY, isBelowSeaLevel(),
            name != null
                ? " \"" + name + "\"" : "");
    }
}