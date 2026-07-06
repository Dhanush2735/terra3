package net.buildtheearth.terraplusplus.projection.impl;

import net.buildtheearth.terraplusplus.projection.GeographicProjection;

/**
 * Web Mercator projection (EPSG:3857).
 * Commonly used by web maps (Google Maps, OpenStreetMap tiles).
 */
public class MercatorProjection implements GeographicProjection {
    
    private static final double EARTH_RADIUS = 6_378_137; // meters
    private static final double MAX_LATITUDE = 85.0511287798; // degrees
    private static final double SCALE = 1.0; // 1 block = 1 meter at equator
    
    @Override
    public double[] toProjected(double latitude, double longitude) {
        // Clamp latitude to valid Mercator range
        latitude = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
        
        double x = Math.toRadians(longitude) * EARTH_RADIUS * SCALE;
        double y = Math.log(Math.tan(Math.PI / 4 + Math.toRadians(latitude) / 2)) * EARTH_RADIUS * SCALE;
        
        return new double[] {x, -y}; // Negative Y because +Z is south
    }
    
    @Override
    public double[] toGeographic(double x, double z) {
        double longitude = Math.toDegrees(x / (EARTH_RADIUS * SCALE));
        double latitude = Math.toDegrees(2 * Math.atan(Math.exp(-z / (EARTH_RADIUS * SCALE))) - Math.PI / 2);
        
        return new double[] {latitude, longitude};
    }
    
    @Override
    public double metersPerBlock(double latitude, double longitude) {
        // Mercator scale increases with latitude
        return SCALE / Math.cos(Math.toRadians(latitude));
    }
    
    @Override
    public String getName() {
        return "Web Mercator";
    }
    
    @Override
    public boolean isValidGeographic(double latitude, double longitude) {
        return latitude >= -MAX_LATITUDE && latitude <= MAX_LATITUDE 
            && longitude >= -180 && longitude <= 180;
    }
    
    @Override
    public double[] getBounds() {
        double[] minCorner = toProjected(-MAX_LATITUDE, -180);
        double[] maxCorner = toProjected(MAX_LATITUDE, 180);
        return new double[] {minCorner[0], minCorner[1], maxCorner[0], maxCorner[1]};
    }
}
