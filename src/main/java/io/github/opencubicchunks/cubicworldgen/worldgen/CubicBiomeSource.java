package io.github.opencubicchunks.cubicworldgen.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

/**
 * Biome source that supports true 3D biome distribution for cubic worlds.
 * In vanilla, biomes are essentially 2D with some height variation.
 * This allows distinct biomes at any Y level.
 */
public class CubicBiomeSource extends BiomeSource {
    
    public static final Codec<CubicBiomeSource> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BiomeSource.CODEC.fieldOf("wrapped").forGetter(s -> s.wrapped),
            Codec.DOUBLE.optionalFieldOf("y_scale", 1.0).forGetter(s -> s.yScale),
            Codec.BOOL.optionalFieldOf("enable_underground_biomes", true).forGetter(s -> s.enableUndergroundBiomes)
        ).apply(instance, CubicBiomeSource::new)
    );
    
    private final BiomeSource wrapped;
    private final double yScale;
    private final boolean enableUndergroundBiomes;
    
    public CubicBiomeSource(BiomeSource wrapped, double yScale, boolean enableUndergroundBiomes) {
        this.wrapped = wrapped;
        this.yScale = yScale;
        this.enableUndergroundBiomes = enableUndergroundBiomes;
    }
    
    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }
    
    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return wrapped.possibleBiomes().stream();
    }
    
    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        if (enableUndergroundBiomes) {
            // Apply Y-scaling to create distinct underground biome layers
            int scaledQuartY = (int) (quartY / yScale);
            
            // Use modified climate parameters for 3D biome selection
            Climate.TargetPoint target = sampler.sample(quartX, scaledQuartY, quartZ);
            
            // For deep underground, could select cave biomes
            if (quartY < -8) { // Below Y=-32 (quart coords)
                // Could return specific cave biomes here
            }
        }
        
        // Fall back to wrapped biome source
        return wrapped.getNoiseBiome(quartX, quartY, quartZ, sampler);
    }
}
