package io.github.opencubicchunks.cubicworldgen.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class CubicWorldGenConfig {
    public static final ForgeConfigSpec SPEC;
    
    public static final ForgeConfigSpec.IntValue GENERATION_HEIGHT_OFFSET;
    public static final ForgeConfigSpec.BooleanValue GENERATE_BEDROCK;
    public static final ForgeConfigSpec.IntValue SURFACE_NOISE_SCALE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_STRUCTURES;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CAVES;
    
    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        
        builder.comment("CubicWorldGen Configuration").push("general");
        
        GENERATION_HEIGHT_OFFSET = builder
            .comment("Y offset for terrain generation in cubic mode")
            .defineInRange("generationHeightOffset", 0, -10000, 10000);
        
        GENERATE_BEDROCK = builder
            .comment("Generate bedrock layer (only in non-cubic mode)")
            .define("generateBedrock", true);
        
        SURFACE_NOISE_SCALE = builder
            .comment("Scale for surface noise (affects terrain variation)")
            .defineInRange("surfaceNoiseScale", 1, 1, 10);
        
        ENABLE_STRUCTURES = builder
            .comment("Enable structure generation")
            .define("enableStructures", true);
        
        ENABLE_CAVES = builder
            .comment("Enable cave/carver generation")
            .define("enableCaves", true);
        
        builder.pop();
        
        SPEC = builder.build();
    }
}
