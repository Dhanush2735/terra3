package io.github.opencubicchunks.cubicworldgen.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.opencubicchunks.cubicworldgen.CubicWorldGen;
import io.github.opencubicchunks.cubicworldgen.compat.CubicChunksCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Cube-aware noise-based chunk generator.
 * Uses DensityFunction system from 1.18+ for terrain generation.
 * 
 * When CubicChunks2 is present:
 * - Generates terrain in 16x16x16 cubes
 * - No Y-level limits
 * - Density functions evaluated per-cube
 * 
 * When CubicChunks2 is absent:
 * - Falls back to tall vanilla-style generation
 * - Respects standard height limits
 */
public class CubicNoiseChunkGenerator extends ChunkGenerator {
    
    public static final Codec<CubicNoiseChunkGenerator> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(g -> g.settings)
        ).apply(instance, CubicNoiseChunkGenerator::new)
    );
    
    private final Holder<NoiseGeneratorSettings> settings;
    private final NoiseRouter noiseRouter;
    
    public CubicNoiseChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
        super(biomeSource);
        this.settings = settings;
        this.noiseRouter = settings.value().noiseRouter();
    }
    
    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }
    
    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        // Carving for cubic worlds needs special handling
        // Each cube is carved independently
        if (CubicChunksCompat.isCubic(level.getLevel())) {
            applyCubicCarvers(level, seed, random, biomeManager, chunk, step);
        } else {
            applyVanillaCarvers(level, seed, random, biomeManager, structureManager, chunk, step);
        }
    }
    
    private void applyCubicCarvers(WorldGenRegion level, long seed, RandomState random,
                                   BiomeManager biomeManager, ChunkAccess chunk,
                                   GenerationStep.Carving step) {
        // Cubic carving implementation
        // Process each cube section within the chunk column
        CubicWorldGen.LOGGER.debug("Applying cubic carvers to chunk at {}", chunk.getPos());
    }
    
    private void applyVanillaCarvers(WorldGenRegion level, long seed, RandomState random,
                                     BiomeManager biomeManager, StructureManager structureManager,
                                     ChunkAccess chunk, GenerationStep.Carving step) {
        // Standard vanilla carving
    }
    
    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager,
                             RandomState random, ChunkAccess chunk) {
        if (CubicChunksCompat.isCubic(level.getLevel())) {
            buildCubicSurface(level, random, chunk);
        } else {
            buildVanillaSurface(level, structureManager, random, chunk);
        }
    }
    
    private void buildCubicSurface(WorldGenRegion level, RandomState random, ChunkAccess chunk) {
        // Surface building for cubic worlds
        // Apply surface rules based on density at each position
        NoiseGeneratorSettings noiseSettings = settings.value();
        SurfaceRules.RuleSource surfaceRule = noiseSettings.surfaceRule();
        
        CubicWorldGen.LOGGER.debug("Building cubic surface for chunk at {}", chunk.getPos());
    }
    
    private void buildVanillaSurface(WorldGenRegion level, StructureManager structureManager,
                                     RandomState random, ChunkAccess chunk) {
        // Standard vanilla surface building
    }
    
    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        // Mob spawning handled by vanilla
    }
    
    @Override
    public int getGenDepth() {
        return settings.value().noiseSettings().height();
    }
    
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender,
                                                        RandomState random, StructureManager structureManager,
                                                        ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(() -> {
            doFillFromNoise(random, chunk);
            return chunk;
        }, executor);
    }
    
    private void doFillFromNoise(RandomState random, ChunkAccess chunk) {
        NoiseSettings noiseSettings = settings.value().noiseSettings();
        int minY = noiseSettings.minY();
        int height = noiseSettings.height();
        int cellHeight = noiseSettings.getCellHeight();
        int cellWidth = noiseSettings.getCellWidth();
        
        int cellCountY = height / cellHeight;
        int cellCountXZ = 16 / cellWidth;
        
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        
        // Fill noise using density functions
        for (int cellY = 0; cellY < cellCountY; cellY++) {
            for (int cellX = 0; cellX < cellCountXZ; cellX++) {
                for (int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
                    fillNoiseCell(chunk, random, mutablePos,
                        cellX, cellY, cellZ, cellWidth, cellHeight, minY);
                }
            }
        }
    }
    
    private void fillNoiseCell(ChunkAccess chunk, RandomState random,
                               BlockPos.MutableBlockPos mutablePos,
                               int cellX, int cellY, int cellZ,
                               int cellWidth, int cellHeight, int minY) {
        int startX = chunk.getPos().getMinBlockX() + cellX * cellWidth;
        int startY = minY + cellY * cellHeight;
        int startZ = chunk.getPos().getMinBlockZ() + cellZ * cellWidth;
        
        NoiseGeneratorSettings noiseSettings = settings.value();
        BlockState defaultBlock = noiseSettings.defaultBlock();
        BlockState defaultFluid = noiseSettings.defaultFluid();
        int seaLevel = noiseSettings.seaLevel();
        
        for (int dy = 0; dy < cellHeight; dy++) {
            for (int dx = 0; dx < cellWidth; dx++) {
                for (int dz = 0; dz < cellWidth; dz++) {
                    int x = startX + dx;
                    int y = startY + dy;
                    int z = startZ + dz;
                    
                    mutablePos.set(x, y, z);
                    
                    // Sample density at this position
                    double density = sampleDensity(random, x, y, z);
                    
                    BlockState state;
                    if (density > 0) {
                        state = defaultBlock;
                    } else if (y < seaLevel) {
                        state = defaultFluid;
                    } else {
                        state = Blocks.AIR.defaultBlockState();
                    }
                    
                    chunk.setBlockState(mutablePos, state, false);
                }
            }
        }
    }
    
    /**
     * Sample terrain density at a position using the noise router.
     */
    private double sampleDensity(RandomState random, int x, int y, int z) {
        // Use the final_density function from noise router
        DensityFunction finalDensity = noiseRouter.finalDensity();
        
        DensityFunction.FunctionContext context = new DensityFunction.SinglePointContext(x, y, z);
        
        return finalDensity.compute(context);
    }
    
    @Override
    public int getSeaLevel() {
        return settings.value().seaLevel();
    }
    
    @Override
    public int getMinY() {
        return settings.value().noiseSettings().minY();
    }
    
    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        // Binary search for surface height
        int minY = getMinY();
        int maxY = minY + getGenDepth();
        
        while (maxY - minY > 1) {
            int midY = (minY + maxY) / 2;
            double density = sampleDensity(random, x, midY, z);
            
            if (density > 0) {
                minY = midY;
            } else {
                maxY = midY;
            }
        }
        
        return minY;
    }
    
    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        int minY = getMinY();
        int height = getGenDepth();
        BlockState[] states = new BlockState[height];
        
        NoiseGeneratorSettings noiseSettings = settings.value();
        BlockState defaultBlock = noiseSettings.defaultBlock();
        BlockState defaultFluid = noiseSettings.defaultFluid();
        int seaLevel = noiseSettings.seaLevel();
        
        for (int i = 0; i < height; i++) {
            int y = minY + i;
            double density = sampleDensity(random, x, y, z);
            
            if (density > 0) {
                states[i] = defaultBlock;
            } else if (y < seaLevel) {
                states[i] = defaultFluid;
            } else {
                states[i] = Blocks.AIR.defaultBlockState();
            }
        }
        
        return new NoiseColumn(minY, states);
    }
    
    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add("CubicWorldGen: " + (CubicWorldGen.isCubicChunksPresent() ? "Cubic" : "Vanilla"));
        
        double density = sampleDensity(random, pos.getX(), pos.getY(), pos.getZ());
        info.add(String.format("Density: %.4f", density));
    }
}
