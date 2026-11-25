package net.buildtheearth.terraplusplus.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class TerraConfig {
    public static final ForgeConfigSpec SPEC;
    
    public static final ForgeConfigSpec.ConfigValue<String> DEFAULT_PROJECTION;
    public static final ForgeConfigSpec.IntValue TILE_CACHE_SIZE;
    public static final ForgeConfigSpec.IntValue HEIGHT_SCALE;
    public static final ForgeConfigSpec.IntValue HEIGHT_OFFSET;
    public static final ForgeConfigSpec.BooleanValue ENABLE_TREES;
    public static final ForgeConfigSpec.BooleanValue ENABLE_OSM_BUILDINGS;
    public static final ForgeConfigSpec.IntValue ASYNC_LOADER_THREADS;
    // TERRAIN_DATA_URL + MAPTERHORN_ELEVATION removed
    // 2026-11-20: single elevation source (Mapterhorn).
    // Later the same day the owner ordered the Y rule
    // (new book >= Y65, old book <= Y64, transition gap):
    // the old book's URL is a fixed constant in
    // TerraDensityFunctions, not a config key, and there
    // is still no dual-source switch to flip. Existing
    // config files that carry the removed keys are ignored.
    public static final ForgeConfigSpec.BooleanValue REAL_CAVES;
    
    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        
        builder.comment("Terra++ Configuration").push("general");
        
        DEFAULT_PROJECTION = builder
            .comment("Default projection for coordinate conversion (bte, equirectangular, mercator)")
            .define("defaultProjection", "bte");
        
        TILE_CACHE_SIZE = builder
            .comment("Maximum number of terrain tiles to cache in memory")
            .defineInRange("tileCacheSize", 256, 16, 4096);
        
        HEIGHT_SCALE = builder
            .comment("Scale factor for terrain height (1 = realistic, 2 = double height)")
            .defineInRange("heightScale", 1, 1, 10);
        
        HEIGHT_OFFSET = builder
            .comment("Y offset for terrain (adjusts sea level)")
            .defineInRange("heightOffset", 64, -1000, 1000);
        
        ENABLE_TREES = builder
            .comment("Enable tree placement based on land cover data")
            .define("enableTrees", true);
        
        ENABLE_OSM_BUILDINGS = builder
            .comment("Enable building outlines from OpenStreetMap (requires OSM data)")
            .define("enableOsmBuildings", false);
        
        ASYNC_LOADER_THREADS = builder
            .comment("Number of threads for async terrain data loading")
            .defineInRange("asyncLoaderThreads", 4, 1, 16);
        
        REAL_CAVES = builder
            .comment("Real-geology caves (Phase 1, 2026-11-20): in karst regions "
                    + "(WHYMAP/WOKAM carbonate & evaporite rocks) carve worm/tunnel "
                    + "caves into the subsurface and paint lush/dripstone/deep-dark "
                    + "underground biomes by depth. Outside karst regions (and "
                    + "under oceans) there are no caves, matching real geology. "
                    + "Set false to disable. See CAVES_PHASE1.md.")
            .define("realCaves", true);
        
        builder.pop();
        
        SPEC = builder.build();
    }
}