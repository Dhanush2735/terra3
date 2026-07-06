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
    public static final ForgeConfigSpec.ConfigValue<String> TERRAIN_DATA_URL;
    
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
        
        TERRAIN_DATA_URL = builder
            .comment("Base URL for terrain data tiles")
            .define("terrainDataUrl", "https://s3.amazonaws.com/elevation-tiles-prod/terrarium");
        
        builder.pop();
        
        SPEC = builder.build();
    }
}