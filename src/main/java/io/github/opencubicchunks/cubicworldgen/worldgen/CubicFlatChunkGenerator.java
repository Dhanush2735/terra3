package io.github.opencubicchunks.cubicworldgen.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Flat world generator for cubic chunks.
 * Generates a simple flat world at Y=0 (or configurable).
 */
public class CubicFlatChunkGenerator extends ChunkGenerator {
    
    public static final Codec<CubicFlatChunkGenerator> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            Codec.INT.fieldOf("surface_y").forGetter(g -> g.surfaceY),
            Codec.INT.fieldOf("layer_thickness").forGetter(g -> g.layerThickness)
        ).apply(instance, CubicFlatChunkGenerator::new)
    );
    
    private final int surfaceY;
    private final int layerThickness;
    
    public CubicFlatChunkGenerator(BiomeSource biomeSource, int surfaceY, int layerThickness) {
        super(biomeSource);
        this.surfaceY = surfaceY;
        this.layerThickness = layerThickness;
    }
    
    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }
    
    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        // No carving in flat worlds
    }
    
    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager,
                             RandomState random, ChunkAccess chunk) {
        // Surface already built in fillFromNoise
    }
    
    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        // Default mob spawning
    }
    
    @Override
    public int getGenDepth() {
        return 384; // Standard height for compatibility
    }
    
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender,
                                                        RandomState random, StructureManager structureManager,
                                                        ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(() -> {
            fillFlatWorld(chunk);
            return chunk;
        }, executor);
    }
    
    private void fillFlatWorld(ChunkAccess chunk) {
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Bedrock layer
                mutablePos.set(minX + x, surfaceY - layerThickness, minZ + z);
                chunk.setBlockState(mutablePos, Blocks.BEDROCK.defaultBlockState(), false);
                
                // Stone layers
                for (int y = surfaceY - layerThickness + 1; y < surfaceY - 1; y++) {
                    mutablePos.set(minX + x, y, minZ + z);
                    chunk.setBlockState(mutablePos, Blocks.STONE.defaultBlockState(), false);
                }
                
                // Dirt layer
                mutablePos.set(minX + x, surfaceY - 1, minZ + z);
                chunk.setBlockState(mutablePos, Blocks.DIRT.defaultBlockState(), false);
                
                // Grass layer
                mutablePos.set(minX + x, surfaceY, minZ + z);
                chunk.setBlockState(mutablePos, Blocks.GRASS_BLOCK.defaultBlockState(), false);
            }
        }
    }
    
    @Override
    public int getSeaLevel() {
        return surfaceY - 1;
    }
    
    @Override
    public int getMinY() {
        return -64;
    }
    
    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        return surfaceY + 1;
    }
    
    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        BlockState[] states = new BlockState[getGenDepth()];
        int minY = getMinY();
        
        for (int i = 0; i < states.length; i++) {
            int y = minY + i;
            if (y < surfaceY - layerThickness) {
                states[i] = Blocks.AIR.defaultBlockState();
            } else if (y == surfaceY - layerThickness) {
                states[i] = Blocks.BEDROCK.defaultBlockState();
            } else if (y < surfaceY - 1) {
                states[i] = Blocks.STONE.defaultBlockState();
            } else if (y == surfaceY - 1) {
                states[i] = Blocks.DIRT.defaultBlockState();
            } else if (y == surfaceY) {
                states[i] = Blocks.GRASS_BLOCK.defaultBlockState();
            } else {
                states[i] = Blocks.AIR.defaultBlockState();
            }
        }
        
        return new NoiseColumn(minY, states);
    }
    
    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add("CubicFlatWorld: surface=" + surfaceY);
    }
}
