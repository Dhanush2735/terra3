package io.github.opencubicchunks.cubicworldgen.mixin;

import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Mixin for ChunkGenerator to add cubic generation hooks.
 */
@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGenerator {
    // Cubic generation hooks
}
