package net.buildtheearth.terraplusplus.projection;

import net.buildtheearth.terraplusplus.TerraPlusPlus;
import net.buildtheearth.terraplusplus.projection.impl.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for geographic projections.
 */
public class ProjectionRegistry {
    private static final Map<String, GeographicProjection> PROJECTIONS = new HashMap<>();
    private static GeographicProjection defaultProjection;
    
    public static void register() {
        // Register built-in projections
        register("equirectangular", new EquirectangularProjection());
        register("mercator", new MercatorProjection());
        register("bte", new BTEProjection());
        register("conformal_dymaxion", new ConformalDymaxionProjection());
        
        // Set default
        defaultProjection = PROJECTIONS.get("bte");
        
        TerraPlusPlus.LOGGER.info("Registered {} geographic projections", PROJECTIONS.size());
    }
    
    public static void register(String name, GeographicProjection projection) {
        PROJECTIONS.put(name.toLowerCase(), projection);
    }
    
    public static Optional<GeographicProjection> get(String name) {
        return Optional.ofNullable(PROJECTIONS.get(name.toLowerCase()));
    }
    
    public static GeographicProjection getDefault() {
        return get("equirectangular").orElseThrow();
    }
    
    public static void setDefault(GeographicProjection projection) {
        defaultProjection = projection;
    }
    
    public static Map<String, GeographicProjection> getAll() {
        return new HashMap<>(PROJECTIONS);
    }
}
