package net.buildtheearth.terraplusplus.projection.impl;

import net.buildtheearth.terraplusplus.projection.GeographicProjection;

/**
 * Equirectangular (Plate Carrée) projection.
 * Simple linear mapping from lat/lon to x/z.
 */
public class EquirectangularProjection implements GeographicProjection {
    
    // Earth's circumference in meters
    private static final double EARTH_CIRCUMFERENCE = 40_075_017;
    // Default scale: 1 block = 1 meter at equator
    private static final double SCALE = 1.0;
    
    @Override
    public double[] toProjected(double latitude, double longitude) {
        double x = longitude * (EARTH_CIRCUMFERENCE / 360.0) * SCALE;
        double z = -latitude * (EARTH_CIRCUMFERENCE / 360.0) * SCALE; // Negative because +Z is south
        return new double[] {x, z};
    }
    
    @Override
    public double[] toGeographic(double x, double z) {
        double longitude = x / ((EARTH_CIRCUMFERENCE / 360.0) * SCALE);
        double latitude = -z / ((EARTH_CIRCUMFERENCE / 360.0) * SCALE);
        return new double[] {latitude, longitude};
    }
    
    @Override
    public double metersPerBlock(double latitude, double longitude) {
        // Scale varies with latitude due to convergence of meridians
        return SCALE * Math.cos(Math.toRadians(latitude));
    }
    
    @Override
    public String getName() {
        return "Equirectangular";
    }
    
    @Override
    public double[] getBounds() {
        double halfWidth = EARTH_CIRCUMFERENCE / 2.0 * SCALE;
        double halfHeight = EARTH_CIRCUMFERENCE / 4.0 * SCALE;
        return new double[] {-halfWidth, -halfHeight, halfWidth, halfHeight};
    }
}
