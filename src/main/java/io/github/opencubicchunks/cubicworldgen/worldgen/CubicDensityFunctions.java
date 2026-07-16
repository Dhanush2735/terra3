package io.github.opencubicchunks.cubicworldgen.worldgen;

import com.mojang.serialization.Codec;
import io.github.opencubicchunks.cubicworldgen.CubicWorldGen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraftforge.registries.DeferredRegister;

public class CubicDensityFunctions {

    public static final DeferredRegister<Codec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
        DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, CubicWorldGen.MODID);
}