package io.github.opencubicchunks.cubicworldgen.worldgen;

import com.mojang.serialization.Codec;
import io.github.opencubicchunks.cubicworldgen.CubicWorldGen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.registries.DeferredRegister;

public class CubicChunkGenerators {

    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS =
        DeferredRegister.create(Registries.CHUNK_GENERATOR, CubicWorldGen.MODID);
}