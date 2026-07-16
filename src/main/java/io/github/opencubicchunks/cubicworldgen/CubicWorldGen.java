package io.github.opencubicchunks.cubicworldgen;

import io.github.opencubicchunks.cubicworldgen.compat.CubicChunksCompat;
import io.github.opencubicchunks.cubicworldgen.config.CubicWorldGenConfig;
import io.github.opencubicchunks.cubicworldgen.worldgen.CubicChunkGenerators;
import io.github.opencubicchunks.cubicworldgen.worldgen.CubicDensityFunctions;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CubicWorldGen.MODID)
public class CubicWorldGen {
    public static final String MODID = "cubicworldgen";
    public static final Logger LOGGER = LoggerFactory.getLogger(CubicWorldGen.class);

    private static boolean cubicChunksPresent = false;

    public CubicWorldGen() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        CubicChunkGenerators.CHUNK_GENERATORS.register(modEventBus);
        CubicDensityFunctions.DENSITY_FUNCTION_TYPES.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CubicWorldGenConfig.SPEC);

        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("CubicWorldGen initializing");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            cubicChunksPresent = ModList.get().isLoaded("cubicchunks")
                || ModList.get().isLoaded("terracubes");

            if (cubicChunksPresent) {
                LOGGER.info("CubicChunks2 detected");
                CubicChunksCompat.initialize();
            } else {
                LOGGER.info("CubicChunks2 not found");
            }
        });
    }

    public static boolean isCubicChunksPresent() {
        return cubicChunksPresent;
    }
}