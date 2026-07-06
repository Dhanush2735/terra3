// ============================================================
// FILE: TerraPlusPlus.java (FULL — Overture aligned)
// ============================================================
package net.buildtheearth.terraplusplus;

import com.mojang.serialization.Codec;

import net.buildtheearth.terraplusplus.client.TerraCreateWorldScreen;
import net.buildtheearth.terraplusplus.commands.TpllCommand;
import net.buildtheearth.terraplusplus.config.TerraConfig;
import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.buildtheearth.terraplusplus.dataset.DatasetManager;
import net.buildtheearth.terraplusplus.debug.TerrainDiagnostic;
import net.buildtheearth.terraplusplus.generator.TerraChunkGenerator;
import net.buildtheearth.terraplusplus.generator.osm.AsyncStructureManager;
import net.buildtheearth.terraplusplus.generator.osm.StructureTickHandler;
import net.buildtheearth.terraplusplus.projection.ProjectionRegistry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(TerraPlusPlus.MODID)
public class TerraPlusPlus {
    public static final String MODID = "terraplusplus";
    public static final Logger LOGGER = LoggerFactory.getLogger(TerraPlusPlus.class);

    private static TerraPlusPlus instance;
    private DatasetManager datasetManager;

    public static final DeferredRegister<Codec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, MODID);

    public static final RegistryObject<Codec<? extends ChunkGenerator>> EARTH_GENERATOR =
            GENERATORS.register("earth", () -> TerraChunkGenerator.CODEC);

    public TerraPlusPlus() {
        instance = this;

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        GENERATORS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TerraConfig.SPEC);

        MinecraftForge.EVENT_BUS.register(this);

        modEventBus.addListener((FMLCommonSetupEvent event) -> {
            CompletableFuture.runAsync(TerrainDiagnostic::runFullCheck);
        });
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ProjectionRegistry.register();
            datasetManager = new DatasetManager();
        });
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Terra++ shutting down...");

        if (datasetManager != null) {
            datasetManager.shutdown();
        }

        AsyncStructureManager async = StructureTickHandler.getAsyncManager();
        if (async != null) {
            async.shutdown();
        }

        LOGGER.info("Terra++ shutdown complete");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        TpllCommand.register(event.getDispatcher());
    }

    public static TerraPlusPlus getInstance() { return instance; }
    public DatasetManager getDatasetManager() { return datasetManager; }
}