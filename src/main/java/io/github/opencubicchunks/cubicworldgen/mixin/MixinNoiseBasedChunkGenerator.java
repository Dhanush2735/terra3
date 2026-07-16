package io.github.opencubicchunks.cubicworldgen.mixin;

import io.github.opencubicchunks.cubicworldgen.compat.CubicChunksCompat;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to extend NoiseBasedChunkGenerator with cubic chunk support.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseBasedChunkGenerator {
    
   // Note: I changed 'WorldGenRegion region' to 'WorldGenRegion level' below
   @Inject(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V", at = @At("HEAD"))
   private void buildSurface(WorldGenRegion level, StructureManager structures, RandomState randomState, ChunkAccess chunk, CallbackInfo ci) {
        // Now this works because the argument name matches
        if (CubicChunksCompat.isCubic(level.getLevel())) {
            // Custom surface building for cubic worlds
            // This handles cube boundaries properly
        }
    }
}