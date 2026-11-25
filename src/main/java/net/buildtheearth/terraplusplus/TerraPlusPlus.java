// ============================================================
// FILE: TerraPlusPlus.java (FULL — Overture aligned)
// ============================================================
package net.buildtheearth.terraplusplus;

import com.mojang.serialization.Codec;

import net.buildtheearth.terraplusplus.client.TerraCreateWorldScreen;
import net.buildtheearth.terraplusplus.commands.TerraCheckCommand;
import net.buildtheearth.terraplusplus.commands.TpllCommand;
import net.buildtheearth.terraplusplus.config.TerraConfig;
import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.buildtheearth.terraplusplus.dataset.DatasetManager;
import net.buildtheearth.terraplusplus.debug.TerrainDiagnostic;
import net.buildtheearth.terraplusplus.generator.TerraChunkGenerator;
import net.buildtheearth.terraplusplus.generator.osm.AsyncStructureManager;
import net.buildtheearth.terraplusplus.generator.osm.StructureTickHandler;
import net.buildtheearth.terraplusplus.projection.ProjectionRegistry;

import net.buildtheearth.terraplusplus.config.TerraSpawnSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
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
        TerraChunkGenerator generator = TerraChunkGenerator.activeInstance;
        if (generator != null) {
            generator.shutdownAsyncWater();
        }

        LOGGER.info("Terra++ shutdown complete");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        TpllCommand.register(event.getDispatcher());
        TerraCheckCommand.register(event.getDispatcher());
    }

    /**
     * Create-world lat/lon (default 0,0) becomes the overworld spawn.
     * Uses the same BTE projection as /tpll. Cancels vanilla biome hunt
     * so spawn is not forced back to block 0,0.
     */
    private static volatile BlockPos pendingWorldSpawn;
    private static volatile boolean applyPendingWorldSpawn;

    @SubscribeEvent
    public void onCreateSpawn(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos spawn = resolveTerraSpawn(level);
        if (spawn == null) {
            return;
        }
        event.getSettings().setSpawn(spawn, 0.0f);
        level.setDefaultSpawnPos(spawn, 0.0f);
        pendingWorldSpawn = spawn;
        applyPendingWorldSpawn = true;
        if (event.isCancelable()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!applyPendingWorldSpawn || pendingWorldSpawn == null) {
            return;
        }
        ServerLevel overworld = event.getServer().overworld();
        if (overworld != null
                && overworld.getChunkSource().getGenerator() instanceof TerraChunkGenerator) {
            overworld.setDefaultSpawnPos(pendingWorldSpawn, 0.0f);
        }
        applyPendingWorldSpawn = false;
        pendingWorldSpawn = null;
        TerraSpawnSettings.consume();
    }

    private BlockPos resolveTerraSpawn(ServerLevel level) {
        if (level == null || !(level.getChunkSource().getGenerator() instanceof TerraChunkGenerator)) {
            return null;
        }
        double[] geo = TerraSpawnSettings.peek();
        double lat = geo[0];
        double lon = geo[1];
        double[] mc = TpllCommand.blockPosFromGeo(lat, lon);
        if (mc == null) {
            LOGGER.warn("Spawn lat={}, lon={} is outside the BTE projection; using 0,0", lat, lon);
            mc = TpllCommand.blockPosFromGeo(0.0, 0.0);
        }
        if (mc == null) {
            mc = new double[] {0.0, 0.0};
        }
        int x = (int) Math.floor(mc[0]);
        int z = (int) Math.floor(mc[1]);
        int y = TpllCommand.spawnYFromBlock(x, z);
        BlockPos spawn = new BlockPos(x, y, z);
        LOGGER.info("Terra++ world spawn set to lat={}, lon={} -> {}, {}, {}", lat, lon, x, y, z);
        return spawn;
    }

    public static TerraPlusPlus getInstance() { return instance; }
    public DatasetManager getDatasetManager() { return datasetManager; }
}