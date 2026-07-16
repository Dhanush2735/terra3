package io.github.opencubicchunks.cubicworldgen.mixin;

import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Mixin for NoiseChunk to support cubic noise sampling.
 */
@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunk {
    // Cubic noise sampling - removes Y bound assumptions
}
